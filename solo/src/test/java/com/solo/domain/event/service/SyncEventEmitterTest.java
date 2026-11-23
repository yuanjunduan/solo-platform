package com.solo.domain.event.service;

import com.solo.domain.event.model.ChangeEvent;
import com.solo.domain.event.support.EventSettings;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.infra.mapper.ChangeEventReadMapper;
import com.solo.infra.mapper.projection.ChangedRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 同步收尾的事件翻译（3.11.7）。
 *
 * <p>钉住两条最贵的规则：<b>5 万条变化只发 1 条 REFRESH_ALL 且一条明细都不回读</b>、
 * <b>消失标记算 REMOVED 而不是 UPDATED</b>。前者决定大轮次会不会把库打穿，
 * 后者决定订阅方会不会把"已删除"当成"改了个名字"。</p>
 */
class SyncEventEmitterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 10, 0, 0);

    private final ChangeEventReadMapper readMapper = mock(ChangeEventReadMapper.class);
    private final ChangeEventPublisher publisher = mock(ChangeEventPublisher.class);
    private final EventSettings settings = mock(EventSettings.class);
    private final SyncEventEmitter emitter = new SyncEventEmitter(readMapper, publisher, settings);

    private static ChangedRow row(String code, String status, LocalDateTime createTime) {
        ChangedRow row = new ChangedRow();
        row.setCode(code);
        row.setName("摄像头 " + code);
        row.setOrgPath("/1/5/");
        row.setStatus(status);
        row.setOnlineStatus("ONLINE");
        row.setCreateTime(createTime);
        return row;
    }

    private void stubDetailFactory() {
        when(publisher.detail(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    ChangeEvent event = new ChangeEvent();
                    event.setItemType(inv.getArgument(0));
                    event.setCode(inv.getArgument(1));
                    event.setChangeType(inv.getArgument(2));
                    event.setOrgPath(inv.getArgument(3));
                    return event;
                });
    }

    @Test
    void noChangePublishesNothing() {
        when(settings.getDetailMax()).thenReturn(500);

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 0, false, false);

        // 无变化不推送：否则订阅方每分钟醒一次、每次什么都没拿到
        verifyNoInteractions(publisher, readMapper);
    }

    @Test
    void hugeRoundDegradesToSingleRefreshWithoutReadingDetails() {
        when(settings.getDetailMax()).thenReturn(500);

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 50_000, false, false);

        // 5 万条只发 1 条，且不回读明细（回读本身就是一次全表扫）
        verify(publisher).publishRefresh(eq(1L), eq(ChangeEvent.SOURCE_INBOUND), eq(5L), eq(9L), anyString());
        verify(publisher, never()).publishDetails(any(), any(), any(), any(), any());
        verifyNoInteractions(readMapper);
    }

    @Test
    void treeRebuildDegradesEvenWhenFewRowsChanged() {
        when(settings.getDetailMax()).thenReturn(500);

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 3, true, false);

        verify(publisher).publishRefresh(any(), any(), any(), any(), anyString());
        verifyNoInteractions(readMapper);
    }

    @Test
    void skippedDeleteDegradesBecauseDetailSemanticsIsIncomplete() {
        when(settings.getDetailMax()).thenReturn(500);

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 3, false, true);

        verify(publisher).publishRefresh(any(), any(), any(), any(), anyString());
        verifyNoInteractions(readMapper);
    }

    @Test
    void smallRoundPublishesDetailsWithCorrectChangeType() {
        when(settings.getDetailMax()).thenReturn(500);
        stubDetailFactory();
        when(readMapper.readOrgChanges(eq(5L), eq(NOW), anyString(), anyInt()))
                .thenReturn(List.of(row("org-1", ResourceStatus.STALE, NOW.minusDays(2))));
        when(readMapper.readDeviceChanges(eq(5L), eq(NOW), anyString(), anyInt()))
                .thenReturn(List.of(row("dev-new", ResourceStatus.ACTIVE, NOW)));
        when(readMapper.readChannelChanges(eq(5L), eq(NOW), anyInt()))
                .thenReturn(List.of(row("ch-old", ResourceStatus.ACTIVE, NOW.minusDays(30))));

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 3, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishDetails(eq(1L), eq(ChangeEvent.SOURCE_INBOUND), eq(5L), eq(9L), captor.capture());

        assertThat(captor.getValue())
                // 消失标记先判：STALE 也写 update_time，按 create_time 会误判成 UPDATED
                .extracting(ChangeEvent::getCode, ChangeEvent::getChangeType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("org-1", ChangeEvent.CHANGE_REMOVED),
                        org.assertj.core.groups.Tuple.tuple("dev-new", ChangeEvent.CHANGE_ADDED),
                        org.assertj.core.groups.Tuple.tuple("ch-old", ChangeEvent.CHANGE_UPDATED));
    }

    @Test
    void emptyDetailReadbackDegradesToRefresh() {
        when(settings.getDetailMax()).thenReturn(500);
        stubDetailFactory();
        when(readMapper.readOrgChanges(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(readMapper.readDeviceChanges(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(readMapper.readChannelChanges(any(), any(), anyInt())).thenReturn(List.of());

        emitter.emitInbound(1L, 5L, "34020000002000000001", 9L, NOW, 3, false, false);

        // 有影响行数却回读不到明细：宁可让客户端重拉快照，也不发一份"看起来没变"的空明细
        verify(publisher).publishRefresh(any(), any(), any(), any(), anyString());
        verify(publisher, never()).publishDetails(any(), any(), any(), any(), any());
    }
}
