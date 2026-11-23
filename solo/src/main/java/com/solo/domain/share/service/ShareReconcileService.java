package com.solo.domain.share.service;

/**
 * 出站对账（6.5 验收 ⑤"对账能纠正偏差"）。
 *
 * <p>对账只补差异，不做全量重推：</p>
 * <ol>
 *   <li>先重算派生（规则增删 / 组织移动 / 通道新入造成的漏派生）；</li>
 *   <li>可见但"没同步且没有在途事件"的通道行 → 补 ADD（超过明细上限就换成一条 REFRESH）；</li>
 *   <li>"撤销中但没有在途 DELETE"的行 → 聚合一条批量 DELETE。</li>
 * </ol>
 *
 * <p><b>只在没有在途事件时补</b>：队列里已经躺着这条资源的事件，再补一条会让上级收到重复 ADD，
 * 典型症状是目录里出现两个同码节点，比漏推更难查。</p>
 */
public interface ShareReconcileService {

    /**
     * 对账一个平台。
     *
     * @return 补了什么；没有偏差时全部为 0（不写 {@code publish_round}，对账本身不是推送轮次）
     */
    ReconcileResult reconcile(Long platformId);

    record ReconcileResult(int derivedAdded, int derivedRestored, int derivedRemoved,
                          int readded, int redeleted, boolean refreshed) {

        public static ReconcileResult empty() {
            return new ReconcileResult(0, 0, 0, 0, 0, false);
        }

        public boolean changed() {
            return derivedAdded + derivedRestored + derivedRemoved + readded + redeleted > 0 || refreshed;
        }
    }
}
