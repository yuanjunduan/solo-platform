package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 统一通道入口的回放请求：设备码与通道码由服务端从 {@code res_channel} 解析，调用方不再自报。
 */
@Schema(description = "通道回放请求（设备/通道码由通道 id 解析）")
@Data
public class ChannelPlaybackStartRequest {

    @Schema(description = "起始时间 yyyy-MM-dd HH:mm:ss")
    @NotBlank(message = "起始时间不能为空")
    private String startTime;

    @Schema(description = "结束时间 yyyy-MM-dd HH:mm:ss")
    @NotBlank(message = "结束时间不能为空")
    private String endTime;

    @Schema(description = "收流模式：UDP / TCP-passive / TCP-active")
    private String transport = "TCP-passive";

    @Schema(description = "码流 token：空 = 主码流，非空 = 子码流")
    private String streamNumber;
}
