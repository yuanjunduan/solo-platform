package com.solo.domain.event.support;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件流的可调参数（3.11.7 / 6.4）。
 *
 * <p>启动时由 {@code EventSelfCheckReporter} 打一次 INFO，避免"以为调大了其实没生效"。</p>
 */
@Getter
@Component
public class EventSettings {

    /**
     * 明细上限：单轮变化数超过它就降级成 REFRESH_ALL。
     * <p>500 的依据：4.9.3 的"单页 20 行"告诉我们前端一次能消化的是几十条；
     * 500 条已经是一屏刷十几次的量，再多订阅方也只能丢掉重拉。</p>
     */
    @Value("${event.detail-max:500}")
    private int detailMax;

    /** 事件保留天数：超过就由清理任务回收（与 outbox 的 7 天口径一致） */
    @Value("${event.retention-days:7}")
    private int retentionDays;

    /** 单次从库里拉多少条推给一个连接（背压的第一道） */
    @Value("${event.batch-size:200}")
    private int batchSize;

    /** 单连接待发队列上限：满了给 lagged 提示并让客户端重连补拉，绝不阻塞生产者 */
    @Value("${event.queue-size:1000}")
    private int queueSize;

    /** 长轮询等待时间：没事件时挂住这么久再返回空，避免 1 秒一轮的空转 */
    @Value("${event.poll-timeout-ms:25000}")
    private long pollTimeoutMs;

    /**
     * 空闲复查周期：连接挂起后最多睡这么久就醒一次查库。
     * <p>为什么不能只靠进程内信号：事件在事务提交前就已插入，唤醒可能早于提交可见；
     * 多实例时别的节点发出的信号更收不到。2 秒是"迟一点但一定看得到"的兜底。</p>
     */
    @Value("${event.idle-poll-ms:2000}")
    private long idlePollMs;

    /** 单连接存活上限：到点由客户端带 Last-Event-ID 自动重连，顺便腾出实例名额 */
    @Value("${event.stream-timeout-ms:1800000}")
    private long streamTimeoutMs;

    /** 心跳间隔（SSE 注释帧）：没有事件也要让网关知道连接还活着 */
    @Value("${event.heartbeat-ms:15000}")
    private long heartbeatMs;

    /** 单实例并发订阅连接上限：到达后返回 429，避免连接把线程/内存吃光 */
    @Value("${event.max-connections:64}")
    private int maxConnections;

    /** 事件保留期回收开关（与 retentionDays 配套，关掉后靠人工清理） */
    @Value("${event.retention-enabled:true}")
    private boolean retentionEnabled;

    public long retentionMs() {
        return retentionDays * 86_400_000L;
    }
}
