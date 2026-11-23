package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设备新增 / 编辑入参。
 */
@Schema(description = "设备保存入参")
@Data
public class DeviceSaveRequest {

    @Schema(description = "设备编码（建立后不可改——业务键）")
    @NotBlank(message = "设备编码不能为空")
    @Size(max = 64, message = "设备编码不能超过 64 字符")
    private String code;

    @Schema(description = "设备名称")
    @NotBlank(message = "设备名称不能为空")
    @Size(max = 128, message = "设备名称不能超过 128 字符")
    private String name;

    @Schema(description = "所属组织 id（必填，通道归属靠它）")
    @NotNull(message = "所属组织不能为空")
    private Long orgId;

    @Schema(description = "设备 IP / 接入地址")
    @Size(max = 64, message = "设备地址不能超过 64 字符")
    private String deviceIp;

    @Schema(description = "厂商")
    @Size(max = 32, message = "厂商不能超过 32 字符")
    private String vendor;

    @Schema(description = "型号")
    @Size(max = 64, message = "型号不能超过 64 字符")
    private String model;

    @Schema(description = "本地别名")
    @Size(max = 128, message = "本地别名不能超过 128 字符")
    private String localName;
}
