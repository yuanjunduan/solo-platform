package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 入站通道点播请求。
 *
 * <p><b>为什么按行 id 而不是按国标编码</b>：入站通道在本地没有 {@code gb_device_channel} 行，
 * 对端点播靠的是"平台国标码 + 通道编码"这一对，而通道编码在本地可能被改写过（暴露码）。
 * 用行 id 让服务端按记录反查来源平台，避免前端拼码拼错导致 INVITE 打到一个不存在的通道。</p>
 */
@Schema(description = "入站通道点播请求")
@Data
public class InboundPlayRequest {

    @Schema(description = "res_channel.id（字符串形式，避免前端精度丢失）")
    private String channelRowId;

    @Schema(description = "收流模式：UDP / TCP-passive")
    private String transport = "TCP-passive";

    @Schema(description = "码流标识 token：留空/null = 主码流；非空 = 该 token 对应的子码流")
    private String streamNumber;
}
