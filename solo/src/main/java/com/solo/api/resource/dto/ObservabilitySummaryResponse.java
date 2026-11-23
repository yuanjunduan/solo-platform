package com.solo.api.resource.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行自检参数汇总（M6，6.7「可观测」）：一眼看清"现在有多少会话、多少人在看、
 * 节点是否可达、今天点播成功失败各多少、关键阈值配成了什么"。
 *
 * <p>全部是<b>只读快照</b>，不做任何清理动作——回收由定时巡检负责，页面只负责展示。</p>
 */
@Schema(description = "运行自检汇总")
@Data
public class ObservabilitySummaryResponse {

    @Schema(description = "快照生成时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;

    @Schema(description = "取流会话统计")
    private SessionStats sessions = new SessionStats();

    @Schema(description = "活跃取流会话明细")
    private List<SessionItem> activeSessions = new ArrayList<>();

    @Schema(description = "媒体节点及其流")
    private List<NodeItem> nodes = new ArrayList<>();

    @Schema(description = "带宽汇总（6.8 风险监控：全网出流码率合计与头部流）")
    private BandwidthStats bandwidth = new BandwidthStats();

    @Schema(description = "级联推流会话数（本平台被上级点播中）")
    private int cascadeSendCount;

    @Schema(description = "语音广播会话数")
    private int broadcastCount;

    @Schema(description = "今日播放记录统计")
    private RecordStats recordsToday = new RecordStats();

    @Schema(description = "关键阈值快照")
    private ConfigSnapshot config = new ConfigSnapshot();

    @Schema(description = "取流会话按状态/类型计数")
    @Data
    public static class SessionStats {
        private int total;
        private int inviting;
        private int ready;
        private int play;
        private int playback;
    }

    @Schema(description = "单个取流会话")
    @Data
    public static class SessionItem {
        private String streamId;
        private String serverId;
        private String deviceId;
        private String channelId;
        private String sessionType;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime readyTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime lastReaderTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime lastRtpTime;
        @Schema(description = "当前观看者数（ZLM 读者；级联/广播消费不计入）")
        private int readers;
        @Schema(description = "是否被级联推流或语音广播消费（这类会话不会被空闲回收）")
        private boolean externalConsumer;
    }

    @Schema(description = "单个媒体节点")
    @Data
    public static class NodeItem {
        private String serverId;
        private String name;
        private String hostIp;
        @Schema(description = "库里的在线状态：1 在线，0 离线")
        private Integer status;
        @Schema(description = "本轮是否能问到 ZLM（探活失败为 false，下面的流列表不可信）")
        private boolean reachable;
        private int streamCount;
        private int totalReaders;
        private List<StreamItem> streams = new ArrayList<>();
    }

    @Schema(description = "节点上的一路流（按 app/stream 聚合多个 schema）")
    @Data
    public static class StreamItem {
        private String app;
        private String stream;
        @Schema(description = "全量观看者数（各 schema 取最大值）")
        private int readers;
        @Schema(description = "码率（字节/秒，各 schema 取最大值）")
        private long bytesSpeed;
        private long aliveSeconds;
        private String videoCodec;
    }

    @Schema(description = "带宽汇总")
    @Data
    public static class BandwidthStats {
        @Schema(description = "全部流码率合计（字节/秒，ZLM bytesSpeed 之和）")
        private long totalBytesPerSec;
        @Schema(description = "折算 Mbps（bytesPerSec × 8 / 1e6，保留 2 位）")
        private double totalMbps;
        @Schema(description = "上报了码率的流数（bytesSpeed>0；不可达节点的流不计入）")
        private int streamCount;
        @Schema(description = "观看者合计（各级联/广播消费的流也计入）")
        private int totalReaders;
        @Schema(description = "码率最高的前 5 路流")
        private List<BandwidthStreamItem> topStreams = new ArrayList<>();
    }

    @Schema(description = "带宽头部流：用于回答\"这波流量是被谁吃掉的\"")
    @Data
    public static class BandwidthStreamItem {
        private String serverId;
        private String app;
        private String stream;
        private long bytesPerSec;
        private double mbps;
        private int readers;
        @Schema(description = "该流同时被级联推流或语音广播消费")
        private boolean externalConsumer;
    }

    @Schema(description = "今日播放记录统计")
    @Data
    public static class RecordStats {
        private long total;
        private long success;
        private long failed;
        @Schema(description = "成功但尚未闭合（仍在观看或客户端没回报停止）")
        private long open;
    }

    @Schema(description = "关键阈值快照（排查时不用翻配置文件）")
    @Data
    public static class ConfigSnapshot {
        @Schema(description = "空闲回收阈值（秒），<=0 关闭")
        private int idleReclaimSec;
        @Schema(description = "点播等流就绪超时（秒）")
        private int inviteTimeoutSec;
        @Schema(description = "回放等流就绪超时（秒）")
        private int playbackTimeoutSec;
        @Schema(description = "级联源流僵尸判定阈值（秒）")
        private long cascadeRtpTimeoutSec;
        @Schema(description = "每跳点播失败冷却（秒）")
        private int hopCooldownSec;
        @Schema(description = "云端录像保留天数")
        private int recordRetentionDays;
    }
}
