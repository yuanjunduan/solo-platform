package com.solo.domain.resource.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 播放 / 回放请求记录（一次点播一行）。
 *
 * <p>与 {@code gb_view_record} 的分工：那张表由 ZLM 回调驱动，统计"多少人在看"；
 * 本表在统一入口同步写入，失败也留痕（被拒、超时、来源离线都记 FAILED + 原因），
 * 回答的是"谁在什么时候请求了哪条通道、结果如何"。</p>
 *
 * <p>时长为 0 或为空表示仍在线或客户端没回报停止；闭合以 {@link #endTime} 非空为准。</p>
 */
@Schema(description = "播放/回放请求记录")
@Data
@TableName("res_play_record")
public class ResPlayRecord {

    public static final String ACTION_PLAY = "PLAY";
    public static final String ACTION_PLAYBACK = "PLAYBACK";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "res_channel.id（同源组按实际取流行记录）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long channelRowId;

    @Schema(description = "取流时使用的通道编码")
    private String channelCode;

    @Schema(description = "通道名称快照")
    private String channelName;

    @Schema(description = "NATIVE / INBOUND")
    private String sourceType;

    @Schema(description = "直接来源平台 id，0 = 本地")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlatformId;

    @Schema(description = "PLAY / PLAYBACK")
    private String action;

    @Schema(description = "ZLM 流 ID")
    private String streamId;

    @Schema(description = "操作者 id")
    private String actorId;

    @Schema(description = "操作者名称快照")
    private String actorName;

    @Schema(description = "客户端 IP")
    private String clientIp;

    @Schema(description = "SUCCESS / FAILED")
    private String result;

    @Schema(description = "失败原因")
    private String failReason;

    @Schema(description = "请求开始时刻")
    private LocalDateTime beginTime;

    @Schema(description = "停止/闭合时刻")
    private LocalDateTime endTime;

    @Schema(description = "时长（秒）")
    private Integer durationSec;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public void close(LocalDateTime end) {
        this.endTime = end;
        if (beginTime != null && end != null) {
            long sec = java.time.Duration.between(beginTime, end).getSeconds();
            this.durationSec = (int) Math.max(0L, sec);
        }
    }
}
