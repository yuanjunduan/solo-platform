package com.solo.domain.sync.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.service.CanonicalChannelService;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.spi.CatalogSession;
import com.solo.domain.sync.spi.CatalogSink;
import com.solo.domain.sync.spi.InboundAdapter;
import com.solo.domain.sync.spi.InboundAdapterRegistry;
import com.solo.domain.sync.spi.PlatformConn;
import com.solo.domain.sync.support.InboundScaffoldService;
import com.solo.domain.sync.support.SyncCooldownStore;
import com.solo.domain.sync.support.SyncSessionRegistry;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三段会话（start → synching → finish）的端到端用例。
 *
 * <p>用一个假适配器把"对端推批次"演出来：{@code trigger} 建任务并 open 会话，
 * 测试替适配器回调 sink 的 {@code onBatch}/{@code onComplete}/{@code onError}，
 * 然后断言服务层把批次写进暂存、把收齐交给合并、把失败落成 FAILED + 冷却。</p>
 *
 * <p>四条"不写在适配器里"的职责是重点：互斥（DB 唯一键翻译）、冷却（只在真正的运行期失败时进）、
 * 会话摘除、暂存放弃。</p>
 */
class InboundSyncServiceImplTest {

    private static final long PLATFORM_ID = 7L;
    private static final String PLATFORM_CODE = "34020000002000000001";

    private PlatformMapper platformMapper;
    private SyncTaskMapper taskMapper;
    private SyncStagingMapper stagingMapper;
    private InboundScaffoldService scaffoldService;
    private SyncCooldownStore cooldownStore;
    private SyncSessionRegistry sessionRegistry;
    private SyncStagingWriter stagingWriter;
    private SyncMergeService mergeService;
    private CanonicalChannelService canonicalChannelService;
    private SyncRoundRecorder roundRecorder;
    private SyncOrgPendingMapper pendingMapper;

    private CapturingAdapter adapter;
    private FakeSession session;
    private InboundSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        platformMapper = mock(PlatformMapper.class);
        taskMapper = mock(SyncTaskMapper.class);
        stagingMapper = mock(SyncStagingMapper.class);
        scaffoldService = mock(InboundScaffoldService.class);
        cooldownStore = mock(SyncCooldownStore.class);
        sessionRegistry = mock(SyncSessionRegistry.class);
        stagingWriter = mock(SyncStagingWriter.class);
        mergeService = mock(SyncMergeService.class);
        canonicalChannelService = mock(CanonicalChannelService.class);
        roundRecorder = mock(SyncRoundRecorder.class);
        pendingMapper = mock(SyncOrgPendingMapper.class);

        session = new FakeSession();
        adapter = new CapturingAdapter(session);
        InboundAdapterRegistry registry = new InboundAdapterRegistry(List.of(adapter));

