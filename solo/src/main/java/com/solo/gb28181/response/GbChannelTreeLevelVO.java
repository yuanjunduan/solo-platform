package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录树一层的数据：当前节点自身统计 + 直接下级。
 * 懒加载时一次返回「本级数字」和「下级节点（带各自子树通道数）」，前端展开一个节点只需一次请求。
 */
@Schema(description = "国标目录树一层数据")
@Data
public class GbChannelTreeLevelVO {

    @Schema(description = "当前节点国标码；空串表示设备根")
    private String orgCode = "";

    @Schema(description = "当前节点组织路径；设备根为 /设备码")
    private String treePath = "";

    @Schema(description = "当前节点（含所有子分组）下的通道总数")
    private long subtreeChannelCount;

    @Schema(description = "直接下级分组数")
    private long childGroupCount;

    @Schema(description = "直接下级通道数")
    private long childChannelCount;

    @Schema(description = "直接下级节点（分组 + 通道，各带 subtreeChannelCount / hasChildren）")
    private List<GbChannelTreeNodeVO> children = new ArrayList<>();
}
