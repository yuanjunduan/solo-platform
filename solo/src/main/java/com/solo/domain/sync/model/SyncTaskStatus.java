package com.solo.domain.sync.model;

/**
 * 一轮同步的状态机（C10 三段会话）。
 *
 * <pre>
 * PENDING ──start──► RUNNING ──首批到达──► SYNCHING ──finish──► SUCCESS
 *    │                 │                    │
 *    └─────────────────┴────────────────────┴──► FAILED / CANCELLED
 * </pre>
 *
 * <p>{@code RUNNING} 与 {@code SYNCHING} 都算活跃态：前者已建会话但一批都没到，后者正在收批。
 * 两者都要参与同平台互斥（{@code sync_task.active_platform_id} 唯一约束）。</p>
 */
public final class SyncTaskStatus {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SYNCHING = "SYNCHING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 与 DDL 里生成列的 CASE 条件必须完全一致，改一处必须改两处 */
    public static final String[] ACTIVE = {PENDING, RUNNING, SYNCHING};

    private SyncTaskStatus() {
    }

    public static boolean isActive(String status) {
        return PENDING.equals(status) || RUNNING.equals(status) || SYNCHING.equals(status);
    }
}
