package com.solo.domain.event.service;

import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.support.EventSettings;
import com.solo.domain.event.support.EventSignal;
import com.solo.domain.event.support.EventStreamRejectedException;
import com.solo.infra.mapper.ChangeEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 建连游标判定与连接上限（6.4 验收标准 ④ / 容量保护）。
 *
 * <p>这四处判定都在<b>建立连接之前</b>完成：判不过直接 409/429，
 * 不允许"先回 200 再莫名断流"——那种故障对接方只能猜。</p>
 */
class EventStreamServiceTest {

    private final ChangeEventMapper eventMapper = mock(ChangeEventMapper.class);
    private final EventSubscriptionService subscriptionService = mock(EventSubscriptionService.class);
    private final EventSettings settings = mock(EventSettings.class);
    private final EventSignal signal = new EventSignal();

    private EventStreamService service;

    private EventStreamService service() {
        if (service == null) {
            when(settings.getMaxConnections()).thenReturn(1);
            when(settings.getBatchSize()).thenReturn(200);
            when(settings.getIdlePollMs()).thenReturn(50L);
            when(settings.getHeartbeatMs()).thenReturn(60_000L);
            when(settings.getQueueSize()).thenReturn(1000);
            when(settings.getStreamTimeoutMs()).thenReturn(60_000L);
            service = new EventStreamService(eventMapper, subscriptionService, settings, signal,
                    new ObjectMapper());
        }
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            // 打断可能已拉起的虚拟线程，避免测试之间串状态
            service.shutdown();
        }
    }

    private static EventSubscription subscription(Long lastEventId, Long platformId, String orgPath) {
        EventSubscription sub = new EventSubscription();
        sub.setId(1L);
        sub.setTenantId(1L);
        sub.setAppKey("ak_test");
        sub.setTopic(EventSubscription.TOPIC_RESOURCE);
        sub.setLastEventId(lastEventId);
        sub.setPlatformId(platformId);
        sub.setOrgPath(orgPath);
        sub.setStatus(EventSubscription.STATUS_ENABLED);
        return sub;
    }

    @Test
    void firstConnectStartsFromCurrentMaxWithoutReplay() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);

        // 首连不回放历史：历史走快照接口，否则一次首连要吐整张表
        assertThat(service().resolveStartCursor(subscription(null, null, null), null)).isEqualTo(120L);
        assertThat(service().resolveStartCursor(subscription(0L, null, null), null)).isEqualTo(120L);
    }

    @Test
    void serverCursorWinsWhenClientSendsSmallerOne() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);

        // 客户端乱传更小的游标不能让服务端回放已发过的事件
        assertThat(service().resolveStartCursor(subscription(110L, null, null), 100L)).isEqualTo(110L);
        // 客户端更靠前则以客户端为准（比如换了实例、服务端记账还没跟上）
        assertThat(service().resolveStartCursor(subscription(100L, null, null), 115L)).isEqualTo(115L);
    }

    @Test
    void cursorAheadOfMaxIsRejected() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);

        assertThatThrownBy(() -> service().resolveStartCursor(subscription(null, null, null), 200L))
                .isInstanceOf(EventStreamRejectedException.class)
                .satisfies(e -> {
                    EventStreamRejectedException rejected = (EventStreamRejectedException) e;
                    assertThat(rejected.getReason()).isEqualTo(EventStreamRejectedException.Reason.CURSOR_EXPIRED);
                    assertThat(rejected.getMinSeq()).isEqualTo(90L);
                    assertThat(rejected.getMaxSeq()).isEqualTo(120L);
                    assertThat(rejected.getSnapshotUrl()).isEqualTo("/open-api/v1/snapshot");
                });
    }

    @Test
    void cursorBeforeRetentionWindowIsRejected() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);

        // 89 + 1 < 90：中间那条 90 已经被回收，补不上了
        assertThatThrownBy(() -> service().resolveStartCursor(subscription(null, null, null), 88L))
                .isInstanceOf(EventStreamRejectedException.class)
                .satisfies(e -> assertThat(((EventStreamRejectedException) e).getReason())
                        .isEqualTo(EventStreamRejectedException.Reason.CURSOR_EXPIRED));

        // 89 + 1 == 90：窗口下界本身还在，可以接着推
        assertThat(service().resolveStartCursor(subscription(89L, null, null), null)).isEqualTo(89L);
    }

    @Test
    void rejectionCarriesScopeAwareSnapshotUrl() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);

        assertThatThrownBy(() -> service().resolveStartCursor(subscription(null, 5L, "/1/5/"), 200L))
                .isInstanceOf(EventStreamRejectedException.class)
                .satisfies(e -> assertThat(((EventStreamRejectedException) e).getSnapshotUrl())
                        // 快照地址必须带着 scope，客户端拿不到它范围之外的口子
                        .isEqualTo("/open-api/v1/snapshot?platformId=5&orgPath=%2F1%2F5%2F"));
    }

    @Test
    void saturatedConnectionIsRejectedWith429Reason() {
        when(eventMapper.selectMinSeq(1L)).thenReturn(90L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(120L);
        when(eventMapper.selectAfter(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        EventStreamService svc = service();
        svc.open(subscription(120L, null, null), null);

        assertThatThrownBy(() -> svc.open(subscription(120L, null, null), null))
                .isInstanceOf(EventStreamRejectedException.class)
                .satisfies(e -> assertThat(((EventStreamRejectedException) e).getReason())
                        .isEqualTo(EventStreamRejectedException.Reason.SATURATED));
    }
}
