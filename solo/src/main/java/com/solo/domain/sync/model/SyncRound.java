package com.solo.domain.sync.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轮次台账（3.13.1）：一轮同步执行事实的一行。
 *
 * <p><b>为什么不合并进 sync_task</b>：{@code sync_task} 是"任务状态"，可重跑、会被下一次重试覆盖；
 * 本表是"执行事实"，不可重写。两周后要回答"那天为什么没删"，读的是这里——
 * 所以 {@code guardRatio} 与 {@code deleteSkipped} 是当时的快照，不是现在读配置反推（C23）。</p>
 *
 * <p>写入策略：{@code start} 先落 RUNNING 一行，收尾按 {@code task_id} 更新为 DONE/FAILED；
 * 台账是运维辅助表，写失败只告警，不允许把已经合并成功的整轮回滚（资源正确性优先）。</p>
 */
@Schema(description = "同步轮次台账：每轮一行")
@Data
@TableName("sync_round")
public class SyncRound {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "对应 sync_task.id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    private String taskSn;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "当时平台名快照，平台改名后仍能对账")
    private String platformName;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    @Schema(description = "RUNNING/DONE/FAILED")
    private String status;

    private Integer orgRecv;
    private Integer devRecv;
    private Integer chRecv;

    private Integer orgInsert;
    private Integer devInsert;
    private Integer devUpdate;
    private Integer devDelete;

    @Schema(description = "重复业务键数（staging upsert 后为 0，预留）")
    private Integer devDup;

    @Schema(description = "G2 是否跳过删除：1=跳过")
    private Integer deleteSkipped;

    @Schema(description = "本轮当时的 G2 阈值快照")
    private BigDecimal guardRatio;

    @Schema(description = "本轮落到[未分类]的设备数")
    private Integer orgMissing;

    @Schema(description = "结果短标记：SUCCESS / DELETE_SKIPPED / EMPTY_STAGING …")
    private String result;

    @Schema(description = "可直接展示的一句话摘要")
    private String summary;

    private String errorCode;
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
