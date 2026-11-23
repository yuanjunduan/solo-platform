package com.solo.gb28181.support;

import com.solo.domain.resource.service.PlayRecordService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.GbBroadcastSessionService;
import com.solo.gb28181.service.GbInviteSessionCleanupService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.mapper.GbMediaServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空闲回收（3.11.3）：只回收"READY 且确实没人看"的点播/回放会话，
 * 并且必须把级联/广播消费与节点不可达这两种"问不到 reader"的情况排除在外。
 */
@ExtendWith(MockitoExtension.class)
class GbIdleReclaimServiceTest {

    private static final String STREAM = "34020000001320000001_34020000001310000001";
    private static final String SERVER_ID = "zlm-1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private GbInviteSessionService inviteSessionService;
    @Mock
    private GbInviteSessionCleanupService inviteCleanup;
    @Mock
    private GbMediaServerMapper mediaServerMapper;
    @Mock
    private ZlmRestClient zlmRestClient;
    @Mock
    private CascadeSendSessionService cascadeSendSessionService;
    @Mock
    private GbBroadcastSessionService broadcastSessionService;
    @Mock
    private PlayRecordService playRecordService;

    private Gb28181Properties properties;
    private GbIdleReclaimService service;

    @BeforeEach
    void setUp() {
        properties = new Gb28181Properties();
        service = new GbIdleReclaimService(properties, inviteSessionService, inviteCleanup,
                mediaServerMapper, zlmRestClient, cascadeSendSessionService, broadcastSessionService,
                playRecordService);
    }

    private GbInviteSession session(String status, String type, LocalDateTime readyTime) {
        GbInviteSession s = new GbInviteSession();
        s.setStreamId(STREAM);
        s.setServerId(SERVER_ID);
        s.setStatus(status);
        s.setSessionType(type);
        s.setStartTime(readyTime);
        s.setReadyTime(readyTime);
        return s;
    }

    private MediaServer server() {
        MediaServer s = new MediaServer();
        s.setServerId(SERVER_ID);
        return s;
    }

    private List<JsonNode> mediaList(String json) {
        return List.of(JSON.readTree(json));
    }

    @Test
    void reapsReadySessionWithoutReadersBeyondThreshold() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(mediaList(
                "{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":0,\"totalReaderCount\":0}"));

        assertThat(service.reap()).isEqualTo(1);

        verify(inviteCleanup).failAndBye(eq(s), anyString(), contains("无观看者"));
        verify(playRecordService).closeByStreamId(STREAM);
        verify(inviteSessionService, never()).update(any());
    }

    @Test
    void keepsSessionWhenZlmStillHasReaders() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        s.setLastReaderTime(LocalDateTime.now().minusSeconds(10));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(mediaList(
                "{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":1,\"totalReaderCount\":2}"));

        assertThat(service.reap()).isZero();

        // 有人看：把 lastReaderTime 往后推，避免下一轮又从头算起
        verify(inviteSessionService).update(s);
        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
    }

    @Test
    void doesNotReapWhenOnlySchemaEntryDiffers() {
        // 同一路流按 schema 有多条，只有 totalReaderCount 是全量；命中第一条不能下结论
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(List.of(
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":0,\"totalReaderCount\":0}"),
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":0,\"totalReaderCount\":3}"),
                JSON.readTree("{\"app\":\"rtp\",\"stream\":\"other\",\"readerCount\":9,\"totalReaderCount\":9}")));

        assertThat(service.reap()).isZero();

        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
    }

    @Test
    void treatsCascadeConsumerAsViewer() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        CascadeSendSession send = new CascadeSendSession();
        send.setSrcStream(STREAM);
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        when(cascadeSendSessionService.listBySrcStream(STREAM)).thenReturn(List.of(send));

        assertThat(service.reap()).isZero();

        // 上级正在看：不问节点，也不回收
        verify(zlmRestClient, never()).getMediaListOrNull(any());
        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
        verify(inviteSessionService).update(s);
    }

    @Test
    void skipsRoundWhenMediaNodeUnreachable() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(null);

        assertThat(service.reap()).isZero();

        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
    }

    @Test
    void ignoresSessionStillInsideThreshold() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(30));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(mediaList(
                "{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":0,\"totalReaderCount\":0}"));

        assertThat(service.reap()).isZero();

        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
    }

    @Test
    void onlyChecksPlayAndPlaybackSessions() {
        properties.getPlay().setIdleReclaimSec(120);
        when(inviteSessionService.listAllActive()).thenReturn(List.of(
                session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_RECORD,
                        LocalDateTime.now().minusSeconds(600)),
                session(GbInviteSession.STATUS_INVITING, GbInviteSession.TYPE_PLAY,
                        LocalDateTime.now().minusSeconds(600))));

        assertThat(service.reap()).isZero();

        verify(mediaServerMapper, never()).selectOne(any());
        verify(inviteCleanup, never()).failAndBye(any(), anyString(), anyString());
    }

    @Test
    void disabledWhenThresholdNotPositive() {
        properties.getPlay().setIdleReclaimSec(0);

        assertThat(service.reap()).isZero();

        verify(inviteSessionService, never()).listAllActive();
    }

    @Test
    void throttlesLastReaderTimeWrite() {
        properties.getPlay().setIdleReclaimSec(120);
        GbInviteSession s = session(GbInviteSession.STATUS_READY, GbInviteSession.TYPE_PLAY,
                LocalDateTime.now().minusSeconds(600));
        s.setLastReaderTime(LocalDateTime.now().minusSeconds(1));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(s));
        MediaServer server = server();
        when(mediaServerMapper.selectOne(any())).thenReturn(server);
        when(zlmRestClient.getMediaListOrNull(server)).thenReturn(mediaList(
                "{\"app\":\"rtp\",\"stream\":\"" + STREAM + "\",\"readerCount\":1,\"totalReaderCount\":1}"));

        assertThat(service.reap()).isZero();

        // 5s 内不重复写 Redis
        verify(inviteSessionService, never()).update(any());
    }

    @Test
    void streamHasReaderChecksExternalConsumersFirst() {
        assertThat(service.streamHasReader(null, STREAM)).isFalse();

        CascadeSendSession send = new CascadeSendSession();
        send.setSrcStream(STREAM);
        when(cascadeSendSessionService.listBySrcStream(STREAM)).thenReturn(List.of(send));
        MediaServer server = server();
        assertThat(service.streamHasReader(server, STREAM)).isTrue();
        verify(zlmRestClient, never()).getMediaListOrNull(any());
    }
}
