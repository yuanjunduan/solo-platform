package com.solo.domain.sync.model;

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
 * 本轮远端目录暂存行（3.8.2 staging）。
 *
 * <p><b>它不是旁路</b>：写失败必须让整轮失败。理由见 C14——旁路（审计、快照）丢了只是少个证据，
 * staging 丢了却是"这一轮的远端基准不完整"，接着算出的消失集合会把大量资源误判为 STALE。</p>
 *
 * <p>常用扩展字段（IP/厂商/型号）直接建列而不是塞 JSON：集合 SQL 要直接读它们，
 * 塞 JSON 就得在 SQL 里写方言相关的 {@code JSON_EXTRACT}，反而更容易出错。</p>
 */
@Schema(description = "本轮远端目录暂存")
@Data
@TableName("sync_staging")
public class SyncStaging {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "ORG/GROUP/DEVICE/CHANNEL")
    private String itemType;

    private String code;

    @Schema(description = "对端给的父编码，可能是组织/分组/设备/平台域")
    private String parentCode;

    private String name;

    @Schema(description = "上游目录项状态 ON/OFF")
    private String onlineStatus;

    @Schema(description = "通道的载体设备编码")
    private String deviceCode;

    private String streamType;

    private String capability;

    private String mediaHint;

    private String deviceIp;

    private String vendor;

    private String model;

    @Schema(description = "215/216（仅分组项）")
    private String groupType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long originPlatformId;

    private String originCode;

    private String originChain;

    private Integer hopCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
