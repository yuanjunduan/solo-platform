package com.solo.config;

import com.solo.common.utils.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪：取请求头中的 traceId 写入 MDC，日志格式通过 %X{traceId} 输出。
 * <p>用 Filter 而非 HandlerInterceptor：鉴权失败、404/405（未匹配到 handler）、
 * /error 转发都不会经过拦截器，只有 Filter 能覆盖请求全生命周期。</p>
 * <p>在 try/finally 里清理 MDC，避免 Tomcat 线程复用导致 traceId 串到下一个请求。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 与前端 solo-ui/src/utils/trace.ts 的 TRACE_ID_HEADER 保持一致 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * 开放 API 的请求号头（与 OpenApiConstants.HEADER_REQUEST_ID 一致），
     * 作为 traceId 的第二优先级来源：传了就用它，和审计日志 request_id 对齐。
     */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** MDC key，与日志 pattern 中的 %X{traceId} 对应；常量统一在 TraceContext 定义 */
    public static final String MDC_TRACE_ID = TraceContext.MDC_TRACE_ID;

    /** 只接受安全字符，防止外部传入换行等内容污染日志（日志注入） */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        // 同时放进 request attribute，业务/切面需要时可直接取
        request.setAttribute(MDC_TRACE_ID, traceId);
        // 回写响应头：前端 5xx 提示里会带上，用户报障即可定位
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /**
     * traceId 优先级：X-Trace-Id → X-Request-Id（开放 API）→ 自动生成。
     * 只接受白名单字符，缺失或不合法（含非法字符/长度越界）时重新生成。
     */
    private String resolveTraceId(HttpServletRequest request) {
        String traceId = validOrNull(request.getHeader(HEADER_TRACE_ID));
        if (traceId != null) {
            return traceId;
        }
        traceId = validOrNull(request.getHeader(HEADER_REQUEST_ID));
        if (traceId != null) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String validOrNull(String value) {
        return value != null && VALID_TRACE_ID.matcher(value).matches() ? value : null;
    }

    /**
     * 异步派发（DeferredResult / SSE）也补一次 MDC，否则异步阶段日志没有 traceId。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
