package com.solo.gb28181.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 本平台国标身份与接入参数（单行表，主键恒为 1）。
 *
 * <p>字段为 null 表示「沿用 application yaml」；非 null 优先于 yaml。
 * 加载时机：{@code GbLocalConfigLoader} 在 SIP 协议栈启动前覆盖
 * {@link com.solo.gb28181.config.Gb28181Properties}。</p>
 *
 * <p>其中 sipId / sipDomain / sipIp / sipPort 属于平台身份，运行期修改不会重建
 * SIP 监听点，也改不了已发出的 Via/Contact，因此只在重启后生效；其余字段保存后即时生效。</p>
 */
@Schema(description = "本平台国标配置（单行）")
@Data
@TableName("gb_local_config")
public class GbLocalConfig {

    @Schema(description = "固定主键，恒为 1")
    @TableId(type = IdType.INPUT)
    private Long id;

    @Schema(description = "本平台 SIP 国标 ID（20 位）")
    private String sipId;

    @Schema(description = "本平台 SIP 域（ID 前 10 位）")
    private String sipDomain;

    @Schema(description = "SIP 监听 IP；空串=自动扫网卡，null=沿用 yaml")
    private String sipIp;

    @Schema(description = "SIP 公网 IP（NAT 场景 Via/Contact 使用）")
    private String sipPublicIp;

    @Schema(description = "SIP 监听端口（修改后需重启）")
    private Integer sipPort;

    @Schema(description = "设备接入 Digest 鉴权密码")
    private String password;

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
}
