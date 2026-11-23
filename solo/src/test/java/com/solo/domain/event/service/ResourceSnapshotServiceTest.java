package com.solo.domain.event.service;

import com.solo.domain.event.model.EventSubscription;
import com.solo.domain.event.model.ResourceSnapshotPage;
import com.solo.domain.event.service.ResourceSnapshotService.SnapshotType;
import com.solo.domain.event.support.EventScopeDeniedException;
import com.solo.infra.mapper.ChangeEventMapper;
import com.solo.infra.mapper.ResourceSnapshotMapper;
import com.solo.infra.mapper.projection.SnapshotRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全量快照的 scope 收敛与分页（6.4 / 3.12.1）。
 *
 * <p>两条底线：<b>订阅 scope 是上限</b>（请求只能收紧，越界直接 403 而不是静默给空），
 * <b>返回的 cursor 与 SSE 对齐</b>（快照 + 增量才能拼成连续状态）。</p>
 */
class ResourceSnapshotServiceTest {

    private final ResourceSnapshotMapper snapshotMapper = mock(ResourceSnapshotMapper.class);
    private final ChangeEventMapper eventMapper = mock(ChangeEventMapper.class);
    private final ResourceSnapshotService service = new ResourceSnapshotService(snapshotMapper, eventMapper);

    private static EventSubscription subscription(Long platformId, String orgPath) {
        EventSubscription sub = new EventSubscription();
        sub.setId(1L);
        sub.setTenantId(1L);
        sub.setAppKey("ak_test");
        sub.setPlatformId(platformId);
        sub.setOrgPath(orgPath);
        return sub;
    }

    private static SnapshotRow row(String code) {
        SnapshotRow row = new SnapshotRow();
        row.setCode(code);
        return row;
    }

    @Test
    void requestCannotWidenPlatformScope() {
        // 订阅只看平台 5，请求要平台 6：明确拒绝，不静默裁剪成空结果
        assertThatThrownBy(() -> service.page(subscription(5L, null), SnapshotType.CHANNEL, 6L, null, 1, 10))
                .isInstanceOf(EventScopeDeniedException.class);
        verify(snapshotMapper, never()).countChannels(any(), any());
    }

    @Test
    void requestCannotWidenOrgScope() {
        assertThatThrownBy(() -> service.page(subscription(null, "/1/5/"), SnapshotType.CHANNEL, null, "/1/", 1, 10))
                .isInstanceOf(EventScopeDeniedException.class);
    }

    @Test
    void narrowerRequestIsAllowedAndKept() {
        when(snapshotMapper.countChannels(5L, "/1/5/9/")).thenReturn(0L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(42L);

        // 订阅限 /1/5/、请求 /1/5/9/：请求是收紧，允许，且生效范围取更窄的那个
        ResourceSnapshotPage page = service.page(subscription(5L, "/1/5/"), SnapshotType.CHANNEL,
                null, "/1/5/9/", 1, 10);

        assertThat(page.platformId()).isEqualTo(5L);
        assertThat(page.orgPath()).isEqualTo("/1/5/9/");
        assertThat(page.cursor()).isEqualTo(42L);
    }

    @Test
    void sizeIsClampedAndDefaults() {
        when(snapshotMapper.countChannels(isNull(), isNull())).thenReturn(0L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(7L);

        assertThat(service.page(subscription(null, null), SnapshotType.CHANNEL, null, null, null, null).size())
                .isEqualTo(ResourceSnapshotService.DEFAULT_SIZE);
        assertThat(service.page(subscription(null, null), SnapshotType.CHANNEL, null, null, null, 9999).size())
                .isEqualTo(ResourceSnapshotService.MAX_SIZE);
    }

    @Test
    void outOfRangePageSkipsQuery() {
        when(snapshotMapper.countChannels(isNull(), isNull())).thenReturn(3L);
        when(eventMapper.selectMaxSeq(1L)).thenReturn(9L);

        ResourceSnapshotPage page = service.page(subscription(null, null), SnapshotType.CHANNEL,
                null, null, 5, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(3L);
        // total 已经算出来了，越界页不必再打一次库
        verify(snapshotMapper, never()).pageChannels(any(), any(), anyInt(), anyInt());
    }

    @Test
    void hasNextFollowsRemainingRows() {
        when(snapshotMapper.countChannels(isNull(), isNull())).thenReturn(25L);
        when(snapshotMapper.pageChannels(isNull(), isNull(), eq(0), eq(10)))
                .thenReturn(List.of(row("34020000001320000001")));
        when(eventMapper.selectMaxSeq(1L)).thenReturn(100L);

        ResourceSnapshotPage first = service.page(subscription(null, null), SnapshotType.CHANNEL,
                null, null, 1, 10);

        assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).hasSize(1);
    }

    @Test
    void typeParserDefaultsToChannelAndRejectsUnknown() {
        assertThat(SnapshotType.of(null)).isEqualTo(SnapshotType.CHANNEL);
        assertThat(SnapshotType.of("  ")).isEqualTo(SnapshotType.CHANNEL);
        assertThat(SnapshotType.of("device")).isEqualTo(SnapshotType.DEVICE);
        assertThat(SnapshotType.of("ORG")).isEqualTo(SnapshotType.ORG);
        assertThatThrownBy(() -> SnapshotType.of("unknown")).isInstanceOf(IllegalArgumentException.class);
    }
}
