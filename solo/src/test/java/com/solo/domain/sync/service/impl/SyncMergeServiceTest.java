package com.solo.domain.sync.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.audit.model.AuditLog;
import com.solo.domain.audit.service.AuditService;
import com.solo.domain.event.service.SyncEventEmitter;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.share.service.ShareDisappearService;
import com.solo.domain.share.service.ShareStatusFollowService;
import com.solo.domain.sync.model.PlatformCatalogVer;
import com.solo.domain.sync.model.SyncRound;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.PlatformCatalogVerMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncChannelAlignmentMapper;
import com.solo.infra.mapper.SyncDeviceAlignmentMapper;
import com.solo.infra.mapper.SyncGroupAlignmentMapper;
import com.solo.infra.mapper.SyncOrgAlignmentMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncRecordMapper;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * finish 阶段（单事务合并）的行为用例。
 *
 * <p>这里覆盖的是"错一行就删库"的部分：G1 空批整轮失败、G2 只跳过删除、
 * 合并顺序（组织→分组→设备→通道、新增→消失）、计数与指纹回填、平台编码兜底。</p>
 *
 * <p>SQL 本身的方言与列集由 {@code SyncAlignmentSqlTest} 守；本类只验证编排层
 * 调了谁、按什么顺序、把什么写回了台账。</p>
 */
class SyncMergeServiceTest {

    private static final long TASK_ID = 9001L;
    private static final long PLATFORM_ID = 7L;
    private static final String PLATFORM_CODE = "34020000002000000001";

    private SyncTaskMapper taskMapper;
    private SyncStagingMapper stagingMapper;
    private PlatformMapper platformMapper;
    private PlatformCatalogVerMapper catalogVerMapper;
    private SyncOrgAlignmentMapper orgAlignment;
    private SyncGroupAlignmentMapper groupAlignment;
    private SyncDeviceAlignmentMapper deviceAlignment;
    private SyncChannelAlignmentMapper channelAlignment;
    private SyncRecordMapper recordMapper;
    private SyncOrgPendingMapper pendingMapper;
    private SyncSnapshotWriter snapshotWriter;
    private SyncRoundRecorder roundRecorder;
    private AuditService auditService;
    private SyncSettings settings;
    private SyncEventEmitter eventEmitter;
    private ShareDisappearService shareDisappearService;
    private ShareStatusFollowService shareStatusFollowService;
    private SyncMergeService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(SyncTaskMapper.class);
        stagingMapper = mock(SyncStagingMapper.class);
        platformMapper = mock(PlatformMapper.class);
        catalogVerMapper = mock(PlatformCatalogVerMapper.class);
        orgAlignment = mock(SyncOrgAlignmentMapper.class);
        groupAlignment = mock(SyncGroupAlignmentMapper.class);
        deviceAlignment = mock(SyncDeviceAlignmentMapper.class);
        channelAlignment = mock(SyncChannelAlignmentMapper.class);
        recordMapper = mock(SyncRecordMapper.class);
        pendingMapper = mock(SyncOrgPendingMapper.class);
        snapshotWriter = mock(SyncSnapshotWriter.class);
        roundRecorder = mock(SyncRoundRecorder.class);
        auditService = mock(AuditService.class);
        eventEmitter = mock(SyncEventEmitter.class);
        shareDisappearService = mock(ShareDisappearService.class);
        shareStatusFollowService = mock(ShareStatusFollowService.class);
        when(shareDisappearService.afterMerge(anyLong()))
                .thenReturn(ShareDisappearService.DisappearResult.empty());

        settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "g2MinLocalRows", 1000L);
        ReflectionTestUtils.setField(settings, "treeMaxDepth", 16);
        ReflectionTestUtils.setField(settings, "deleteMode", "mark");

        service = new SyncMergeService(taskMapper, stagingMapper, platformMapper, catalogVerMapper,
                orgAlignment, groupAlignment, deviceAlignment, channelAlignment,
                recordMapper, pendingMapper, snapshotWriter, roundRecorder, auditService, settings,
                eventEmitter, shareDisappearService, shareStatusFollowService);

        SyncTask task = new SyncTask();
        task.setId(TASK_ID);
        task.setPlatformId(PLATFORM_ID);
        task.setTenantId(1L);
        task.setStatus(SyncTaskStatus.RUNNING);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(platformMapper.selectById(PLATFORM_ID)).thenReturn(platform("0.2"));
        when(taskMapper.updateIfActive(any(SyncTask.class))).thenReturn(1);
        when(catalogVerMapper.selectById(PLATFORM_ID)).thenReturn(null);
    }

    @Test
    @DisplayName("G1：暂存 0 行但本地有数据 → 整轮失败，不标消失、不删暂存")
    void rejectsEmptyStagingWhenLocalHasRows() {
        when(stagingMapper.countByTask(TASK_ID)).thenReturn(0L);
        when(stagingMapper.countInboundOrg(PLATFORM_ID)).thenReturn(5L);
        when(stagingMapper.countInboundDevice(PLATFORM_ID)).thenReturn(3L);
        when(stagingMapper.countInboundChannel(PLATFORM_ID)).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.finish(TASK_ID));

        assertTrue(ex.getMessage().startsWith("SYNC_EMPTY_STAGING"), ex.getMessage());
        verify(taskMapper, never()).updateById(any(SyncTask.class));
        verify(stagingMapper, never()).deleteByPlatform(anyLong());
        verifyNoInteractions(orgAlignment, groupAlignment, deviceAlignment, channelAlignment);
        verifyNoInteractions(catalogVerMapper);
    }

    @Test
    @DisplayName("正常一轮：先建树后设备通道、先新增后消失，计数/指纹/版本表全部回填")
    void mergesInOrderAndWritesCounters() {
        stubCounts(10L, 2L, 1L, 3L, 4L);

        // 组织两轮插完（2 → 0），分组一轮（1 → 0）；子节点修复到收敛（0）
        when(orgAlignment.insertReady(eq(TASK_ID), anyLong(), any(LocalDateTime.class))).thenReturn(2, 0);
        when(groupAlignment.insertReady(eq(TASK_ID), anyLong(), any(LocalDateTime.class))).thenReturn(1, 0);
        when(orgAlignment.repairChildren(anyLong(), any(LocalDateTime.class))).thenReturn(0);
        when(groupAlignment.repairChildren(anyLong(), any(LocalDateTime.class))).thenReturn(0);

        when(orgAlignment.updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList())).thenReturn(1);
        when(groupAlignment.updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList())).thenReturn(0);
        when(deviceAlignment.updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList())).thenReturn(2);
        when(channelAlignment.updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList())).thenReturn(3);

        when(deviceAlignment.insertNew(eq(TASK_ID), anyLong(), any(LocalDateTime.class), anyList())).thenReturn(1);
        when(channelAlignment.insertNew(eq(TASK_ID), anyLong(), any(LocalDateTime.class), anyList())).thenReturn(2);
        when(channelAlignment.countOrphanByMissingDevice(TASK_ID)).thenReturn(1L);

        // 四类都没有预计消失的行 → 闸门全部放行
        when(orgAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);
        when(groupAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);
        when(channelAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);

        when(stagingMapper.fingerprintSum(TASK_ID)).thenReturn(0x1a2bL);
        when(deviceAlignment.countOrgMissing(PLATFORM_ID)).thenReturn(2L);
        when(channelAlignment.countOrgMissing(PLATFORM_ID)).thenReturn(1L);

        service.finish(TASK_ID);

        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateById(captor.capture());
        SyncTask done = captor.getValue();

        assertEquals(SyncTaskStatus.SUCCESS, done.getStatus());
        assertEquals(100, done.getProgress());
        assertEquals(10, done.getExpectedTotal());
        assertEquals(10, done.getRecvTotal());
        assertEquals(6, done.getAddedCount(), "组织 2 + 分组 1 + 设备 1 + 通道 2");
        assertEquals(6, done.getUpdatedCount(), "组织 1 + 分组 0 + 设备 2 + 通道 3");
        assertEquals(0, done.getStaleCount());
        assertEquals(1, done.getFailedCount(), "载体设备缺失的孤儿通道必须在 failed_count 里可见");
        assertEquals(0, done.getDeleteSkipped());
        assertEquals(0, new BigDecimal("0.2").compareTo(done.getGuardRatio()), "阈值快照必须入库供回查");
        assertEquals(3, done.getOrgMissingCount());
        assertEquals(Long.toHexString(0x1a2bL) + "-10", done.getCatalogFingerprint());
        assertNotNull(done.getEndTime());

        ArgumentCaptor<PlatformCatalogVer> verCaptor = ArgumentCaptor.forClass(PlatformCatalogVer.class);
        verify(catalogVerMapper).insert(verCaptor.capture());
        assertEquals(TASK_ID, verCaptor.getValue().getLastTaskId());
        assertEquals(10, verCaptor.getValue().getItemCount());

        // 顺序是硬的：先建两棵树，再批量更新；设备早于通道；新增/更新早于消失标记
        // 建树是"重复调用直到插入 0 行"，所以 insertReady 各被调两次（2 → 0 / 1 → 0）
        InOrder order = inOrder(orgAlignment, groupAlignment, deviceAlignment, channelAlignment);
        order.verify(orgAlignment, times(2)).insertReady(eq(TASK_ID), anyLong(), any(LocalDateTime.class));
        order.verify(groupAlignment, times(2)).insertReady(eq(TASK_ID), anyLong(), any(LocalDateTime.class));
        order.verify(orgAlignment).updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList());
        order.verify(groupAlignment).updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList());
        order.verify(deviceAlignment).insertNew(eq(TASK_ID), anyLong(), any(LocalDateTime.class), anyList());
        order.verify(deviceAlignment).updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList());
        order.verify(channelAlignment).insertNew(eq(TASK_ID), anyLong(), any(LocalDateTime.class), anyList());
        order.verify(channelAlignment).updateDiff(eq(TASK_ID), any(LocalDateTime.class), anyList());
        order.verify(orgAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), eq(PLATFORM_CODE), any(LocalDateTime.class));
        order.verify(groupAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), any(LocalDateTime.class));
        order.verify(deviceAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), eq(PLATFORM_CODE), any(LocalDateTime.class));
        order.verify(channelAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), any(LocalDateTime.class));

        verify(stagingMapper).deleteByPlatform(PLATFORM_ID);
    }

    @Test
    @DisplayName("G2：预计消失数超过 本地×阈值 且本地过千 → 只跳过该类删除，新增/更新照常")
    void skipsDeleteOnlyForTrippedCollection() {
        // 设备本地 2000 行，阈值 0.2 → 上限 400；预计消失 1000 行，必须跳过
        stubCounts(500L, 0L, 0L, 2000L, 0L);
        when(orgAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);
        when(groupAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(1000L);
        when(channelAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);
        when(deviceAlignment.markMissing(anyLong(), anyLong(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(channelAlignment.markMissing(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(0);

        service.finish(TASK_ID);

        verify(deviceAlignment, never())
                .markMissing(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
        verify(orgAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), eq(PLATFORM_CODE), any(LocalDateTime.class));
        verify(channelAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), any(LocalDateTime.class));

        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getDeleteSkipped(), "跳过删除必须在台账可见（C11）");
        assertEquals(0, captor.getValue().getStaleCount());
        assertEquals(0, new BigDecimal("0.2").compareTo(captor.getValue().getGuardRatio()));
    }

    @Test
    @DisplayName("平台编码为空 → 用 localDomain 当根组织码，避免把平台根组织误判为消失")
    void fallsBackToLocalDomainAsPlatformCode() {
        Platform platform = platform("0");
        platform.setCode("   ");
        platform.setLocalDomain("34020000002000000009");
        when(platformMapper.selectById(PLATFORM_ID)).thenReturn(platform);

        stubCounts(1L, 0L, 0L, 0L, 0L);

        service.finish(TASK_ID);

        verify(orgAlignment).countWouldStale(eq(TASK_ID), eq(PLATFORM_ID), eq("34020000002000000009"));
        verify(deviceAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID),
                eq("34020000002000000009"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("轮次在收尾前已结束（updateIfActive=0）→ 直接返回，不读暂存不再回写")
    void ignoresRoundAlreadyFinished() {
        when(taskMapper.updateIfActive(any(SyncTask.class))).thenReturn(0);

        service.finish(TASK_ID);

        verify(stagingMapper, never()).countByTask(anyLong());
        verify(stagingMapper, never()).deleteByPlatform(anyLong());
        verify(taskMapper, never()).updateById(any(SyncTask.class));
        verifyNoInteractions(orgAlignment, groupAlignment, deviceAlignment, channelAlignment, shareDisappearService, shareStatusFollowService);
    }

    @Test
    @DisplayName("平台既无编码也无域 → 抛错，不进入合并")
    void requiresPlatformCode() {
        Platform platform = platform("0");
        platform.setCode(null);
        platform.setLocalDomain(null);
        when(platformMapper.selectById(PLATFORM_ID)).thenReturn(platform);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.finish(TASK_ID));

        assertTrue(ex.getMessage().contains("编码"), ex.getMessage());
        verify(stagingMapper, never()).countByTask(anyLong());
    }

    @Test
    @DisplayName("mark 模式：设备消失只标 STALE，并紧贴标记写一条 STALE 留痕")
    void recordsStaleWhenDeleteModeIsMark() {
        stubCounts(10L, 0L, 0L, 2000L, 0L);
        stubNoOrgGroupChannelStale();
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(5L);
        when(deviceAlignment.markMissing(anyLong(), anyLong(), anyString(), any(LocalDateTime.class))).thenReturn(5);

        service.finish(TASK_ID);

        verify(deviceAlignment).markMissing(eq(TASK_ID), eq(PLATFORM_ID), eq(PLATFORM_CODE), any(LocalDateTime.class));
        verify(deviceAlignment, never()).markRemoved(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
        verify(recordMapper).recordDevice(eq(TASK_ID), eq(PLATFORM_ID), eq("STALE"), eq("STALE"),
                any(LocalDateTime.class));
        verify(auditService).record(eq("SYNC_MARK_STALE"), eq(AuditLog.TARGET_PLATFORM),
                eq(String.valueOf(PLATFORM_ID)), anyString());
    }

    @Test
    @DisplayName("real 模式：设备消失落 REMOVED，留痕类型 REMOVE，审计记 SYNC_DELETE")
    void marksRemovedWhenDeleteModeIsReal() {
        ReflectionTestUtils.setField(settings, "deleteMode", "real");
        stubCounts(10L, 0L, 0L, 2000L, 0L);
        stubNoOrgGroupChannelStale();
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(4L);
        when(deviceAlignment.markRemoved(anyLong(), anyLong(), anyString(), any(LocalDateTime.class))).thenReturn(4);

        service.finish(TASK_ID);

        verify(deviceAlignment).markRemoved(eq(TASK_ID), eq(PLATFORM_ID), eq(PLATFORM_CODE), any(LocalDateTime.class));
        verify(deviceAlignment, never()).markMissing(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
        // C12：资源落定后必须在同一事务里联动共享（撤销上级可见性）
        verify(shareDisappearService).afterMerge(PLATFORM_ID);
        verify(shareStatusFollowService).afterMerge(PLATFORM_ID);
        verify(recordMapper).recordDevice(eq(TASK_ID), eq(PLATFORM_ID), eq("REMOVED"), eq("REMOVE"),
                any(LocalDateTime.class));
        verify(auditService).record(eq("SYNC_DELETE"), eq(AuditLog.TARGET_PLATFORM),
                eq(String.valueOf(PLATFORM_ID)), anyString());
    }

    @Test
    @DisplayName("重现即恢复：恢复必须早于新增，且留痕为 RESTORE（3.8.4 规则 3）")
    void restoresBeforeInsertAndRecordsRestore() {
        stubCounts(10L, 1L, 0L, 1L, 0L);
        stubNoOrgGroupChannelStale();
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);
        when(deviceAlignment.restoreActive(eq(TASK_ID), eq(PLATFORM_ID), any(LocalDateTime.class))).thenReturn(2);

        service.finish(TASK_ID);

        InOrder order = inOrder(deviceAlignment);
        order.verify(deviceAlignment).restoreActive(eq(TASK_ID), eq(PLATFORM_ID), any(LocalDateTime.class));
        order.verify(deviceAlignment).insertNew(eq(TASK_ID), anyLong(), any(LocalDateTime.class), anyList());
        verify(recordMapper).recordDevice(eq(TASK_ID), eq(PLATFORM_ID), eq("ACTIVE"), eq("RESTORE"),
                any(LocalDateTime.class));
        verify(auditService).record(eq("SYNC_RESTORE"), eq(AuditLog.TARGET_PLATFORM),
                eq(String.valueOf(PLATFORM_ID)), anyString());
    }

    @Test
    @DisplayName("组织缺失登记必须早于清暂存：重试要拿 parent_code 重走第①步")
    void capturesPendingOrgBeforeClearingStaging() {
        stubCounts(10L, 1L, 0L, 1L, 0L);
        stubNoOrgGroupChannelStale();
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);

        service.finish(TASK_ID);

        InOrder order = inOrder(pendingMapper, stagingMapper);
        order.verify(pendingMapper).capture(eq(TASK_ID), eq(1L), any(LocalDateTime.class));
        order.verify(stagingMapper).deleteByPlatform(PLATFORM_ID);
        verify(pendingMapper).deleteResolved(PLATFORM_ID);
    }

    @Test
    @DisplayName("轮次台账：delete_skipped 与当时的 guard_ratio 必须落盘可回查（3.13.1）")
    void writesLedgerWithGuardRatioAndSkipFlag() {
        stubCounts(10L, 0L, 0L, 2000L, 0L);
        stubNoOrgGroupChannelStale();
        when(deviceAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(1000L);

        service.finish(TASK_ID);

        ArgumentCaptor<SyncRound> captor = ArgumentCaptor.forClass(SyncRound.class);
        verify(roundRecorder).complete(captor.capture());
        SyncRound round = captor.getValue();
        assertEquals(TASK_ID, round.getTaskId());
        assertEquals(1, round.getDeleteSkipped());
        assertEquals(0, new BigDecimal("0.2").compareTo(round.getGuardRatio()));
        assertEquals("DELETE_SKIPPED", round.getResult());
    }

    private void stubNoOrgGroupChannelStale() {
        when(orgAlignment.countWouldStale(anyLong(), anyLong(), anyString())).thenReturn(0L);
        when(groupAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);
        when(channelAlignment.countWouldStale(anyLong(), anyLong())).thenReturn(0L);
    }

    private void stubCounts(long staged, long org, long group, long device, long channel) {
        when(stagingMapper.countByTask(TASK_ID)).thenReturn(staged);
        when(stagingMapper.countInboundOrg(PLATFORM_ID)).thenReturn(org);
        when(stagingMapper.countInboundGroup(PLATFORM_ID)).thenReturn(group);
        when(stagingMapper.countInboundDevice(PLATFORM_ID)).thenReturn(device);
        when(stagingMapper.countInboundChannel(PLATFORM_ID)).thenReturn(channel);
    }

    private static Platform platform(String guardRatio) {
        Platform platform = new Platform();
        platform.setId(PLATFORM_ID);
        platform.setCode(PLATFORM_CODE);
        platform.setName("下级平台");
        platform.setTenantId(1L);
        platform.setDeleteGuardRatio(new BigDecimal(guardRatio));
        return platform;
    }
}
