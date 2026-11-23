package com.solo.domain.sync.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织缺失设备的延迟重试队列（3.8.2）。
 *
 * <p>为什么要单独一张表：重试要重走 4 步回填的第 ① 步（{@code parent_code} 命中组织），
 * 而 {@code parent_code} 只存在于 {@code sync_staging}，收尾时它就被清掉了。
 * 把待重试的 {@code (device_code, parent_code)} 抄一份出来，既不破坏"收尾清 staging"，
 * 也不用给 res_device 加一个只为重试存在的列。</p>
 */
@Schema(description = "组织缺失设备延迟重试队列")
@Data
@TableName("sync_org_pending")
public class SyncOrgPending {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long platformId;

    @Schema(description = "本地设备编码（INBOUND）")
    private String deviceCode;

    @Schema(description = "对端给的父编码，重试时重走第 ① 步")
    private String parentCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
