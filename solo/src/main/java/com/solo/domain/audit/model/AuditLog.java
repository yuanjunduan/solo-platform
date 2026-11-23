package com.solo.domain.audit.model;

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
 * 审计日志：只追加，不更新不删除（3.12.6）。
 *
 * <p><b>审计是旁路</b>：异步、可丢、可关。审计写失败绝不能让业务事务回滚——
 * 否则"审计库抖动"会变成"共享配置保存不了"。这与 staging 的定位相反：
 * staging 是主链路，写失败必须让整轮同步失败（3.13.3）。</p>
 */
@Schema(description = "审计日志（只追加）")
@Data
@TableName("audit_log")
public class AuditLog {

    public static final String TARGET_ORG = "ORG";
    public static final String TARGET_DEVICE = "DEVICE";
    public static final String TARGET_CHANNEL = "CHANNEL";
    public static final String TARGET_PLATFORM = "PLATFORM";
    public static final String TARGET_SHARE = "SHARE";
    /** 播放记录（3.11.6）：播放记录本身只读，这里的对象是"记录集合"，导出时按批留痕 */
    public static final String TARGET_PLAY_RECORD = "PLAY_RECORD";

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "操作人 id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    @Schema(description = "操作人名称快照")
    private String operatorName;

    @Schema(description = "动作编码，如 SAVE_SHARE / REVOKE_SHARE / DELETE_RESOURCE / EXPORT")
    private String action;

    @Schema(description = "对象类型 ORG/DEVICE/CHANNEL/PLATFORM/SHARE")
    private String targetType;

    @Schema(description = "对象标识（编码或 id 字符串）")
    private String targetId;

    @Schema(description = "关键字段快照（禁止写凭证与报文原文）")
    private String detail;

    @Schema(description = "客户端 IP")
    private String clientIp;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
