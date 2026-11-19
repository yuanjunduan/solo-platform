package com.solo.common.utils;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * traceId 上下文传播。
 * <p>Web 请求由 TraceIdFilter 把 X-Trace-Id 写入 MDC；跨线程时（@Async、原生线程池、
 * CompletableFuture、@Scheduled）用这里的包装方法把 MDC 带过去。</p>
 * <p>提交任务时若当前线程已有 traceId 则沿用，保证整条链路日志可串；没有时
 * （定时任务、SIP 监听线程等）生成 bg- 前缀的新 traceId，便于和前端请求区分。</p>
 */
public final class TraceContext {

    /** MDC key，与 logging.pattern 里的 %X{traceId} 对应 */
    public static final String MDC_TRACE_ID = "traceId";

    /** 无父 traceId 时生成的前缀：background */
    private static final String BACKGROUND_PREFIX = "bg-";

    private TraceContext() {
    }

    /** 当前线程的 traceId，可能为 null */
    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    /** 生成 32 位十六进制 traceId，与前端 newTraceId 格式一致 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 包装 Runnable：提交线程捕获 MDC，执行线程恢复后运行，结束后还原执行线程原有 MDC。
     * 适用于没有 TaskDecorator 机制的 Executor（原生 ThreadPoolExecutor 等）。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> parent = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.setContextMap(contextOf(parent));
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /** 包装 Supplier，语义同 {@link #wrap(Runnable)} */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        Map<String, String> parent = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.setContextMap(contextOf(parent));
            try {
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 以提交线程的上下文为基础补齐 traceId：
     * 有父 traceId 则沿用（异步任务仍属同一条链路）；没有则<b>本次执行</b>生成一个。
     * <p>定时任务的 Runnable 由调度器包装一次后反复复用，traceId 必须在运行期生成，
     * 否则每次调度都会共用同一个值，无法区分是哪一次执行。</p>
     */
    private static Map<String, String> contextOf(Map<String, String> parent) {
        Map<String, String> context = parent == null ? new HashMap<>() : new HashMap<>(parent);
        String traceId = context.get(MDC_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            context.put(MDC_TRACE_ID, BACKGROUND_PREFIX + shortId());
        }
        return context;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static void restore(Map<String, String> previous) {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
