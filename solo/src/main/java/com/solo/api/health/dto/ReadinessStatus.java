package com.solo.api.health.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 就绪探针结果（M7）：数据库、Redis 是平台核心依赖，任一不可用就返回 503，
 * 让负载均衡把本实例摘掉；媒体节点（ZLM）默认只报告不参与判定——
 * ZLM 抖动时管理 API、目录同步、SSE 都还能用，没必要把整个实例摘掉。
 */
@Schema(description = "就绪探针结果")
@Data
public class ReadinessStatus {

    @Schema(description = "是否可对外提供服务")
    private boolean ready;

    @Schema(description = "本轮实际探测时刻（缓存命中时会早于请求时刻）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkedAt;

    @Schema(description = "返回的这份结果是多久之前探测的；0 = 本次实时探测")
    private long cachedForMs;

    @Schema(description = "各依赖探测结果")
    private List<Dependency> dependencies = new ArrayList<>();

    @Schema(description = "单个依赖")
    @Data
    public static class Dependency {
        @Schema(description = "db / redis / media")
        private String name;
        @Schema(description = "UP / DOWN")
        private String status;
        @Schema(description = "一句话原因，不包含连接串、密码等敏感信息")
        private String detail;
        @Schema(description = "本次探测耗时（毫秒）")
        private long costMs;
    }
}
