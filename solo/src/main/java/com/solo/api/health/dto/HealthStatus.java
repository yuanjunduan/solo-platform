package com.solo.api.health.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存活探针结果（M7）：只回答"进程还在不在"，不碰任何外部依赖。
 *
 * <p>与就绪探针分开是部署编排的硬要求：DB 抖动时不应该让编排器反复重启进程，
 * 只应该把它从负载均衡摘掉（{@code /api/health/ready} 返回 503）。</p>
 */
@Schema(description = "存活探针结果")
@Data
public class HealthStatus {

    @Schema(description = "固定 UP；进程能响应就一定活着")
    private String status;

    @Schema(description = "应用名")
    private String service;

    @Schema(description = "构建版本；IDE 里直接跑时为 dev")
    private String version;

    @Schema(description = "进程启动时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @Schema(description = "已运行秒数")
    private long uptimeSeconds;
}
