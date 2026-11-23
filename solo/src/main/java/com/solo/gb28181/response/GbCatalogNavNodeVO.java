package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 左树节点：三种来源共用一个结构，靠 {@code nodeType} 区分。
 *
 * <p>本地组织来自 {@code gb_org}，下级平台来自 {@code gb_platform_registry}，
 * 镜像分组来自 {@code gb_device_channel}(parental=1)。前端不需要知道背后的表，
 * 只用 key 递归请求下一层、用 domain/platformGbId/orgId 拼右侧列表的查询条件。</p>
 */
@Schema(description = "目录左树节点")
@Data
public class GbCatalogNavNodeVO {

    @Schema(description = "节点唯一标识：org:{组织id} / plat:{端点码} / grp:{端点码}:{组织码}")
    private String key;

    @Schema(description = "父节点 key；根层为空")
    private String parentKey;

    @Schema(description = "节点类型：ORG 本地组织 / PLATFORM 下级平台 / GROUP 镜像分组")
    private String nodeType;

    @Schema(description = "展示名称")
    private String label;

    @Schema(description = "该节点所属资源域：ORG=LOCAL，PLATFORM/GROUP=SUBORDINATE")
    private String domain;

    @Schema(description = "本地组织主键（nodeType=ORG）")
    private Long orgId;

    @Schema(description = "目录来源端点国标码（nodeType=PLATFORM/GROUP）")
    private String platformGbId;

    @Schema(description = "镜像分组国标码（nodeType=GROUP）")
    private String groupCode;

    @Schema(description = "分组的物化路径（nodeType=GROUP），右侧列表按它做子树前缀过滤；旧数据未建路径时为空")
    private String treePath;

    @Schema(description = "端点的编码类型：PLATFORM=平台 / DEVICE=设备（仅 PLATFORM 节点）")
    private String kind;

    @Schema(description = "端点在线状态 0离线 1在线 2未注册（仅 PLATFORM 节点）")
    private Integer onlineStatus;

    @Schema(description = "是否还有下一层（决定展开箭头）")
    private boolean hasChildren;

    @Schema(description = "直接归属该组织的本地设备数，不含子组织（仅 ORG 节点）")
    private Long directDeviceCount;

    @Schema(description = "子树通道数（PLATFORM=端点总通道数，GROUP=该分组含子分组的通道数）")
    private Long subtreeChannelCount;

    @Schema(description = "直接下级分组数（仅 PLATFORM 节点）")
    private Long childGroupCount;
}
