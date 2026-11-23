package com.solo.infra.ops;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 慢 SQL 统计与拦截器单测（M7）：验证阈值、最近快照上限、WARN 限流与关闭开关。
 */
class SlowSqlStatsTest {

    @Test
    void shouldOnlyCountTotal_whenFasterThanThreshold() {
        SlowSqlStats stats = new SlowSqlStats(1000, 20);

        stats.record("com.solo.infra.mapper.FooMapper.selectList", 3, 1);

        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getSlow()).isZero();
        assertThat(stats.recent()).isEmpty();
    }

    @Test
    void shouldRecordSlowEntry_withPeakAndParamCount() {
        SlowSqlStats stats = new SlowSqlStats(1000, 20);

        stats.record("com.solo.infra.mapper.FooMapper.selectPage", 1200, 2);
        stats.record("com.solo.infra.mapper.FooMapper.selectList", 2500, 0);

        assertThat(stats.getSlow()).isEqualTo(2);
        assertThat(stats.getPeakMs()).isEqualTo(2500);
        List<SlowSqlStats.Entry> recent = stats.recent();
        assertThat(recent).hasSize(2);
        // 最新在前
        assertThat(recent.get(0).statement()).endsWith("selectList");
        assertThat(recent.get(0).costMs()).isEqualTo(2500);
        assertThat(recent.get(1).paramCount()).isEqualTo(2);
    }

    @Test
    void shouldKeepOnlyLatest20Entries() {
        SlowSqlStats stats = new SlowSqlStats(1, 20);

        for (int i = 1; i <= 25; i++) {
            stats.record("stmt-" + i, 10, 0);
        }

        List<SlowSqlStats.Entry> recent = stats.recent();
        assertThat(recent).hasSize(20);
        assertThat(recent.get(0).statement()).isEqualTo("stmt-25");
        assertThat(recent.get(19).statement()).isEqualTo("stmt-6");
    }

    @Test
    void shouldLimitWarnPerMinute_whileStillCountingAll() throws Exception {
        SlowSqlStats stats = new SlowSqlStats(1, 3);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            for (int i = 0; i < 10; i++) {
                stats.record("stmt-" + i, 10, 0);
            }

            assertThat(stats.getSlow()).isEqualTo(10);
            assertThat(appender.list).hasSize(3);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void shouldDisableDetection_whenThresholdNonPositive() {
        SlowSqlStats stats = new SlowSqlStats(0, 20);

        stats.record("com.solo.infra.mapper.FooMapper.selectList", 9999, 0);

        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getSlow()).isZero();
        assertThat(stats.recent()).isEmpty();
    }

    @Test
    void reportShouldStaySilent_whenNoNewSlowSql() throws Exception {
        SlowSqlStats stats = new SlowSqlStats(1, 20);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            stats.report();
            assertThat(appender.list).isEmpty();

            // 慢语句本身打一条 WARN
            stats.record("stmt", 10, 0);
            assertThat(appender.list).hasSize(1);

            // 汇总再补一条 INFO
            stats.report();
            assertThat(appender.list).hasSize(2);

            // 第二次汇总没有新增，不再重复打日志
            stats.report();
            assertThat(appender.list).hasSize(2);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void interceptorShouldRecordExecution_evenWhenFast() throws Throwable {
        SlowSqlStats stats = new SlowSqlStats(1, 20);
        Object result = runThroughInterceptor(stats, 20);

        assertThat(result).isNull();
        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.recent()).singleElement()
                .satisfies(e -> {
                    assertThat(e.statement()).isEqualTo("com.solo.infra.mapper.FooMapper.selectList");
                    assertThat(e.paramCount()).isEqualTo(2);
                });
    }

    @Test
    void interceptorShouldNotRecordSlow_whenExecutionBelowThreshold() throws Throwable {
        SlowSqlStats stats = new SlowSqlStats(1000, 20);
        runThroughInterceptor(stats, 0);

        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getSlow()).isZero();
    }

    /** 用 mock Executor 走一遍拦截器；慢语句靠 threshold=1 加毫秒级延时制造 */
    private static Object runThroughInterceptor(SlowSqlStats stats, long sleepMs) throws Throwable {
        Executor executor = mock(Executor.class);
        if (sleepMs > 0) {
            doAnswer(inv -> {
                Thread.sleep(sleepMs);
                return null;
            }).when(executor).query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        MappedStatement statement = mock(MappedStatement.class);
        when(statement.getId()).thenReturn("com.solo.infra.mapper.FooMapper.selectList");

        Method method = Executor.class.getMethod("query", MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        Invocation invocation = new Invocation(executor, method,
                new Object[]{statement, Map.of("a", 1, "b", 2), RowBounds.DEFAULT, null});
        return new SlowSqlInterceptor(stats).intercept(invocation);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SlowSqlStats.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(SlowSqlStats.class);
        logger.detachAppender(appender);
    }
}
