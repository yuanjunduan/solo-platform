package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "通道云台拉框缩放请求")
@Data
public class ChannelPtzDragZoomRequest {

    @Schema(description = "in=放大 / out=缩小")
    @NotBlank
    private String action;

    @Schema(description = "播放窗口长度像素")
    @NotNull
    private Integer length;

    @Schema(description = "播放窗口宽度像素")
    @NotNull
    private Integer width;

    @Schema(description = "拉框中心横轴像素")
    @NotNull
    private Integer midPointX;

    @Schema(description = "拉框中心纵轴像素")
    @NotNull
    private Integer midPointY;

    @Schema(description = "拉框长度像素")
    @NotNull
    private Integer lengthX;

    @Schema(description = "拉框宽度像素")
    @NotNull
    private Integer lengthY;
}
