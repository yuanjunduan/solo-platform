package com.solo.infra.mapper.projection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 推送队列按状态计数投影（资源分享总览页的四个数字）。
 */
@Schema(description = "推送队列状态计数")
@Data
public class OutboxStatusCount {

    @Schema(description = "PENDING/SENT/FAILED/DEAD/PAUSED")
    private String status;

    @Schema(description = "该状态条数")
    private Long total;
}
