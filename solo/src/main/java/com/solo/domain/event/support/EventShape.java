package com.solo.domain.event.support;

/**
 * 一次变更该发什么（3.11.7 的两种形态 + "不发"）。
 *
 * <p>把"发不发、发哪种"做成枚举而不是布尔，是为了让调用点只有一处 switch，
 * 也让"无变化不推送"这条规则在类型上就不可绕过——避免有人写成
 * {@code if (big) refresh else detail}，于是空变化也发了一条 detail。</p>
 */
public enum EventShape {

    /** 本轮没有任何变化：一条都不发，避免"心跳式刷新" */
    NONE,

    /** 逐条明细：变化量小，订阅方按 code 增量更新 */
    DETAIL,

    /** 只说"已刷新"：变化量大 / 树重建 / 删除被跳过 / 明细回读为空 */
    REFRESH
}
