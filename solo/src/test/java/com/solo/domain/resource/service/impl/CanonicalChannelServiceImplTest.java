package com.solo.domain.resource.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.service.CanonicalChannelService;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.projection.CanonicalAnomaly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同源组维护（3.10.3）：组主选取、绑定/解除的边界、L1 自动关联只补空。
 *
 * <p>全 mock：要验证的是"该不该改、把谁的指针改成谁"，不是 SQL 能不能跑。
 * canonical 更新走显式 {@code @Update}（不是 LambdaUpdateWrapper），所以这里不需要
 * 初始化 MyBatis-Plus 的 lambda 缓存。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CanonicalChannelServiceImplTest {

    @Mock
    private ResChannelMapper channelMapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private CanonicalChannelServiceImpl service;

    @Test
    void bindPrefersLocalRowAsLeader() {
        // 入站行更早，但本地行（source_platform_id=0）优先当组主：上级抖动时组里仍有一条自己能支配的链路
        ResChannel inbound = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.of(2026, 1, 1, 0, 0));
        ResChannel local = channel(20L, "NATIVE", 0L, 0L, LocalDateTime.of(2026, 6, 1, 0, 0));
        when(channelMapper.selectBatchIds(List.of(10L, 20L))).thenReturn(List.of(inbound, local));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of());
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(1);
        // bind 成功后回读组视图：组主 20 自己 + 被指过去的 10
        when(channelMapper.selectById(20L)).thenReturn(local);
        when(channelMapper.selectByCanonicalGroup(20L)).thenReturn(List.of(inbound, local));

        service.bind(List.of(10L, 20L));

        verify(channelMapper).updateCanonical(eq(10L), eq("INBOUND"), eq(0L), eq(20L), any());
        verify(channelMapper, never()).updateCanonical(eq(20L), any(), any(), any(), any());
        verify(auditService).record(eq("BIND_CANONICAL"), eq("CHANNEL"), eq("20"), any());
    }

    @Test
    void bindLeaderTieBreaksByCreateTimeThenId() {
        // 都不是本地行 → 创建时间最早者当组主；创建时间相同再比 id
        ResChannel late = channel(3L, "INBOUND", 7L, 0L, LocalDateTime.of(2026, 3, 1, 0, 0));
        ResChannel earlyA = channel(1L, "INBOUND", 7L, 0L, LocalDateTime.of(2026, 1, 1, 0, 0));
        ResChannel earlyB = channel(2L, "INBOUND", 7L, 0L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelMapper.selectBatchIds(List.of(3L, 1L, 2L))).thenReturn(List.of(late, earlyA, earlyB));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of());
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(1);
        when(channelMapper.selectById(1L)).thenReturn(earlyA);
        when(channelMapper.selectByCanonicalGroup(1L)).thenReturn(List.of(late, earlyA, earlyB));

        service.bind(List.of(3L, 1L, 2L));

        verify(channelMapper).updateCanonical(eq(3L), any(), eq(0L), eq(1L), any());
        verify(channelMapper).updateCanonical(eq(2L), any(), eq(0L), eq(1L), any());
        verify(channelMapper, never()).updateCanonical(eq(1L), any(), any(), any(), any());
    }

    @Test
    void bindRejectsMemberAlreadyInAnotherGroup() {
        ResChannel linked = channel(10L, "INBOUND", 5L, 9L, LocalDateTime.now());
        ResChannel fresh = channel(11L, "INBOUND", 5L, 0L, LocalDateTime.now());
        when(channelMapper.selectBatchIds(List.of(10L, 11L))).thenReturn(List.of(linked, fresh));

        assertThatThrownBy(() -> service.bind(List.of(10L, 11L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已属于其它同源组");
        verify(channelMapper, never()).updateCanonical(any(), any(), any(), any(), any());
    }

    @Test
    void bindRejectsExistingLeaderToAvoidTwoLevelPointers() {
        // 选中的行本身是组主：它的子记录还指着它，直接并入会形成"子指向子"
        ResChannel leader = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.now());
        ResChannel other = channel(11L, "INBOUND", 5L, 0L, LocalDateTime.now());
        when(channelMapper.selectBatchIds(List.of(10L, 11L))).thenReturn(List.of(leader, other));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of(10L));

        assertThatThrownBy(() -> service.bind(List.of(10L, 11L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已是同源组主");
        verify(channelMapper, never()).updateCanonical(any(), any(), any(), any(), any());
    }

    @Test
    void bindRejectsSingleRow() {
        assertThatThrownBy(() -> service.bind(List.of(10L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("至少选择 2 条");
    }

    @Test
    void unbindMemberOnlyClearsItsOwnPointer() {
        ResChannel member = channel(11L, "INBOUND", 5L, 10L, LocalDateTime.now());
        when(channelMapper.selectById(11L)).thenReturn(member);
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(1);

        service.unbind(11L);

        verify(channelMapper).updateCanonical(eq(11L), eq("INBOUND"), eq(10L), eq(0L), any());
        verify(auditService).record(eq("UNBIND_CANONICAL"), eq("CHANNEL"), eq("11"), any());
    }

    @Test
    void unbindLeaderPromotesNewLeaderAmongRemainingMembers() {
        ResChannel leader = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.now());
        ResChannel localMember = channel(12L, "NATIVE", 0L, 10L, LocalDateTime.now());
        ResChannel inboundMember = channel(11L, "INBOUND", 5L, 10L, LocalDateTime.now());
        when(channelMapper.selectById(10L)).thenReturn(leader);
        when(channelMapper.selectByCanonicalGroup(10L)).thenReturn(List.of(leader, inboundMember, localMember));
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(1);

        service.unbind(10L);

        // 新组主：本地行优先 → 12 置 0，其余成员指向 12
        verify(channelMapper).updateCanonical(eq(12L), eq("NATIVE"), eq(10L), eq(0L), any());
        verify(channelMapper).updateCanonical(eq(11L), eq("INBOUND"), eq(10L), eq(12L), any());
        verify(auditService).record(eq("UNBIND_CANONICAL"), eq("CHANNEL"), eq("10"), any());
    }

    @Test
    void unbindAloneRowIsRejected() {
        ResChannel alone = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.now());
        when(channelMapper.selectById(10L)).thenReturn(alone);
        when(channelMapper.selectByCanonicalGroup(10L)).thenReturn(List.of(alone));

        assertThatThrownBy(() -> service.unbind(10L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不在同源组中");
        verify(channelMapper, never()).updateCanonical(any(), any(), any(), any(), any());
    }

    @Test
    void afterMergeLinksOnlyUntouchedGroup() {
        // 组 A：两行都没有指针 → 自动关联；组 B：已有一条人工指针 → 整组跳过（系统不自动解除/不拆组）
        ResChannel a1 = channel(1L, "INBOUND", 7L, 0L, LocalDateTime.of(2026, 1, 1, 0, 0));
        ResChannel a2 = channel(2L, "INBOUND", 7L, 0L, LocalDateTime.of(2026, 2, 1, 0, 0));
        ResChannel b1 = channel(3L, "INBOUND", 7L, 0L, LocalDateTime.now());
        ResChannel b2 = channel(4L, "INBOUND", 7L, 3L, LocalDateTime.now());
        a1.setOriginPlatformId(9L);
        a1.setOriginCode("CH-A");
        a2.setOriginPlatformId(9L);
        a2.setOriginCode("CH-A");
        b1.setOriginPlatformId(9L);
        b1.setOriginCode("CH-B");
        b2.setOriginPlatformId(9L);
        b2.setOriginCode("CH-B");
        when(channelMapper.selectByOriginPairsOf(7L)).thenReturn(List.of(a1, a2, b1, b2));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of());
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(1);

        int linked = service.afterMerge(7L);

        assertThat(linked).isEqualTo(1);
        verify(channelMapper).updateCanonical(eq(2L), eq("INBOUND"), eq(0L), eq(1L), any());
        verify(channelMapper, never()).updateCanonical(eq(4L), any(), any(), any(), any());
        verify(auditService).record(eq("AUTO_LINK_CANONICAL"), eq("CHANNEL"), eq("1"), any());
        // 收尾核对一次：核对只读，异常不影响自动关联结果
        verify(channelMapper).countCanonicalAnomalies();
    }

    @Test
    void afterMergeSkipsGroupWhoseLeaderIsReferenced() {
        // 两行都没指针，但组主已被别的行引用 → 也不能再动，否则会出现两级指针
        ResChannel c1 = channel(1L, "INBOUND", 7L, 0L, LocalDateTime.now());
        ResChannel c2 = channel(2L, "INBOUND", 7L, 0L, LocalDateTime.now());
        c1.setOriginPlatformId(9L);
        c1.setOriginCode("CH-C");
        c2.setOriginPlatformId(9L);
        c2.setOriginCode("CH-C");
        when(channelMapper.selectByOriginPairsOf(7L)).thenReturn(List.of(c1, c2));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of(1L));

        int linked = service.afterMerge(7L);

        assertThat(linked).isZero();
        verify(channelMapper, never()).updateCanonical(any(), any(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void playCandidatesUseLeaderGroupAndDropRemoved() {
        ResChannel member = channel(12L, "INBOUND", 5L, 10L, LocalDateTime.now());
        ResChannel leader = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.now());
        ResChannel removed = channel(13L, "INBOUND", 5L, 10L, LocalDateTime.now());
        removed.setStatus("REMOVED");
        when(channelMapper.selectById(12L)).thenReturn(member);
        when(channelMapper.selectByCanonicalGroup(10L)).thenReturn(List.of(removed, leader, member));

        List<ResChannel> candidates = service.playCandidates(12L);

        // 请求行仍在列表里（选路由调用方决定用哪条），已消失的行不再参与
        assertThat(candidates).extracting(ResChannel::getId).containsExactly(12L, 10L);
    }

    @Test
    void bindConflictWhenRowWasChangedConcurrently() {
        ResChannel a = channel(10L, "INBOUND", 5L, 0L, LocalDateTime.now());
        ResChannel b = channel(11L, "INBOUND", 5L, 0L, LocalDateTime.now());
        when(channelMapper.selectBatchIds(List.of(10L, 11L))).thenReturn(List.of(a, b));
        when(channelMapper.selectReferencedLeaderIds(anyCollection())).thenReturn(List.of());
        // CAS 影响 0 行：读完之后别人先把这条绑走了 → 整组回滚，不覆盖别人的结果
        when(channelMapper.updateCanonical(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.bind(List.of(10L, 11L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("刷新后重试");
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void integrityMapsAnomalyCounters() {
        CanonicalAnomaly anomaly = new CanonicalAnomaly();
        anomaly.setSelfPointer(1L);
        anomaly.setOrphan(2L);
        anomaly.setTwoLevel(3L);
        anomaly.setLeaderRemoved(4L);
        when(channelMapper.countCanonicalAnomalies()).thenReturn(anomaly);

        CanonicalChannelService.Integrity integrity = service.checkIntegrity();

        assertThat(integrity.total()).isEqualTo(10);
        assertThat(integrity.clean()).isFalse();
        assertThat(integrity.twoLevel()).isEqualTo(3);
    }

    @Test
    void integrityIsCleanWhenNoAnomaly() {
        when(channelMapper.countCanonicalAnomalies()).thenReturn(new CanonicalAnomaly());

        CanonicalChannelService.Integrity integrity = service.checkIntegrity();

        assertThat(integrity.clean()).isTrue();
        assertThat(integrity.total()).isZero();
    }

    @Test
    void missingChannelIsRejected() {
        when(channelMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.group(99L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("通道不存在");
        verify(channelMapper, never()).selectByCanonicalGroup(any());
    }

    private static ResChannel channel(Long id, String sourceType, Long sourcePlatformId,
                                      Long canonicalId, LocalDateTime createTime) {
        ResChannel row = new ResChannel();
        row.setId(id);
        row.setCode("CH-" + id);
        row.setStatus("ACTIVE");
        row.setOnlineStatus("ON");
        row.setSourceType(sourceType);
        row.setSourcePlatformId(sourcePlatformId);
        row.setCanonicalId(canonicalId);
        row.setCreateTime(createTime);
        return row;
    }
}
