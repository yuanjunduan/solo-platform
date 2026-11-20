package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "实时点播开始请求")
@Data
public class PlayStartRequest {

    @Schema(description = "设备国标 20 位 ID")
    private String deviceId;

    @Schema(description = "通道国标 20 位 ID")
    private String channelId;

    @Schema(description = "收流模式：UDP / TCP-passive")
    private String transport = "TCP-passive";

    // 不传/null = 主码流；要子码流由调用方显式传 streamnumber:1（前端大窗口监控即如此）
    @Schema(description = "码流标识 token：留空/null = 主码流；非空 = 该 token 对应的子码流")
    private String streamNumber;
}
