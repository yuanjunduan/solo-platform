package com.solo.domain.share.support;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 出站共享的可调参数（6.5）。
 *
 * <p>与入站 {@code EventSettings} 分开放：两套阈值含义不同，
 * 合在一个类里迟早有人把"事件明细上限"当"推送明细上限"去调。</p>
 *
 * <p>启动时由自检打印一次，避免"以为调大了其实没生效"。</p>
 */
@Getter
@Component
public class ShareSettings {

    /**
     * 明细上限：单轮待推条数超过它就合并成一条 REFRESH（C18）。
     * <p>500 与入站同口径：上一级平台拉一次目录的成本远低于收 500 条独立 NOTIFY，
     * 而 500 条之后的逐条推送基本必然超时重来。</p>
     */
    @Value("${publish.detail-max:500}")
    private int detailMax;

    /** 单条事件的投递重试上限，超过置 DEAD（不再自动重试，等人工） */
    @Value("${publish.retry-max:5}")
    private int retryMax;

    /**
     * 失败退避基数（毫秒）：第 n 次重试等 {@code base * 2^(n-1)}，封顶 10 分钟。
     * <p>指数退避而不是固定间隔：上级平台离线通常不是"1 秒后就恢复"，
     * 固定 30 秒重试 5 次＝两分半就把机会用光。</p>
     */
    @Value("${publish.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    /** 单次从队列取多少条投递（一个平台分片一页） */
    @Value("${publish.batch-size:100}")
    private int batchSize;

    /**
     * 一轮调度最多处理几个平台。
     * <p>按平台分片而不是把积压拉平：一个上级长时间离线时，它的万条积压不能拖住其它上级的实时推送。</p>
     */
    @Value("${publish.platform-batch:20}")
    private int platformBatch;

    /** 最大级联跳数（C20 第三道），与 {@code ShareGuard.DEFAULT_MAX_HOP} 保持一致 */
    @Value("${publish.max-hop:3}")
    private int maxHop;

    /** 调度器扫一轮的间隔（毫秒）；没有待推事件时几乎零成本 */
    @Value("${publish.interval-ms:30000}")
    private long intervalMs;

    /**
     * 平台级互斥的租约时长（C24）。
     * <p>放 DB 行锁/Redis 而不是进程内变量：多实例部署时进程内锁挡不住另一个节点
     * 同时给同一个上级推送，收端会看到乱序与重复。</p>
     */
    @Value("${publish.platform-lock-ttl-ms:300000}")
    private long platformLockTtlMs;

    /** 对账开关：只补差异不做全量重推，关掉后只能靠人工点"重新推送" */
    @Value("${publish.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    /** 对账周期（毫秒），默认 1 小时 */
    @Value("${publish.reconcile-interval-ms:3600000}")
    private long reconcileIntervalMs;

    /** 已投递事件保留天数（与事件流同口径）；回收时保留平台内最大 seq 那一行，避免号段回退 */
    @Value("${publish.retention-days:7}")
    private int retentionDays;

    /** 退避等待：第 {@code retryCount} 次失败后应等的毫秒数，封顶 10 分钟 */
    public long backoffMs(int retryCount) {
        int n = Math.max(retryCount, 1);
        long base = Math.max(retryBackoffMs, 1000L);
        int shift = Math.min(n - 1, 10);
        long delay = base << shift;
        return Math.min(delay, 600_000L);
    }
}
