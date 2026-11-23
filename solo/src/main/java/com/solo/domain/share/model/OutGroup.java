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
 * 某上级平台的虚拟组织树节点（215 业务分组 / 216 虚拟组织，3.9.1）。
 *
 * <p><b>为什么不能直接复用本地组织树</b>：GB28181 上级看到的目录是
 * {@code DeviceID + ParentID} 的扁平列表，它对"组织"的理解就是"父节点是分组编码"。
 * 同一个本地组织共享给 A、B 两个上级时，两棵树可以挂法不同（3.9.1 的第 ② 段），
 * 所以独立成表、独立编码（C6）。</p>
 *
 * <p>{@code sourceOrgId} 是"一键生成"的幂等键：同一个来源组织反复镜像只更新名称与位置，
 * 不新建节点，否则上级会看到一堆同名分组。</p>
 */
@Schema(description = "上级虚拟组织树节点（215/216）")
@Data
@TableName("out_group")
public class OutGroup {

    public static final String TYPE_BUSINESS = "215";
    public static final String TYPE_VIRTUAL = "216";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "目标上级平台")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "该上级看到的节点码（= 暴露码）")
    private String code;

    @Schema(description = "父节点，根为 NULL")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "父节点暴露码，组装报文用")
    private String parentCode;

    @Schema(description = "物化路径 /id/id/")
    private String path;

    private Integer level;

    private String name;

    @Schema(description = "215 业务分组 / 216 虚拟组织")
    private String groupType;

    @Schema(description = "镜像来源组织 id（一键生成幂等键）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOrgId;

    @Schema(description = "镜像来源组织路径快照")
    private String sourceOrgPath;

    private Integer sortNo;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
