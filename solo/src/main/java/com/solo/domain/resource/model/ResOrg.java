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
 * 组织（本地自建与下级平台镜像同表，来源作列）。
 *
 * <p><b>为什么来源是列不是表</b>：三张资源表字段差异大，按"资源类型"拆表；来源只影响归属与可写性，
 * 跨来源统一查询天然一条 SQL。见架构文档 3.5.1。</p>
 *
 * <p><b>业务键唯一</b>：{@code (source_type, source_platform_id, code)}。同步写入一律按此键原地 UPDATE，
 * 禁止"删了重建"——{@code id} 会被共享关系与暴露码引用，重建会让对上级的映射全部失联（C3 / 3.6.6）。</p>
 *
 * <p><b>物化路径</b>：{@code path} 形如 {@code /1/5/9/}。子树查询用前缀匹配；
 * 换父时整棵子树按前缀替换一次性重写，不递归逐条 UPDATE。</p>
 */
@Schema(description = "组织（本地与下级镜像同表，来源作列）")
@Data
@TableName("res_org")
public class ResOrg {

    @Schema(description = "主键")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "预留多租户")
    private Long tenantId;

    @Schema(description = "组织编码（本地自编 / 上游原始码）")
    private String code;

    @Schema(description = "父组织编码，根为 null")
    private String parentCode;

    @Schema(description = "父组织主键（同源内有效），根为 null")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "物化路径 /id/id/")
    private String path;

    @Schema(description = "层级，根 = 1")
    private Integer level;

    @Schema(description = "组织名称")
    private String name;

    @Schema(description = "本地别名（同步不覆盖）")
    private String localName;

    @Schema(description = "NATIVE / INBOUND")
    private String sourceType;

    @Schema(description = "直接来源平台 id，0 = 本地")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlatformId;

    @Schema(description = "来源链（平台 id 逗号串），用于追溯与防环")
    private String originChain;

    @Schema(description = "级联跳数")
    private Integer hopCount;

    @Schema(description = "ACTIVE/STALE/OFFLINE/REMOVED")
    private String status;

    @Schema(description = "确认消失时间（软删）")
    private LocalDateTime removedAt;

    @Schema(description = "1=4 步回填未命中，挂在[未分类]（C13 第 4 步）")
    private Integer orgMissing;

    @Schema(description = "1=本行就是[未分类]兜底组织")
    private Integer unclassified;

    @Schema(description = "同级排序号")
    private Integer sortNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
