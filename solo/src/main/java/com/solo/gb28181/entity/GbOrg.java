package com.solo.gb28181.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 本地组织树节点。
 *
 * <p>只服务于"本地域"（{@code gb_device.domain = LOCAL}）的资源归属，与面向上级的
 * 推送分组 {@code gb_platform_group} 是两套结构：后者是某个上级平台视角下的 215/216 目录，
 * 由"导入生成"从本地组织派生出来，派生后各自独立演化。</p>
 *
 * <p>路径 {@code path} 形如 {@code /1/5/9/}（首尾都带斜杠），子树查询用
 * {@code path LIKE '/1/5/%'}，不会误匹配 {@code /1/50/}。物理删除（不加逻辑删除列），
 * 删除前必须校验无子组织、无设备/通道归属。</p>
 */
@Schema(description = "本地组织树节点")
@Data
@TableName("gb_org")
public class GbOrg {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "父组织主键（引用同表 id；根节点 = 0）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "组织名称（同一父节点下唯一）")
    private String name;

    @Schema(description = "组织国标编码（导入生成上级分组时写 215/216，可为空）")
    private String code;

    @Schema(description = "物化路径 /根id/.../自身id/，子树查询用 LIKE 前缀")
    private String path;

    @Schema(description = "层级（根 = 1）")
    private Integer level;

    @Schema(description = "同级排序（小的在前）")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
