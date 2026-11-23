package com.solo.gb28181.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "点播 / 回放 / 下载会话（Redis 主存）")
@Data
public class GbInviteSession {

    public static final String STATUS_INVITING = "INVITING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TYPE_PLAY = "PLAY";
    public static final String TYPE_PLAYBACK = "PLAYBACK";
    public static final String TYPE_DOWNLOAD = "DOWNLOAD";
    public static final String TYPE_RECORD = "RECORD";

    @Schema(description = "业务流 ID（ZLM stream 名，格式 deviceId_channelId，回放再附时间段后缀）")
    private String streamId;

    @Schema(description = "承载流节点 ID（ZLM 节点 ID）")
    private String serverId;

    @Schema(description = "设备国标 ID")
    private String deviceId;

    @Schema(description = "通道国标 ID")
    private String channelId;

    @Schema(description = "SSRC（10 位）")
    private String ssrc;

    @Schema(description = "RTP 端口（ZLM openRtpServer 返回）")
    private Integer rtpPort;

    @Schema(description = "传输模式（UDP / TCP-active / TCP-passive）")
    private String transport;

    @Schema(description = "码流 token（空/null 为主码流，实时点播默认；回放固定 null；非空为子码流，如 1 / stream:sub）")
    private String streamNumber;

    @Schema(description = "SIP Call-ID")
    private String callId;

    @Schema(description = "SIP From-tag")
    private String fromTag;

    @Schema(description = "SIP To-tag")
    private String toTag;

    @Schema(description = "会话类型 PLAY/PLAYBACK/DOWNLOAD/RECORD")
    private String sessionType;

    @Schema(description = "回放起始时间（Unix epoch 字符串），实时点播留空")
    private String startTimeStr;
    @Schema(description = "回放结束时间（Unix epoch 字符串）")
    private String endTimeStr;

    @Schema(description = "会话状态 INVITING/READY/STOPPED/TIMEOUT/FAILED")
    private String status;

    @Schema(description = "HTTP-FLV 播放地址")
    private String flvUrl;

    @Schema(description = "WS-FLV 播放地址")
    private String wsFlvUrl;

    @Schema(description = "HLS 播放地址")
    private String hlsUrl;

    @Schema(description = "WebRTC 播放地址")
    private String rtcUrl;

    @Schema(description = "会话开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @Schema(description = "流就绪时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime readyTime;

    @Schema(description = "会话停止时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime stopTime;

    /**
     * 最后一次确认还在收 RTP 的时刻，<b>僵尸判定唯一依据</b>。
     * <p>为什么不直接在 ZLM 上查：ZLM 的 {@code getMediaList} 没有"最后收流时刻"字段——
     * {@code createStamp} 是流创建时刻，{@code aliveSecond} 取的也是流的创建时刻（不是最后收流时刻）。
     * 所以只能由巡检（每 10s）采样 {@code bytesSpeed} 后回写：有码流就刷新，没码流就保持旧值，
     * 超过 {@code gb28181.cascade.rtp-timeout-sec} 即判定源流已死。</p>
     */
    @Schema(description = "最后一次确认收到 RTP 的时刻（巡检采样回写）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastRtpTime;

    /**
     * 最后一次确认"有人观看"的时刻（ZLM totalReaderCount &gt; 0 或正在被上级/广播消费）。
     * <p>空闲回收的唯一依据：没有观看者超过阈值就断开，避免长期占用上游带宽（3.11.3）。
     * 从未有观看者时以 {@code readyTime} 起算，否则"点播后一直没人打开播放器"永远回收不掉。</p>
     */
    @Schema(description = "最后一次确认有观看者的时刻（空闲回收依据）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastReaderTime;

    @Schema(description = "失败原因")
    private String failReason;
}