        SyncSettings settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "stallTimeoutMs", 75_000L);
        ReflectionTestUtils.setField(settings, "cooldownExtraMs", 10_000L);

        service = new InboundSyncServiceImpl(platformMapper, taskMapper, stagingMapper, registry,
                scaffoldService, cooldownStore, sessionRegistry, stagingWriter, mergeService,
                canonicalChannelService, roundRecorder, pendingMapper, settings);

        when(platformMapper.selectById(PLATFORM_ID)).thenReturn(platform(Platform.STATUS_ONLINE));
        when(scaffoldService.ensure(any(Platform.class)))
                .thenReturn(new InboundScaffoldService.Scaffold(PLATFORM_CODE, 11L, 12L, 13L));
        when(taskMapper.updateIfActive(any(SyncTask.class))).thenReturn(1);
    }

    @Test
    @DisplayName("全链路：trigger 建 RUNNING 任务 → 批次进暂存 → onComplete 交给合并并摘会话")
    void runsStartSynchingFinishChain() {
        Long taskId = service.trigger(PLATFORM_ID, null);

        ArgumentCaptor<SyncTask> inserted = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).insert(inserted.capture());
        SyncTask task = inserted.getValue();
        assertEquals(taskId, task.getId());
        assertEquals(PLATFORM_ID, task.getPlatformId());
        assertEquals(SyncTaskStatus.RUNNING, task.getStatus());
        assertEquals("MANUAL", task.getTriggerType());
        assertEquals(0, task.getProgress());
        assertNotNull(task.getBeginTime());
        assertTrue(task.getTaskSn().startsWith("T" + PLATFORM_ID + "-"), task.getTaskSn());

        verify(sessionRegistry).put(taskId, session);
        assertEquals(PLATFORM_CODE, adapter.openedConn.code());

        CatalogBatch batch = new CatalogBatch(List.of(), 3, 3);
        adapter.sink.onBatch(batch);
        verify(stagingWriter).append(task, PLATFORM_CODE, batch);

        adapter.sink.onComplete();
        verify(mergeService).finish(taskId);
        // 收尾后跑一次 L1 同源自动关联（3.10.3）；关联失败不影响轮次成功
        verify(canonicalChannelService).afterMerge(PLATFORM_ID);
        verify(sessionRegistry).close(taskId);
        verify(cooldownStore, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("同源自动关联抛错不把已成功的轮次改成 FAILED")
    void autoLinkFailureDoesNotFailRound() {
        service.trigger(PLATFORM_ID, null);
        when(canonicalChannelService.afterMerge(PLATFORM_ID)).thenThrow(new RuntimeException("boom"));

        adapter.sink.onComplete();

        verify(mergeService).finish(anyLong());
        verify(taskMapper, never()).updateIfActive(any(SyncTask.class));
        verify(sessionRegistry).close(anyLong());
    }

    @Test
    @DisplayName("停用的平台不允许发起同步")
    void rejectsDisabledPlatform() {
        when(platformMapper.selectById(PLATFORM_ID)).thenReturn(platform(Platform.STATUS_DISABLED));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.trigger(PLATFORM_ID, null));

        assertTrue(ex.getMessage().contains("禁用"), ex.getMessage());
        verify(taskMapper, never()).insert(any(SyncTask.class));
    }

    @Test
    @DisplayName("已有活跃轮次 → SYNC_ALREADY_RUNNING，不再建任务")
    void rejectsWhenRoundAlreadyActive() {
        SyncTask active = new SyncTask();
        active.setTaskSn("T7-1");
        when(taskMapper.selectActiveByPlatform(PLATFORM_ID)).thenReturn(active);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.trigger(PLATFORM_ID, null));

        assertTrue(ex.getMessage().startsWith("SYNC_ALREADY_RUNNING"), ex.getMessage());
        verify(taskMapper, never()).insert(any(SyncTask.class));
    }

    @Test
    @DisplayName("并发触发被唯一键挡下 → 翻译成 SYNC_ALREADY_RUNNING")
    void translatesDuplicateKeyAsAlreadyRunning() {
        when(taskMapper.insert(any(SyncTask.class))).thenThrow(new DuplicateKeyException("uk_active_platform"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.trigger(PLATFORM_ID, "MANUAL"));

        assertTrue(ex.getMessage().startsWith("SYNC_ALREADY_RUNNING"), ex.getMessage());
        verify(sessionRegistry, never()).put(anyLong(), any(CatalogSession.class));
    }

    @Test
    @DisplayName("open 前置条件失败（对端未注册）→ 任务落 FAILED 但不进冷却，配置改完即可重试")
    void openFailureDoesNotStartCooldown() {
        adapter.openFailure = new ServiceException("SYNC_PEER_NOT_REGISTERED: 对端未注册");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.trigger(PLATFORM_ID, null));

        assertTrue(ex.getMessage().startsWith("SYNC_PEER_NOT_REGISTERED"), ex.getMessage());

        ArgumentCaptor<SyncTask> patch = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateIfActive(patch.capture());
        assertEquals(SyncTaskStatus.FAILED, patch.getValue().getStatus());
        assertEquals("SYNC_PEER_NOT_REGISTERED", patch.getValue().getErrorCode());
        verify(cooldownStore, never()).markFailed(anyLong());
        verify(sessionRegistry, never()).put(anyLong(), any(CatalogSession.class));
        verify(stagingMapper).deleteHistory(eq(PLATFORM_ID), anyLong());
    }

    @Test
    @DisplayName("适配器报错 → FAILED + 冷却 + 摘会话 + 保留本轮现场只清历史")
    void adapterErrorFailsAndCoolsDown() {
        service.trigger(PLATFORM_ID, null);

        adapter.sink.onError("SIP_TIMEOUT", "对端超时未回目录");

        ArgumentCaptor<SyncTask> patch = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateIfActive(patch.capture());
        assertEquals(SyncTaskStatus.FAILED, patch.getValue().getStatus());
        assertEquals("SIP_TIMEOUT", patch.getValue().getErrorCode());
        assertEquals("对端超时未回目录", patch.getValue().getErrorMsg());
        assertNotNull(patch.getValue().getEndTime());
        verify(cooldownStore).markFailed(PLATFORM_ID);
        verify(sessionRegistry).close(anyLong());
        verify(stagingMapper).deleteHistory(eq(PLATFORM_ID), anyLong());
    }

    @Test
    @DisplayName("收齐后合并失败（G1 空批）→ 轮次 FAILED，错误码从异常前缀解析")
    void mergeFailureFailsRoundAndCoolsDown() {
        service.trigger(PLATFORM_ID, null);
        org.mockito.Mockito.doThrow(new ServiceException("SYNC_EMPTY_STAGING: 本轮收到 0 条"))
                .when(mergeService).finish(anyLong());

        adapter.sink.onComplete();

        ArgumentCaptor<SyncTask> patch = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateIfActive(patch.capture());
        assertEquals(SyncTaskStatus.FAILED, patch.getValue().getStatus());
        assertEquals("SYNC_EMPTY_STAGING", patch.getValue().getErrorCode());
        verify(cooldownStore).markFailed(PLATFORM_ID);
        verify(sessionRegistry).close(anyLong());
    }

    @Test
    @DisplayName("取消：活跃轮次置 CANCELLED、摘会话、保留本轮现场只清历史")
    void cancelMarksCancelledAndDropsStaging() {
        SyncTask task = new SyncTask();
        task.setId(9001L);
        task.setPlatformId(PLATFORM_ID);
        task.setStatus(SyncTaskStatus.RUNNING);
        when(taskMapper.selectById(9001L)).thenReturn(task);

        service.cancel(9001L);

        ArgumentCaptor<SyncTask> patch = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateIfActive(patch.capture());
        assertEquals(SyncTaskStatus.CANCELLED, patch.getValue().getStatus());
        assertEquals("SYNC_CANCELLED", patch.getValue().getErrorCode());
        verify(sessionRegistry).close(9001L);
        verify(stagingMapper).deleteHistory(PLATFORM_ID, 9001L);
    }

    @Test
    @DisplayName("已结束的轮次再取消 → 什么都不做")
    void cancelIgnoresFinishedRound() {
        SyncTask task = new SyncTask();
        task.setId(9001L);
        task.setStatus(SyncTaskStatus.SUCCESS);
        when(taskMapper.selectById(9001L)).thenReturn(task);

        service.cancel(9001L);

        verify(taskMapper, never()).updateIfActive(any(SyncTask.class));
        verify(sessionRegistry, never()).close(anyLong());
    }

    @Test
    @DisplayName("无进展超时兜底：所有活跃轮次置 FAILED + 冷却，返回清理条数")
    void sweepTimeoutFailsStalledRounds() {
        SyncTask first = new SyncTask();
        first.setId(9001L);
        first.setPlatformId(PLATFORM_ID);
        first.setStatus(SyncTaskStatus.RUNNING);
        SyncTask second = new SyncTask();
        second.setId(9002L);
        second.setPlatformId(8L);
        second.setStatus(SyncTaskStatus.SYNCHING);
        when(taskMapper.selectList(any())).thenReturn(List.of(first, second));

        int swept = service.sweepTimeout();

        assertEquals(2, swept);
        verify(taskMapper, times(2)).updateIfActive(any(SyncTask.class));
        verify(cooldownStore).markFailed(PLATFORM_ID);
        verify(cooldownStore).markFailed(8L);
        verify(sessionRegistry).close(9001L);
        verify(sessionRegistry).close(9002L);
    }

    @Test
    @DisplayName("会话先完成、后登记 → 登记时补摘，句柄不残留")
    void closesSessionWhenCompletionArrivesBeforeRegistration() {
        // open 里同步回调 onComplete，模拟对端在 trigger 返回前就把目录推完
        adapter.completeOnOpen = true;

        service.trigger(PLATFORM_ID, null);

        verify(mergeService).finish(anyLong());
        // 一次来自 onComplete 的 finally，一次来自 attachSession 发现 done 的补摘
        verify(sessionRegistry, times(2)).close(anyLong());
    }

    private static Platform platform(String status) {
        Platform platform = new Platform();
        platform.setId(PLATFORM_ID);
        platform.setCode(PLATFORM_CODE);
        platform.setName("下级平台");
        platform.setTenantId(1L);
        platform.setStatus(status);
        platform.setProtocol("SIP_GB28181");
        platform.setSyncProtocol("SIP_GB28181");
        return platform;
    }

    /** 假适配器：记录 open 参数，把 sink 暴露给测试用例驱动 */
    private static final class CapturingAdapter implements InboundAdapter {

        private final CatalogSession session;
        private PlatformConn openedConn;
        private CatalogSink sink;
        private RuntimeException openFailure;
        private boolean completeOnOpen;

        private CapturingAdapter(CatalogSession session) {
            this.session = session;
        }

        @Override
        public String protocol() {
            return "SIP_GB28181";
        }

        @Override
        public CatalogSession open(PlatformConn conn, CatalogSink catalogSink) {
            this.openedConn = conn;
            this.sink = catalogSink;
            if (openFailure != null) {
                throw openFailure;
            }
            if (completeOnOpen) {
                catalogSink.onComplete();
            }
            return session;
        }
    }

    private static final class FakeSession implements CatalogSession {

        private int closed;

        @Override
        public String ref() {
            return "34020000002000000001";
        }

        @Override
        public void close() {
            closed++;
        }
    }
}
