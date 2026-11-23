package com.solo.api.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 同步任务分页查询（同步中心列表）。
 *
 * <p>不提供"按时间范围"：轮次台账天然按时间倒序翻页就够了，
 * 多一个时间条件会让前端多两个日期控件，而运维排障一般是"看最近 20 条"。</p>
 */
@Schema(description = "同步任务分页查询")
@Data
public class SyncTaskQueryRequest {

    @Schema(description = "页码，从 1 开始")
    private Long page = 1L;

    @Schema(description = "每页条数")
    private Long pageSize = 20L;

    @Schema(description = "平台 id；空=全部平台")
    private Long platformId;

    @Schema(description = "状态 PENDING/RUNNING/SYNCHING/SUCCESS/FAILED/CANCELLED；空=全部")
    private String status;

    @Schema(description = "触发方式 MANUAL/SCHEDULED/NOTIFY；空=全部")
    private String triggerType;
}
