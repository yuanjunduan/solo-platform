package com.solo.domain.share.support;

import com.solo.domain.share.model.ShareStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台汇总"取最差"（3.9.6）。
 *
 * <p>这里最关键的一条：空集合必须返回 {@code null}，不能返回 SYNCED。
 * "没有共享项"和"共享正常"在页面上是两种完全不同的结论。</p>
 */
class ShareStatusMergeTest {

    @Test
    void severityOrderIsDeadFailedPendingRevokingSynced() {
        assertThat(ShareStatusMerge.rank(ShareStatus.DEAD)).isGreaterThan(ShareStatusMerge.rank(ShareStatus.FAILED));
        assertThat(ShareStatusMerge.rank(ShareStatus.FAILED)).isGreaterThan(ShareStatusMerge.rank(ShareStatus.PENDING));
        assertThat(ShareStatusMerge.rank(ShareStatus.PENDING)).isGreaterThan(ShareStatusMerge.rank(ShareStatus.REVOKING));
        assertThat(ShareStatusMerge.rank(ShareStatus.REVOKING)).isGreaterThan(ShareStatusMerge.rank(ShareStatus.SYNCED));
    }

    @Test
    void unknownStatusIsTreatedAsPending() {
        assertThat(ShareStatusMerge.rank("SOMETHING_NEW")).isEqualTo(ShareStatusMerge.rank(ShareStatus.PENDING));
        assertThat(ShareStatusMerge.rank(null)).isEqualTo(ShareStatusMerge.rank(ShareStatus.PENDING));
    }

    @Test
    void worstPicksTheMostSevere() {
        // 9 条正常 + 1 条死亡 必须显示"死亡"，不能被多数正常稀释
        List<String> statuses = List.of(
                ShareStatus.SYNCED, ShareStatus.SYNCED, ShareStatus.SYNCED, ShareStatus.SYNCED,
                ShareStatus.PENDING, ShareStatus.REVOKING, ShareStatus.SYNCED, ShareStatus.SYNCED,
                ShareStatus.SYNCED, ShareStatus.DEAD);

        assertThat(ShareStatusMerge.worst(statuses)).isEqualTo(ShareStatus.DEAD);
        assertThat(ShareStatusMerge.worst(List.of(ShareStatus.SYNCED, ShareStatus.PENDING)))
                .isEqualTo(ShareStatus.PENDING);
        assertThat(ShareStatusMerge.worst(List.of(ShareStatus.SYNCED, ShareStatus.REVOKING)))
                .isEqualTo(ShareStatus.REVOKING);
    }

    @Test
    void worstOfEmptyOrNullIsNull() {
        assertThat(ShareStatusMerge.worst(List.of())).isNull();
        assertThat(ShareStatusMerge.worst(null)).isNull();
    }

    @Test
    void nullElementInListBecomesPending() {
        assertThat(ShareStatusMerge.worst(java.util.Arrays.asList(ShareStatus.SYNCED, null)))
                .isEqualTo(ShareStatus.PENDING);
    }

    @Test
    void fromOutboxMapsDeliveryResult() {
        assertThat(ShareStatusMerge.fromOutbox(ShareStatus.OUTBOX_SENT)).isEqualTo(ShareStatus.SYNCED);
        assertThat(ShareStatusMerge.fromOutbox(ShareStatus.OUTBOX_FAILED)).isEqualTo(ShareStatus.FAILED);
        assertThat(ShareStatusMerge.fromOutbox(ShareStatus.OUTBOX_DEAD)).isEqualTo(ShareStatus.DEAD);
        // 平台离线期间攒下的队列不是错误，恢复后会自动补
        assertThat(ShareStatusMerge.fromOutbox(ShareStatus.OUTBOX_PAUSED)).isEqualTo(ShareStatus.PENDING);
        assertThat(ShareStatusMerge.fromOutbox(ShareStatus.OUTBOX_PENDING)).isEqualTo(ShareStatus.PENDING);
        assertThat(ShareStatusMerge.fromOutbox(null)).isEqualTo(ShareStatus.PENDING);
        assertThat(ShareStatusMerge.fromOutbox("UNKNOWN")).isEqualTo(ShareStatus.PENDING);
    }
}
