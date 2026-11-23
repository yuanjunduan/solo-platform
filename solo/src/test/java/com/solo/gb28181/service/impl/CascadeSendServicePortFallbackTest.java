package com.solo.gb28181.service.impl;

import com.solo.common.ServiceException;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上级 ACK 后的推流端口策略（决策 D5）：
 * 优先复用回 200 OK 时预分配给 SDP 的端口；该端口被占则换下一个重试，
 * 并接受"实际端口与 SDP 不一致"——UDP/TCP 被动下上级不校验本平台源端口。
 */
@ExtendWith(MockitoExtension.class)
class CascadeSendServicePortFallbackTest {

    private static final String CALL_ID = "call-1";
    private static final String SERVER_ID = "zlm-1";
    private static final String STREAM = "34020000001320000001_34020000001310000001";
    private static final String SSRC = "0000123456";
    private static final String DST_IP = "10.9.9.9";
    private static final int DST_PORT = 9000;
    private static final int PREALLOCATED_PORT = 50100;

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

    private CascadeSendSession udpSession() {
        CascadeSendSession s = new CascadeSendSession();
        s.setSendStreamId("send_" + CALL_ID);
        s.setServerId(SERVER_ID);
        s.setSrcApp("rtp");
        s.setSrcStream(STREAM);
        s.setSsrc(SSRC);
        s.setDstIp(DST_IP);
        s.setDstPort(DST_PORT);
        s.setTransport("UDP");
        s.setCallId(CALL_ID);
        s.setStatus(CascadeSendSession.STATUS_WAIT_ACK);
        s.setLocalSendPort(PREALLOCATED_PORT);
        return s;
    }

    private MediaServer server() {
        MediaServer s = new MediaServer();
        s.setServerId(SERVER_ID);
        return s;
    }

    @Test
    void ackReusesPreallocatedPort() {
        CascadeSendSession session = udpSession();
        when(sendSessionService.getByCallId(CALL_ID)).thenReturn(session);
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(zlmRestClient.startSendRtp(any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyBoolean(), eq(PREALLOCATED_PORT))).thenReturn(PREALLOCATED_PORT);

        service.onUpstreamAck(CALL_ID);

        // 复用预分配端口，不应再向会话服务要新端口
        verify(sendSessionService, never()).acquireSendPort(anyInt(), anyInt());
        assertThat(session.getLocalSendPort()).isEqualTo(PREALLOCATED_PORT);
        assertThat(session.getStatus()).isEqualTo(CascadeSendSession.STATUS_SENDING);
    }

    @Test
    void ackFallsBackToNextPortWhenPreallocatedPortIsInUse() {
        CascadeSendSession session = udpSession();
        when(sendSessionService.getByCallId(CALL_ID)).thenReturn(session);
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(sendSessionService.acquireSendPort(anyInt(), anyInt())).thenReturn(50101);
        when(zlmRestClient.startSendRtp(any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyBoolean(), eq(PREALLOCATED_PORT)))
                .thenThrow(new ServiceException("bind: address already in use"));
        when(zlmRestClient.startSendRtp(any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyBoolean(), eq(50101))).thenReturn(50101);

        service.onUpstreamAck(CALL_ID);

        // 换端口成功：会话记实际端口，状态仍推进到 SENDING
        assertThat(session.getLocalSendPort()).isEqualTo(50101);
        assertThat(session.getStatus()).isEqualTo(CascadeSendSession.STATUS_SENDING);
        verify(zlmRestClient, times(2)).startSendRtp(any(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void ackDoesNotRetryOnNonPortError() {
        CascadeSendSession session = udpSession();
        when(sendSessionService.getByCallId(CALL_ID)).thenReturn(session);
        when(mediaServerMapper.selectOne(any())).thenReturn(server());
        when(zlmRestClient.startSendRtp(any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyBoolean(), eq(PREALLOCATED_PORT)))
                .thenThrow(new ServiceException("code=-400 can not find the stream"));

        service.onUpstreamAck(CALL_ID);

        // 非端口类错误换端口也无用：只尝试一次，然后走清理（会话被移除）
        verify(zlmRestClient, times(1)).startSendRtp(any(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyBoolean(), any());
        verify(sendSessionService).removeBySendStreamId("send_" + CALL_ID);
    }
}
