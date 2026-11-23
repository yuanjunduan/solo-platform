package com.solo.gb28181.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 级联平台登记行（左树根层节点） */
@Schema(description = "级联平台登记行")
@Data
public class GbPlatformRegistryVO {

    @Schema(description = "平台/接入端点国标码")
    private String platformGbId;

    @Schema(description = "级联方向 DOWNSTREAM / UPSTREAM / REMOTE")
    private String direction;

    @Schema(description = "端点类型 PLATFORM / DEVICE")
    private String kind;

    @Schema(description = "展示名称")
    private String name;

    @Schema(description = "在线状态 0离线 1在线 2未注册")
    private Integer onlineStatus;

    @Schema(description = "最后 Catalog 同步时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCatalogSyncTime;

    @Schema(description = "通道数")
    private Integer channelCount;

    @Schema(description = "分组数")
    private Integer groupCount;
}
