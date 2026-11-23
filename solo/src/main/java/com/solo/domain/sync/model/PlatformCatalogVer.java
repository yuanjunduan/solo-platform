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
 * 平台目录版本：本轮全量结果的快照指纹（3.8.2 第 3e 步）。
 *
 * <p>指纹按 {@code code} 排序取哈希，未变则跳过整轮合并——这是定时全量的性能关键，
 * 省掉 100 万行的无意义 UPDATE。</p>
 *
 * <p>注意指纹只覆盖"编码集合"，不覆盖字段内容：内容变了但编码集合没变时，
 * 差异更新那条集合 SQL 仍会正确写入（它按列比对），只是不会大范围重扫。
 * 所以指纹命中只跳过"新增/消失计算"，不能跳过差异更新。</p>
 */
@Schema(description = "平台目录版本：快照指纹")
@Data
@TableName("platform_catalog_ver")
public class PlatformCatalogVer {

    @TableId(type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long platformId;

    private Long tenantId;

    private String fingerprint;

    private Integer itemCount;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastTaskId;

    private LocalDateTime lastSuccessAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
