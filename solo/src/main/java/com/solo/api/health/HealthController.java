package com.solo.api.health;

import com.solo.api.health.dto.HealthStatus;
import com.solo.api.health.dto.ReadinessStatus;
import com.solo.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针（M7）：供容器编排 / 负载均衡 / 监控系统调用，<b>免登录</b>。
 *
 * <p>只暴露"活着 / 能不能接活"两个事实，不返回配置、连接串、线程栈等敏感信息。
 * 路径已加入 {@code WebMvcConfig} 的 JWT 放行清单。</p>
 */
@Tag(name = "健康检查", description = "存活与就绪探针（免登录，供部署编排与监控使用）")
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @Operation(summary = "存活探针", description = "不访问任何外部依赖；返回 UPTIME 即进程存活")
    @GetMapping
    public Result<HealthStatus> health() {
        return Result.success(healthService.liveness());
    }

    /**
     * 就绪探针：DB / Redis 不可用返回 503（负载均衡摘除），响应体仍带依赖明细便于排障。
     */
    @Operation(summary = "就绪探针", description = "数据库与 Redis 不可用时返回 503；媒体节点默认只报告不参与判定")
    @GetMapping("/ready")
    public ResponseEntity<Result<ReadinessStatus>> ready() {
        ReadinessStatus status = healthService.readiness();
        if (status.isReady()) {
            return ResponseEntity.ok(Result.success(status));
        }
        Result<ReadinessStatus> body = Result.error(503, "依赖未就绪");
        body.setData(status);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
