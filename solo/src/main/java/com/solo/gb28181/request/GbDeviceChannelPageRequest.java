package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "国标资源目录项分页查询请求")
@Data
public class GbDeviceChannelPageRequest {

    @Schema(description = "当前页码（从 1 开始）")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 20L;

    @Schema(description = "所属设备国标 ID")
    private String deviceId;

    @Schema(description = "来源下级平台国标编码（精确匹配 gb_device_channel.platform_id）")
    private String platformId;

    @Schema(description = "20 位国标通道码")
    private String channelId;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "在线状态：ON / OFF / UNREGISTERED")
    private String status;

    @Schema(description = "父分组 GB 码前缀（按前缀匹配）")
    private String parentId;

    @Schema(description = "资源类型过滤：0=仅通道、1=仅分组、null=全部")
    private Integer parental;

    @Schema(description = "数据来源：1 系统新增、2 表格导入、3 设备注册")
    private Integer source;

    @Schema(description = "云台类型：0 未知、1 球机、2 半球、3 固定枪机、4 遥控枪机（仅对通道生效）")
    private Integer ptzType;

    @Schema(description = "资源域：LOCAL 本地 / SUBORDINATE 下级平台 / REMOTE 第三方资源平台 / ALL 全部；空=全部")
    private String domain;

    @Schema(description = "左树选中分组时传入该分组的 tree_path（形如 /端点码/分组码），按前缀过滤含全部子分组")
    private String pathPrefix;

    /** 未归属任何本地组织时的过滤哨兵，与前端约定 -1 */
    @Schema(description = "所属组织 id（通道自身 org_id 优先，为空则继承所属设备的 org_id）；-1 表示未归属；空=不过滤")
    private Long orgId;

    @Schema(description = "按组织过滤时是否包含所有子孙组织，默认 true")
    private boolean includeSubtree = true;
}
