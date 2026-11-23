package com.solo.domain.sync.model;

/**
 * 单条变更类型，落 {@code sync_record.change_type}（3.8.6）。
 *
 * <p>{@link #RESTORE} 单独一类：{@code REMOVED} 的资源在上游重新出现时不是"新增"，
 * 旧行会复活，计数与告警口径都不一样（C12 恢复路径）。</p>
 */
public final class SyncChangeType {

    public static final String ADD = "ADD";
    public static final String UPDATE = "UPDATE";
    public static final String STALE = "STALE";
    public static final String REMOVE = "REMOVE";
    public static final String RESTORE = "RESTORE";

    private SyncChangeType() {
    }
}
