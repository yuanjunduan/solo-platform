package com.solo.gb28181.support;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 源流存活判定（架构约束 C21c：按源流判活，不靠信令）。
 *
 * <p><b>为什么不能直接问 ZLM"最后一次收到 RTP 是什么时候"</b>：ZLM 的 {@code getMediaList} 里没有这个字段。
 * 实测（ZLMediaKit 2024-08 构建 + master 源码）：每项只有 {@code createStamp}（流创建 unix 秒）、
 * {@code aliveSecond}（取的是 {@code _ticker.createdTime()/1000}，也是流的创建时刻）、
 * {@code bytesSpeed}（当前出/入码率，字节/秒）、{@code readerCount} / {@code totalReaderCount}。
 * 所以"最后一次收流时刻"必须由本平台巡检时<b>采样 {@code bytesSpeed} 自行回写</b>到
 * {@code GbInviteSession.lastRtpTime}。</p>
 *
 * <p>判据分三档，宁可漏判也不误杀：</p>
 * <ol>
 *   <li><b>流不在节点上</b> → 确定已死（对端崩溃后 ZLM 的 RTP 端口超时会自己注销流）</li>
 *   <li><b>流在、有码流</b> → 活的，刷新 {@code lastRtpTime}</li>
 *   <li><b>流在、没码流</b> → 看有没有本地观看者：有人在看不判死（HLS 拉流、暂停等都会出现瞬时 0），
 *       没人看才是疑似，交给阈值判</li>
 * </ol>
 * <p>节点问不到（网络问题 / 节点挂了）一律放行——一次抖动不能把所有会话当僵尸清掉。</p>
 */
public final class GbInviteLiveness {

    private GbInviteLiveness() {
    }

    public enum Probe {
        /** 流在节点上，且正在出码流 —— 活的 */
        FLOWING,
        /** 流在节点上，当前没有码流，但有人观看 —— 不判死，避免误杀正在播的流 */
        WATCHED,
        /** 流在节点上，既没码流也没人看 —— 疑似僵尸，交给阈值判 */
        SILENT,
        /** 流已经不在节点上 —— 确定僵尸 */
        GONE,
        /** 节点问不到 —— 本轮不判 */
        UNREACHABLE
    }

    /**
     * 采样一次 ZLM 的流列表，判定某路源流的状态。
     *
     * @param mediaList {@code ZlmRestClient.getMediaListOrNull} 的结果；{@code null} 表示节点不可达
     * @param app       ZLM 应用名（国标取流固定 {@code rtp}）
     * @param streamId  源流名（{@code deviceId_channelId}）
     */
    public static Probe probe(List<JsonNode> mediaList, String app, String streamId) {
        if (mediaList == null) {
            return Probe.UNREACHABLE;
        }
        if (app == null || app.isBlank() || streamId == null || streamId.isBlank()) {
            return Probe.UNREACHABLE;
        }
        boolean found = false;
        long maxBytesPerSec = 0L;
        int maxReaders = 0;
        for (JsonNode node : mediaList) {
            if (node == null) {
                continue;
            }
            if (!app.equals(node.path("app").asText()) || !streamId.equals(node.path("stream").asText())) {
                continue;
            }
            found = true;
            // ZLM 会把同一路流按 schema（rtsp/rtmp/hls/ts/fmp4…）各返回一条：
            // 取最大值，不能命中第一条就下结论（同 CascadeSendService.streamHasReader 的处理）
            maxBytesPerSec = Math.max(maxBytesPerSec, node.path("bytesSpeed").asLong(0L));
            maxReaders = Math.max(maxReaders, Math.max(
                    node.path("readerCount").asInt(0), node.path("totalReaderCount").asInt(0)));
        }
        if (!found) {
            return Probe.GONE;
        }
        if (maxBytesPerSec > 0L) {
            return Probe.FLOWING;
        }
        return maxReaders > 0 ? Probe.WATCHED : Probe.SILENT;
    }

    /**
     * 僵尸判定：距最后一次确认收流是否已超过阈值。
     * <p>{@code lastRtpTime} 可能还没被巡检写过（会话刚就绪那几秒），此时退回 {@code readyTime} 起算，
     * 否则刚起来的会话会被"永远没有时间戳"保护住，实际永远判不出来。</p>
     * <p>阈值 &le; 0 表示关闭巡检；时钟回拨导致差值为负时判为"不是僵尸"。</p>
     */
    public static boolean isZombie(LocalDateTime lastRtpTime, LocalDateTime readyTime,
                                   LocalDateTime now, long timeoutSec) {
        if (timeoutSec <= 0 || now == null) {
            return false;
        }
        LocalDateTime last = lastRtpTime != null ? lastRtpTime : readyTime;
        if (last == null) {
            return false;
        }
        return Duration.between(last, now).getSeconds() >= timeoutSec;
    }
}
