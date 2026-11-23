package com.solo.gb28181.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 级联平台登记簿。
 *
 * <p>解决的是"左树第二层没有数据源"的问题：{@code gb_platform} 只是接入黑名单
 * （platform_id + enabled），{@code gb_parent_platform} 只存上级，都无法回答
 * "有哪些下级平台 / 第三方平台、各自同步到什么时候、有多少通道"。</p>
 *
 * <p>{@code platform_gb_id} 与 {@code gb_device_channel.device_id} 同值——那正是发起
 * Catalog 的端点编码，所以本表不引入新的业务键，只做聚合与展示。</p>
 */
@Schema(description = "级联平台登记簿")
@Data
@TableName("gb_platform_registry")
public class GbPlatformRegistry {

    @Schema(description = "主键 ID")
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "平台/接入端点国标码，等于 gb_device_channel.device_id")
    private String platformGbId;

    @Schema(description = "级联方向 DOWNSTREAM下级 / UPSTREAM上级 / REMOTE第三方资源平台")
    private String direction;

    @Schema(description = "端点类型 PLATFORM平台（11-13位=200）/ DEVICE接入设备或网关")
    private String kind;

    @Schema(description = "展示名称")
    private String name;

    @Schema(description = "信令 IP")
    private String sipIp;

    @Schema(description = "信令端口")
    private Integer sipPort;

    @Schema(description = "在线状态 0离线 1在线 2未注册")
    private Integer onlineStatus;

    @Schema(description = "最后 Catalog 同步时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCatalogSyncTime;

    @Schema(description = "通道数（parental=0）")
    private Integer channelCount;

    @Schema(description = "分组数（parental=1）")
    private Integer groupCount;

    @Schema(description = "1启用 0停用（停用后左树不展示）")
    private Integer enabled;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
