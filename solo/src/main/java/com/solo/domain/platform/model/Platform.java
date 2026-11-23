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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台连接：一个外部平台一条记录，方向由两个能力位表达。
 *
 * <p><b>为什么方向不做成枚举</b>：国标允许两个平台互为上下级。用
 * {@code accept_inbound}（它是我的下级，可向本平台推目录）与
 * {@code push_outbound}（它是我的上级，本平台可向它推目录）两个独立开关，
 * 四种组合（10 只收 / 01 只发 / 11 双向 / 00 仅登记）都合法，见 3.4.2。</p>
 *
 * <p>本表<b>只存连接与能力</b>，不存任何资源数据：资源表只保留 {@code source_platform_id} 外键值，
 * 不做 JOIN 取业务字段（架构设计原则 7）。</p>
 */
@Schema(description = "平台连接：一个外部平台一条记录，方向由能力位表达")
@Data
@TableName("platform")
public class Platform {

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_UNREACHABLE = "UNREACHABLE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String POLICY_MANUAL = "MANUAL";
    public static final String POLICY_SCHEDULED = "SCHEDULED";
    public static final String POLICY_NOTIFY = "NOTIFY";
    public static final String POLICY_BOTH = "BOTH";

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "平台唯一标识（本系统内），建议用国标码")
    private String code;

    @Schema(description = "平台名称")
    private String name;

    @Schema(description = "HIKVISION/DAHUA/GB28181/OTHER")
    private String vendor;

    @Schema(description = "旧版单一协议位，M2a 起仅作缺省兜底")
    private String protocol;

    @Schema(description = "目录同步协议（3.7.1：三层协议必须分开）")
    private String syncProtocol;

    @Schema(description = "共享推送协议")
    private String publishProtocol;

    @Schema(description = "取流协议（同一平台可能三种都不同）")
    private String mediaProtocol;

    @Schema(description = "平台地址")
    private String host;

    @Schema(description = "平台端口")
    private Integer port;

    @Schema(description = "本平台在该连接中使用的 SIP 域/编码前缀")
    private String localDomain;

    @Schema(description = "1=可向本平台推目录（它是我的下级）")
    private Integer acceptInbound;

    @Schema(description = "1=本平台可向它推目录（它是我的上级）")
    private Integer pushOutbound;

    @Schema(description = "ONLINE/OFFLINE/UNREACHABLE/DISABLED")
    private String status;

    @Schema(description = "最后在线时间")
    private LocalDateTime lastOnlineAt;

    @Schema(description = "MANUAL/SCHEDULED/NOTIFY/BOTH")
    private String syncPolicy;

    @Schema(description = "定时同步表达式")
    private String syncCron;

    @Schema(description = "G2 批量删除保护阈值，0=关闭；首次接入必须手工设 0.2（C25）")
    private BigDecimal deleteGuardRatio;

    @Schema(description = "备注")
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public boolean acceptsInbound() {
        return acceptInbound != null && acceptInbound == 1;
    }

    public boolean pushesOutbound() {
        return pushOutbound != null && pushOutbound == 1;
    }
}
