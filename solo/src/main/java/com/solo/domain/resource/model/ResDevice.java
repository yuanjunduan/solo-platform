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
 * 设备（本地自建与下级平台镜像同表）。
 *
 * <p><b>位置双写</b>：{@code org_id} 是归属，{@code org_path} 是归属组织的路径快照——
 * 设备列表按"组织子树"筛选时直接用 {@code org_path LIKE '/1/5/%'} 一次命中，
 * 不必 JOIN 组织表。换父由组织侧重写子树时同步刷新（见 {@code OrgService}）。</p>
 *
 * <p><b>指纹</b>：{@code fingerprint} 只哈希稳定分量（IPC 地址 + 厂商 + 机型），
 * 不含通道数、名称等易变项——它只是"疑似同一台设备"的强证据，用于跨路径预填关联，
 * 绝不据此物理合并（3.10.3 L4a）。</p>
 */
@Schema(description = "设备（本地与下级镜像同表，含跨路径识别指纹）")
@Data
@TableName("res_device")
public class ResDevice {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "设备编码")
    private String code;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "本地别名（同步不覆盖）")
    private String localName;

    @Schema(description = "所属组织 id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orgId;

    @Schema(description = "所属组织路径快照，用于子树筛选")
    private String orgPath;

    @Schema(description = "设备 IP / 接入地址")
    private String deviceIp;

    @Schema(description = "厂商")
    private String vendor;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "通道数（最近一次统计，仅展示）")
    private Integer channelCount;

    @Schema(description = "去重强证据：稳定分量哈希")
    private String fingerprint;

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

    @Schema(description = "来源链（平台 id 逗号串）")
    private String originChain;

    @Schema(description = "级联跳数")
    private Integer hopCount;

    @Schema(description = "ACTIVE/STALE/OFFLINE/REMOVED")
    private String status;

    @Schema(description = "确认消失时间（软删）")
    private LocalDateTime removedAt;

    @Schema(description = "1=4 步回填未命中，挂在[未分类]（C13 第 4 步）")
    private Integer orgMissing;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
