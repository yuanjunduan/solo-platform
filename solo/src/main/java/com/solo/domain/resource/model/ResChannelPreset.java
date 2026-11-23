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
 * 通道预置位：编号由设备侧定义（1-255），本表只存"给用户看的名称"。
 *
 * <p>不复用遗留 {@code gb_ptz_preset}：那张表按 {@code gb_device_channel.id} 关联，
 * 而入站通道在本地没有 {@code gb_device_channel} 行，只有 {@code res_channel}。
 * 预置位对本地与入站都适用（入站通道的指令发到来源平台），所以统一挂 {@code res_channel.id}。</p>
 */
@Schema(description = "通道预置位")
@Data
@TableName("res_channel_preset")
public class ResChannelPreset {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "res_channel.id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long channelRowId;

    @Schema(description = "设备侧预置位编号 1-255")
    private Integer presetIndex;

    @Schema(description = "用户命名")
    private String name;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
