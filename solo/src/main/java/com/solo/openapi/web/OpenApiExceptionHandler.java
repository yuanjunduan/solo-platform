package com.solo.openapi.web;

import com.solo.common.ServiceException;
import com.solo.domain.event.support.EventScopeDeniedException;
import com.solo.domain.event.support.EventStreamRejectedException;
import com.solo.openapi.constant.OpenApiConstants;
import com.solo.openapi.constant.OpenApiErrorCode;
import com.solo.openapi.exception.OpenApiException;
import com.solo.openapi.support.OpenApiContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API 专用异常处理，只作用于 /open-api/**，与内部 GlobalExceptionHandler 互不干扰。
 *
 * <p>必须优先于无包范围的 GlobalExceptionHandler：否则 OpenApiException / ServiceException
 * 会被全局兜底捕获，第三方将收到内部 Result 包装体（甚至 HTTP 200 + 系统异常），
 * 拿不到约定的 OpenApiResult 与开放错误码。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.solo.openapi.controller")
public class OpenApiExceptionHandler {

    @ExceptionHandler(OpenApiException.class)
    public ResponseEntity<OpenApiResult<Void>> handleOpenApi(OpenApiException e, HttpServletRequest request) {
        OpenApiErrorCode errorCode = e.getErrorCode();
        log.warn("[开放API] 业务异常 appKey={} code={} msg={}",
                request.getHeader(OpenApiConstants.HEADER_APP_KEY), errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(OpenApiResult.error(errorCode.getCode(), e.getMessage(),
                        OpenApiContextHolder.requestId(request)));
    }

    /**
     * 订阅连接受限：409 要带 {@code snapshotUrl}（6.4 验收标准 ④），
     * 429 只是"这个实例满了"，让客户端换实例重试。
     */
    @ExceptionHandler(EventStreamRejectedException.class)
    public ResponseEntity<OpenApiResult<Map<String, Object>>> handleEventStream(
            EventStreamRejectedException e, HttpServletRequest request) {
        String rid = OpenApiContextHolder.requestId(request);
        log.warn("[开放API] 订阅连接被拒 appKey={} reason={} msg={}",
                request.getHeader(OpenApiConstants.HEADER_APP_KEY), e.getReason(), e.getMessage());
        if (e.getReason() == EventStreamRejectedException.Reason.SATURATED) {
            return ResponseEntity.status(OpenApiErrorCode.STREAM_LIMIT_EXCEEDED.getHttpStatus())
                    .body(OpenApiResult.error(OpenApiErrorCode.STREAM_LIMIT_EXCEEDED.getCode(),
                            e.getMessage(), null, rid));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("snapshotUrl", e.getSnapshotUrl());
        data.put("minSeq", e.getMinSeq());
        data.put("maxSeq", e.getMaxSeq());
        return ResponseEntity.status(OpenApiErrorCode.CURSOR_EXPIRED.getHttpStatus())
                .body(OpenApiResult.error(OpenApiErrorCode.CURSOR_EXPIRED.getCode(),
                        e.getMessage(), data, rid));
    }

    /**
     * 请求的 scope 超出订阅授权范围：明确 403，不做静默裁剪
     * （静默裁剪会让人以为"这个平台没资源"，3.12.5）。
     */
    @ExceptionHandler(EventScopeDeniedException.class)
    public ResponseEntity<OpenApiResult<Void>> handleScopeDenied(EventScopeDeniedException e,
                                                                 HttpServletRequest request) {
        log.warn("[开放API] 越权查询 appKey={} msg={}",
                request.getHeader(OpenApiConstants.HEADER_APP_KEY), e.getMessage());
        return ResponseEntity.status(OpenApiErrorCode.SCOPE_DENIED.getHttpStatus())
                .body(OpenApiResult.error(OpenApiErrorCode.SCOPE_DENIED.getCode(), e.getMessage(),
                        OpenApiContextHolder.requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OpenApiResult<Void>> handleValid(MethodArgumentNotValidException e,
                                                           HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse(OpenApiErrorCode.PARAM_INVALID.getMessage());
        return ResponseEntity.status(OpenApiErrorCode.PARAM_INVALID.getHttpStatus())
                .body(OpenApiResult.error(OpenApiErrorCode.PARAM_INVALID.getCode(), msg,
                        OpenApiContextHolder.requestId(request)));
    }

    /**
     * 内部业务层统一抛 ServiceException，开放侧不能把这些原样回成 500。
     * 这里按消息关键字粗分到开放错误码，消息原文透传给对接方方便排查。
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<OpenApiResult<Void>> handleService(ServiceException e, HttpServletRequest request) {
        OpenApiErrorCode errorCode = mapServiceError(e.getMessage());
        log.warn("[开放API] 业务异常 appKey={} code={} msg={}",
                request.getHeader(OpenApiConstants.HEADER_APP_KEY), errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(OpenApiResult.error(errorCode.getCode(), e.getMessage(),
                        OpenApiContextHolder.requestId(request)));
    }

    private OpenApiErrorCode mapServiceError(String message) {
        if (message == null) {
            return OpenApiErrorCode.INTERNAL_ERROR;
        }
        if (message.contains("设备不存在")) {
            return OpenApiErrorCode.DEVICE_NOT_FOUND;
        }
        if (message.contains("通道不存在")) {
            return OpenApiErrorCode.RESOURCE_NOT_FOUND;
        }
        if (message.contains("离线")) {
            return OpenApiErrorCode.DEVICE_OFFLINE;
        }
        if (message.contains("会话不存在") || message.contains("流不存在")
                || message.contains("非回放会话") || message.contains("流尚未就绪")) {
            return OpenApiErrorCode.STREAM_NOT_ACTIVE;
        }
        if (message.contains("不能为空") || message.contains("不合法")
                || message.contains("不支持") || message.contains("必须晚于")) {
            return OpenApiErrorCode.PARAM_INVALID;
        }
        if (message.contains("回放")) {
            return OpenApiErrorCode.PLAYBACK_FAILED;
        }
        if (message.contains("INVITE") || message.contains("点播超时") || message.contains("点播失败")
                || message.contains("正在请求点播") || message.contains("RTP 收流端口")
                || message.contains("SIP Provider")) {
            return OpenApiErrorCode.SIP_INVITE_FAILED;
        }
        return OpenApiErrorCode.INTERNAL_ERROR;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> handleOther(Exception e, HttpServletRequest request) {
        log.error("[开放API] 系统异常 requestId={}", OpenApiContextHolder.requestId(request), e);
        return ResponseEntity.status(OpenApiErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(OpenApiResult.error(OpenApiErrorCode.INTERNAL_ERROR.getCode(),
                        OpenApiErrorCode.INTERNAL_ERROR.getMessage(),
                        OpenApiContextHolder.requestId(request)));
    }
}
