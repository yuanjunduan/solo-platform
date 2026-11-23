package com.solo.gb28181.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.domain.resource.service.PlayRecordService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.GbBroadcastSessionService;
import com.solo.gb28181.service.GbInviteSessionCleanupService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.mapper.GbMediaServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点播/回放空闲回收（3.11.3）：一路取流长时间没有观看者就主动 BYE + 关流，
 * 避免长期占用上游设备带宽和本地收流端口。
 *
 * <p><b>什么算"有人在看"</b>：ZLM 的 {@code readerCount/totalReaderCount &gt; 0}，
 * 或者该源流正在被级联推流（上级平台点播）/{@link GbBroadcastSessionService 语音广播}消费。
 * 后两者不会体现在 ZLM 的 readerCount 上，漏判会把上级正在看的流掐断。</p>
 *
 * <p><b>节点不可达时跳过本轮</b>：{@link ZlmRestClient#getMediaListOrNull} 返回 null 表示
 * 压根没问到节点，不能当成"没人看"——一次网络抖动就清掉所有会话，代价比多占几分钟带宽大得多。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GbIdleReclaimService {

    private static final String LOG_TAG = "空闲回收";
    private static final String APP = "rtp";
    /** 观看者刷新落 Redis 的最小间隔，10s 巡检下不必每次都写 */
    private static final long TOUCH_INTERVAL_MS = 5_000L;

    private final Gb28181Properties properties;
    private final GbInviteSessionService inviteSessionService;
    private final GbInviteSessionCleanupService inviteCleanup;
    private final GbMediaServerMapper mediaServerMapper;
    private final ZlmRestClient zlmRestClient;
    private final CascadeSendSessionService cascadeSendSessionService;
    private final GbBroadcastSessionService broadcastSessionService;
    private final PlayRecordService playRecordService;

    /**
     * 巡检一轮：回收所有"READY 且无观看者超过阈值"的点播/回放会话。
     *
     * @return 本轮回收的会话数
     */
    public int reap() {
        int thresholdSec = properties.getPlay().getIdleReclaimSec();
        if (thresholdSec <= 0) {
            return 0;
        }
        List<GbInviteSession> sessions = inviteSessionService.listAllActive();
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        Map<String, MediaServer> serverCache = new HashMap<>();
        // 同一节点上可能有几十路流，一轮只拉一次 getMediaList
        Map<String, List<JsonNode>> mediaCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        int reaped = 0;
        for (GbInviteSession session : sessions) {
            if (!GbInviteSession.STATUS_READY.equals(session.getStatus())) {
                continue;
            }
            String type = session.getSessionType();
            if (!GbInviteSession.TYPE_PLAY.equals(type) && !GbInviteSession.TYPE_PLAYBACK.equals(type)) {
                continue;
            }
            try {
                if (hasExternalConsumer(session.getStreamId())) {
                    touch(session, now);
                    continue;
                }
                List<JsonNode> mediaList = mediaListOf(session.getServerId(), serverCache, mediaCache);
                if (mediaList == null) {
                    // 节点问不到：无法判定，放行
                    continue;
                }
                if (maxReaders(mediaList, session.getStreamId()) > 0) {
                    touch(session, now);
                    continue;
                }
                LocalDateTime baseline = baseline(session);
                if (baseline == null) {
                    continue;
                }
                long idleSec = Duration.between(baseline, now).getSeconds();
                if (idleSec < thresholdSec) {
                    continue;
                }
                inviteCleanup.failAndBye(session, LOG_TAG,
                        "无观看者 " + idleSec + "s（阈值 " + thresholdSec + "s）");
                closePlayRecord(session.getStreamId());
                reaped++;
            } catch (Exception e) {
                log.warn("[{}] 巡检单条会话异常(忽略) 流ID:{} 错误:{}",
                        LOG_TAG, session.getStreamId(), e.getMessage());
            }
        }
        return reaped;
    }

    /**
     * 是否仍有观看者（ZLM 播放器或级联/广播消费）。供可观测页与回收判定共用。
     */
    public boolean streamHasReader(MediaServer server, String streamId) {
        if (server == null) {
            return false;
        }
        if (hasExternalConsumer(streamId)) {
            return true;
        }
        List<JsonNode> mediaList = zlmRestClient.getMediaListOrNull(server);
        return mediaList != null && maxReaders(mediaList, streamId) > 0;
    }

    /** 级联推流 / 语音广播不算 ZLM 读者，但确实是有人在消费这路流 */
    private boolean hasExternalConsumer(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return false;
        }
        return !cascadeSendSessionService.listBySrcStream(streamId).isEmpty()
                || !broadcastSessionService.listBySrcStream(streamId).isEmpty();
    }

    /**
     * ZLM 会把同一路流按 schema（ts / rtsp / fmp4 / rtmp…）各返回一条，
     * 只有 {@code totalReaderCount} 是全量；取最大值，命中第一条不能下结论。
     */
    private int maxReaders(List<JsonNode> mediaList, String streamId) {
        int max = 0;
        for (JsonNode node : mediaList) {
            if (!APP.equals(node.path("app").asText()) || !streamId.equals(node.path("stream").asText())) {
                continue;
            }
            max = Math.max(max, Math.max(
                    node.path("readerCount").asInt(0), node.path("totalReaderCount").asInt(0)));
        }
        return max;
    }

    /**
     * 从未有观看者的会话以 readyTime（再退 startTime）起算，
     * 否则"点播后一直没人打开播放器"永远回收不掉。
     */
    private LocalDateTime baseline(GbInviteSession session) {
        if (session.getLastReaderTime() != null) {
            return session.getLastReaderTime();
        }
        return session.getReadyTime() != null ? session.getReadyTime() : session.getStartTime();
    }

    /** 刷新"最后一次有观看者"时刻；写 Redis 按 {@link #TOUCH_INTERVAL_MS} 节流 */
    private void touch(GbInviteSession session, LocalDateTime now) {
        LocalDateTime last = session.getLastReaderTime();
        if (last != null && Duration.between(last, now).toMillis() < TOUCH_INTERVAL_MS) {
            return;
        }
        session.setLastReaderTime(now);
        try {
            inviteSessionService.update(session);
        } catch (Exception e) {
            log.warn("[{}] 回写 lastReaderTime 失败 流ID:{} 错误:{}",
                    LOG_TAG, session.getStreamId(), e.getMessage());
        }
    }

    /** 回收同时把播放记录闭合：否则该行一直停在"未结束"，时长永远算不出来 */
    private void closePlayRecord(String streamId) {
        try {
            playRecordService.closeByStreamId(streamId);
        } catch (Exception e) {
            log.warn("[{}] 闭合播放记录失败 流ID:{} 错误:{}", LOG_TAG, streamId, e.getMessage());
        }
    }

    private List<JsonNode> mediaListOf(String serverId,
                                       Map<String, MediaServer> serverCache,
                                       Map<String, List<JsonNode>> mediaCache) {
        if (serverId == null) {
            return null;
        }
        // 查询失败( null )也要缓存，否则同一节点上有多少会话就会打多少次失败请求
        if (!mediaCache.containsKey(serverId)) {
            MediaServer server = serverCache.computeIfAbsent(serverId, id -> mediaServerMapper.selectOne(
                    new LambdaQueryWrapper<MediaServer>().eq(MediaServer::getServerId, id)));
            mediaCache.put(serverId, server == null ? null : zlmRestClient.getMediaListOrNull(server));
        }
        return mediaCache.get(serverId);
    }
}
