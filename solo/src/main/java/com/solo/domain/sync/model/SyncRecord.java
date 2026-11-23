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

import java.time.LocalDateTime;

/**
 * 单条资源变更留痕（3.8.6）。
 *
 * <p>只存摘要不存整行：目的是回答"这条通道是几点变的、变前叫什么"，
 * 不是做行级版本库。要 full before/after 属于报文快照（C 档，默认关）。</p>
 */
@Schema(description = "同步明细：单条资源变更留痕")
@Data
@TableName("sync_record")
public class SyncRecord {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    private String itemType;

    private String code;

    @Schema(description = "ADD/UPDATE/STALE/REMOVE/RESTORE")
    private String changeType;

    private String beforeSummary;

    private String afterSummary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
