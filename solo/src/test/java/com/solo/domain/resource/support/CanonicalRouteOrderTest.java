package com.solo.domain.resource.support;

import com.solo.domain.resource.model.ResChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同源组取流顺序（3.10.3「播放」行）。
 *
 * <p>纯函数排序，不连库：这里要钉住的是"用户点选的优先、其余在线优先、再比跳数、最后 id 兜底"，
 * 以及"已消失的行不参与选路"。</p>
 */
class CanonicalRouteOrderTest {

    @Test
    void requestedRowStaysFirstEvenIfOtherRowIsBetter() {
        ResChannel requested = channel(10L, "ON", 5);
        ResChannel betterHop = channel(11L, "ON", 1);
        ResChannel local = channel(12L, "ON", 0);

        List<ResChannel> ordered = CanonicalRouteOrder.order(List.of(local, betterHop, requested), 10L);

        // 用户点选即明确意图：不因为别处跳数更少就擅自替换
        assertThat(ordered).extracting(ResChannel::getId).containsExactly(10L, 12L, 11L);
    }

    @Test
    void withoutPreferenceOnlineBeatsOfflineAndFewerHopsWin() {
        ResChannel offline = channel(1L, "OFF", 0);
        ResChannel onlineFar = channel(2L, "ON", 4);
        ResChannel onlineNear = channel(3L, "ON", 1);

        List<ResChannel> ordered = CanonicalRouteOrder.order(List.of(offline, onlineFar, onlineNear), null);

        assertThat(ordered).extracting(ResChannel::getId).containsExactly(3L, 2L, 1L);
    }

    @Test
    void missingHopCountSortsAfterKnownHops() {
        ResChannel unknownHop = channel(1L, "ON", null);
        ResChannel twoHops = channel(2L, "ON", 2);

        List<ResChannel> ordered = CanonicalRouteOrder.order(List.of(unknownHop, twoHops), null);

        // 跳数缺失按最大处理：没有证据不能排在有证据的前面
        assertThat(ordered).extracting(ResChannel::getId).containsExactly(2L, 1L);
    }

    @Test
    void removedRowsAreDroppedAndTiesFallBackToId() {
        ResChannel removed = channel(1L, "ON", 0);
        removed.setStatus("REMOVED");
        ResChannel sameA = channel(2L, "ON", 1);
        ResChannel sameB = channel(3L, "ON", 1);

        List<ResChannel> ordered = CanonicalRouteOrder.order(List.of(sameB, removed, sameA), null);

        assertThat(ordered).extracting(ResChannel::getId).containsExactly(2L, 3L);
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertThat(CanonicalRouteOrder.order(null, 1L)).isEmpty();
        assertThat(CanonicalRouteOrder.order(List.of(), 1L)).isEmpty();
    }

    private static ResChannel channel(Long id, String onlineStatus, Integer hopCount) {
        ResChannel row = new ResChannel();
        row.setId(id);
        row.setStatus("ACTIVE");
        row.setOnlineStatus(onlineStatus);
        row.setHopCount(hopCount);
        return row;
    }
}
