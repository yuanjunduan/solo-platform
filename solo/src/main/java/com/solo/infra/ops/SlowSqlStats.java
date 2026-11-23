package com.solo.infra.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢 SQL 统计（M7，6.7「慢查询治理」）。
 *
 * <p>只记录语句 id（{@code Mapper.方法}）与耗时，不拼 SQL 原文与参数值：
 * 慢查询日志最容易泄露凭证与报文，而定位问题只需要知道是哪条语句。</p>
 *
 * <p>日志限流：慢语句成批出现时（例如某条统计 SQL 被循环调用）每行都打会把
 * 日志文件滚爆，所以每分钟最多 {@code perf.slow-sql-warn-per-minute} 条 WARN；
 * 超出部分只计数，由 5 分钟一次的汇总补上整体视图。</p>
 */
@Slf4j
@Component
public class SlowSqlStats {

    private static final int RECENT_MAX = 20;

    private final long thresholdMs;
    private final int warnPerMinute;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();
    private final AtomicLong peakMs = new AtomicLong();
    private final AtomicLong warnWindowMinute = new AtomicLong(-1);
    private final AtomicInteger warnInWindow = new AtomicInteger();
    private final AtomicLong lastReportedSlow = new AtomicLong();
    private final Deque<Entry> recent = new ConcurrentLinkedDeque<>();

    public SlowSqlStats(@Value("${perf.slow-sql-ms:1000}") long thresholdMs,
                        @Value("${perf.slow-sql-warn-per-minute:20}") int warnPerMinute) {
        this.thresholdMs = thresholdMs;
        this.warnPerMinute = Math.max(1, warnPerMinute);
    }

    /**
     * 记录一次 SQL 执行。无论快慢都会累加总数，慢于阈值才进入慢集合。
     *
     * @param statementId MappedStatement id，如 {@code com.solo.infra.mapper.XxxMapper.selectList}
     * @param costMs      执行耗时（毫秒）
     * @param paramCount  参数个数（Map/Collection 的元素数，其余按 1 计）
     */
    public void record(String statementId, long costMs, int paramCount) {
        total.incrementAndGet();
        if (thresholdMs <= 0 || costMs < thresholdMs) {
            return;
        }
        slow.incrementAndGet();
        peakMs.accumulateAndGet(costMs, Math::max);
        Entry entry = new Entry(LocalDateTime.now(), statementId, costMs, paramCount);
        recent.addFirst(entry);
        while (recent.size() > RECENT_MAX) {
            recent.pollLast();
        }
        if (allowWarn()) {
            log.warn("[慢SQL] {} 耗时 {}ms（阈值 {}ms）参数数={}",
                    statementId, costMs, thresholdMs, paramCount);
        }
    }

    /** 每 5 分钟汇总一次增量：单条 WARN 看不出"慢语句是不是在集中出现" */
    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void report() {
        long current = slow.get();
        long delta = current - lastReportedSlow.getAndSet(current);
        if (delta > 0) {
            log.info("[慢SQL] 近 5 分钟新增 {} 条（累计 {} 条，峰值 {}ms，阈值 {}ms）",
                    delta, current, peakMs.get(), thresholdMs);
        }
    }

    private boolean allowWarn() {
        long minute = System.currentTimeMillis() / 60_000;
        long current = warnWindowMinute.get();
        if (current != minute && warnWindowMinute.compareAndSet(current, minute)) {
            warnInWindow.set(0);
        }
        return warnInWindow.incrementAndGet() <= warnPerMinute;
    }

    public long getThresholdMs() {
        return thresholdMs;
    }

    public long getTotal() {
        return total.get();
    }

    public long getSlow() {
        return slow.get();
    }

    public long getPeakMs() {
        return peakMs.get();
    }

    /** 最近的慢语句快照（最多 20 条，最新在前），供运行自检页展示 */
    public List<Entry> recent() {
        return new ArrayList<>(recent);
    }

    /** 一条慢语句（不含 SQL 原文与参数值） */
    public record Entry(LocalDateTime time, String statement, long costMs, int paramCount) {
    }
}
