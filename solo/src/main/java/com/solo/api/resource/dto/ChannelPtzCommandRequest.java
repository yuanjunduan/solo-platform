package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "通道云台方向控制请求")
@Data
public class ChannelPtzCommandRequest {

    @Schema(description = "指令：left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop")
    @NotBlank
    private String command;

    @Schema(description = "移动速度（0-255）")
    private Integer speed = 100;

    @Schema(description = "缩放速度（0-15）")
    private Integer zoomSpeed = 5;
}
