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
 * 设备↔组织人工绑定（C13 第 ② 步）。
 *
 * <p>上游报文里的 {@code parent_code} 不可信：它可能是设备码、分组码，甚至平台域。
 * 运维在界面上纠正过一次之后，本轮没命中也要优先恢复这个绑定，
 * 否则每轮全量都要把所有已纠正的设备再打回 {@code [未分类]}。</p>
 */
@Schema(description = "设备↔组织人工绑定")
@Data
@TableName("device_org_binding")
public class DeviceOrgBinding {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "0=本地")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlatformId;

    private String deviceCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orgId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
