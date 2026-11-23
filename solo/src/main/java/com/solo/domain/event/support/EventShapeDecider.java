package com.solo.domain.event.support;

/**
 * 降级判定（3.11.7 的规则表），纯函数，便于单测把每条规则钉住。
 *
 * <table>
 *   <tr><th>情况</th><th>结论</th></tr>
 *   <tr><td>无任何变化</td><td>{@link EventShape#NONE}</td></tr>
 *   <tr><td>组织树重建（新增/移动/删除组织）</td><td>{@link EventShape#REFRESH}</td></tr>
 *   <tr><td>删除被闸门跳过（3.8.4）</td><td>{@link EventShape#REFRESH}</td></tr>
 *   <tr><td>有影响行数但明细回读为空</td><td>{@link EventShape#REFRESH}</td></tr>
 *   <tr><td>变化总数 &gt; 阈值</td><td>{@link EventShape#REFRESH}</td></tr>
 *   <tr><td>其余</td><td>{@link EventShape#DETAIL}</td></tr>
 * </table>
 *
 * <p><b>为什么"删除被跳过"就整体降级</b>：跳过意味着本轮明细的语义已经不完整
 * （"没出现在报文里"不代表"消失了"），只发一半明细会让订阅方算出错误的差集。</p>
 */
public final class EventShapeDecider {

    private EventShapeDecider() {
    }

    /**
     * @param changedCount  本轮影响行数（来自真实 UPDATE/INSERT 影响行数之和）
     * @param detailCount   写库后回读到的明细条数
     * @param treeRebuilt   组织树是否重建（有组织新增/移动/删除）
     * @param deleteSkipped 是否存在被 G2 跳过的删除
     * @param detailMax     阈值 {@code event.detail-max}，默认 500
     */
    public static EventShape decide(int changedCount,
                                    int detailCount,
                                    boolean treeRebuilt,
                                    boolean deleteSkipped,
                                    int detailMax) {
        if (changedCount <= 0) {
            // 无变化不推送：否则订阅方每分钟醒一次、每次什么都没拿到
            return EventShape.NONE;
        }
        if (treeRebuilt) {
            return EventShape.REFRESH;
        }
        if (deleteSkipped) {
            return EventShape.REFRESH;
        }
        if (detailCount <= 0) {
            // 有影响行数却回读不到明细：并发删改或回读口径漏了，安全优先
            return EventShape.REFRESH;
        }
        if (changedCount > detailMax) {
            return EventShape.REFRESH;
        }
        return EventShape.DETAIL;
    }
}
