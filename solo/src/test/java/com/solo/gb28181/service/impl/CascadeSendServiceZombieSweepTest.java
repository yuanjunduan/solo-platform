package com.solo.gb28181.service.impl;

import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.CatalogShareService;
import com.solo.gb28181.service.GbInviteSessionCleanupService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.gb28181.service.GbParentPlatformService;
import com.solo.gb28181.service.GbPlayService;
import com.solo.gb28181.sip.SipSender;
import com.solo.gb28181.transmit.cmd.PlatformSipHeaderProvider;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.mapper.GbMediaServerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sip.message.Request;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 源流巡检（C21c）：源流已死时要<b>先 stopSendRtp 再发 BYE</b>，顺序反了会出现
 * "BYE 已发、推流还在"的窗口（上级已释放会话，本平台还在往它端口推流）。
 *
 * <p>另一半同样重要：节点问不到、有观看者、还没到阈值，这三种情况一律不许清——误杀会把正常观看掐断。</p>
 */
@ExtendWith(MockitoExtension.class)
class CascadeSendServiceZombieSweepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SERVER_ID = "zlm-1";
    private static final String STREAM = "34020000001320000001_34020000001310000001";
    private static final String SSRC = "0000123456";
    private static final String SEND_STREAM_ID = "send_call-1";
    private static final Long PLATFORM_DB_ID = 7L;

    @Spy
    private Gb28181Properties properties = new Gb28181Properties();
    @Mock
    private CatalogShareService catalogShareService;
    @Mock
    private CascadeSendSessionService sendSessionService;
    @Mock
    private GbInviteSessionService inviteSessionService;
    @Mock
    private GbParentPlatformService gbParentPlatformService;
    @Mock
    private GbPlayService playService;
    @Mock
    private GbInviteSessionCleanupService support;
    @Mock
    private ZlmRestClient zlmRestClient;
    @Mock
    private GbMediaServerMapper mediaServerMapper;
    @Mock
    private PlatformSipHeaderProvider headerProvider;
    @Mock
    private SipSender sipSender;
    @Mock
    private GbParentPlatformMapper gbParentPlatformMapper;

    @InjectMocks
    private CascadeSendService service;

    /** 会话已就绪一分钟、期间没确认到任何 RTP（lastRtpTime 停在 60s 前，超过默认阈值 30s） */
    private GbInviteSession readyInvite() {
        LocalDateTime longAgo = LocalDateTime.now().minusSeconds(60);
        GbInviteSession s = new GbInviteSession();
        s.setStreamId(STREAM);
        s.setServerId(SERVER_ID);
        s.setDeviceId("34020000001320000001");
        s.setChannelId("34020000001310000001");
        s.setStatus(GbInviteSession.STATUS_READY);
        s.setSessionType(GbInviteSession.TYPE_PLAY);
        s.setReadyTime(longAgo);
        s.setLastRtpTime(longAgo);
        return s;
    }

    private CascadeSendSession sendSession() {
        CascadeSendSession s = new CascadeSendSession();
        s.setSendStreamId(SEND_STREAM_ID);
        s.setServerId(SERVER_ID);
        s.setPlatformDbId(PLATFORM_DB_ID);
        s.setPlatformServerGbId("34020000002000000001");
        s.setLocalChannelId("34020000001310000001");
        s.setSrcApp("rtp");
        s.setSrcStream(STREAM);
        s.setSsrc(SSRC);
        s.setCallId("call-1");
        s.setFromTag("upstream-from-tag");
        s.setToTag("local-to-tag");
        s.setStatus(CascadeSendSession.STATUS_SENDING);
        return s;
    }

    private MediaServer server() {
        MediaServer s = new MediaServer();
        s.setServerId(SERVER_ID);
        return s;
    }

    /** 造一条 ZLM getMediaList 条目（字段名与 2024-08 构建一致） */
    private static JsonNode media(String stream, long bytesSpeed, int readers) throws Exception {
        return MAPPER.readTree("""
                {"app":"rtp","stream":"%s","schema":"rtsp","bytesSpeed":%d,"readerCount":%d,"totalReaderCount":%d}"""
                .formatted(stream, bytesSpeed, readers, readers));
    }

    @Test
    void sweepStopsSendRtpBeforeBye() throws Exception {
        GbInviteSession invite = readyInvite();
        CascadeSendSession send = sendSession();
        Request bye = org.mockito.Mockito.mock(Request.class);
        GbParentPlatform platform = new GbParentPlatform();
        platform.setId(PLATFORM_DB_ID);
        platform.setServerGbId("34020000002000000001");

        when(inviteSessionService.listAllActive()).thenReturn(List.of(invite));
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        // 源流已经从节点上消失（对端崩溃后 ZLM 的 RTP 端口超时会自己注销流）
        when(zlmRestClient.getMediaListOrNull(any())).thenReturn(List.of());
        when(sendSessionService.listBySrcStream(STREAM)).thenReturn(List.of(send));
        when(gbParentPlatformMapper.selectById(PLATFORM_DB_ID)).thenReturn(platform);
        when(headerProvider.localIp()).thenReturn("127.0.0.1");
        when(headerProvider.createByeToUpstream(any(), any(), any(), any())).thenReturn(bye);

        assertThat(service.sweepZombieSources()).isEqualTo(1);

        // 顺序是契约本身：先停推流，再删会话，最后才 BYE 上级
        InOrder order = inOrder(zlmRestClient, sendSessionService, sipSender);
        order.verify(zlmRestClient).stopSendRtp(any(), eq("rtp"), eq(STREAM), eq(SSRC));
        order.verify(sendSessionService).removeBySendStreamId(SEND_STREAM_ID);
        order.verify(sipSender).send(eq("127.0.0.1"), eq(bye));
        // 有上级会话在推时，来源侧由 cleanup 的最后一跳回收，不该再走 timeoutSession
        verify(support, never()).timeoutSession(any(), anyString(), anyString());
    }

    @Test
    void sweepSkipsWhenNodeUnreachable() {
        when(inviteSessionService.listAllActive()).thenReturn(List.of(readyInvite()));
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        // null = 没问到节点，不是"没有流"：一次抖动不能把所有会话当僵尸清掉
        when(zlmRestClient.getMediaListOrNull(any())).thenReturn(null);

        assertThat(service.sweepZombieSources()).isZero();
        verifyNoInteractions(sendSessionService, support);
    }

    @Test
    void sweepSkipsDownloadAndRecordSessions() {
        GbInviteSession download = readyInvite();
        download.setSessionType(GbInviteSession.TYPE_DOWNLOAD);
        GbInviteSession record = readyInvite();
        record.setSessionType(GbInviteSession.TYPE_RECORD);
        when(inviteSessionService.listAllActive()).thenReturn(List.of(download, record));

        assertThat(service.sweepZombieSources()).isZero();
        // TCP 文件流本来就没有连续 RTP：连节点都不该问
        verifyNoInteractions(mediaServerMapper, zlmRestClient, sendSessionService, support);
    }

    @Test
    void sweepReapsSourceWhenNoUpstreamSession() {
        GbInviteSession invite = readyInvite();
        when(inviteSessionService.listAllActive()).thenReturn(List.of(invite));
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(zlmRestClient.getMediaListOrNull(any())).thenReturn(List.of());
        when(sendSessionService.listBySrcStream(STREAM)).thenReturn(List.of());

        assertThat(service.sweepZombieSources()).isEqualTo(1);

        // 没有上级在推 → 直接回收来源侧（BYE 下级 + 关 RTP 端口 + 删会话）
        verify(support).timeoutSession(eq(invite), eq("级联点播"), contains("消失"));
    }

    @Test
    void sweepSilentWithinThresholdDoesNotReap() throws Exception {
        GbInviteSession invite = readyInvite();
        // 10s 前还确认在收流，默认阈值 30s：还没到期
        invite.setLastRtpTime(LocalDateTime.now().minusSeconds(10));
        when(inviteSessionService.listAllActive()).thenReturn(List.of(invite));
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(zlmRestClient.getMediaListOrNull(any())).thenReturn(List.of(media(STREAM, 0, 0)));

        assertThat(service.sweepZombieSources()).isZero();
        verifyNoInteractions(sendSessionService, support);
    }

    @Test
    void sweepRefreshesLastRtpTimeWhileFlowing() throws Exception {
        GbInviteSession invite = readyInvite();
        when(inviteSessionService.listAllActive()).thenReturn(List.of(invite));
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(zlmRestClient.getMediaListOrNull(any())).thenReturn(List.of(media(STREAM, 512000, 0)));

        assertThat(service.sweepZombieSources()).isZero();

        // 有码流 → 刷新 lastRtpTime 并落 Redis（下次判定从此刻起算）
        assertThat(invite.getLastRtpTime()).isAfter(LocalDateTime.now().minusSeconds(2));
        verify(inviteSessionService).update(invite);
        verifyNoInteractions(sendSessionService, support);
    }
}
