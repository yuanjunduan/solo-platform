package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "通道预置位保存请求")
@Data
public class ChannelPresetSaveRequest {

    @Schema(description = "设备侧预置位编号 1-255；同编号已存在则覆盖该槽位")
    @NotNull
    @Min(1)
    @Max(255)
    private Integer presetIndex;

    @Schema(description = "用户命名；留空按“预置位<编号>”生成")
    private String name;
}
