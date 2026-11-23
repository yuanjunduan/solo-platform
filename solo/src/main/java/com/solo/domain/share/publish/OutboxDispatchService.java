package com.solo.domain.share.publish;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.model.PublishRound;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.model.ShareStatus;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareCatalogService;
import com.solo.domain.share.support.ShareSettings;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.OutboxMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.PublishRoundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbox 投递（3.9.5 / C17 / C18）。
 *
 * <p>一轮 = 一个平台的一段积压。顺序是硬的：</p>
 * <ol>
 *   <li>平台不在线 → 队列挂起（{@code PAUSED}），不判失败：离线不是错误，恢复后要自动补；</li>
 *   <li>积压超过 {@code publish.detail-max} → 发一次全量快照，把非删除行一次性收口（C18）；</li>
 *   <li>其余按 {@code seq_no} 升序逐条发，2xx 才算 SENT；失败按指数退避，超上限置 DEAD。</li>
 * </ol>
 *
 * <p><b>降级时删除行仍然逐条发</b>：Catalog NOTIFY 是增量 upsert，快照里"没出现"的节点
 * 上级不会删。把 DELETE 一起合并进快照等于把撤销吞掉——这是这份代码里最容易写错的一处。</p>
 *
 * <p><b>平台级互斥放 DB</b>（C24）：同一平台存在 {@code RUNNING} 轮次时直接跳过本轮，
 * 多实例部署时另一个节点也挡得住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxDispatchService {

    private static final long DEFAULT_TENANT_ID = 1L;

    /** 业务批量删除事件的 payload 类型标记 */
    public static final String KIND_CHANNEL_BATCH = "CHANNEL_BATCH";

    private final OutboxMapper outboxMapper;
    private final OutShareMapper outShareMapper;
    private final PublishRoundMapper publishRoundMapper;
    private final GbParentPlatformMapper parentPlatformMapper;
    private final ShareCatalogService catalogService;
    private final OutCodeService outCodeService;
    private final OutboundSipDelivery delivery;
    private final ShareSettings settings;
    private final ObjectMapper objectMapper;
    /** 来源平台国标码缓存（平台码建立后不可修改，见 PlatformServiceImpl.update） */
    private final PlatformMapper platformMapper;
    private final Map<Long, String> originCodeCache = new ConcurrentHashMap<>();

    /** 一轮的结果（调度器与手工"立即推送"共用） */
    public record RoundResult(Long roundId, String status, int planned, int sent,
                             int failed, boolean degraded, String summary) {
        public static RoundResult empty() {
            return new RoundResult(null, null, 0, 0, 0, false, "无待推事件");
        }

        public static RoundResult skipped(String reason) {
            return new RoundResult(null, PublishRound.STATUS_SKIPPED, 0, 0, 0, false, reason);
        }

        public boolean delivered() {
            return sent > 0 && failed == 0;
        }
    }

    // ------------------------------------------------------------------
    // 调度入口
    // ------------------------------------------------------------------

    /** 扫一轮有积压的平台（一个平台一条积压链，不互相拖住） */
    public int dispatchPending(int platformLimit) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> platformIds = outboxMapper.selectPendingPlatformIds(now, Math.max(1, platformLimit));
        int delivered = 0;
        for (Long platformId : platformIds) {
            try {
                RoundResult result = dispatchPlatform(platformId, PublishRound.TRIGGER_SCHEDULED);
                delivered += result.sent();
            } catch (RuntimeException e) {
                log.warn("[上级推送] 平台投递异常 platform={} err={}", platformId, e.getMessage(), e);
            }
        }
        return delivered;
    }

    /**
     * 投递一个平台的全部可投递事件。
     *
     * <p>先看平台再看队列：离线期间队列是 {@code PAUSED}，若先按"待推条数"提前返回，
     * 恢复在线后就永远等不来第一次扫描。</p>
     *
     * <p>没有任何待推行时直接返回（不写空的 {@code publish_round}：每 30 秒一行空台账，
     * 两周后这张表就没法看了）。</p>
     */
    public RoundResult dispatchPlatform(Long platformId, String triggerType) {
        if (platformId == null) {
            return RoundResult.skipped("上级平台为空");
        }
        LocalDateTime now = LocalDateTime.now();
        GbParentPlatform platform = parentPlatformMapper.selectById(platformId);
        if (platform == null) {
            outboxMapper.pauseByPlatform(platformId, now);
            return RoundResult.skipped("上级平台不存在，队列挂起");
        }
        if (!Integer.valueOf(1).equals(platform.getEnabled())) {
            outboxMapper.pauseByPlatform(platformId, now);
            log.info("[上级推送] 平台已禁用，队列挂起 platform={}", platformId);
            return RoundResult.skipped("上级平台已禁用，队列挂起");
        }
        if (!Integer.valueOf(1).equals(platform.getStatus())) {
            outboxMapper.pauseByPlatform(platformId, now);
            log.info("[上级推送] 平台离线，队列挂起 platform={}", platformId);
            return RoundResult.skipped("上级平台离线，队列挂起");
        }
        // 已在线：把离线期间挂起的行放回队列（序号不变，上级看到的顺序不变）
        outboxMapper.resumeByPlatform(platformId, now);
        // 恢复 PAUSED 之后重新统计：否则 planned 会漏掉刚放回队列的那一段
        long pending = outboxMapper.countPending(platformId);
        if (pending <= 0) {
            return RoundResult.empty();
        }
        if (publishRoundMapper.countRunning(platformId) > 0) {
            log.info("[上级推送] 上一轮仍在执行，跳过本轮 platform={}", platformId);
            return RoundResult.skipped("上一轮仍在执行");
        }

        PublishRound round = openRound(platform, triggerType, (int) Math.min(pending, Integer.MAX_VALUE));
        int sent = 0;
        int failed = 0;
        boolean degraded = false;
        try {
            // ---- 超量降级（C18）：先发快照，再放过删除行 ----
            long maxSeq = outboxMapper.maxSeq(platformId);
            int detailMax = settings.getDetailMax();
            if (detailMax > 0 && pending > detailMax) {
                degraded = true;
                OutboundSipDelivery.Delivery snapshot = delivery.notifySnapshot(
                        platform, catalogService.loadCatalogItems(platformId));
                if (!snapshot.ok()) {
                    return finishRound(round, 0, (int) Math.min(pending, Integer.MAX_VALUE),
                            true, "SNAPSHOT_FAILED", snapshot.error(),
                            "超量降级快照发送失败：" + snapshot.error());
                }
                int collapsed = outboxMapper.markBacklogSent(platformId, maxSeq, round.getId(), LocalDateTime.now());
                outShareMapper.markAllPushed(platformId, LocalDateTime.now());
                log.info("[上级推送] 超量降级 platform={} 待推={} 阈值={} 快照收口={}",
                        platformId, pending, detailMax, collapsed);
            }

            // ---- 逐条投递 ----
            while (true) {
                List<OutboxEvent> page = outboxMapper.selectDispatchable(
                        platformId, LocalDateTime.now(), Math.max(1, settings.getBatchSize()));
                if (page.isEmpty()) {
                    break;
                }
                for (OutboxEvent event : page) {
                    if (deliverOne(platform, event, round.getId())) {
                        sent++;
                    } else {
                        failed++;
                    }
                }
                if (page.size() < Math.max(1, settings.getBatchSize())) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[上级推送] 轮次异常 platform={} err={}", platformId, e.getMessage(), e);
            return finishRound(round, sent, failed, degraded, "ROUND_ERROR", e.getMessage(),
                    "轮次异常中断：" + e.getMessage());
        }
        String summary = degraded
                ? "超量降级：已发全量快照，删除逐条投递"
                : "逐条投递完成";
        return finishRound(round, sent, failed, degraded, null, null, summary);
    }

    // ------------------------------------------------------------------
    // 单条投递
    // ------------------------------------------------------------------

    private boolean deliverOne(GbParentPlatform platform, OutboxEvent event, Long roundId) {
        Map<String, Object> payload = parsePayload(event);
        if (PublishEventType.REFRESH.equals(event.getEventType())) {
            return deliverRefresh(platform, event, roundId);
        }
        if (payload != null && KIND_CHANNEL_BATCH.equals(str(payload.get("kind")))) {
            return deliverBatchDelete(platform, event, payload, roundId);
        }
        GbDeviceChannel item = toItem(payload);
        if (item == null) {
            outboxMapper.markDead(event.getId(), "payload 缺失或不可解析（seq=" + event.getSeqNo() + "）",
                    roundId, LocalDateTime.now());
            log.warn("[上级推送] 事件报文素材缺失，判死 platform={} seq={} event={}",
                    platform.getId(), event.getSeqNo(), event.getEventType());
            return false;
        }
        OutboundSipDelivery.Delivery result = delivery.notifyItem(platform, item, event.getEventType());
        if (result.ok()) {
            onDelivered(event, roundId);
            return true;
        }
        onFailed(event, result.error(), roundId);
        return false;
    }

    /**
     * REFRESH：先发全量快照，再把"撤销中"的通道逐条 DEL。
     *
     * <p>撤销行没有自己的 outbox 事件（整平台撤销只写一条 REFRESH），所以这里必须现场补发；
     * 漏了这步的结果是"上级目录里还挂着已撤销的通道，点播回来又被拒绝"。</p>
     */
    private boolean deliverRefresh(GbParentPlatform platform, OutboxEvent event, Long roundId) {
        List<GbDeviceChannel> snapshot = catalogService.loadCatalogItems(platform.getId());
        OutboundSipDelivery.Delivery result = delivery.notifySnapshot(platform, snapshot);
        if (!result.ok()) {
            onFailed(event, result.error(), roundId);
            return false;
        }
        List<OutShare> revoking = outShareMapper.selectRevoking(platform.getId());
        for (OutShare row : revoking) {
            String exposed = outCodeService.exposedCodeOf(platform.getId(), ShareRefType.CHANNEL, row.getRefCode());
            if (!StringUtils.hasText(exposed)) {
                continue;
            }
            GbDeviceChannel item = new GbDeviceChannel();
            item.setChannelId(row.getRefCode());
            item.setExposedCode(exposed);
            item.setParental(0);
            item.setName(row.getRefCode());
            OutboundSipDelivery.Delivery del = delivery.notifyItem(platform, item, PublishEventType.DELETE);
            if (!del.ok()) {
                onFailed(event, del.error(), roundId);
                return false;
            }
            outCodeService.tombstone(platform.getId(), ShareRefType.CHANNEL, row.getRefCode());
            outShareMapper.markRevokedDeliveredByCode(
                    platform.getId(), row.getRefCode(), LocalDateTime.now());
        }
        outShareMapper.markAllPushed(platform.getId(), LocalDateTime.now());
        onDelivered(event, roundId);
        return true;
    }

    /**
     * 批量删除（C12 第 3 步：一个平台一条 DELETE 事件，payload 里带多个节点）。
     * <p>逐条发、全部 2xx 才算成功：删一半就标 SENT 会让剩余节点永远留在上级。</p>
     */
    private boolean deliverBatchDelete(GbParentPlatform platform, OutboxEvent event,
                                       Map<String, Object> payload, Long roundId) {
        List<Map<String, Object>> items = mapList(payload.get("items"));
        if (items.isEmpty()) {
            outboxMapper.markSent(event.getId(), roundId, LocalDateTime.now());
            return true;
        }
        for (Map<String, Object> map : items) {
            GbDeviceChannel item = toItem(map);
            if (item == null) {
                continue;
            }
            OutboundSipDelivery.Delivery result = delivery.notifyItem(platform, item, PublishEventType.DELETE);
            if (!result.ok()) {
                onFailed(event, result.error(), roundId);
                return false;
            }
            String nativeCode = str(map.get("nativeCode"));
            if (StringUtils.hasText(nativeCode)) {
                outCodeService.tombstone(platform.getId(), ShareRefType.CHANNEL, nativeCode);
                // 逐条收尾：只把这一批真正删掉的节点标成已同步
                outShareMapper.markRevokedDeliveredByCode(platform.getId(), nativeCode, LocalDateTime.now());
            }
        }
        outboxMapper.markSent(event.getId(), roundId, LocalDateTime.now());
        return true;
    }

    // ------------------------------------------------------------------
    // 结果回写
    // ------------------------------------------------------------------

    private void onDelivered(OutboxEvent event, Long roundId) {
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.markSent(event.getId(), roundId, now);
        // 删除投递成功才置墓碑（需求 3.9.4：推送成功后才允许清理映射）
        if (PublishEventType.DELETE.equals(event.getEventType())
                && StringUtils.hasText(event.getRefCode())
                && StringUtils.hasText(event.getRefType())) {
            outCodeService.tombstone(event.getPlatformId(), event.getRefType(), event.getRefCode());
        }
        markRowStatus(event, ShareStatus.SYNCED, null, now);
    }

    private void onFailed(OutboxEvent event, String error, Long roundId) {
        LocalDateTime now = LocalDateTime.now();
        int retry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        String message = error == null ? "未知错误" : error;
        if (retry >= Math.max(1, settings.getRetryMax())) {
            outboxMapper.markDead(event.getId(), message, roundId, now);
            markRowStatus(event, ShareStatus.DEAD, message, now);
            log.warn("[上级推送] 超过重试上限置 DEAD platform={} seq={} retry={} err={}",
                    event.getPlatformId(), event.getSeqNo(), retry, message);
            return;
        }
        long backoff = settings.backoffMs(retry);
        outboxMapper.markFailed(event.getId(), retry, now.plusNanos(backoff * 1_000_000L),
                message, roundId, now);
        markRowStatus(event, ShareStatus.FAILED, message, now);
        log.info("[上级推送] 投递失败将重试 platform={} seq={} retry={} 退避={}ms err={}",
                event.getPlatformId(), event.getSeqNo(), retry, backoff, message);
    }

    private void markRowStatus(OutboxEvent event, String status, String error, LocalDateTime now) {
        if (event.getRefId() == null || !StringUtils.hasText(event.getRefType())) {
            return;
        }
        List<OutShare> rows = outShareMapper.selectByRefInPlatform(
                event.getPlatformId(), event.getRefType(), event.getRefId());
        for (OutShare row : rows) {
            if (ShareRefType.ORG.equals(row.getRefType())) {
                continue;
            }
            outShareMapper.updateStatus(row.getId(), status, error, now);
        }
    }

    private PublishRound openRound(GbParentPlatform platform, String triggerType, int planned) {
        LocalDateTime now = LocalDateTime.now();
        PublishRound round = new PublishRound();
        round.setId(IdWorker.getId());
        round.setTenantId(DEFAULT_TENANT_ID);
        round.setPlatformId(platform.getId());
        round.setPlatformName(platform.getName());
        round.setTriggerType(triggerType);
        round.setStatus(PublishRound.STATUS_RUNNING);
        round.setPlanned(planned);
        round.setSent(0);
        round.setFailed(0);
        round.setDegraded(0);
        round.setThreshold(settings.getDetailMax());
        round.setBeginTime(now);
        round.setCreateTime(now);
        round.setUpdateTime(now);
        publishRoundMapper.insert(round);
        return round;
    }

    private RoundResult finishRound(PublishRound round, int sent, int failed, boolean degraded,
                                    String errorCode, String errorMsg, String summary) {
        LocalDateTime now = LocalDateTime.now();
        String status = (failed > 0 || errorCode != null)
                ? PublishRound.STATUS_FAILED : PublishRound.STATUS_DONE;
        int planned = round.getPlanned() == null ? 0 : round.getPlanned();
        publishRoundMapper.finish(round.getId(), status, sent, failed, degraded ? 1 : 0,
                settings.getDetailMax(), errorCode, errorMsg, summary, now);
        log.info("[上级推送] 轮次收尾 platform={} round={} status={} 应推={} 实推={} 失败={} 降级={}",
                round.getPlatformId(), round.getId(), status, planned, sent, failed, degraded);
        return new RoundResult(round.getId(), status, planned, sent, failed, degraded, summary);
    }

    // ------------------------------------------------------------------
    // payload 解析
    // ------------------------------------------------------------------

    private Map<String, Object> parsePayload(OutboxEvent event) {
        String json = event.getPayload();
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            log.warn("[上级推送] payload 解析失败 platform={} seq={} err={}",
                    event.getPlatformId(), event.getSeqNo(), e.getMessage());
            return null;
        }
    }

    /** 按 payload 快照还原一个 Item（投递阶段不再回查资源：它可能已经不存在了） */
    private GbDeviceChannel toItem(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String kind = str(payload.get("kind"));
        GbDeviceChannel item = new GbDeviceChannel();
        if ("GROUP".equals(kind)) {
            String code = str(payload.get("code"));
            if (!StringUtils.hasText(code)) {
                return null;
            }
            item.setChannelId(code);
            item.setName(str(payload.get("name")));
            item.setParentId(str(payload.get("parentCode")));
            item.setParental(1);
            item.setStatus("ON");
            return item;
        }
        String exposed = str(payload.get("exposedCode"));
        if (!StringUtils.hasText(exposed)) {
            return null;
        }
        item.setExposedCode(exposed);
        item.setChannelId(firstText(str(payload.get("nativeCode")), exposed));
        item.setName(str(payload.get("name")));
        item.setParentId(str(payload.get("parentCode")));
        item.setParental(0);
        item.setManufacturer(firstText(str(payload.get("manufacturer")), str(payload.get("vendor"))));
        item.setModel(str(payload.get("model")));
        item.setOwner(firstText(str(payload.get("owner")), str(payload.get("deviceName"))));
        item.setIpAddress(str(payload.get("deviceIp")));
        item.setSafetyWay(0);
        item.setRegisterWay(1);
        item.setSecrecy(0);
        item.setPtzType(1);
        item.setStatus("ON".equalsIgnoreCase(str(payload.get("status"))) ? "ON" : "OFF");
        item.setCivilCode(str(payload.get("parentCode")));
        // 全局身份（3.9.3）：恒带，只增不改。链路首段即最上游平台国标码（写事件时已快照），
        // 命中就不用回查平台表；本地资源全空，由 XML 兜底成本平台国标码 + 自身码。
        String originChain = str(payload.get("originChain"));
        item.setOriginChain(originChain);
        item.setOriginCode(str(payload.get("originCode")));
        item.setOriginPlatformCode(firstText(chainHead(originChain), originCodeOf(payload.get("originPlatformId"))));
        // 跳数原样透传：接收方按"上游值 + 1"计，本平台不重复加一（本地资源 0 不写该标签）
        item.setHopCount(asInteger(payload.get("hopCount")));
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    /** 来源链首段 = 最上游平台国标码；空链返回 null */
    private static String chainHead(String originChain) {
        if (!StringUtils.hasText(originChain)) {
            return null;
        }
        for (String part : originChain.split(",")) {
            if (StringUtils.hasText(part)) {
                return part.trim();
            }
        }
        return null;
    }

    /**
     * 来源平台本地 id → 国标码。只对历史数据生效（新数据的来源链已带首段），
     * 缓存 miss 不写入，避免平台行暂时不可读时把"空"固化下来。
     */
    private String originCodeOf(Object rawId) {
        Long platformId = asLong(rawId);
        if (platformId == null || platformId <= 0) {
            return null;
        }
        String cached = originCodeCache.get(platformId);
        if (cached != null) {
            return cached;
        }
        try {
            Platform platform = platformMapper.selectById(platformId);
            if (platform != null && StringUtils.hasText(platform.getCode())) {
                String code = platform.getCode().trim();
                originCodeCache.put(platformId, code);
                return code;
            }
        } catch (RuntimeException e) {
            log.warn("[上级推送] 来源平台国标码解析失败 platform={} err={}", platformId, e.getMessage());
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInteger(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? null : parsed.intValue();
    }
}
