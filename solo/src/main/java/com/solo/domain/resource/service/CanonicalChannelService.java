package com.solo.domain.resource.service;

import com.solo.domain.resource.model.ResChannel;

import java.util.List;

/**
 * 同源组（{@code canonical_id}）维护与选路（3.10.3）。
 *
 * <p><b>只做逻辑关联，不做物理合并</b>：同一台物理通道经两条路径进来时保留两行，
 * 每行仍带着自己来源的暴露码、共享关系与可用性；关联只回答"这两行看起来是一台"。</p>
 *
 * <p>三条硬规则（来自文档，代码里逐条落实）：</p>
 * <ol>
 *   <li>组主选取：{@code source_platform_id=0}（本地）优先 → 创建时间最早 → id 最小；</li>
 *   <li>关联单向（子指向组主），解除只把 {@code canonical_id} 置 0；</li>
 *   <li>关联/解除都写审计，<b>系统绝不自动解除</b>（自动关联只做"补空"，不覆盖已有指针）。</li>
 * </ol>
 */
public interface CanonicalChannelService {

    /** 单行所在同源组；未关联时成员只有它自己 */
    SourceGroup group(Long channelId);

    /**
     * 把若干条通道关联成一个新的同源组。
     *
     * <p>只允许"新建组"：任一成员已属于别的组（或本身是别组组主）都会被拒绝，
     * 必须先人工解除——一次操作拆散别人的组属于不可逆的误操作。</p>
     */
    SourceGroup bind(List<Long> channelIds);

    /**
     * 解除单行关联。解除的是组主且组内还有成员时，自动从剩余成员里提升新组主，
     * 不留"子记录指向一个已经不是组主的行"的悬空指针。
     */
    void unbind(Long channelId);

    /** 播放候选（按 用户点选 → 在线 → 跳数少 → id 小 排好序，已剔除 REMOVED） */
    List<ResChannel> playCandidates(Long channelId);

    /**
     * 一次入站同步收尾后的 L1 自动关联（3.10.3：全局身份完全相同 → 100% 置信，自动关联）。
     *
     * <p>前提是两边都带了 {@code (origin_platform_id, origin_code)}。只补"全组都还没有指针"的组，
     * 已有指针（含人工组）一律不动——系统不做自动解除。</p>
     *
     * @return 本次新关联的通道行数
     */
    int afterMerge(Long sourcePlatformId);

    /**
     * 双向一致性核对（只读）：统计"指针说不通"的存活行。
     *
     * <p>核对只报不改。文档明确"系统不允许自动解除"，所以孤儿/两级指针这类历史脏数据
     * 也只能由人工逐条解除或重绑——这里的作用是让问题在运维页面上先可见。</p>
     */
    Integrity checkIntegrity();

    /** 同源组快照：组主 + 全部成员（按 id 升序） */
    record SourceGroup(Long leaderId, List<ResChannel> members) {

        /** 是否真的成组（成员 ≥ 2） */
        public boolean linked() {
            return members != null && members.size() > 1;
        }
    }

    /** 核对结果：四类异常 + 总数 */
    record Integrity(long selfPointer, long orphan, long twoLevel, long leaderRemoved) {

        public long total() {
            return selfPointer + orphan + twoLevel + leaderRemoved;
        }

        /** 双向关系自洽 */
        public boolean clean() {
            return total() == 0;
        }
    }
}
