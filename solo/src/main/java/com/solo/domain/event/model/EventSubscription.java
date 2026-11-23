package com.solo.domain.event.model;

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
 * 对外订阅（3.5.3 表 14）：一个开放应用对 {@code resource} 主题的一份订阅。
 *
 * <p><b>scope 支持两级</b>（Q10）：{@code platformId} 指定只看某个来源平台，
 * {@code orgPath} 指定只看某棵组织子树（path 前缀）。两者都为空 = 租户内全部可见。
 * scope 即授权——推送前必须再过滤一次，不能只信订阅时校验过的结论（3.12.1）。</p>
 *
 * <p>{@code lastEventId} 是<b>服务端记账</b>：客户端带 {@code Last-Event-ID} 时以两者较大值为准，
 * 没带时从这个游标续。这样"客户端乱传一个更小的游标"不会导致重复回放。
 * 游标只在事件成功写出后才推进，断了就停在原处等重连。</p>
 */
@Schema(description = "对外订阅：scope 即授权")
@Data
@TableName("event_subscription")
public class EventSubscription {

    public static final String TOPIC_RESOURCE = "resource";

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long tenantId;

    @Schema(description = "绑定的开放应用（API Key 归属）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long appId;

    @Schema(description = "应用标识快照，应用轮换 Key 后仍能对账")
    private String appKey;

    @Schema(description = "订阅主题，首版只有 resource")
    private String topic;

    @Schema(description = "scope 一级：只看这个来源平台")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    @Schema(description = "scope 二级：只看这棵组织子树（path 前缀）")
    private String orgPath;

    @Schema(description = "服务端已发出的最大游标")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastEventId;

    @Schema(description = "1 启用 0 停用")
    private Integer status;

    private String remark;

    /** 联表带出：应用名，管理端列表用（不落库） */
    @TableField(exist = false)
    private String appName;

    /** 联表带出：来源平台名，管理端列表用（不落库） */
    @TableField(exist = false)
    private String platformName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
