package com.solo.domain.resource.model;

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
 * 通道：位置（org）+ 载体（device）双链路。
 *
 * <p><b>org_id 非空是硬约束</b>：需求要求"通道必须归属组织"。上游目录没给组织时，
 * 同步链路兜到平台的 {@code [未分类]} 组织，绝不允许写 0 或 null（架构约束 C4）。</p>
 *
 * <p><b>org_path 是路径快照</b>：树上下钻/按组织子树筛通道只需 {@code org_path LIKE}，
 * 组织换父时由组织侧统一刷新。</p>
 *
 * <p><b>全局身份</b>：{@code (origin_platform_id, origin_code)} 用于跨路径识别"同一条物理通道"；
 * {@code canonical_id} 指向同实体组的主记录（0 = 自身为组主）。只做逻辑关联，绝不物理合并——
 * 每个来源仍保留自己的一行，才能对各上级给出各自的暴露码（3.10.3）。</p>
 */
@Schema(description = "通道：位置(org) + 载体(device) 双链路")
@Data
@TableName("res_channel")
public class ResChannel {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "通道编码")
    private String code;

    @Schema(description = "通道名称")
    private String name;

    @Schema(description = "本地别名（同步不覆盖）")
    private String localName;

    @Schema(description = "所属组织 id（非空）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orgId;

    @Schema(description = "所属组织路径快照")
    private String orgPath;

    @Schema(description = "所属设备 id（取流载体）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deviceId;

    @Schema(description = "215/216 分组 id（可空）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long groupId;

    @Schema(description = "MAIN/SUB/THIRD")
    private String streamType;

    @Schema(description = "ON/OFF")
    private String onlineStatus;

    @Schema(description = "PTZ/RECORD/AUDIO/TALK 位串")
    private String capability;

    @Schema(description = "取流线索（不含凭据）")
    private String mediaHint;

    @Schema(description = "NATIVE / INBOUND")
    private String sourceType;

    @Schema(description = "直接来源平台 id，0 = 本地")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlatformId;

    @Schema(description = "最上游平台 id，0 = 本地或未知")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long originPlatformId;

    @Schema(description = "最上游平台上的原始编码")
    private String originCode;

    @Schema(description = "码链 JSON（最上游 → 直接来源），仅展示")
    private String codeChain;

    @Schema(description = "来源链（平台国标码逗号串，最上游 → 直接来源）；上游未提供时只有直接来源一段")
    private String originChain;

    @Schema(description = "级联跳数")
    private Integer hopCount;

    @Schema(description = "实体组主记录 id，0 = 自身为组主")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long canonicalId;

    @Schema(description = "ACTIVE/STALE/OFFLINE/REMOVED")
    private String status;

    @Schema(description = "确认消失时间（软删）")
    private LocalDateTime removedAt;

    @Schema(description = "1=通道归属未解析到有效组织，挂在[未分类]")
    private Integer orgMissing;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
