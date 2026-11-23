package com.solo.openapi.controller;

import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.service.EventStreamService;
import com.solo.domain.event.service.EventSubscriptionService;
import com.solo.openapi.auth.OpenAppContext;
import com.solo.openapi.auth.RequireScope;
import com.solo.openapi.constant.OpenApiConstants;
import com.solo.openapi.constant.OpenApiErrorCode;
import com.solo.openapi.exception.OpenApiException;
import com.solo.openapi.support.OpenApiContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 开放 API 资源事件流（6.4 第三阶段）。
 *
 * <p><b>游标带两条路</b>：浏览器原生 {@code EventSource} 重连时只会带
 * {@code Last-Event-ID} 请求头（不带的话没法自己签）；而签名客户端（curl / 服务端 SDK）
 * 通常不方便设请求头，所以也接一个 {@code lastEventId} 查询参数。<b>关键差别</b>：
 * 查询参数参与签名（{@code canonicalQuery}），请求头不参与——服务端两者取较大值，
 * 谁传都不影响游标只进不退。</p>
 *
 * <p><b>首次连接没有游标时不回放历史</b>：从当前最大游标开始，历史态由
 * {@code /open-api/v1/snapshot} 按 scope 拉取。否则一次首连就要把整张事件表吐一遍。</p>
 *
 * <p>拒绝语义：游标超保留窗口 → 409（带 {@code snapshotUrl}）；本实例连接已满 → 429。
 * 两者都在返回 emitter 之前判掉，客户端不会先收到 200 再莫名断流。</p>
 */
@Tag(name = "开放API-资源事件流", description = "SSE 订阅资源变更：游标续传 + 两级 scope")
@RestController
@RequestMapping("/open-api/v1/events")
@RequiredArgsConstructor
public class OpenEventController {

    private final EventSubscriptionService subscriptionService;
    private final EventStreamService streamService;

    @Operation(summary = "订阅资源变更（SSE），带 Last-Event-ID 续传")
    @RequireScope(value = OpenApiConstants.SCOPE_EVENT_SUBSCRIBE, name = "资源事件订阅")
    @GetMapping("/stream")
    public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String headerCursor,
                             @RequestParam(name = "lastEventId", required = false) String paramCursor,
                             HttpServletRequest request) {
        OpenAppContext context = OpenApiContextHolder.get(request);
        if (context == null) {
            // 过滤器的兜底：没经过鉴权的请求到不了这里
            throw new OpenApiException(OpenApiErrorCode.MISSING_CREDENTIAL);
        }
        EventSubscription subscription = subscriptionService.loadOrCreate(
                context.getAppId(), context.getAppKey());
        if (subscription.getStatus() != null
                && subscription.getStatus() != EventSubscription.STATUS_ENABLED) {
            // 停订后仍能连上就白停了；scope 已通过校验，这里只判订阅自身状态
            throw new OpenApiException(OpenApiErrorCode.SCOPE_DENIED, "该应用的事件订阅已停用");
        }
        Long requested = headerCursor != null ? parseCursor(headerCursor, "Last-Event-ID")
                : parseCursor(paramCursor, "lastEventId");
        return streamService.open(subscription, requested);
    }

    private Long parseCursor(String raw, String field) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new OpenApiException(OpenApiErrorCode.PARAM_INVALID, field + " 必须是数字游标");
        }
    }
}
