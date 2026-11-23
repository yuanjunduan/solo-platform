package com.solo.gb28181.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目录同步进度快照（数据源：Redis {@code gb28181:sync:status:{deviceId}}，30 分钟 TTL）。
 *
 * <p>内嵌在 {@link GbCatalogSourceVO} 里，来源卡片不必再为每个端点单独请求一次同步状态。</p>
 */
@Schema(description = "目录同步进度快照")
@Data
public class GbCatalogSyncVO {

    @Schema(description = "IDLE 从未同步 / RUNNING 进行中 / SUCCESS 收齐 / FAILED 超时或异常收尾")
    private String status = "IDLE";

    @Schema(description = "已接收条目数")
    private Long received = 0L;

    @Schema(description = "目录总数（设备上报 SumNum）")
    private Long total = 0L;

    @Schema(description = "进度百分比 0-100")
    private Double percent = 0.0;

    @Schema(description = "本次同步开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @Schema(description = "最近一次进度更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdateAt;

    @Schema(description = "结束时间（收尾时写入，格式 yyyy-MM-dd HH:mm:ss）")
    private String finishedAt;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "重复条目数（分片重叠时由合并逻辑丢弃）")
    private Long duplicateCount = 0L;
}
