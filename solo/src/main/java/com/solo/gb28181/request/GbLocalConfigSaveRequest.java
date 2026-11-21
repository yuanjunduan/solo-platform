package com.solo.gb28181.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 保存本平台国标配置。整行覆盖（null 表示该项回落 yaml）。
 *
 * <p>password 的语义单独处理：null/空串表示“不修改”；要清空密码（开放模式）
 * 传 {@code resetPassword=true}。</p>
 */
@Schema(description = "保存本平台国标配置")
@Data
public class GbLocalConfigSaveRequest {

    @Schema(description = "本平台 SIP 国标 ID（20 位）")
    private String sipId;

    @Schema(description = "本平台 SIP 域（必须是 SIP ID 前 10 位）")
    private String sipDomain;

    @Schema(description = "SIP 监听 IP；空串=自动扫网卡；null=沿用 yaml")
    private String sipIp;

    @Schema(description = "SIP 公网 IP")
    private String sipPublicIp;

    @Schema(description = "SIP 监听端口（保存后需重启生效）")
    private Integer sipPort;

    @Schema(description = "设备接入鉴权密码；null/空=不修改")
    private String password;

    @Schema(description = "是否清空密码（true=改为开放模式）")
    private Boolean resetPassword;

    @Schema(description = "设备注册有效期（秒）")
    private Integer registerExpires;

    @Schema(description = "心跳超时倍数")
    private Integer keepaliveTimeoutMultiplier;

    @Schema(description = "手动存活探测超时（毫秒）")
    private Long detectTimeoutMs;

    @Schema(description = "优先用报文源地址作为设备地址")
    private Boolean useSourceIpAsRemoteAddress;

    @Schema(description = "Catalog 响应单包 Item 数（1/2/4/8）")
    private Integer catalogPageSize;

    @Schema(description = "Catalog 分片 ACK 超时（毫秒）")
    private Long catalogAckTimeoutMs;

    @Schema(description = "Catalog 兜底发送间隔（毫秒）")
    private Long catalogPageIntervalMs;

    @Schema(description = "上级点播本地发送端口段起始")
    private Integer sendRtpPortStart;

    @Schema(description = "上级点播本地发送端口段结束")
    private Integer sendRtpPortEnd;
}
