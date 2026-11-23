package com.solo.domain.share.service.impl;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.service.ShareOutboxWriter;
import com.solo.domain.share.support.ShareEvent;
import com.solo.infra.mapper.OutGroupMapper;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 状态跟随（6.6）：只把"真的变了"的状态补成出站事件。
 *
 * <p>最容易写错的两处：① 首次见到共享项就把 OFF 发出去（上级会看到一次莫名其妙的掉线）；
 * ② 每轮同步都给同一批离线通道重发 OFF（把推送额度烧在没变化的事情上）。</p>
 */
class ShareStatusFollowServiceImplTest {

    private static final long PLATFORM = 9L;
    private static final long CHANNEL_ID = 100L;

    private OutShareMapper outShareMapper;
    private ResChannelMapper channelMapper;
    private ShareOutboxWriter outboxWriter;
    private OutCodeService outCodeService;
    private ShareStatusFollowServiceImpl service;

    @BeforeEach
    void setUp() {
        outShareMapper = mock(OutShareMapper.class);
        channelMapper = mock(ResChannelMapper.class);
        outboxWriter = mock(ShareOutboxWriter.class);
        outCodeService = mock(OutCodeService.class);
        service = new ShareStatusFollowServiceImpl(outShareMapper, channelMapper,
                mock(ResDeviceMapper.class), mock(OutGroupMapper.class), outCodeService, outboxWriter);
    }

    @Test
    @DisplayName("来源平台为空 → 直接返回，不查库")
    void ignoresNullPlatform() {
        assertThat(service.afterMerge(null)).isZero();

        verifyNoInteractions(outShareMapper, outboxWriter);
    }

    @Test
    @DisplayName("没有状态变化 → 零事件、零回写")
    void doesNothingWhenNothingChanged() {
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of());

        assertThat(service.afterMerge(PLATFORM)).isZero();

        verify(outShareMapper, never()).updateRefOnlineStatus(anyLong(), anyString(), any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("首次见到共享项（库里为空）→ 只落基线，不发事件")
    void recordsBaselineWithoutEvent() {
        OutShare row = row(1L, null, "OFF");
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of(row));

        assertThat(service.afterMerge(PLATFORM)).isZero();

        verify(outShareMapper).updateRefOnlineStatus(eq(1L), eq("OFF"), any(LocalDateTime.class));
        verifyNoInteractions(outboxWriter, outCodeService);
    }

    @Test
    @DisplayName("在线 → 离线：发一条 OFF，payload 里的 Status 也是 OFF")
    void emitsOffWhenChannelGoesOffline() {
        OutShare row = row(1L, "ON", "OFF");
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of(row));
        when(outCodeService.exposedCodeOf(PLATFORM, "CHANNEL", "34020000001310000001"))
                .thenReturn("34029900001310000042");
        when(channelMapper.selectById(CHANNEL_ID)).thenReturn(channel("OFF"));
        when(outboxWriter.submit(eq(PLATFORM), any(), eq(false))).thenReturn(1);

        assertThat(service.afterMerge(PLATFORM)).isEqualTo(1);

        ShareEvent event = captureSingleEvent();
        assertThat(event.eventType()).isEqualTo(PublishEventType.OFF);
        assertThat(event.refCode()).isEqualTo("34020000001310000001");
        assertThat(event.exposedCode()).isEqualTo("34029900001310000042");
        assertThat(event.payload()).containsEntry("status", "OFF");
        verify(outShareMapper).updateRefOnlineStatus(eq(1L), eq("OFF"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("离线 → 在线：发一条 ON（上级据此把状态翻回来）")
    void emitsOnWhenChannelComesBack() {
        OutShare row = row(1L, "OFF", "ON");
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of(row));
        when(outCodeService.exposedCodeOf(PLATFORM, "CHANNEL", "34020000001310000001"))
                .thenReturn("34029900001310000042");
        when(channelMapper.selectById(CHANNEL_ID)).thenReturn(channel("ON"));
        when(outboxWriter.submit(eq(PLATFORM), any(), eq(false))).thenReturn(1);

        assertThat(service.afterMerge(PLATFORM)).isEqualTo(1);

        ShareEvent event = captureSingleEvent();
        assertThat(event.eventType()).isEqualTo(PublishEventType.ON);
        assertThat(event.payload()).containsEntry("status", "ON");
    }

    @Test
    @DisplayName("没有暴露码 → 不发事件（上级定位不到节点），但基线照样推进")
    void skipsWhenNoExposedCode() {
        OutShare row = row(1L, "ON", "OFF");
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of(row));
        when(outCodeService.exposedCodeOf(anyLong(), anyString(), anyString())).thenReturn(null);

        assertThat(service.afterMerge(PLATFORM)).isZero();

        verify(outShareMapper).updateRefOnlineStatus(eq(1L), eq("OFF"), any(LocalDateTime.class));
        verify(outboxWriter, never()).submit(anyLong(), any(), eq(false));
    }

    @Test
    @DisplayName("多个上级的状态变化按平台分组，各自独立提交")
    void groupsEventsByPlatform() {
        OutShare a = row(1L, "ON", "OFF");
        OutShare b = row(2L, "ON", "OFF");
        b.setPlatformId(10L);
        when(outShareMapper.selectStatusChangedBySource(PLATFORM, 5000)).thenReturn(List.of(a, b));
        when(outCodeService.exposedCodeOf(anyLong(), anyString(), anyString()))
                .thenReturn("34029900001310000042");
        when(channelMapper.selectById(CHANNEL_ID)).thenReturn(channel("OFF"));
        when(outboxWriter.submit(anyLong(), any(), eq(false))).thenReturn(1);

        assertThat(service.afterMerge(PLATFORM)).isEqualTo(2);

        verify(outboxWriter).submit(eq(PLATFORM), any(), eq(false));
        verify(outboxWriter).submit(eq(10L), any(), eq(false));
    }

    // ------------------------------------------------------------------

    private ShareEvent captureSingleEvent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShareEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxWriter).submit(eq(PLATFORM), captor.capture(), eq(false));
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    private static OutShare row(Long id, String stored, String current) {
        OutShare row = new OutShare();
        row.setId(id);
        row.setPlatformId(PLATFORM);
        row.setRefType("CHANNEL");
        row.setRefId(CHANNEL_ID);
        row.setRefCode("34020000001310000001");
        row.setRefAlive(1);
        row.setEnabled(1);
        row.setStoredOnlineStatus(stored);
        row.setRefOnlineStatus(current);
        return row;
    }

    private static ResChannel channel(String onlineStatus) {
        ResChannel channel = new ResChannel();
        channel.setId(CHANNEL_ID);
        channel.setCode("34020000001310000001");
        channel.setName("测试通道");
        channel.setOnlineStatus(onlineStatus);
        return channel;
    }
}
