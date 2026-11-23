package com.solo.api.sync;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.api.sync.dto.SyncTaskDetail;
import com.solo.api.sync.dto.SyncTaskQueryRequest;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.sync.model.SyncRecord;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.service.InboundSyncService;
import com.solo.domain.sync.service.SyncCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步中心：任务列表、进度、差异、失败明细（4.2 / 4.6）。
 *
 * <p>触发与取消走 {@link InboundSyncService}（状态机），查询走 {@link SyncCenterService}。
 * 两者分开注入是刻意：控制器不该知道"触发"内部怎么拿到会话句柄。</p>
 */
@Tag(name = "同步中心", description = "入站同步轮次：触发、取消、台账与明细")
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncCenterController {

    private final InboundSyncService inboundSyncService;
    private final SyncCenterService syncCenterService;

    @Operation(summary = "任务分页（按创建时间倒序）")
    @PostMapping("/task/page")
    @RequirePermission({"sync:list"})
    public Result<PageResult<SyncTask>> page(@RequestBody SyncTaskQueryRequest req) {
        return Result.success(syncCenterService.pageTasks(req));
    }

    @Operation(summary = "任务详情（含闸门快照与变更留痕）")
    @GetMapping("/task/{id}")
    @RequirePermission({"sync:list"})
    public Result<SyncTaskDetail> detail(@PathVariable("id") Long id) {
        return Result.success(syncCenterService.detail(id));
    }

    @Operation(summary = "任务变更留痕分页")
    @GetMapping("/task/{id}/records")
    @RequirePermission({"sync:list"})
    public Result<PageResult<SyncRecord>> records(
            @PathVariable("id") Long id,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") Long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") Long pageSize) {
        return Result.success(syncCenterService.pageRecords(id, page, pageSize));
    }

    @Operation(summary = "发起一轮全量同步（立即返回轮次 id，数据在批次到达时写入）")
    @OperationLog(module = "同步中心", type = BusinessType.OTHER, description = "触发入站同步")
    @PostMapping("/trigger/{platformId}")
    @RequirePermission({"sync:trigger"})
    public Result<Long> trigger(
            @PathVariable("platformId") Long platformId,
            @Parameter(description = "MANUAL / SCHEDULED / NOTIFY，默认 MANUAL")
            @RequestParam(required = false) String triggerType) {
        return Result.success(inboundSyncService.trigger(platformId, triggerType));
    }

    @Operation(summary = "取消轮次（丢弃暂存，资源不动）")
    @OperationLog(module = "同步中心", type = BusinessType.OTHER, description = "取消入站同步")
    @PostMapping("/cancel/{taskId}")
    @RequirePermission({"sync:cancel"})
    public Result<Void> cancel(@PathVariable("taskId") Long taskId) {
        inboundSyncService.cancel(taskId);
        return Result.success();
    }
}
