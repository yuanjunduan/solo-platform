package com.solo.infra.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 只读收口第三层：MyBatis 持久层拦截（3.6.5）。
 *
 * <p>拦 {@link Executor} 的 {@code update}，因为 INSERT / UPDATE / DELETE 都从这里出去——
 * 拦 Mapper 方法名挂不住自定义 SQL，拦 JDBC 层又拿不到 statement id（日志里看不出是哪条映射）。</p>
 *
 * <p><b>三种模式</b>（{@code resource.write-guard.mode}）：</p>
 * <ul>
 *   <li>{@code off} —— 全关；</li>
 *   <li>{@code warn}（默认）—— 打告警并累计计数，业务照常。放默认值是因为现有 M1 的
 *       硬删除接口还在用，直接抛错会让"删除组织"这类按钮立刻 500；</li>
 *   <li>{@code strict} —— 直接抛异常。开发/CI 环境用这个，让越权写法在测试里就炸掉。</li>
 * </ul>
 *
 * <p>告警限流：同一个 statement 每累计 100 次才再打一条，避免批量任务把日志刷满
 * ——但计数不会被吞，可以据此判断"这条路径到底有多热"。</p>
 */
@Slf4j
@Intercepts(@Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class}))
public class ResourceWriteGuardInterceptor implements Interceptor {

    /** 每个 statement 打一次日志的倍数 */
    private static final long LOG_EVERY = 100;

    private final String mode;
    private final AtomicLong violations = new AtomicLong();

    public ResourceWriteGuardInterceptor(String mode) {
        this.mode = mode == null ? "warn" : mode.trim().toLowerCase();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if ("off".equals(mode)) {
            return invocation.proceed();
        }
        Object[] args = invocation.getArgs();
        if (args.length < 1 || !(args[0] instanceof MappedStatement statement)) {
            return invocation.proceed();
        }
        BoundSql boundSql = statement.getBoundSql(args.length > 1 ? args[1] : null);
        String violation = ResourceWriteInspector.inspect(statement.getId(), boundSql.getSql());
        if (violation == null) {
            return invocation.proceed();
        }

        long total = violations.incrementAndGet();
        if ("strict".equals(mode)) {
            throw new IllegalStateException("[资源只读守卫] " + violation);
        }
        // 首次必打，之后每 100 次一条：既不静默，也不把批量任务刷成日志海
        if (total == 1 || total % LOG_EVERY == 0) {
            log.warn("[资源只读守卫] {}（累计 {} 次）", violation, total);
        }
        return invocation.proceed();
    }

    /** 供自检/运维页面读：进程启动以来被拦下的可疑写次数 */
    public long violationCount() {
        return violations.get();
    }

    public String mode() {
        return mode;
    }
}
