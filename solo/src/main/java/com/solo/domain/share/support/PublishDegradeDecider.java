package com.solo.domain.share.support;

/**
 * 出站超量降级判定（C18 / 6.5）。
 *
 * <p>规则只有一条：<b>单轮待推条数 > {@code publish.detail-max} 时合并为一条 REFRESH</b>，
 * 不做逐条循环。2 万通道的逐条 NOTIFY 会把 SIP 线程池和上级平台一起打穿，
 * 而上一次目录拉取本来就能对齐——降级不是丢数据，是把"增量"换成"让对端重拉"。</p>
 *
 * <p>与入站 {@code EventShapeDecider} 同构，但<b>独立实现</b>：入站看的是"本轮变化数"，
 * 出站看的是"本平台待推队列积压数"，两者口径不同，合并会让阈值互相干扰。</p>
 */
public final class PublishDegradeDecider {

    public enum Shape {
        /** 没有待推事件 */
        NONE,
        /** 逐条推 */
        DETAIL,
        /** 合并成一条 REFRESH */
        REFRESH
    }

    private PublishDegradeDecider() {
    }

    /**
     * @param plannedCount 本轮待推条数（该平台 outbox 里 PENDING/FAILED 的条数）
     * @param detailMax    明细上限；{@code <= 0} 视为不限
     * @param treeRebuilt  虚拟组织树是否重建过（重建即降级：逐条推送会对不上父节点）
     */
    public static Shape decide(int plannedCount, int detailMax, boolean treeRebuilt) {
        if (plannedCount <= 0) {
            return Shape.NONE;
        }
        if (treeRebuilt) {
            return Shape.REFRESH;
        }
        if (detailMax > 0 && plannedCount > detailMax) {
            return Shape.REFRESH;
        }
        return Shape.DETAIL;
    }
}
