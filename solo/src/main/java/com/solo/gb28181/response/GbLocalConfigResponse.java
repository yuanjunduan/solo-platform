package com.solo.gb28181.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 本平台国标配置视图。
 *
 * <p>password 恒为掩码；身份项若与当前运行值不一致，restartRequired=true，
 * 同时通过 running* 字段给出“当前实际生效值”，便于页面标注待重启项。</p>
 */
@Schema(description = "本平台国标配置")
@Data
public class GbLocalConfigResponse {

    @Schema(description = "本平台 SIP 国标 ID（20 位）")
    private String sipId;

    @Schema(description = "本平台 SIP 域")
    private String sipDomain;

    @Schema(description = "SIP 监听 IP")
    private String sipIp;

    @Schema(description = "SIP 公网 IP")
    private String sipPublicIp;

    @Schema(description = "SIP 监听端口")
    private Integer sipPort;

    @Schema(description = "鉴权密码（掩码）")
    private String password;

    @Schema(description = "是否已配置密码")
    private Boolean passwordConfigured;

    @Schema(description = "设备注册有效期（秒）")
    private Integer registerExpires;

    @Schema(description = "心跳超时倍数")
    private Integer keepaliveTimeoutMultiplier;

    @Schema(description = "手动存活探测超时（毫秒）")
    private Long detectTimeoutMs;

    @Schema(description = "优先用报文源地址作为设备地址")
    private Boolean useSourceIpAsRemoteAddress;

    @Schema(description = "Catalog 响应单包 Item 数")
    private Integer catalogPageSize;

    @Schema(description = "Catalog 分片 ACK 超时（毫秒）")
    private Long catalogAckTimeoutMs;

    @Schema(description = "Catalog 兜底发送间隔（毫秒）")
    private Long catalogPageIntervalMs;

    @Schema(description = "上级点播本地发送端口段起始")
    private Integer sendRtpPortStart;

    @Schema(description = "上级点播本地发送端口段结束")
    private Integer sendRtpPortEnd;

    @Schema(description = "身份项是否有改动待重启生效")
    private Boolean restartRequired;

    @Schema(description = "当前运行中的 SIP ID")
    private String runningSipId;

    @Schema(description = "当前运行中的 SIP 域")
    private String runningSipDomain;

    @Schema(description = "当前运行中的 SIP 监听 IP")
    private String runningSipIp;

    @Schema(description = "当前运行中的 SIP 监听端口")
    private Integer runningSipPort;

    @Schema(description = "配置来源：db=库配置，yaml=未配置回落配置文件")
    private String source;
}
