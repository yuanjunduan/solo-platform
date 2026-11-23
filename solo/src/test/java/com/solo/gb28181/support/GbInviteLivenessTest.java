package com.solo.gb28181.support;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源流存活判定契约（C21c）。
 *
 * <p>重点不是"能不能判死"，而是<b>绝不能误杀</b>：节点问不到、有本地观看者、瞬时报 0 码流，
 * 这三种情况都必须放行；只有"流从节点上消失"和"长时间无码流且没人看"才允许进入阈值判定。</p>
 */
class GbInviteLivenessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String APP = "rtp";
    private static final String STREAM = "34020000001320000001_34020000001310000001";

    /** 造一条 ZLM getMediaList 条目（字段名与 2024-08 构建一致：bytesSpeed / readerCount / totalReaderCount） */
    private static JsonNode media(String app, String stream, String schema, long bytesSpeed, int readers) throws Exception {
        return MAPPER.readTree("""
                {"app":"%s","stream":"%s","schema":"%s","bytesSpeed":%d,
                 "readerCount":%d,"totalReaderCount":%d,"createStamp":1750000000,"aliveSecond":1750000000}"""
                .formatted(app, stream, schema, bytesSpeed, readers, readers));
    }

    @Test
    void streamAbsentFromNodeIsGone() throws Exception {
        // 对端崩溃后 ZLM 的 RTP 端口超时会自己注销流，所以"查不到"就是确定已死
        List<JsonNode> other = List.of(media(APP, "another_stream", "rtsp", 4000, 0));
        assertThat(GbInviteLiveness.probe(other, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.GONE);
        assertThat(GbInviteLiveness.probe(List.of(), APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.GONE);
    }

    @Test
    void positiveBytesSpeedIsFlowing() throws Exception {
        List<JsonNode> list = List.of(media(APP, STREAM, "rtsp", 512000, 0));
        assertThat(GbInviteLiveness.probe(list, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.FLOWING);
    }

    @Test
    void zeroBytesSpeedButWatchedIsNeverJudged() throws Exception {
        // HLS 拉流、用户暂停、切片刷新都会出现瞬时 0 码流：只要有人看就不判死
        List<JsonNode> list = List.of(media(APP, STREAM, "hls", 0, 2));
        assertThat(GbInviteLiveness.probe(list, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.WATCHED);
    }

    @Test
    void zeroBytesSpeedAndNobodyWatchingIsSilent() throws Exception {
        List<JsonNode> list = List.of(media(APP, STREAM, "rtsp", 0, 0));
        assertThat(GbInviteLiveness.probe(list, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.SILENT);
    }

    @Test
    void unreachableNodeIsNeverJudged() {
        // null = 没问到节点（不是"没有流"）：一次抖动不能把所有会话当僵尸清掉
        assertThat(GbInviteLiveness.probe(null, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.UNREACHABLE);
        assertThat(GbInviteLiveness.probe(List.of(), null, STREAM)).isEqualTo(GbInviteLiveness.Probe.UNREACHABLE);
        assertThat(GbInviteLiveness.probe(List.of(), APP, " ")).isEqualTo(GbInviteLiveness.Probe.UNREACHABLE);
    }

    @Test
    void sameStreamAppearsOncePerSchemaSoTakeTheMax() throws Exception {
        // ZLM 按 schema 各返回一条：其中一条为 0、另一条有码流 → 判定为活的
        List<JsonNode> list = List.of(
                media(APP, STREAM, "hls", 0, 0),
                media(APP, STREAM, "rtsp", 512000, 0),
                media("live", STREAM, "rtsp", 0, 0));
        assertThat(GbInviteLiveness.probe(list, APP, STREAM)).isEqualTo(GbInviteLiveness.Probe.FLOWING);
    }

    @Test
    void zombieOnlyAfterThreshold() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 10, 0, 0);
        assertThat(GbInviteLiveness.isZombie(now.minusSeconds(31), null, now, 30)).isTrue();
        assertThat(GbInviteLiveness.isZombie(now.minusSeconds(30), null, now, 30)).isTrue();
        assertThat(GbInviteLiveness.isZombie(now.minusSeconds(29), null, now, 30)).isFalse();
    }

    @Test
    void fallsBackToReadyTimeBeforeFirstSample() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 10, 0, 0);
        // lastRtpTime 还没被巡检写过（会话刚就绪那几秒）→ 从 readyTime 起算，否则永远判不出来
        assertThat(GbInviteLiveness.isZombie(null, now.minusSeconds(60), now, 30)).isTrue();
        assertThat(GbInviteLiveness.isZombie(null, now.minusSeconds(10), now, 30)).isFalse();
        // 两个都没有时间戳 → 无依据，放行
        assertThat(GbInviteLiveness.isZombie(null, null, now, 30)).isFalse();
    }

    @Test
    void disabledOrClockSkewNeverReaps() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 10, 0, 0);
        assertThat(GbInviteLiveness.isZombie(now.minusSeconds(600), null, now, 0)).isFalse();
        assertThat(GbInviteLiveness.isZombie(now.minusSeconds(600), null, now, -1)).isFalse();
        // 时钟回拨：差值为负不判死
        assertThat(GbInviteLiveness.isZombie(now.plusSeconds(60), null, now, 30)).isFalse();
    }
}
