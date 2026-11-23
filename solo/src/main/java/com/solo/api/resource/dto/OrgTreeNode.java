package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 组织树节点（组织管理页左树）。
 *
 * <p>字段是"给页面的最小集合"：树结构 + 直接资源计数 + 是否可写。
 * 不带 origin_chain / hop_count 这类追溯字段——它们属于详情弹窗，塞进树只增大响应体积。</p>
 */
@Schema(description = "组织树节点")
@Data
public class OrgTreeNode {

    @Schema(description = "组织 id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "父组织 id（根 = null）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "组织编码")
    private String code;

    @Schema(description = "组织名称")
    private String name;

    @Schema(description = "本地别名")
    private String localName;

    @Schema(description = "物化路径 /id/id/")
    private String path;

    @Schema(description = "层级，根 = 1")
    private Integer level;

    @Schema(description = "同级排序号")
    private Integer sortNo;

    @Schema(description = "来源 NATIVE / INBOUND")
    private String sourceType;

    @Schema(description = "状态 ACTIVE/STALE/OFFLINE/REMOVED")
    private String status;

    @Schema(description = "是否可由用户编辑（INBOUND 为 false，前端据此隐藏编辑入口）")
    private Boolean writable;

    @Schema(description = "直接归属本组织的设备数（不含子组织）")
    private Long deviceCount;

    @Schema(description = "直接归属本组织的通道数（不含子组织）")
    private Long channelCount;

    @Schema(description = "子组织")
    private List<OrgTreeNode> children;
}
