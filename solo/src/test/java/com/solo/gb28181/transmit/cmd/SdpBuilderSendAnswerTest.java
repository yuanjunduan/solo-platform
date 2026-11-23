package com.solo.gb28181.transmit.cmd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构约束 C21a 的回归断言：回给上级的 200 OK，媒体地址必须由本平台构造。
 * <p>上级 SDP 里只允许取三样东西——收流目的地址（c=/m=）、SSRC（y=）、传输语义；
 * 本平台的 c=/o= 绝不能出现上级或来源侧的地址，否则上级收到一个自己不可达的地址，
 * 表现为"上级点播黑屏，而本侧取流、推流全部正常"。</p>
 */
class SdpBuilderSendAnswerTest {

    /** 上级 INVITE 携带的 SDP：c= 是上级自己的收流地址，不是本平台地址 */
    private static final String UPSTREAM_SDP = """
            v=0
            o=34020000002000000001 0 0 IN IP4 10.9.9.9
            s=Play
            c=IN IP4 10.9.9.9
            t=0 0
            m=video 9000 RTP/AVP 96
            a=recvonly
            a=rtpmap:96 PS/90000
            y=0000123456
            """;

    /** 本平台 ZLM 对外地址 */
    private static final String PLATFORM_IP = "192.168.1.100";
    private static final String PLATFORM_GB_ID = "34020000001320000001";
    private static final int SEND_PORT = 50100;

    @Test
    void answerUsesPlatformAddressNotUpstreamAddress() {
        // 前置：确认测试数据里确实解析出了上级地址，否则下面的"不含"断言是空跑
        assertThat(SdpBuilder.extractConnectionIp(UPSTREAM_SDP)).isEqualTo("10.9.9.9");
        assertThat(SdpBuilder.extractSsrc(UPSTREAM_SDP)).isEqualTo("0000123456");

        String answer = SdpBuilder.buildSendAnswer(
                PLATFORM_GB_ID, PLATFORM_IP, SEND_PORT, "0000123456", SdpBuilder.TRANSPORT_UDP);

        // c= 与 o= 都必须是本平台地址
        assertThat(answer).contains("c=IN IP4 " + PLATFORM_IP);
        assertThat(answer).contains("o=" + PLATFORM_GB_ID + " 0 0 IN IP4 " + PLATFORM_IP);
        // 上级地址一个字符都不许出现
        assertThat(answer).doesNotContain("10.9.9.9");
    }

    @Test
    void answerKeepsUpstreamSsrcAndPlatformPort() {
        String answer = SdpBuilder.buildSendAnswer(
                PLATFORM_GB_ID, PLATFORM_IP, SEND_PORT, "0000123456", SdpBuilder.TRANSPORT_UDP);

        // SSRC 必须与上级 SDP 的 y= 一致，否则上级按 y= 收流会丢包
        assertThat(answer).contains("y=0000123456");
        // m= 端口是本平台发送端口，且不能为 0（RFC 4566：0 表示禁用该媒体流，与 a=sendonly 矛盾）
        assertThat(answer).contains("m=video " + SEND_PORT + " RTP/AVP 96");
        assertThat(answer).contains("a=sendonly");
    }

    @Test
    void tcpActiveUpstreamGetsPassiveSetup() {
        // 上级 TCP-active 表示"上级来连本平台"，本平台应答必须是 passive
        String answer = SdpBuilder.buildSendAnswer(
                PLATFORM_GB_ID, PLATFORM_IP, SEND_PORT, "0000123456", SdpBuilder.TRANSPORT_TCP_ACTIVE);

        assertThat(answer).contains("TCP/RTP/AVP");
        assertThat(answer).contains("a=setup:passive");
    }

    @Test
    void tcpPassiveUpstreamGetsActiveSetup() {
        // 上级 TCP-passive 表示"上级等本平台去连"，本平台应答必须是 active
        String answer = SdpBuilder.buildSendAnswer(
                PLATFORM_GB_ID, PLATFORM_IP, SEND_PORT, "0000123456", SdpBuilder.TRANSPORT_TCP_PASSIVE);

        assertThat(answer).contains("TCP/RTP/AVP");
        assertThat(answer).contains("a=setup:active");
    }

    @Test
    void udpAnswerHasNoSetupLine() {
        String answer = SdpBuilder.buildSendAnswer(
                PLATFORM_GB_ID, PLATFORM_IP, SEND_PORT, "0000123456", SdpBuilder.TRANSPORT_UDP);

        assertThat(answer).doesNotContain("a=setup:");
        assertThat(answer).doesNotContain("TCP/RTP/AVP");
    }
}
