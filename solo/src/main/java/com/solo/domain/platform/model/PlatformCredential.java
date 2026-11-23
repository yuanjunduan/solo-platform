package com.solo.domain.platform.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 平台认证配置：密文存储，查询接口只返回掩码（3.12.4）。
 *
 * <p>GB35114 首版不要求（C28），但表结构先带 5 个可空占位、{@code cipher_suite} 默认 {@code NONE}，
 * 后续启用国密级联时不必再改表。</p>
 *
 * <p>本实体只在服务层内部使用，<b>禁止直接作为接口响应体</b>：任何 {@code *_cipher} 字段泄漏到前端
 * 都等于把凭证库交出去。</p>
 */
@Schema(description = "平台认证配置（密文，不直接对外）")
@Data
@TableName("platform_credential")
public class PlatformCredential {

    public static final String CIPHER_NONE = "NONE";

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "平台 id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "认证账号（明文，仅账号）")
    private String username;

    @Schema(description = "密码密文")
    private String passwordCipher;

    @Schema(description = "证书密文")
    private String certCipher;

    @Schema(description = "私钥密文")
    private String privateKeyCipher;

    @Schema(description = "NONE/SM2/SM3/SM4")
    private String cipherSuite;

    @Schema(description = "GB35114 证书标识（占位）")
    private String gb35114CertId;

    @Schema(description = "GB35114 证书类型（占位）")
    private String gb35114CertType;

    @Schema(description = "GB35114 密钥算法（占位）")
    private String gb35114KeyAlg;

    @Schema(description = "GB35114 协议版本（占位）")
    private String gb35114ProtoVer;

    @Schema(description = "GB35114 扩展位（占位）")
    private String gb35114Ext;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
