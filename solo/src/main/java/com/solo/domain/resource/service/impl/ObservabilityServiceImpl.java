package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.api.resource.dto.ObservabilitySummaryResponse;
import com.solo.domain.resource.model.ResPlayRecord;
import com.solo.domain.resource.service.ObservabilityService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
import com.solo.gb28181.entity.GbBroadcastSession;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.GbBroadcastSessionService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.infra.mapper.ResPlayRecordMapper;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.properties.MediaProperties;
import com.solo.media.service.MediaServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行自检：一次请求聚合四类信息——取流会话、媒体节点流、级联/广播消费、今日播放记录。
 * <p>只有探活（{@code getMediaListOrNull}）会走网络，节点不可达时标 {@code reachable=false}，
 * 流列表留空，不让一次抖动把整个页面拖成 500。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityServiceImpl implements ObservabilityService {

    private static final String APP = "rtp";

    /** 带宽卡片只展示头部几路：再多也不是"看板"，而是"报表" */
    private static final int TOP_BANDWIDTH_STREAMS = 5;

    private final GbInviteSessionService inviteSessionService;
    private final CascadeSendSessionService cascadeSendSessionService;
    private final GbBroadcastSessionService broadcastSessionService;
    private final MediaServerService mediaServerService;
    private final ZlmRestClient zlmRestClient;
    private final ResPlayRecordMapper playRecordMapper;
    private final Gb28181Properties gb28181Properties;
    private final MediaProperties mediaProperties;

    @Override
    public ObservabilitySummaryResponse summary() {
        ObservabilitySummaryResponse resp = new ObservabilitySummaryResponse();
        resp.setGeneratedAt(LocalDateTime.now());

        List<CascadeSendSession> cascadeSends = safeList(cascadeSendSessionService.listAllActive());
        List<GbBroadcastSession> broadcasts = safeList(broadcastSessionService.listAllActive());
        resp.setCascadeSendCount(cascadeSends.size());
        resp.setBroadcastCount(broadcasts.size());
        Set<String> consumedStreams = new HashSet<>();
        for (CascadeSendSession s : cascadeSends) {
            if (s.getSrcStream() != null) {
                consumedStreams.add(s.getSrcStream());
            }
        }
        for (GbBroadcastSession s : broadcasts) {
            if (s.getSrcStream() != null) {
                consumedStreams.add(s.getSrcStream());
            }
        }

        // 节点 + 流：同一路流按 schema 多条，聚合后才是真实观看人数
        Map<String, Integer> readersByStream = new HashMap<>();
        resp.setNodes(collectNodes(readersByStream));
        resp.setBandwidth(summarizeBandwidth(resp.getNodes(), consumedStreams));

        resp.setSessions(countSessions());
        resp.setActiveSessions(collectSessions(readersByStream, consumedStreams));
        resp.setRecordsToday(countTodayRecords());
        resp.setConfig(snapshotConfig());
        return resp;
    }

    private List<ObservabilitySummaryResponse.NodeItem> collectNodes(Map<String, Integer> readersByStream) {
        List<ObservabilitySummaryResponse.NodeItem> nodes = new ArrayList<>();
        List<MediaServer> servers;
        try {
            servers = mediaServerService.list();
        } catch (Exception e) {
            log.warn("[运行自检] 读取媒体节点失败:{}", e.getMessage());
            return nodes;
        }
        for (MediaServer server : servers) {
            ObservabilitySummaryResponse.NodeItem node = new ObservabilitySummaryResponse.NodeItem();
            node.setServerId(server.getServerId());
            node.setName(server.getName());
            node.setHostIp(server.getHostIp());
            node.setStatus(server.getStatus());
            List<JsonNode> mediaList = zlmRestClient.getMediaListOrNull(server);
            if (mediaList == null) {
                node.setReachable(false);
                nodes.add(node);
                continue;
            }
            node.setReachable(true);
            Map<String, ObservabilitySummaryResponse.StreamItem> aggregated = new HashMap<>();
            for (JsonNode item : mediaList) {
                String app = item.path("app").asText();
                String stream = item.path("stream").asText();
                String key = app + "|" + stream;
                ObservabilitySummaryResponse.StreamItem row = aggregated.get(key);
                if (row == null) {
                    row = new ObservabilitySummaryResponse.StreamItem();
                    row.setApp(app);
                    row.setStream(stream);
                    aggregated.put(key, row);
                }
                row.setReaders(Math.max(row.getReaders(), Math.max(
                        item.path("readerCount").asInt(0), item.path("totalReaderCount").asInt(0))));
                row.setBytesSpeed(Math.max(row.getBytesSpeed(), item.path("bytesSpeed").asLong(0)));
                row.setAliveSeconds(Math.max(row.getAliveSeconds(), item.path("aliveSecond").asLong(0)));
                if (row.getVideoCodec() == null && item.path("videoCodec").isString()) {
                    row.setVideoCodec(item.path("videoCodec").asText());
                }
            }
            List<ObservabilitySummaryResponse.StreamItem> streams = new ArrayList<>(aggregated.values());
            streams.sort(Comparator.comparingInt(ObservabilitySummaryResponse.StreamItem::getReaders).reversed()
                    .thenComparing(ObservabilitySummaryResponse.StreamItem::getStream));
            node.setStreams(streams);
            node.setStreamCount(streams.size());
            node.setTotalReaders(streams.stream().mapToInt(ObservabilitySummaryResponse.StreamItem::getReaders).sum());
            for (ObservabilitySummaryResponse.StreamItem stream : streams) {
                if (APP.equals(stream.getApp()) && stream.getStream() != null) {
                    readersByStream.put(server.getServerId() + "|" + stream.getStream(), stream.getReaders());
                }
            }
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 带宽汇总（6.8）：ZLM 的 {@code bytesSpeed} 是"字节/秒"，换算成 Mbps 才符合运维口径。
     *
     * <p>不可达节点的流列表为空，自然不计入；{@code bytesSpeed<=0} 的流（刚建流还没起量）
     * 只算观看人数，不算码率——否则平均码率会被刚起的流拉低。</p>
     */
    private ObservabilitySummaryResponse.BandwidthStats summarizeBandwidth(
            List<ObservabilitySummaryResponse.NodeItem> nodes, Set<String> consumedStreams) {
        ObservabilitySummaryResponse.BandwidthStats stats = new ObservabilitySummaryResponse.BandwidthStats();
        List<ObservabilitySummaryResponse.BandwidthStreamItem> rows = new ArrayList<>();
        long totalBytes = 0;
        int readers = 0;
        for (ObservabilitySummaryResponse.NodeItem node : nodes) {
            for (ObservabilitySummaryResponse.StreamItem stream : node.getStreams()) {
                readers += stream.getReaders();
                if (stream.getBytesSpeed() <= 0) {
                    continue;
                }
                totalBytes += stream.getBytesSpeed();
                ObservabilitySummaryResponse.BandwidthStreamItem row =
                        new ObservabilitySummaryResponse.BandwidthStreamItem();
                row.setServerId(node.getServerId());
                row.setApp(stream.getApp());
                row.setStream(stream.getStream());
                row.setBytesPerSec(stream.getBytesSpeed());
                row.setMbps(toMbps(stream.getBytesSpeed()));
                row.setReaders(stream.getReaders());
                row.setExternalConsumer(stream.getStream() != null && consumedStreams.contains(stream.getStream()));
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(ObservabilitySummaryResponse.BandwidthStreamItem::getBytesPerSec)
                .reversed()
                .thenComparing(ObservabilitySummaryResponse.BandwidthStreamItem::getStream,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        stats.setTotalBytesPerSec(totalBytes);
        stats.setTotalMbps(toMbps(totalBytes));
        stats.setStreamCount(rows.size());
        stats.setTotalReaders(readers);
        stats.setTopStreams(rows.size() <= TOP_BANDWIDTH_STREAMS
                ? rows
                : new ArrayList<>(rows.subList(0, TOP_BANDWIDTH_STREAMS)));
        return stats;
    }

    /** 字节/秒 → Mbps，两位小数：运维看的是量级，不需要更细的精度 */
    private static double toMbps(long bytesPerSec) {
        return BigDecimal.valueOf(bytesPerSec)
                .multiply(BigDecimal.valueOf(8))
                .divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private ObservabilitySummaryResponse.SessionStats countSessions() {
        ObservabilitySummaryResponse.SessionStats stats = new ObservabilitySummaryResponse.SessionStats();
        for (GbInviteSession session : safeList(inviteSessionService.listAllActive())) {
            stats.setTotal(stats.getTotal() + 1);
            if (GbInviteSession.STATUS_INVITING.equals(session.getStatus())) {
                stats.setInviting(stats.getInviting() + 1);
            } else if (GbInviteSession.STATUS_READY.equals(session.getStatus())) {
                stats.setReady(stats.getReady() + 1);
            }
            if (GbInviteSession.TYPE_PLAY.equals(session.getSessionType())) {
                stats.setPlay(stats.getPlay() + 1);
            } else if (GbInviteSession.TYPE_PLAYBACK.equals(session.getSessionType())) {
                stats.setPlayback(stats.getPlayback() + 1);
            }
        }
        return stats;
    }

    private List<ObservabilitySummaryResponse.SessionItem> collectSessions(Map<String, Integer> readersByStream,
                                                                          Set<String> consumedStreams) {
        List<ObservabilitySummaryResponse.SessionItem> items = new ArrayList<>();
        for (GbInviteSession session : safeList(inviteSessionService.listAllActive())) {
            ObservabilitySummaryResponse.SessionItem item = new ObservabilitySummaryResponse.SessionItem();
            item.setStreamId(session.getStreamId());
            item.setServerId(session.getServerId());
            item.setDeviceId(session.getDeviceId());
            item.setChannelId(session.getChannelId());
            item.setSessionType(session.getSessionType());
            item.setStatus(session.getStatus());
            item.setStartTime(session.getStartTime());
            item.setReadyTime(session.getReadyTime());
            item.setLastReaderTime(session.getLastReaderTime());
            item.setLastRtpTime(session.getLastRtpTime());
            item.setReaders(readersByStream.getOrDefault(session.getServerId() + "|" + session.getStreamId(), 0));
            item.setExternalConsumer(consumedStreams.contains(session.getStreamId()));
            items.add(item);
        }
        items.sort(Comparator.comparing(ObservabilitySummaryResponse.SessionItem::getStartTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private ObservabilitySummaryResponse.RecordStats countTodayRecords() {
        ObservabilitySummaryResponse.RecordStats stats = new ObservabilitySummaryResponse.RecordStats();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        stats.setTotal(selectCount(w -> w.ge(ResPlayRecord::getBeginTime, todayStart)));
        stats.setSuccess(selectCount(w -> w.ge(ResPlayRecord::getBeginTime, todayStart)
                .eq(ResPlayRecord::getResult, ResPlayRecord.RESULT_SUCCESS)));
        stats.setFailed(selectCount(w -> w.ge(ResPlayRecord::getBeginTime, todayStart)
                .eq(ResPlayRecord::getResult, ResPlayRecord.RESULT_FAILED)));
        stats.setOpen(selectCount(w -> w.ge(ResPlayRecord::getBeginTime, todayStart)
                .eq(ResPlayRecord::getResult, ResPlayRecord.RESULT_SUCCESS)
                .isNull(ResPlayRecord::getEndTime)));
        return stats;
    }

    /** 统计失败不影响整体自检：记录表查不动就给 0，页面照常打开 */
    private long selectCount(java.util.function.Consumer<LambdaQueryWrapper<ResPlayRecord>> customizer) {
        try {
            LambdaQueryWrapper<ResPlayRecord> wrapper = new LambdaQueryWrapper<>();
            customizer.accept(wrapper);
            Long count = playRecordMapper.selectCount(wrapper);
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("[运行自检] 统计播放记录失败:{}", e.getMessage());
            return 0L;
        }
    }

    private ObservabilitySummaryResponse.ConfigSnapshot snapshotConfig() {
        ObservabilitySummaryResponse.ConfigSnapshot config = new ObservabilitySummaryResponse.ConfigSnapshot();
        config.setIdleReclaimSec(gb28181Properties.getPlay().getIdleReclaimSec());
        config.setInviteTimeoutSec(mediaProperties.getInviteTimeoutSeconds());
        config.setPlaybackTimeoutSec(mediaProperties.getPlaybackTimeoutSeconds());
        config.setCascadeRtpTimeoutSec(gb28181Properties.getCascade().getRtpTimeoutSec());
        config.setHopCooldownSec(gb28181Properties.getCascade().getHopCooldownSec());
        config.setRecordRetentionDays(gb28181Properties.getRecord().getRetentionDays());
        return config;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
