package com.solo.api.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 统一通道入口的录像查询请求。
 */
@Schema(description = "通道录像查询请求")
@Data
public class ChannelRecordQueryRequest {

    @Schema(description = "起始时间 yyyy-MM-dd HH:mm:ss")
    @NotBlank(message = "起始时间不能为空")
    private String startTime;

    @Schema(description = "结束时间 yyyy-MM-dd HH:mm:ss")
    @NotBlank(message = "结束时间不能为空")
    private String endTime;
}
