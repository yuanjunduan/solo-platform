package com.solo.api.resource;

import com.solo.aop.RequirePermission;
import com.solo.api.resource.dto.ObservabilitySummaryResponse;
import com.solo.common.Result;
import com.solo.domain.resource.service.ObservabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行自检（M6，6.7 可观测）：只读快照，不触发任何清理动作。
 */
@Tag(name = "运行自检", description = "取流会话、媒体节点流、级联/广播消费、今日播放记录与关键阈值汇总")
@RestController
@RequestMapping("/api/res/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    @Operation(summary = "运行自检汇总")
    @GetMapping("/summary")
    @RequirePermission({"res:observability:view"})
    public Result<ObservabilitySummaryResponse> summary() {
        return Result.success(observabilityService.summary());
    }
}
