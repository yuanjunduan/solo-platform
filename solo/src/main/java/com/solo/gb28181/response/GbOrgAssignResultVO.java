package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "调整组织归属结果")
@Data
public class GbOrgAssignResultVO {

    @Schema(description = "目标组织名称；清空归属时为 null")
    private String orgName;

    @Schema(description = "实际调整的设备数")
    private Integer devices;

    @Schema(description = "实际调整的通道数")
    private Integer channels;
}
