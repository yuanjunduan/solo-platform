package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "批量调整资源组织归属请求")
@Data
public class GbOrgAssignRequest {

    @Schema(description = "目标组织 id；null 或 -1 表示清空（设备变未归属，通道回落为跟随设备）")
    private Long orgId;

    @Schema(description = "设备主键 id 列表")
    private List<Long> deviceIds;

    @Schema(description = "通道主键 id 列表")
    private List<Long> channelIds;
}
