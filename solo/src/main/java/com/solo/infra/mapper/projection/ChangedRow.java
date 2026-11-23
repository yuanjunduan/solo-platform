package com.solo.infra.mapper.projection;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本轮被写库触碰的一行资源（3.11.7 的"写库后回读"）。
 *
 * <p>选行口径是 {@code update_time = 本轮 now}：三条集合 SQL（新增/差异更新/消失标记）
 * 都把 {@code update_time} 写成同一个 {@code now}，所以它就是"本轮真正改了哪些行"的
 * 干净快照——比在内存里做 diff 便宜，也不会把"值没变"的行算进来。</p>
 *
 * <p>{@code create_time == now} 就是 ADDED，其余是 UPDATED；{@code status} 落在
 * STALE/REMOVED 就是 REMOVED。</p>
 */
@Data
public class ChangedRow {

    private String code;
    private String name;
    private String orgPath;
    private String status;
    private String onlineStatus;
    private LocalDateTime createTime;
}
