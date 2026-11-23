package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 播放记录查询条件（按人 / 通道 / 时间 / 动作 / 结果）。
 */
@Schema(description = "播放记录查询条件")
@Data
public class PlayRecordQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    @Schema(description = "通道行 id（res_channel.id）")
    private Long channelRowId;

    @Schema(description = "通道编码模糊匹配")
    private String channelCode;

    @Schema(description = "操作者 id")
    private String actorId;

    @Schema(description = "PLAY / PLAYBACK")
    private String action;

    @Schema(description = "SUCCESS / FAILED")
    private String result;

    @Schema(description = "开始时间下界 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime begin;

    @Schema(description = "开始时间上界 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime end;
}
