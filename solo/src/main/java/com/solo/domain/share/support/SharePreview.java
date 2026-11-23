package com.solo.domain.share.support;

import java.util.List;

/**
 * 编排预览结果（4.5.3 提交前的"已共享 / 新增 / 需重新编号 / 冲突"）。
 *
 * <p><b>为什么不做成"直接提交再看结果"</b>：暴露码一经分配不复用，误点一次就白烧一堆序号；
 * 而防环拒绝只在提交时才知道，用户会以为共享成功了。预览把这两件事提前到点击之前。</p>
 *
 * <p>{@code renumber} 只统计<b>本次需要新分配编码</b>的入站通道：已共享或从墓碑恢复的行
 * 沿用原码，不算重新编号。</p>
 */
public record SharePreview(int total, int shared, int added, int restored,
                           int excluded, int renumber, List<Conflict> conflicts) {

    public static SharePreview empty() {
        return new SharePreview(0, 0, 0, 0, 0, 0, List.of());
    }

    /**
     * 一条会被拒绝的资源（防环 / 跳数 / 自身域）。
     *
     * @param reason 给用户看的完整文案，直接来自 {@code ShareGuard.message}
     */
    public record Conflict(Long refId, String code, String name, String reason) {
    }
}
