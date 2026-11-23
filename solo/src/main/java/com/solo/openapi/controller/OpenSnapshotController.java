package com.solo.openapi.controller;

import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.model.ResourceSnapshotPage;
import com.solo.domain.event.service.EventSubscriptionService;
import com.solo.domain.event.service.ResourceSnapshotService;
import com.solo.openapi.auth.OpenAppContext;
import com.solo.openapi.auth.RequireScope;
import com.solo.openapi.constant.OpenApiConstants;
import com.solo.openapi.constant.OpenApiErrorCode;
import com.solo.openapi.exception.OpenApiException;
import com.solo.openapi.support.OpenApiContextHolder;
import com.solo.openapi.web.OpenApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API 全量快照（6.4）。
 *
 * <p>推荐接入顺序：<b>先拉快照拿 {@code cursor}，再带 {@code Last-Event-ID=cursor} 连 SSE</b>。
 * 反过来的话，快照与事件流交界处那段变化只能靠客户端自己去重，服务端帮不上。</p>
 *
 * <p>{@code platformId / orgPath} 只能收紧订阅 scope，放宽会被 403 拒绝
 * （3.12.5 "未授权订阅其他平台资源"）。</p>
 */
@Tag(name = "开放API-资源快照", description = "按 scope 分页拉取入站资源当前态")
@RestController
@RequestMapping("/open-api/v1/snapshot")
@RequiredArgsConstructor
public class OpenSnapshotController {

    private final EventSubscriptionService subscriptionService;
    private final ResourceSnapshotService snapshotService;

    @Operation(summary = "资源快照（分页）：取 cursor 后连 SSE 续增量")
    @RequireScope(value = OpenApiConstants.SCOPE_EVENT_SUBSCRIBE, name = "资源事件订阅")
    @GetMapping
    public OpenApiResult<ResourceSnapshotPage> page(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "platformId", required = false) Long platformId,
            @RequestParam(name = "orgPath", required = false) String orgPath,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            HttpServletRequest request) {
        OpenAppContext context = OpenApiContextHolder.get(request);
        if (context == null) {
            throw new OpenApiException(OpenApiErrorCode.MISSING_CREDENTIAL);
        }
        EventSubscription subscription = subscriptionService.loadOrCreate(
                context.getAppId(), context.getAppKey());
        if (subscription.getStatus() != null
                && subscription.getStatus() != EventSubscription.STATUS_ENABLED) {
            throw new OpenApiException(OpenApiErrorCode.SCOPE_DENIED, "该应用的事件订阅已停用");
        }
        ResourceSnapshotService.SnapshotType target = parseType(type);
        ResourceSnapshotPage result = snapshotService.page(subscription, target,
                platformId, orgPath, page, size);
        return OpenApiResult.success(result, OpenApiContextHolder.requestId(request));
    }

    private ResourceSnapshotService.SnapshotType parseType(String raw) {
        try {
            return ResourceSnapshotService.SnapshotType.of(raw);
        } catch (IllegalArgumentException e) {
            throw new OpenApiException(OpenApiErrorCode.PARAM_INVALID,
                    "type 仅支持 ORG/DEVICE/CHANNEL，收到：" + raw);
        }
    }
}
