package com.solo.domain.share.publish;

import com.solo.domain.share.support.ShareSettings;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.sip.SipSender;
import com.solo.gb28181.transmit.cmd.PlatformCatalogXml;
import com.solo.gb28181.transmit.cmd.PlatformCommander;
import com.solo.gb28181.transmit.cmd.PlatformNotifyXml;
import com.solo.gb28181.transmit.cmd.PlatformSipHeaderProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.ResponseEvent;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 出站报文的<b>同步</b>投递（3.9.5 的 "200 即 SENT"）。
 *
 * <p>遗留的 {@code PlatformCommander} 那几个推送方法是 fire-and-forget：日志里有、
 * 队列里也已经标成功，上级到底收没收到没人知道。Outbox 要判 2xx 就必须自己等应答，
 * 所以这里走 {@link SipSender} 的回调版 + {@code CountDownLatch}，把"有没有回 200"
 * 变成一个可以落库的布尔值。</p>
 *
 * <p><b>超时兜底</b>：{@code SipSubscribe.sweep()} 是周期扫描，回调可能比我们自己等的
 * 时间更晚到；这里固定多等 2 秒，避免把"还没判定"当成"失败"。</p>
 *
 * <p>全量快照按上级的 {@code catalog_group} 分片发送（1/2/4/8）：一次性把两万个 Item
 * 塞进一个 UDP 报文，结果只会是被分片、被丢弃、然后整轮超时。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundSipDelivery {

    /** 等应答时在 ackTimeout 之上多给的余量（订阅扫描周期 + 线程调度） */
    private static final long LATCH_GRACE_MS = 2_000L;

    private final SipSender sipSender;
    private final PlatformSipHeaderProvider headerProvider;
    private final Gb28181Properties properties;
    private final ShareSettings settings;

    /** 投递结果：只有 2xx 才算成功 */
    public record Delivery(boolean ok, String error) {
        public static Delivery success() {
            return new Delivery(true, null);
        }

        public static Delivery failure(String error) {
            return new Delivery(false, error);
        }
    }

    /**
     * 推一条 Catalog 变更事件（ADD / UPDATE / DEL）。
     *
     * @param event 目录变更类型；ADD/UPDATE/DEL 之外的值直接判失败（不猜）
     */
    public Delivery notifyItem(GbParentPlatform platform, GbDeviceChannel item, String event) {
        if (platform == null || item == null) {
            return Delivery.failure("报文要素缺失：平台或节点为空");
        }
        String localIp = headerProvider.localIp();
        String transport = transportOf(platform);
        String sn = String.valueOf(PlatformCommander.nextSn());
        long timeoutMs = ackTimeoutMs();
        try {
            String xml = PlatformCatalogXml.buildEventNotify(platform, properties.getId(), sn, item, event);
            CallIdHeader callId = sipSender.newCallId(localIp, transport);
            if (callId == null) {
                return Delivery.failure("无法创建 Call-ID（SIP Provider 未就绪）");
            }
            Request request = headerProvider.createNotifyRequest(platform, "catalog", xml, callId);
            Delivery delivery = sendAndWait(localIp, request, timeoutMs);
            if (delivery.ok()) {
                log.info("[上级推送] 事件送达 上级平台:{} Event:{} 节点:{} SN:{}",
                        platform.getServerGbId(), event, nodeIdOf(item), sn);
            } else {
                log.warn("[上级推送] 事件失败 上级平台:{} Event:{} 节点:{} SN:{} 原因:{}",
                        platform.getServerGbId(), event, nodeIdOf(item), sn, delivery.error());
            }
            return delivery;
        } catch (Exception e) {
            log.warn("[上级推送] 事件发送异常 上级平台:{} Event:{} 节点:{} 错误:{}",
                    platform.getServerGbId(), event, nodeIdOf(item), e.getMessage(), e);
            return Delivery.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 推全量目录快照（REFRESH 降级 / 对账补推）。按平台分片，逐片等 2xx。
     *
     * <p>空快照也要发一片 {@code SumNum=0}：那是"本平台没有共享任何节点"的明确表达，
     * 上级据此才能把残留目录对齐。</p>
     */
    public Delivery notifySnapshot(GbParentPlatform platform, List<GbDeviceChannel> items) {
        if (platform == null) {
            return Delivery.failure("平台为空");
        }
        List<GbDeviceChannel> target = items == null ? List.of() : items;
        int pageSize = pageSizeOf(platform);
        int total = target.size();
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        String localIp = headerProvider.localIp();
        String transport = transportOf(platform);
        long timeoutMs = ackTimeoutMs();

        for (int page = 0; page < pages; page++) {
            int start = page * pageSize;
            int end = Math.min(start + pageSize, total);
            List<GbDeviceChannel> chunk = new ArrayList<>(target.subList(start, end));
            String sn = String.valueOf(PlatformCommander.nextSn());
            try {
                String xml = PlatformNotifyXml.buildCatalogList(platform, properties.getId(),
                        properties.getId(), chunk, sn);
                CallIdHeader callId = sipSender.newCallId(localIp, transport);
                if (callId == null) {
                    return Delivery.failure("无法创建 Call-ID（SIP Provider 未就绪）");
                }
                Request request = headerProvider.createNotifyRequest(platform, "catalog", xml, callId);
                Delivery delivery = sendAndWait(localIp, request, timeoutMs);
                if (!delivery.ok()) {
                    log.warn("[上级推送] 快照分片失败 上级平台:{} SN:{} 分片:{}/{} 条目:{} 原因:{}",
                            platform.getServerGbId(), sn, page + 1, pages, chunk.size(), delivery.error());
                    return delivery;
                }
            } catch (Exception e) {
                log.warn("[上级推送] 快照分片异常 上级平台:{} SN:{} 分片:{}/{} 错误:{}",
                        platform.getServerGbId(), sn, page + 1, pages, e.getMessage(), e);
                return Delivery.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        log.info("[上级推送] 全量快照送达 上级平台:{} 条目:{} 分片:{} 结构:{}",
                platform.getServerGbId(), total, pages, PlatformCatalogXml.describe(target));
        return Delivery.success();
    }

    /** 该平台生效的单包 Item 数：per-platform 配置优先（1/2/4/8），越界回落全局默认 */
    public int pageSizeOf(GbParentPlatform platform) {
        int global = Math.max(1, properties.getCascade().getCatalogPageSize());
        if (platform == null || platform.getCatalogGroup() == null) {
            return global;
        }
        int value = platform.getCatalogGroup();
        return switch (value) {
            case 1, 2, 4, 8 -> value;
            default -> global;
        };
    }

    // ------------------------------------------------------------------

    private Delivery sendAndWait(String localIp, Request request, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<ResponseEvent> onOk = e -> latch.countDown();
        Consumer<ResponseEvent> onError = e -> {
            int status = e.getResponse() == null ? -1 : e.getResponse().getStatusCode();
            error.set("SIP " + status);
            latch.countDown();
        };
        Runnable onTimeout = () -> {
            error.set("无应答（超时 " + timeoutMs + "ms）");
            latch.countDown();
        };
        sipSender.send(localIp, request, onOk, onError, onTimeout, timeoutMs);
        boolean done = latch.await(timeoutMs + LATCH_GRACE_MS, TimeUnit.MILLISECONDS);
        if (!done) {
            return Delivery.failure("无应答（等待 " + (timeoutMs + LATCH_GRACE_MS) + "ms）");
        }
        String reason = error.get();
        return reason == null ? Delivery.success() : Delivery.failure(reason);
    }

    private long ackTimeoutMs() {
        long configured = properties.getCascade().getCatalogAckTimeoutMs();
        return Math.max(500L, configured);
    }

    private static String transportOf(GbParentPlatform platform) {
        return platform.getTransport() == null ? "UDP" : platform.getTransport();
    }

    private static String nodeIdOf(GbDeviceChannel item) {
        if (item == null) {
            return "-";
        }
        boolean group = item.getParental() != null && item.getParental() == 1;
        return group ? item.getChannelId() : item.getExposedOrChannelId();
    }
}
