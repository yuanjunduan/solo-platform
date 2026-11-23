package com.solo.domain.resource.service.impl;

import com.solo.api.resource.dto.ObservabilitySummaryResponse;
import com.solo.domain.resource.service.ObservabilityService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.GbBroadcastSessionService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.infra.mapper.ResPlayRecordMapper;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.properties.MediaProperties;
import com.solo.media.service.MediaServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 运行自检聚合：节点不可达要显式标出、同一路流多 schema 要聚合、记录统计失败要降级为 0。
 */
@ExtendWith(MockitoExtension.class)
class ObservabilityServiceImplTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private GbInviteSessionService inviteSessionService;
    @Mock
    private CascadeSendSessionService cascadeSendSessionService;
    @Mock
    private GbBroadcastSessionService broadcastSessionService;
    @Mock
    private MediaServerService mediaServerService;
    @Mock
    private ZlmRestClient zlmRestClient;
    @Mock
    private ResPlayRecordMapper playRecordMapper;

    private ObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new ObservabilityServiceImpl(inviteSessionService, cascadeSendSessionService,
                broadcastSessionService, mediaServerService, zlmRestClient, playRecordMapper,
                new Gb28181Properties(), new MediaProperties());
    }

    private MediaServer server() {
        MediaServer s = new MediaServer();
        s.setServerId("zlm-1");
        s.setName("节点一");
        s.setHostIp("10.0.0.5");
        s.setStatus(1);
        return s;
    }

    private GbInviteSession session(String streamId) {
        GbInviteSession s = new GbInviteSession();
        s.setStreamId(streamId);
        s.setServerId("zlm-1");
        s.setStatus(GbInviteSession.STATUS_READY);
        s.setSessionType(GbInviteSession.TYPE_PLAY);
        s.setStartTime(LocalDateTime.now().minusMinutes(5));
        s.setReadyTime(LocalDateTime.now().minusMinutes(5));
        return s;
    }

    @Test
    void aggregatesSchemasIntoOneStream() {
        MediaServer server = server();
        when(mediaServerService.list()).thenReturn(List.of(server));
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(List.of(
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s1\",\"readerCount\":1,\"totalReaderCount\":2,"
                        + "\"bytesSpeed\":100,\"aliveSecond\":10,\"videoCodec\":\"H264\"}"),
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s1\",\"readerCount\":3,\"totalReaderCount\":5,"
                        + "\"bytesSpeed\":900,\"aliveSecond\":30,\"videoCodec\":\"H264\"}"),
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s2\",\"readerCount\":0,\"totalReaderCount\":0}")));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(session("s1")));
        when(playRecordMapper.selectCount(any())).thenReturn(0L);

        ObservabilitySummaryResponse resp = service.summary();

        assertThat(resp.getNodes()).hasSize(1);
        ObservabilitySummaryResponse.NodeItem node = resp.getNodes().get(0);
        assertThat(node.isReachable()).isTrue();
        assertThat(node.getStreamCount()).isEqualTo(2);
        assertThat(node.getTotalReaders()).isEqualTo(5);
        // 按 readers 倒序：s1 在前
        assertThat(node.getStreams().get(0).getStream()).isEqualTo("s1");
        assertThat(node.getStreams().get(0).getReaders()).isEqualTo(5);
        assertThat(node.getStreams().get(0).getBytesSpeed()).isEqualTo(900);
        assertThat(node.getStreams().get(0).getAliveSeconds()).isEqualTo(30);

        assertThat(resp.getSessions().getTotal()).isEqualTo(1);
        assertThat(resp.getSessions().getReady()).isEqualTo(1);
        assertThat(resp.getActiveSessions()).hasSize(1);
        assertThat(resp.getActiveSessions().get(0).getReaders()).isEqualTo(5);
        assertThat(resp.getActiveSessions().get(0).isExternalConsumer()).isFalse();
        assertThat(resp.getRecordsToday().getTotal()).isZero();
        assertThat(resp.getConfig().getIdleReclaimSec()).isEqualTo(120);
    }

    @Test
    void marksNodeUnreachableWhenMediaListUnavailable() {
        MediaServer server = server();
        when(mediaServerService.list()).thenReturn(List.of(server));
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(null);
        when(inviteSessionService.listAllActive()).thenReturn(List.of());
        when(playRecordMapper.selectCount(any())).thenReturn(0L);

        ObservabilitySummaryResponse resp = service.summary();

        assertThat(resp.getNodes()).hasSize(1);
        assertThat(resp.getNodes().get(0).isReachable()).isFalse();
        assertThat(resp.getNodes().get(0).getStreams()).isEmpty();
        assertThat(resp.getNodes().get(0).getTotalReaders()).isZero();
    }

    @Test
    void flagsSessionConsumedByCascade() {
        MediaServer server = server();
        when(mediaServerService.list()).thenReturn(List.of(server));
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(List.of());
        when(inviteSessionService.listAllActive()).thenReturn(List.of(session("s1")));
        CascadeSendSession send = new CascadeSendSession();
        send.setSrcStream("s1");
        when(cascadeSendSessionService.listAllActive()).thenReturn(List.of(send));
        when(playRecordMapper.selectCount(any())).thenReturn(0L);

        ObservabilitySummaryResponse resp = service.summary();

        assertThat(resp.getCascadeSendCount()).isEqualTo(1);
        assertThat(resp.getActiveSessions()).hasSize(1);
        assertThat(resp.getActiveSessions().get(0).isExternalConsumer()).isTrue();
    }

    @Test
    void summarizesBandwidthWithTopStreams() {
        MediaServer server = server();
        when(mediaServerService.list()).thenReturn(List.of(server));
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(List.of(
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s1\",\"readerCount\":2,\"bytesSpeed\":500000}"),
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s2\",\"readerCount\":1,\"bytesSpeed\":1250000}"),
                // 刚建流还没起量：算人、不算带宽
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"s3\",\"readerCount\":0,\"bytesSpeed\":0}")));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(session("s1")));
        CascadeSendSession send = new CascadeSendSession();
        send.setSrcStream("s1");
        when(cascadeSendSessionService.listAllActive()).thenReturn(List.of(send));
        when(playRecordMapper.selectCount(any())).thenReturn(0L);

        ObservabilitySummaryResponse.BandwidthStats bandwidth = service.summary().getBandwidth();

        assertThat(bandwidth.getStreamCount()).isEqualTo(2);
        assertThat(bandwidth.getTotalBytesPerSec()).isEqualTo(1_750_000L);
        assertThat(bandwidth.getTotalMbps()).isEqualTo(14.0);
        assertThat(bandwidth.getTotalReaders()).isEqualTo(3);
        assertThat(bandwidth.getTopStreams()).hasSize(2);
        // 码率倒序：s2 在头
        assertThat(bandwidth.getTopStreams().get(0).getStream()).isEqualTo("s2");
        assertThat(bandwidth.getTopStreams().get(0).getMbps()).isEqualTo(10.0);
        assertThat(bandwidth.getTopStreams().get(0).isExternalConsumer()).isFalse();
        assertThat(bandwidth.getTopStreams().get(1).getStream()).isEqualTo("s1");
        assertThat(bandwidth.getTopStreams().get(1).isExternalConsumer()).isTrue();
    }

    @Test
    void recordStatsDegradeToZeroOnQueryFailure() {
        MediaServer server = server();
        when(mediaServerService.list()).thenReturn(List.of(server));
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(List.of());
        when(inviteSessionService.listAllActive()).thenReturn(List.of());
        when(playRecordMapper.selectCount(any())).thenThrow(new RuntimeException("db down"));

        ObservabilitySummaryResponse resp = service.summary();

        assertThat(resp.getRecordsToday().getTotal()).isZero();
        assertThat(resp.getRecordsToday().getSuccess()).isZero();
        assertThat(resp.getRecordsToday().getFailed()).isZero();
        assertThat(resp.getRecordsToday().getOpen()).isZero();
    }
}
