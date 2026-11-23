package com.solo.openapi.controller;

import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.enums.BusinessType;
import com.solo.domain.event.model.EventSubscription;
import com.solo.openapi.request.OpenSubscriptionPageRequest;
import com.solo.openapi.request.OpenSubscriptionSaveRequest;
import com.solo.openapi.service.OpenSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外资源事件订阅管理端（6.4）。
 *
 * <p>跟第三方调的 {@code /open-api/v1/events/stream} 不是一回事：这里走平台内部 JWT + RBAC，
 * 用来给某个应用收紧/放宽可见范围、以及修复数据后重置游标。</p>
 */
@Tag(name = "开放API订阅管理", description = "第三方应用的资源事件订阅范围与游标维护")
@RestController
@RequestMapping("/api/openapi/subscription")
@RequiredArgsConstructor
public class OpenSubscriptionController {

    private final OpenSubscriptionService subscriptionService;

    @Operation(summary = "分页查询订阅")
    @PostMapping("/page")
    @RequirePermission("openapi:subscription:list")
    public Result<PageResult<EventSubscription>> page(@Valid @RequestBody OpenSubscriptionPageRequest req) {
        return Result.success(subscriptionService.page(req));
    }

    @Operation(summary = "编辑订阅范围/启停/备注")
    @OperationLog(module = "开放API订阅", type = BusinessType.EDIT, description = "编辑资源事件订阅")
    @PostMapping("/update")
    @RequirePermission("openapi:subscription:edit")
    public Result<Void> update(@Valid @RequestBody OpenSubscriptionSaveRequest req) {
        subscriptionService.updateScope(req);
        return Result.success();
    }

    @Operation(summary = "游标重置（从当前最新处重新开始，不回放历史）")
    @OperationLog(module = "开放API订阅", type = BusinessType.EDIT, description = "重置订阅游标")
    @PostMapping("/{id}/reset-cursor")
    @RequirePermission("openapi:subscription:edit")
    public Result<Void> resetCursor(@PathVariable Long id) {
        subscriptionService.resetCursor(id);
        return Result.success();
    }
}
