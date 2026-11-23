package com.solo.domain.share.support;

import com.solo.domain.share.model.ShareStatus;

import java.util.Collection;

/**
 * 共享状态汇总（3.9.6 的"平台汇总取最差"）。
 *
 * <p>一个上级平台下有多条共享项，页面上只显示一个汇总状态。规则是<b>取最差</b>：
 * 只要有一条 DEAD，这个平台就是 DEAD——把 9 条 SYNCED 和 1 条 DEAD 显示成"正常"
 * 会让用户在毫无预警的情况下丢掉一个通道。</p>
 *
 * <p>优先级：{@code DEAD > FAILED > PENDING > REVOKING > SYNCED}；
 * 出现未知值按 PENDING 处理（未知 ≠ 成功，宁可让用户点进去看一眼）。</p>
 */
public final class ShareStatusMerge {

    private ShareStatusMerge() {
    }

    /** 严重度：数字越大越严重；未知值按 PENDING 的档位 */
    public static int rank(String status) {
        if (status == null) {
            return 3;
        }
        return switch (status) {
            case ShareStatus.DEAD -> 5;
            case ShareStatus.FAILED -> 4;
            case ShareStatus.REVOKING -> 2;
            case ShareStatus.SYNCED -> 1;
            case ShareStatus.PENDING -> 3;
            default -> 3;
        };
    }

    /** 取最差；空集合返回 {@code null}（"没有共享项"与"共享正常"必须能区分开） */
    public static String worst(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        String worst = null;
        int worstRank = -1;
        for (String status : statuses) {
            int r = rank(status);
            if (r > worstRank) {
                worstRank = r;
                worst = status == null ? ShareStatus.PENDING : status;
            }
        }
        return worst;
    }

    /**
     * 由 outbox 投递结果推出共享项状态。
     *
     * <p>{@code PAUSED} 落在"还没推成功"，所以是 PENDING 而不是 FAILED——
     * 平台离线期间攒下的队列不是错误，恢复后会自动补。</p>
     */
    public static String fromOutbox(String outboxStatus) {
        if (outboxStatus == null) {
            return ShareStatus.PENDING;
        }
        return switch (outboxStatus) {
            case ShareStatus.OUTBOX_SENT -> ShareStatus.SYNCED;
            case ShareStatus.OUTBOX_FAILED -> ShareStatus.FAILED;
            case ShareStatus.OUTBOX_DEAD -> ShareStatus.DEAD;
            case ShareStatus.OUTBOX_PAUSED, ShareStatus.OUTBOX_PENDING -> ShareStatus.PENDING;
            default -> ShareStatus.PENDING;
        };
    }
}
