package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 目录树右侧通道分页请求：选中的组织 + 是否包含子组织。
 * {@code orgCode} 为空表示设备根（该设备全部通道）。
 */
@Schema(description = "组织子树通道分页请求")
@Data
public class GbChannelSubtreePageRequest {

    @Schema(description = "当前页码（从 1 开始）")
    private Long page = 1L;

    @Schema(description = "每页条数，默认 200")
    private Long pageSize = 200L;

    @Schema(description = "所属设备国标 ID")
    private String deviceId;

    @Schema(description = "选中的组织国标码；空=设备根")
    private String orgCode;

    @Schema(description = "是否包含子组织下的通道：true=整棵子树，false=只含直接挂在该组织下的通道")
    private Boolean includeSubtree = Boolean.FALSE;

    @Schema(description = "通道名称/国标码关键字")
    private String keyword;

    @Schema(description = "在线状态：ON / OFF / UNREGISTERED")
    private String status;
}
