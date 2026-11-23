package com.solo.api.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台新增/编辑入参（4.4.1 的三步向导最终提交的就是这一份）。
 *
 * <p><b>code 只在新增时可写</b>：它是资源表的 {@code source_platform_id} 指向的行标识，
 * 也是平台根组织的编码来源，改了等于换一个平台——编辑接口会忽略它并提示不可改。</p>
 *
 * <p>没做的部分：证书/密码。凭据进 {@code platform_credential}，走单独的接口与掩码展示，
 * 不进这个 DTO（避免"保存平台"顺手把明文密码写进日志）。</p>
 */
@Schema(description = "平台新增/编辑入参")
@Data
public class PlatformSaveRequest {

    @Schema(description = "平台编码（建议用国标码，建立后不可修改）")
    @NotBlank(message = "平台编码不能为空")
    @Size(max = 64, message = "平台编码最长 64")
    private String code;

    @Schema(description = "平台名称")
    @NotBlank(message = "平台名称不能为空")
    @Size(max = 128, message = "平台名称最长 128")
    private String name;

    @Schema(description = "厂商：HIKVISION/DAHUA/GB28181/OTHER")
    private String vendor;

    @Schema(description = "目录同步协议，默认 SIP_GB28181")
    private String syncProtocol;

    @Schema(description = "共享推送协议，默认 SIP_GB28181")
    private String publishProtocol;

    @Schema(description = "取流协议，默认 SIP_GB28181")
    private String mediaProtocol;

    @Schema(description = "平台地址")
    @Size(max = 64)
    private String host;

    @Schema(description = "平台端口，默认 5060")
    private Integer port;

    @Schema(description = "本平台在该连接中使用的 SIP 域/编码前缀")
    @Size(max = 64)
    private String localDomain;

    @Schema(description = "1=它是我的下级，可向本平台推目录")
    private Integer acceptInbound;

    @Schema(description = "1=它是我的上级，本平台可向它推目录")
    private Integer pushOutbound;

    @Schema(description = "MANUAL/SCHEDULED/NOTIFY/BOTH")
    private String syncPolicy;

    @Schema(description = "定时同步表达式（syncPolicy 含 SCHEDULED 时必填）")
    @Size(max = 64)
    private String syncCron;

    @Schema(description = "G2 批量删除保护阈值，0=关闭；首次接入建议 0.2")
    @DecimalMin(value = "0", message = "阈值不能为负")
    @DecimalMax(value = "1", message = "阈值不能大于 1")
    private BigDecimal deleteGuardRatio;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255")
    private String remark;
}
