package com.solo.gb28181.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目录来源（通道页第一维度）。
 *
 * <p>来源 = 发起 Catalog 的端点编码（{@code gb_device_channel.device_id}），由目录数据自身聚合得出，
 * 不依赖 gb_platform（那是接入禁用名单）等任何登记表：推过目录的端点才会出现，没推过的不会。</p>
 */
@Schema(description = "目录来源（下级平台 / 接入设备）")
@Data
public class GbCatalogSourceVO {

    @Schema(description = "来源端点国标编码（= gb_device_channel.device_id）")
    private String deviceId;

    @Schema(description = "展示名称：优先用户自定义名，其次设备上报名；都没有则前端回退编码")
    private String name;

    @Schema(description = "来源类型：PLATFORM=下级平台（编码第 11-13 位为 200）/ DEVICE=设备或网关")
    private String kind;

    @Schema(description = "资源域：LOCAL 本地 / SUBORDINATE 下级平台（端点不在 gb_device 里时按 SUBORDINATE）")
    private String domain;

    @Schema(description = "端点在线状态（1 在线 / 0 离线 / 2 未注册）；端点无 gb_device 行时为空")
    private Integer onlineStatus;

    @Schema(description = "最近一次目录同步时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCatalogSyncTime;

    @Schema(description = "目录条目总数（分组 + 通道）")
    private Long itemCount;

    @Schema(description = "分组数")
    private Long groupCount;

    @Schema(description = "通道数")
    private Long channelCount;

    @Schema(description = "在线通道数")
    private Long onlineChannelCount;

    @Schema(description = "离线通道数")
    private Long offlineChannelCount;

    @Schema(description = "目录同步进度快照；never synced 时 status=IDLE")
    private GbCatalogSyncVO sync;
}
