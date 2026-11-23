package com.solo.domain.sync.model;

import java.util.List;

/**
 * 各资源表的可变列清单（集合 SQL 的唯一列来源，C9）。
 *
 * <p>什么算"可变列"：由上游目录内容决定、每轮同步都要对齐的字段。
 * 什么<b>不</b>算：</p>
 * <ul>
 *   <li>{@code org_id} / {@code org_path} / {@code device_id} —— 由 4 步回填另写，不来自目录原始字段；</li>
 *   <li>{@code status} / {@code removed_at} —— 由状态机写（STALE/OFFLINE/REMOVED/恢复），不是字段覆盖；</li>
 *   <li>{@code local_name} / {@code canonical_id} —— 本地侧数据，同步永不覆盖；</li>
 *   <li>{@code code} / 来源三元组 —— 业务键，不可变。</li>
 * </ul>
 *
 * <p>往这里加一个字段 = 同时改 SET 与 WHERE，这是刻意设计的：加列时被迫想一遍
 * "差异判断要不要带上它"，而不是只改 SET 后留下永远命中的 WHERE。</p>
 */
public final class SyncColumns {

    /** 设备：目录里能带来变化的字段 */
    public static final List<MutableColumn> DEVICE = List.of(
            MutableColumn.of("name"),
            MutableColumn.of("device_ip"),
            MutableColumn.of("vendor"),
            MutableColumn.of("model"),
            MutableColumn.of("origin_platform_id"),
            MutableColumn.of("origin_code"),
            MutableColumn.of("origin_chain"),
            MutableColumn.of("hop_count")
    );

    /**
     * 通道：注意 {@code online_status} 也在这里。
     * <p>上游目录项状态（ON/OFF）是目录内容，变了就该对齐；它与 {@code status}
     * 是两个东西——后者是"这条通道还在不在"（ACTIVE/STALE/REMOVED）。</p>
     */
    public static final List<MutableColumn> CHANNEL = List.of(
            MutableColumn.of("name"),
            MutableColumn.of("stream_type"),
            MutableColumn.of("online_status"),
            MutableColumn.of("capability"),
            MutableColumn.of("media_hint"),
            MutableColumn.of("origin_platform_id"),
            MutableColumn.of("origin_code"),
            MutableColumn.of("origin_chain"),
            MutableColumn.of("hop_count")
    );

    /**
     * 组织：目录里能带来变化的字段。
     * <p>{@code parent_code} / {@code parent_id} / {@code path} / {@code level} 都是结构字段，
     * 由建树与树修复单独处理，不进差异更新——它们变一次要连带整棵子树，不是"覆盖一个列"。</p>
     */
    public static final List<MutableColumn> ORG = List.of(
            MutableColumn.of("name"),
            MutableColumn.of("origin_chain"),
            MutableColumn.of("hop_count")
    );

    /** 分组：215 业务分组 / 216 虚拟组织 */
    public static final List<MutableColumn> GROUP = List.of(
            MutableColumn.of("name"),
            MutableColumn.of("group_type"),
            MutableColumn.of("origin_chain"),
            MutableColumn.of("hop_count")
    );

    private SyncColumns() {
    }
}
