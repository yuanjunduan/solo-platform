package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "国标设备分页查询请求")
@Data
public class GbDevicePageRequest {

    @Schema(description = "当前页码（从 1 开始）")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 20L;

    @Schema(description = "设备国标 ID（模糊匹配）")
    private String deviceId;

    @Schema(description = "设备名称（模糊匹配）")
    private String name;

    @Schema(description = "在线状态：0 离线、1 在线、2 未注册")
    private Integer status;

    @Schema(description = "数据来源：1 系统新增、2 表格导入、3 设备注册")
    private Integer source;

    @Schema(description = "资源域：LOCAL 本地 / SUBORDINATE 下级平台 / REMOTE 第三方资源平台 / ALL 全部；空=全部")
    private String domain;

    @Schema(description = "左树选中的平台/接入端点国标码：精确匹配 device_id（选中平台节点时只显示该端点自身）")
    private String platformId;

    /** 未归属任何本地组织时的过滤哨兵，与前端约定 -1 */
    @Schema(description = "所属本地组织 id；-1 表示未归属组织的本地设备；空=不过滤")
    private Long orgId;

    @Schema(description = "按组织过滤时是否包含所有子孙组织，默认 true")
    private boolean includeSubtree = true;
}
