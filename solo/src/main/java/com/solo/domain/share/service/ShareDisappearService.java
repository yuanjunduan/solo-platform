package com.solo.domain.share.service;

/**
 * 资源消失的共享联动（C12 / 3.8.4）。
 *
 * <p>"消失"与"用户撤销"是同一个动作的两个触发源，共用一套语义：
 * <b>不删行、只置 {@code ref_alive=0}，生成一条 DELETE 事件</b>，暴露码留到投递成功才置墓碑
 * （3.9.5：推送成功后才允许清理映射）。</p>
 *
 * <p>本服务只负责"共享侧"的三件事，资源的 {@code status=REMOVED} 由同步合并先落库：</p>
 * <ol>
 *   <li>把受影响通道行置死（{@code REVOKING} + {@code operator=SYSTEM_REMOVED}）；</li>
 *   <li><b>按平台聚合</b>写一条批量 DELETE（C12 第 3 步：不按通道发散），
 *       条目顺序"通道 → 分组（深 → 浅）"；</li>
 *   <li>规则行失效后交给派生重算清理其派生物；上游重现时反向恢复。</li>
 * </ol>
 *
 * <p><b>{@code Propagation.MANDATORY} 是刻意的</b>：C12 要求四步同事务。
 * 若在事务外调用，Spring 直接抛错，而不是留下"资源删了、上级还看得见"的窗口。</p>
 */
public interface ShareDisappearService {

    /**
     * 系统因资源消失而做的撤销，用这个操作人打标。
     * <p>恢复路径只复活这类行：用户主动撤销的共享不能被上游重现自动复活。</p>
     */
    String OPERATOR_SYSTEM_REMOVED = "SYSTEM_REMOVED";

    /**
     * 一轮同步合并收尾后调用（必须在 {@code res_*} 消失标记之后、同一事务内）。
     *
     * @param sourcePlatformId 本轮同步的来源平台（{@code platform.id}），只影响它自己推来的资源
     */
    DisappearResult afterMerge(Long sourcePlatformId);

    /**
     * 联动结果（进同步日志，不进轮次台账：出站事实另有 {@code publish_round}，见 C17）。
     *
     * @param brokenChannels  本轮因资源消失而置死的通道行
     * @param deadRules       本轮失效的子树规则行
     * @param removedGroups   本轮从虚拟组织树里摘掉的分组节点
     * @param restoredChannels 上游重现后恢复的通道行
     * @param restoredRules   上游重现后恢复的规则行
     */
    record DisappearResult(int brokenChannels, int deadRules, int removedGroups,
                           int restoredChannels, int restoredRules) {

        public static DisappearResult empty() {
            return new DisappearResult(0, 0, 0, 0, 0);
        }

        /** 有没有任何联动发生（没有就不必打日志） */
        public boolean changed() {
            return brokenChannels + deadRules + removedGroups + restoredChannels + restoredRules > 0;
        }
    }
}
