package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "通道实时点播请求")
@Data
public class ChannelLiveStartRequest {

    @Schema(description = "收流模式：UDP / TCP-passive")
    private String transport = "TCP-passive";

    @Schema(description = "码流标识 token：留空/null = 主码流；非空 = 该 token 对应的子码流")
    private String streamNumber;
}
