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
 * 一轮全量同步的任务台账（3.8.1 三段会话 + 3.13.1 轮次台账）。
 *
 * <p><b>为什么台账必须落盘</b>：两周后要能回答"那天为什么没删"——
 * 所以 {@code guard_ratio} 与 {@code delete_skipped} 是当时的快照，不是现在读配置反推（C23）。</p>
 *
 * <p>{@code active_platform_id} 是库里的生成列（互斥用），<b>刻意不映射</b>：
 * 映射了就得在每次 insert/update 时排除它，反而容易出现"生成列被写入"的错误。</p>
 */
@Schema(description = "同步任务：一轮全量一行")
@Data
@TableName("sync_task")
public class SyncTask {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "轮次业务号")
    private String taskSn;

    @Schema(description = "来源平台")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "MANUAL/SCHEDULED/NOTIFY")
    private String triggerType;

    @Schema(description = "PENDING/RUNNING/SYNCHING/SUCCESS/FAILED/CANCELLED")
    private String status;

    @Schema(description = "0-100")
    private Integer progress;

    @Schema(description = "对端告知的总条数（SumNum）")
    private Integer expectedTotal;

    @Schema(description = "已收条数")
    private Integer recvTotal;

    private Integer orgRecv;
    private Integer devRecv;
    private Integer chRecv;

    private Integer addedCount;
    private Integer updatedCount;
    private Integer staleCount;
    private Integer failedCount;

    @Schema(description = "G2 跳过删除：必须在台账/详情/事件三处可见（C11）")
    private Integer deleteSkipped;

    @Schema(description = "本轮当时的 G2 阈值快照，供事后回查（C23）")
    private BigDecimal guardRatio;

    @Schema(description = "4 步回填落到[未分类]的行数（C13 第 4 步）")
    private Integer orgMissingCount;

    @Schema(description = "本轮目录指纹")
    private String catalogFingerprint;

    @Schema(description = "错误码：SYNC_EMPTY_STAGING 等")
    private String errorCode;

    private String errorMsg;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
