package com.solo.infra.ops;

import lombok.RequiredArgsConstructor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Collection;
import java.util.Map;

/**
 * 慢 SQL 探测（M7，6.7「慢查询治理」）：在 Executor 层计时，覆盖 BaseMapper 与 XML 语句。
 *
 * <p>只拦 4 参 {@code query} 与 {@code update}：6 参 {@code query} 会被 4 参内部调用，
 * 两边都拦会把同一次执行记两遍。</p>
 *
 * <p>异常也记录耗时：慢查询与超时往往同时出现，失败的那次同样有诊断价值。</p>
 */
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private final SlowSqlStats stats;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            Object[] args = invocation.getArgs();
            MappedStatement statement = args[0] instanceof MappedStatement ms ? ms : null;
            String id = statement == null ? "unknown" : statement.getId();
            stats.record(id, costMs, paramCount(args.length > 1 ? args[1] : null));
        }
    }

    /** 只数个数，不展开参数：展开会触发大对象 toString，反而拖慢主链路 */
    private static int paramCount(Object parameter) {
        if (parameter == null) {
            return 0;
        }
        if (parameter instanceof Map<?, ?> map) {
            return map.size();
        }
        if (parameter instanceof Collection<?> collection) {
            return collection.size();
        }
        if (parameter.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(parameter);
        }
        return 1;
    }
}
