package com.solo.domain.event.service;

import com.solo.domain.event.model.ChangeEvent;
import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.support.EventScope;
import com.solo.domain.event.support.EventSettings;
import com.solo.domain.event.support.EventSignal;
import com.solo.domain.event.support.EventStreamRejectedException;
import com.solo.infra.mapper.ChangeEventMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订阅事件流（6.4 第三阶段 / 3.11.7）。
 *
 * <p><b>为什么是"查库推流"而不是"Redis 扇出"</b>：游标的唯一事实源是
 * {@code change_event.seq}。用发布订阅做投递，补偿逻辑（谁没收到、重连补哪里）
 * 最后还是要回到查库；既然如此，直接把"查库 + 推进游标"做成唯一路径，
 * 多实例就天然一致——每个实例各自按游标查、各自推、游标只进不退。</p>
 *
 * <p><b>三条硬要求落在哪：</b></p>
 * <ol>
 *   <li><b>游标不能跳号</b>：每条事件都带 {@code id: seq}，包括 {@code REFRESH_ALL}；
 *       连落后提示（lagged）都故意不带 id——它只是提醒，不是事件。</li>
 *   <li><b>背压</b>：单连接固定 {@code batch-size} 一批，发完再查下一批；落后超过
 *       {@code queue-size} 时发一条 lagged 提示让对接方改拉快照，但<b>绝不跳过事件</b>。</li>
 *   <li><b>超窗口 409</b>：连接建立前就判，判不过直接拒（{@link EventStreamRejectedException}），
 *       不进入推流阶段——避免客户端先收到 200 再莫名断流。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：一个连接一条虚拟线程阻塞在"查库 / 等信号"上。集成方数量是
 * 个位数到几十，虚拟线程让阻塞等待几乎不占平台线程；真正的上限由
 * {@code event.max-connections} 显式卡住，防止连接数把内存吃光。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventStreamService {

    /** 事件名：两种形态共用（形态写在 data.shape 里，客户端只挂一个监听） */
    public static final String EVENT_NAME = "resource.changed";
    /** 落后提示：不带 id，不占游标 */
    public static final String EVENT_LAGGED = "lagged";
    /** 建连确认：把本次起点、scope、心跳间隔回给对接方，省一轮对账 */
    public static final String EVENT_READY = "ready";

    /** 客户端重连间隔（毫秒），写进 SSE 的 retry 字段 */
    private static final long RECONNECT_MS = 3000;
    /** 全量快照地址（M3-S5）：409 与 lagged 都用它 */
    public static final String SNAPSHOT_PATH = "/open-api/v1/snapshot";

    private final ChangeEventMapper eventMapper;
    private final EventSubscriptionService subscriptionService;
    private final EventSettings settings;
    private final EventSignal signal;
    private final ObjectMapper objectMapper;

    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 建立一条订阅连接。
     *
     * @param requested 客户端携带的 {@code Last-Event-ID}（可为空）
     * @throws EventStreamRejectedException 并发已满或游标超出保留窗口
     */
    public SseEmitter open(EventSubscription subscription, Long requested) {
        if (activeConnections.incrementAndGet() > Math.max(1, settings.getMaxConnections())) {
            activeConnections.decrementAndGet();
            throw EventStreamRejectedException.saturated(settings.getMaxConnections());
        }
        long start;
        try {
            start = resolveStartCursor(subscription, requested);
        } catch (RuntimeException e) {
            activeConnections.decrementAndGet();
            throw e;
        }

        SseEmitter emitter = new SseEmitter(settings.getStreamTimeoutMs());
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            // 到点主动收尾：客户端会自动带 Last-Event-ID 重连，顺带换到负载更低的实例
            emitter.complete();
        });

        executor.execute(() -> stream(emitter, subscription, start, closed));
        log.info("[资源事件] 订阅连接已建立 appKey={} scope={} 起点={} 活跃连接={}",
                subscription.getAppKey(), EventScope.of(subscription.getPlatformId(), subscription.getOrgPath()).label(),
                start, activeConnections.get());
        return emitter;
    }

    /**
     * 游标三选一：客户端带的、服务端记账的，取较大者；都没有就从当前最大游标开始。
     *
     * <p>首连没有游标时<b>不回放历史</b>：历史靠快照接口按 scope 拉取，
     * 否则一次首连就要把整张事件表吐一遍（可能几十万条）。</p>
     *
     * <p>包级可见：这条判定是 409 的唯一来源，单测直接钉它，不必拉起连接与线程。</p>
     */
    long resolveStartCursor(EventSubscription subscription, Long requested) {
        long tenantId = tenantOf(subscription);
        long min = eventMapper.selectMinSeq(tenantId);
        long max = eventMapper.selectMaxSeq(tenantId);
        long server = subscription.getLastEventId() == null ? 0L : subscription.getLastEventId();
        long client = requested == null ? 0L : Math.max(0L, requested);
        long effective = Math.max(server, client);
        EventScope scope = EventScope.of(subscription.getPlatformId(), subscription.getOrgPath());

        if (effective <= 0) {
            return max;
        }
        if (effective > max) {
            // 游标超前只可能来自换环境或库重建：它之后永远不会有事件
            throw EventStreamRejectedException.cursorExpired(effective, min, max, snapshotUrl(scope));
        }
        // min 是现存最小事件；effective 之后的事件必须全部还在，即 effective + 1 >= min
        if (effective + 1 < min) {
            throw EventStreamRejectedException.cursorExpired(effective, min, max, snapshotUrl(scope));
        }
        return effective;
    }

    private void stream(SseEmitter emitter, EventSubscription subscription, long start,
                        AtomicBoolean closed) {
        long tenantId = tenantOf(subscription);
        EventScope scope = EventScope.of(subscription.getPlatformId(), subscription.getOrgPath());
        int batchSize = Math.max(1, settings.getBatchSize());
        long idlePollMs = Math.max(200L, settings.getIdlePollMs());
        long heartbeatMs = Math.max(1000L, settings.getHeartbeatMs());
        long cursor = start;
        long lastWrite = System.currentTimeMillis();
        boolean laggedHinted = false;

        try {
            sendReady(emitter, scope, cursor, heartbeatMs);
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                List<ChangeEvent> batch = eventMapper.selectAfter(tenantId, cursor, batchSize);
                if (batch.isEmpty()) {
                    laggedHinted = false;
                    long now = System.currentTimeMillis();
                    if (now - lastWrite >= heartbeatMs) {
                        emitter.send(SseEmitter.event().comment("ping"));
                        lastWrite = now;
                        continue;
                    }
                    long wait = Math.min(idlePollMs, heartbeatMs - (now - lastWrite));
                    signal.await(wait);
                    continue;
                }

                if (batch.size() >= batchSize) {
                    laggedHinted = hintIfLagging(emitter, tenantId, cursor, scope, laggedHinted);
                }
                for (ChangeEvent event : batch) {
                    if (closed.get()) {
                        return;
                    }
                    if (scope.accepts(event)) {
                        // 先写出去再推进游标：写出失败时游标停在最后一条已送达的位置
                        emitter.send(eventOf(event));
                        subscriptionService.advance(subscription, event.getSeq());
                    }
                    cursor = event.getSeq();
                    lastWrite = System.currentTimeMillis();
                }
            }
        } catch (IOException | IllegalStateException e) {
            // 客户端断开 / 容器收回：不是错误，游标停在已送达位置等重连
            log.debug("[资源事件] 订阅连接结束 appKey={} 最后游标={} 原因={}",
                    subscription.getAppKey(), cursor, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[资源事件] 订阅推流异常 appKey={} 最后游标={} err={}",
                    subscription.getAppKey(), cursor, e.getMessage(), e);
        } finally {
            activeConnections.decrementAndGet();
            closed.set(true);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 已结束/已超时，重复 complete 无副作用
            }
            log.info("[资源事件] 订阅连接释放 appKey={} 最后游标={} 活跃连接={}",
                    subscription.getAppKey(), cursor, activeConnections.get());
        }
    }

    /**
     * 落后提示：只发提示、不跳事件（"事件可以合并，游标不能跳号"）。
     * <p>同一段落后只提示一次，避免把提示本身变成刷屏的事件流。</p>
     *
     * @return 本次是否已提示（供调用方记住状态）
     */
    private boolean hintIfLagging(SseEmitter emitter, long tenantId, long cursor,
                                  EventScope scope, boolean alreadyHinted) throws IOException {
        if (alreadyHinted) {
            return true;
        }
        long max = eventMapper.selectMaxSeq(tenantId);
        long behind = max - cursor;
        if (behind <= settings.getQueueSize()) {
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("behind", behind);
        data.put("cursor", cursor);
        data.put("queueSize", settings.getQueueSize());
        data.put("snapshotUrl", snapshotUrl(scope));
        data.put("hint", "积压超过单连接缓冲上限，建议改用快照接口；本连接仍会按序补发，不会跳号");
        emitter.send(SseEmitter.event().name(EVENT_LAGGED).data(data, MediaType.APPLICATION_JSON));
        log.warn("[资源事件] 订阅方落后 tenant={} cursor={} 积压={} 已发送 lagged 提示",
                tenantId, cursor, behind);
        return true;
    }

    private void sendReady(SseEmitter emitter, EventScope scope, long cursor, long heartbeatMs)
            throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cursor", cursor);
        data.put("scope", scope.label());
        data.put("snapshotUrl", snapshotUrl(scope));
        data.put("heartbeatMs", heartbeatMs);
        data.put("shape", "CHANGED_DETAIL/REFRESH_ALL");
        emitter.send(SseEmitter.event()
                .name(EVENT_READY)
                .reconnectTime(RECONNECT_MS)
                .data(data, MediaType.APPLICATION_JSON));
    }

    /**
     * 一条事件的 SSE 帧。{@code id} 必须是 seq——它就是客户端的 {@code Last-Event-ID}。
     */
    private SseEmitter.SseEventBuilder eventOf(ChangeEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", event.getSeq());
        data.put("shape", event.getShape());
        data.put("sourceType", event.getSourceType());
        data.put("platformId", event.getPlatformId());
        data.put("scope", EventScope.labelOf(event));
        data.put("orgPath", event.getOrgPath());
        data.put("refType", event.getItemType());
        data.put("refCode", event.getCode());
        data.put("changeType", event.getChangeType());
        data.put("taskId", event.getTaskId());
        data.put("ts", event.getCreateTime() == null ? null : event.getCreateTime().toString());
        data.put("payload", parsePayload(event.getPayload()));
        return SseEmitter.event()
                .id(String.valueOf(event.getSeq()))
                .name(EVENT_NAME)
                .data(data, MediaType.APPLICATION_JSON);
    }

    /** payload 是库里的 JSON 字符串：解析失败就原样给字符串，不能让一条摘要脏数据掐断连接 */
    private Object parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            return json;
        }
    }

    /** 快照地址按订阅 scope 拼，客户端拿不到它 scope 之外的口子 */
    public static String snapshotUrl(EventScope scope) {
        StringBuilder sb = new StringBuilder(SNAPSHOT_PATH);
        if (scope.platformId() != null) {
            sb.append("?platformId=").append(scope.platformId());
        }
        if (scope.orgPath() != null) {
            sb.append(scope.platformId() == null ? "?" : "&").append("orgPath=")
                    .append(urlEncode(scope.orgPath()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long tenantOf(EventSubscription subscription) {
        return subscription.getTenantId() == null
                ? EventSubscriptionService.DEFAULT_TENANT_ID : subscription.getTenantId();
    }

    /** 当前活跃连接数（自检/测试用） */
    public int activeConnections() {
        return activeConnections.get();
    }

    /** 停止信号：让所有挂在 await 上的连接尽快看到关闭标记 */
    public void wakeAll() {
        signal.raise();
    }

    @PreDestroy
    void shutdown() {
        wakeAll();
        executor.shutdownNow();
    }
}
