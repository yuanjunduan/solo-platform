package com.solo.domain.share.model;

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
 * 共享关系：资源 ↔ 挂载组 ↔ 上级（3.9.1 的第 ③ 段）。
 *
 * <p>两种行同表（3.9.2）：</p>
 * <ul>
 *   <li><b>规则行</b>：{@code scopeMode=SUBTREE, refType=ORG}，存"组织 X 含子树"，<b>不展开</b>；</li>
 *   <li><b>资源行</b>：{@code refType=CHANNEL/DEVICE}，人工勾选（{@code derived=0}）或由规则派生（{@code derived=1}）。</li>
 * </ul>
 *
 * <p><b>剔除不删行、也不新插行</b>：把对应资源行的 {@code enabled} 置 0（优先级高于规则）。
 * 若资源行还不存在（规则刚建、派生还没跑），则插入一条 {@code derived=1, enabled=0} 的抑制行，
 * 避免"先剔除、后被规则重新派生成可见"。</p>
 *
 * <p><b>撤销只置 {@code ref_alive=0}</b>（D2）：删行会丢掉"这个通道曾共享给谁"，
 * 资源恢复时又要人工重配。</p>
 */
@Schema(description = "共享关系：资源 ↔ 组 ↔ 上级")
@Data
@TableName("out_share")
public class OutShare {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "目标上级平台")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "挂在该上级哪个虚拟组织下")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long groupId;

    @Schema(description = "ORG=子树规则 / DEVICE / CHANNEL=具体资源")
    private String refType;

    @Schema(description = "资源主键（规则行=组织 id）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long refId;

    @Schema(description = "NATIVE / INBOUND")
    private String refSourceType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refSourcePlatformId;

    @Schema(description = "资源原始编码（规则行为组织码）")
    private String refCode;

    @Schema(description = "资源所在组织路径；规则行为规则路径")
    private String orgPath;

    @Schema(description = "EXPLICIT / SUBTREE")
    private String scopeMode;

    @Schema(description = "1=由 SUBTREE 规则派生")
    private Integer derived;

    @Schema(description = "0=排除（剔除/停用），优先级高于规则")
    private Integer enabled;

    @Schema(description = "0=资源已 REMOVED，保留关系便于恢复")
    private Integer refAlive;

    /**
     * 上次推给上级的在线状态（ON/OFF）。
     * <p>状态跟随时用来区分"一直离线"与"刚刚离线"：只比较 {@code res_channel.online_status}
     * 会让每一轮同步都给同一批离线通道重发 OFF。</p>
     */
    @Schema(description = "上次推给上级的在线状态 ON/OFF")
    private String refOnlineStatus;

    /** 查询瞬时带出：库里存的上次状态（与 {@link #refOnlineStatus} 被当前值覆盖时区分基线） */
    @TableField(exist = false)
    private String storedOnlineStatus;

    @Schema(description = "PENDING/SYNCED/FAILED/DEAD/REVOKING")
    private String status;

    private String lastError;

    private LocalDateTime lastPushedAt;

    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 联表带出：资源名称（不落库） */
    @TableField(exist = false)
    private String refName;

    /** 联表带出：平台名称（不落库） */
    @TableField(exist = false)
    private String platformName;

    /** 联表带出：挂载组名称（不落库） */
    @TableField(exist = false)
    private String groupName;

    public boolean isRule() {
        return ShareScopeMode.SUBTREE.equals(scopeMode);
    }

    public boolean isUsable() {
        return enabled != null && enabled == 1 && refAlive != null && refAlive == 1;
    }
}
