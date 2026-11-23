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
 * 目录分组：215 业务分组 / 216 虚拟组织。
 *
 * <p>与 {@link ResOrg} 的区别：组织回答"资源归谁管"（必须有一个），分组回答"上游把资源放在哪个抽屉里"
 * （可以没有）。上游 Catalog 里的 215/216 节点落这张表，通道用 {@code group_id} 挂上去，
 * 这样既满足"通道必须归属组织"，又能原样保留上游的目录形状。</p>
 */
@Schema(description = "目录分组：215 业务分组 / 216 虚拟组织")
@Data
@TableName("res_group")
public class ResGroup {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "分组编码")
    private String code;

    @Schema(description = "分组名称")
    private String name;

    @Schema(description = "父分组 id，根为 null")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "物化路径 /id/id/")
    private String path;

    @Schema(description = "层级，根 = 1")
    private Integer level;

    @Schema(description = "215 业务分组 / 216 虚拟组织")
    private String groupType;

    @Schema(description = "NATIVE / INBOUND")
    private String sourceType;

    @Schema(description = "直接来源平台 id，0 = 本地")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlatformId;

    @Schema(description = "来源链（平台 id 逗号串）")
    private String originChain;

    @Schema(description = "级联跳数")
    private Integer hopCount;

    @Schema(description = "ACTIVE/STALE/OFFLINE/REMOVED")
    private String status;

    @Schema(description = "确认消失时间（软删）")
    private LocalDateTime removedAt;

    @Schema(description = "同级排序号")
    private Integer sortNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
