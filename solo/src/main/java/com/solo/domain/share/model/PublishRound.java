package com.solo.domain.share.model;

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
 * 推送轮次台账：<b>出站事实</b>，一轮一行（C17 / 6.5）。
 *
 * <p>与入站 {@code sync_round} 同口径但<b>不同表</b>：一个是入站事实，一个是出站事实，
 * 混用会让"上一轮为什么没补齐"这种问题变成猜谜。</p>
 *
 * <p>必须落盘 {@code planned/sent/failed/degraded/threshold}：否则两周后没人能回答
 * "那次是不是因为超过明细上限被动降级了"。{@code status} 与 {@code summary}
 * 直接展示在"推送记录"页，不用再拼日志。</p>
 */
@Schema(description = "推送轮次台账")
@Data
@TableName("publish_round")
public class PublishRound {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_RECONCILE = "RECONCILE";
    public static final String TRIGGER_RESOURCE = "RESOURCE";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    private String platformName;

    @Schema(description = "MANUAL/SCHEDULED/RECONCILE/RESOURCE")
    private String triggerType;

    @Schema(description = "RUNNING/DONE/FAILED/SKIPPED")
    private String status;

    @Schema(description = "本轮应推条数")
    private Integer planned;

    @Schema(description = "实际提交成功条数")
    private Integer sent;

    private Integer failed;

    @Schema(description = "1=超阈值已合并为 REFRESH")
    private Integer degraded;

    @Schema(description = "当时的 publish.detail-max")
    private Integer threshold;

    private String errorCode;

    private String errorMsg;

    private String summary;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
