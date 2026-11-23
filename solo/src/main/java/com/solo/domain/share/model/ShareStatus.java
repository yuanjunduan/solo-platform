package com.solo.domain.share.model;

/**
 * 共享项 / 推送队列的状态。
 *
 * <p>前五个是<b>共享项</b>的界面状态（3.9.6），后五个是<b>outbox</b> 的投递状态。
 * 两者名字重叠（PENDING/SENT/FAILED/DEAD）但语义不同：
 * 共享项看的是"这条共享最近一次推送的结果"，outbox 看的是"这条事件投出去没有"。
 * 共享项的状态由 outbox 的结果回写，不在同一次事务里推断。</p>
 */
public final class ShareStatus {

    // ---- 共享项（out_share.status，3.9.6）----
    public static final String PENDING = "PENDING";
    public static final String SYNCED = "SYNCED";
    public static final String FAILED = "FAILED";
    public static final String DEAD = "DEAD";
    public static final String REVOKING = "REVOKING";

    // ---- 推送队列（outbox.status）----
    public static final String OUTBOX_PENDING = "PENDING";
    public static final String OUTBOX_SENT = "SENT";
    public static final String OUTBOX_FAILED = "FAILED";
    public static final String OUTBOX_DEAD = "DEAD";
    public static final String OUTBOX_PAUSED = "PAUSED";

    private ShareStatus() {
    }
}
