package com.solo.gb28181.inbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.sync.model.CatalogItem;
import com.solo.domain.sync.model.CatalogItemType;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.spi.CatalogSink;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.utils.SipUtils;
import com.solo.infra.mapper.PlatformMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GB28181 目录响应的投递口：把 SIP 侧已解析的批次转成 {@link CatalogItem}，交给当前活跃的同步轮次。
 *
 * <p><b>为什么是"旁路"而不是新写一个 MessageHandler</b>：消息路由按 (RootType, CmdType) 唯一，
 * 再加一个 Catalog 处理器会与既有的 {@code CatalogResponseMessageHandler} 抢路由。这里在既有
 * 处理器解析完分片之后旁挂一次投递，对旧链路的落库行为零改动。</p>
 *
 * <p><b>本类只保留"投递口"这一份进程内状态</b>：SIP 响应只能在进程内接收，投递口无法外置。
 * 轮次状态（status/progress/超时/冷却/互斥）一律在 {@code sync_task} 与 Redis 里（C10），
 * 所以本进程重启最多丢"这一批没送出去"，不会产生脏的轮次记录。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Gb28181CatalogTap {

    /** 来源链列宽：sync_staging.origin_chain / res_channel.origin_chain 均为 VARCHAR(128) */
    private static final int MAX_CHAIN_LENGTH = 128;

    /** 最上游平台国标码 → 本地 platform.id 的反查入口（来源身份落链用） */
    private final PlatformMapper platformMapper;

    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();

    /**
     * 登记投递口。
     *
     * @return false = 该设备已有活跃轮次（同平台互斥，C24）
     */
    public boolean register(String deviceId, Long platformId, CatalogSink sink) {
        return deliveries.putIfAbsent(deviceId, new Delivery(platformId, sink)) == null;
    }

    public void unregister(String deviceId) {
        deliveries.remove(deviceId);
    }

    public boolean isActive(String deviceId) {
        return deliveries.containsKey(deviceId);
    }

    /** 既有处理器解析完一片后调用；内部做条数累计，收齐即结束 */
    public void onBatch(String deviceId, int sumNum, Integer declaredNum, List<GbDeviceChannel> raw) {
        Delivery delivery = deliveries.get(deviceId);
        if (delivery == null || raw == null || raw.isEmpty()) {
            return;
        }
        List<CatalogItem> items = new ArrayList<>(raw.size());
        // 最上游平台国标码 → 本地 platform.id：整批一次反查，逐条查会把一次目录同步放大成 N 次
        Map<String, Long> platformIdByCode = resolveOriginPlatforms(raw);
        for (GbDeviceChannel channel : raw) {
            CatalogItem item = convert(deviceId, channel);
            if (item != null) {
                applyOrigin(item, channel, deviceId, delivery.platformId, platformIdByCode);
                items.add(item);
            }
        }
        try {
            delivery.sink.onBatch(new CatalogBatch(items, sumNum > 0 ? sumNum : null, declaredNum));
        } catch (RuntimeException e) {
            // 暂存写失败必须让整轮失败（C14）：不能吞、不能跳过这一批继续
            log.error("[资源同步] 批次落库失败 deviceId={} 条数={}", deviceId, items.size(), e);
            deliverError(deviceId, "STAGING_WRITE_FAILED", e.getMessage());
            return;
        }
        int received = delivery.received.addAndGet(raw.size());
        if (sumNum > 0 && received >= sumNum) {
            deliverComplete(deviceId);
        }
    }

    /** 设备通告空目录（SumNum=0）：本轮正常收齐，由合并阶段处理"全都没有了" */
    public void onEmptyCatalog(String deviceId) {
        deliverComplete(deviceId);
    }

    private void deliverComplete(String deviceId) {
        Delivery delivery = deliveries.remove(deviceId);
        if (delivery == null) {
            return;
        }
        try {
            delivery.sink.onComplete();
        } catch (RuntimeException e) {
            log.error("[资源同步] 收尾回调失败 deviceId={}", deviceId, e);
        }
    }

    private void deliverError(String deviceId, String errorCode, String message) {
        Delivery delivery = deliveries.remove(deviceId);
        if (delivery == null) {
            return;
        }
        try {
            delivery.sink.onError(errorCode, message);
        } catch (RuntimeException e) {
            log.error("[资源同步] 失败回调本身出错 deviceId={}", deviceId, e);
        }
    }

    // ---------------- 解析 ----------------

    /**
     * 单条目录项 → 统一模型。
     *
     * <p>类型判定与既有处理器保持同一套信号（ID 长度 + 类型码 + Parental），只把"目录"再细分成
     * 组织 / 业务分组 / 虚拟组织 / 设备：</p>
     * <ul>
     *   <li>≤8 位：GB/T 2260 行政码，强制组织</li>
     *   <li>215 / 216：业务分组 / 虚拟组织</li>
     *   <li>200：平台（对下级平台而言就是一台设备），落 {@code res_device}</li>
     *   <li>其余 Parental=1：目录，按组织处理</li>
     *   <li>Parental=0：通道</li>
     * </ul>
     */
    static CatalogItem convert(String deviceId, GbDeviceChannel channel) {
        String code = channel.getChannelId();
        if (code == null || code.isBlank()) {
            return null;
        }
        int len = code.length();
        int typeCode = len >= 13 ? SipUtils.getTypeCodeFromGbCode(code) : 0;
        boolean directory = channel.getParental() != null && channel.getParental() == 1;

        CatalogItemType type;
        if (len <= 8) {
            type = CatalogItemType.ORG;
        } else if (typeCode == 215) {
            type = CatalogItemType.GROUP_BUSINESS;
        } else if (typeCode == 216) {
            type = CatalogItemType.GROUP_VIRTUAL;
        } else if (typeCode == 200) {
            type = CatalogItemType.DEVICE;
        } else if (directory) {
            type = CatalogItemType.ORG;
        } else {
            type = CatalogItemType.CHANNEL;
        }

        CatalogItem item = new CatalogItem();
        item.setCode(code);
        item.setParentCode(channel.getParentId());
        item.setType(type);
        item.setName(channel.getName() == null || channel.getName().isBlank() ? code : channel.getName());
        item.setStatus(channel.getStatus() == null ? "ON" : channel.getStatus().toUpperCase());
        item.setHopCount(0);
        item.setCodeChain(List.of());

        Map<String, String> attrs = new HashMap<>();
        attrs.put("sourceDeviceId", deviceId);
        attrs.put("typeCode", String.valueOf(typeCode));
        attrs.put("parental", String.valueOf(directory ? 1 : 0));
        if (channel.getCivilCode() != null) {
            attrs.put("civilCode", channel.getCivilCode());
        }
        item.setAttrs(attrs);

        if (type == CatalogItemType.CHANNEL) {
            item.setDeviceIp(channel.getIpAddress());
            item.setVendor(channel.getManufacturer());
            item.setModel(channel.getModel());
            if (channel.getPtzType() != null && channel.getPtzType() > 0) {
                item.setCapability("PTZ");
            }
        }
        return item;
    }

    // ---------------- 全局身份（3.9.3 / 3.10.3） ----------------

    /**
     * 把上游给的全局身份落到 {@link CatalogItem} 上。
     *
     * <p>三条规则（对应文档 3.9.3）：</p>
     * <ol>
     *   <li><b>透传优先</b>：上游给了 {@code OriginPlatformId/OriginCode} 就用它，本平台只负责往后传，
     *       不再覆盖成"直接来源"——覆盖掉就再也拼不回跨级链路了。</li>
     *   <li><b>可缺失但要诚实</b>：上游没给（多数厂商默认不透传扩展元素）就只能填直接来源，
     *       来源链长度为 1。宁可知情地退化，也不能编一个看起来更完整的最上游。</li>
     *   <li><b>跳数只增</b>：入站资源的 {@code hopCount} = 上游告知值 + 1，本平台本地资源为 0。</li>
     * </ol>
     *
     * @param directGbCode     直接来源平台的国标码（就是发起本次 Catalog 的注册方）
     * @param directPlatformId 直接来源平台的本地 id（国标码反查不到时的兜底）
     */
    private static void applyOrigin(CatalogItem item, GbDeviceChannel channel, String directGbCode,
                                    Long directPlatformId, Map<String, Long> platformIdByCode) {
        String originPlatformCode = trimToNull(channel.getOriginPlatformCode());
        String originCode = trimToNull(channel.getOriginCode());
        String upstreamChain = trimToNull(channel.getOriginChain());
        Integer upstreamHop = channel.getHopCount();

        // 最上游平台的本地 id：国标码查得到才换，查不到退回直接来源（对方的库 id 我们认不出）
        Long resolvedId = directPlatformId;
        if (originPlatformCode != null) {
            Long mapped = platformIdByCode.get(originPlatformCode);
            if (mapped != null) {
                resolvedId = mapped;
            }
        }
        item.setOriginPlatformId(resolvedId == null ? 0L : resolvedId);
        item.setOriginCode(originCode == null ? item.getCode() : originCode);
        item.setOriginChain(buildOriginChain(upstreamChain, originPlatformCode, directGbCode));
        int hop = upstreamHop == null || upstreamHop < 0 ? 0 : upstreamHop;
        item.setHopCount(hop + 1);
    }

    /**
     * 组装来源链：上游链 → 上游声明的起点 → 直接来源。
     * <p>逐段去重（同一平台重复出现只留第一次）并按列宽截断：链被截断只损失追溯精度，
     * 不会让写入失败；超宽反而会让整批暂存插入失败（C14）。</p>
     */
    private static String buildOriginChain(String upstreamChain, String originPlatformCode, String directGbCode) {
        StringBuilder sb = new StringBuilder();
        if (upstreamChain != null) {
            for (String part : upstreamChain.split(",")) {
                appendHop(sb, part);
            }
        }
        appendHop(sb, originPlatformCode);
        appendHop(sb, directGbCode);
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendHop(StringBuilder sb, String hop) {
        String value = trimToNull(hop);
        if (value == null || containsHop(sb, value)) {
            return;
        }
        int extra = (sb.isEmpty() ? 0 : 1) + value.length();
        if (sb.length() + extra > MAX_CHAIN_LENGTH) {
            log.debug("[资源同步] 来源链已达列宽上限，丢弃后续跳 value={}", value);
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(value);
    }

    private static boolean containsHop(StringBuilder sb, String hop) {
        if (sb.isEmpty()) {
            return false;
        }
        for (String part : sb.toString().split(",")) {
            if (part.equals(hop)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本批出现的最上游平台国标码 → {@code platform.id}，一次 {@code IN} 查询解决。
     * <p>查库失败不阻断同步：来源身份是增强信息，退回"直接来源"仍能正确防环
     * （{@code ShareGuard} 同时比本地 id 与国标码）。</p>
     */
    private Map<String, Long> resolveOriginPlatforms(List<GbDeviceChannel> raw) {
        if (platformMapper == null) {
            return Map.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (GbDeviceChannel channel : raw) {
            String code = trimToNull(channel.getOriginPlatformCode());
            if (code != null) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        try {
            List<Platform> platforms = platformMapper.selectList(
                    new LambdaQueryWrapper<Platform>().in(Platform::getCode, codes));
            Map<String, Long> out = new HashMap<>(platforms.size());
            for (Platform platform : platforms) {
                String code = trimToNull(platform.getCode());
                if (code != null) {
                    out.put(code, platform.getId());
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("[资源同步] 最上游平台反查失败，按直接来源落库 codes={}", codes, e);
            return Map.of();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 一个设备的投递口 + 本轮已收条数（收齐判断用） */
    private static final class Delivery {
        private final Long platformId;
        private final CatalogSink sink;
        private final AtomicInteger received = new AtomicInteger();

        private Delivery(Long platformId, CatalogSink sink) {
            this.platformId = platformId;
            this.sink = sink;
        }
    }
}
