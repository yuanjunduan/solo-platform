package com.solo.infra.mapper.projection;

import lombok.Data;

/**
 * 同源组一致性核对的计数投影（M5-S5）。
 *
 * <p>四个数都是"存活行"里的异常数；全为 0 表示 {@code canonical_id} 的双向关系自洽。
 * 核对只报不改——修复必须走人工解除/重绑（3.10.3 不允许系统自动解除）。</p>
 */
@Data
public class CanonicalAnomaly {

    /** {@code canonical_id = id}：自指，语义上应写 0 */
    private Long selfPointer;

    /** 指向不存在的行：孤儿指针 */
    private Long orphan;

    /** 组主自己还有 {@code canonical_id}：两级指针，子记录找不到最终组主 */
    private Long twoLevel;

    /** 组主已 REMOVED：成员还在但组主不再可播 */
    private Long leaderRemoved;

    public long selfPointerCount() {
        return selfPointer == null ? 0L : selfPointer;
    }

    public long orphanCount() {
        return orphan == null ? 0L : orphan;
    }

    public long twoLevelCount() {
        return twoLevel == null ? 0L : twoLevel;
    }

    public long leaderRemovedCount() {
        return leaderRemoved == null ? 0L : leaderRemoved;
    }

    public long total() {
        return selfPointerCount() + orphanCount() + twoLevelCount() + leaderRemovedCount();
    }
}
