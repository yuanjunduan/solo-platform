package com.solo.domain.sync.support;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 同步链路的可调参数（3.8.2 的"默认 75s 可配"那一类）。
 *
 * <p>集中一处是为了让"超时""冷却""闸门""清理"几个数在一屏里能对照着看：
 * 冷却窗口 = {@code stallTimeoutMs + cooldownExtraMs}，改一个必须同时想另一个。</p>
 *
 * <p>启动时由 {@code SyncSelfCheckReporter} 把实际生效值打一次 INFO（3.13.6）——
 * 排查"以为开了其实没开"不用再猜。</p>
 */
@Getter
@Component
public class SyncSettings {

    /** 连续多久没有新批次即判超时（按"无进展"，不按总时长，C10） */
    @Value("${sync.stall-timeout-ms:75000}")
    private long stallTimeoutMs;

    /** 失败后冷却窗口的额外秒数：补齐"本端已空闲、对端仍在等它自己的超时"的空窗 */
    @Value("${sync.cooldown-extra-ms:10000}")
    private long cooldownExtraMs;

    /** G2 的本地规模下限：本地行数不超过它就不做比例保护（小平台一次全删是合理的） */
    @Value("${sync.g2.min-local-rows:1000}")
    private long g2MinLocalRows;

    /** 暂存批量写入的一页行数（PG 参数上限 65535，22 列 × 500 行仍很安全） */
    @Value("${sync.staging.page-size:500}")
    private int stagingPageSize;

    /** 建树循环的轮数上限：正常层级个位数，超了说明目录里有环 */
    @Value("${sync.tree.max-depth:16}")
    private int treeMaxDepth;

    /**
     * 删除动作级开关（3.8.2）：{@code mark}=只标 STALE，{@code real}=标 REMOVED（软删）。
     *
     * <p>默认 {@code mark} 而不是文档写的 {@code real}：M4 之前还没有 out_share / outbox，
     * 真删无法生成撤销共享的 DELETE 事件；等 M4 的级联副作用落地后再切 {@code real}。
     * 这不是换引擎，只影响"消失"这一个动作，切开后观察一轮即可回退。</p>
     */
    @Value("${sync.delete.mode:mark}")
    private String deleteMode;

    /** 失败轮现场保留时长（3.13.4）：超期的 staging 由周期清理回收 */
    @Value("${sync.residue.retention-hours:24}")
    private long residueRetentionHours;

    /**
     * 启动后是否清一次上次运行遗留（3.13.4）。
     *
     * <p>默认开启：单实例部署时，启动那一刻库里不可能有"真在跑"的轮次，
     * 全是被 kill / 断电留下的暂存。多实例滚动重启时请关掉——否则会把对端
     * 正在收的批次清空（后果是那边 G1 判空批整轮失败，数据不会丢但白跑一轮）。</p>
     */
    @Value("${sync.residue.startup-clean:true}")
    private boolean residueStartupClean;

    /** 组织缺失设备延迟重试的防抖秒数（3.8.2） */
    @Value("${sync.org-retry.debounce-seconds:30}")
    private long orgRetryDebounceSeconds;

    /** 组织缺失设备延迟重试的强制上限（分钟）：一直等不到新一轮也要跑一次 */
    @Value("${sync.org-retry.max-wait-minutes:10}")
    private long orgRetryMaxWaitMinutes;

    /** 原始报文快照开关（3.13.2，C 档：默认关闭） */
    @Value("${sync.snapshot.enabled:false}")
    private boolean snapshotEnabled;

    /** 快照保留轮数（含当前轮），默认 2 */
    @Value("${sync.snapshot.keep-rounds:2}")
    private int snapshotKeepRounds;

    /** 快照异步队列容量：满了丢弃并告警，绝不阻塞同步（3.13.3） */
    @Value("${sync.snapshot.queue-size:1024}")
    private int snapshotQueueSize;

    public long cooldownMs() {
        return stallTimeoutMs + cooldownExtraMs;
    }

    /** {@code real} 才会把消失行置 REMOVED；其余值一律按 mark 处理 */
    public boolean realDelete() {
        return "real".equalsIgnoreCase(deleteMode == null ? "" : deleteMode.trim());
    }

    public long residueRetentionMs() {
        return residueRetentionHours * 3_600_000L;
    }
}
