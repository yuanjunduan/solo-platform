package com.solo.domain.event.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级判定表（3.11.7）。
 *
 * <p>"事件可以合并，游标不能跳号"：合并的决策点就在这几个分支上，
 * 判错的后果是订阅方拿到的明细不完整却以为完整。</p>
 */
class EventShapeDeciderTest {

    private static final int DETAIL_MAX = 500;

    @Test
    void noChangeMeansNothingPushed() {
        assertThat(EventShapeDecider.decide(0, 0, false, false, DETAIL_MAX)).isEqualTo(EventShape.NONE);
        assertThat(EventShapeDecider.decide(-1, 0, false, false, DETAIL_MAX)).isEqualTo(EventShape.NONE);
        // 即使树重建标记为真，没有变化也不推
        assertThat(EventShapeDecider.decide(0, 0, true, true, DETAIL_MAX)).isEqualTo(EventShape.NONE);
    }

    @Test
    void treeRebuiltDegradesToRefresh() {
        assertThat(EventShapeDecider.decide(3, 3, true, false, DETAIL_MAX)).isEqualTo(EventShape.REFRESH);
    }

    @Test
    void skippedDeleteDegradesToRefresh() {
        // 跳过删除意味着明细语义不完整，"没出现在报文里"不再等于"消失了"
        assertThat(EventShapeDecider.decide(3, 3, false, true, DETAIL_MAX)).isEqualTo(EventShape.REFRESH);
    }

    @Test
    void emptyDetailWithChangedRowsDegradesToRefresh() {
        assertThat(EventShapeDecider.decide(3, 0, false, false, DETAIL_MAX)).isEqualTo(EventShape.REFRESH);
    }

    @Test
    void overThresholdDegradesToRefresh() {
        assertThat(EventShapeDecider.decide(DETAIL_MAX, DETAIL_MAX, false, false, DETAIL_MAX))
                .isEqualTo(EventShape.DETAIL);
        assertThat(EventShapeDecider.decide(DETAIL_MAX + 1, DETAIL_MAX + 1, false, false, DETAIL_MAX))
                .isEqualTo(EventShape.REFRESH);
    }

    @Test
    void normalRoundKeepsDetail() {
        assertThat(EventShapeDecider.decide(12, 12, false, false, DETAIL_MAX)).isEqualTo(EventShape.DETAIL);
    }
}
