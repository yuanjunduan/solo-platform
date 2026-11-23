package com.solo.domain.share.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站超量降级（C18）。
 *
 * <p>降级不是丢数据，而是把"逐条增量"换成"让对端重拉一次目录"；
 * 但虚拟组织树重建过时必须无条件降级——逐条推会大量对上不存在的父节点。</p>
 */
class PublishDegradeDeciderTest {

    @Test
    void emptyQueueIsNoop() {
        assertThat(PublishDegradeDecider.decide(0, 500, false)).isEqualTo(PublishDegradeDecider.Shape.NONE);
        assertThat(PublishDegradeDecider.decide(-1, 500, false)).isEqualTo(PublishDegradeDecider.Shape.NONE);
    }

    @Test
    void withinThresholdPushesDetail() {
        assertThat(PublishDegradeDecider.decide(1, 500, false)).isEqualTo(PublishDegradeDecider.Shape.DETAIL);
        assertThat(PublishDegradeDecider.decide(500, 500, false)).isEqualTo(PublishDegradeDecider.Shape.DETAIL);
    }

    @Test
    void overThresholdMergesIntoRefresh() {
        assertThat(PublishDegradeDecider.decide(501, 500, false)).isEqualTo(PublishDegradeDecider.Shape.REFRESH);
        assertThat(PublishDegradeDecider.decide(20_000, 500, false)).isEqualTo(PublishDegradeDecider.Shape.REFRESH);
    }

    @Test
    void treeRebuiltAlwaysDegrades() {
        assertThat(PublishDegradeDecider.decide(1, 500, true)).isEqualTo(PublishDegradeDecider.Shape.REFRESH);
    }

    @Test
    void nonPositiveDetailMaxMeansUnlimited() {
        assertThat(PublishDegradeDecider.decide(100_000, 0, false)).isEqualTo(PublishDegradeDecider.Shape.DETAIL);
        assertThat(PublishDegradeDecider.decide(100_000, -1, false)).isEqualTo(PublishDegradeDecider.Shape.DETAIL);
    }
}
