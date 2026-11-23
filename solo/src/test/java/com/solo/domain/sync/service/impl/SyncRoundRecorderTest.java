package com.solo.domain.sync.service.impl;

import com.solo.domain.platform.model.Platform;
import com.solo.domain.sync.model.SyncRound;
import com.solo.domain.sync.model.SyncTask;
import com.solo.infra.mapper.SyncRoundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次台账写入（3.13.1）的行为用例。
 *
 * <p>重点是"台账是旁证"这条语义：写失败绝不能让一次已经合并成功的同步整轮回滚，
 * 所以异常必须在这里被吞掉。</p>
 */
class SyncRoundRecorderTest {

    private SyncRoundMapper roundMapper;
    private SyncRoundRecorder recorder;

    @BeforeEach
    void setUp() {
        roundMapper = mock(SyncRoundMapper.class);
        recorder = new SyncRoundRecorder(roundMapper);
    }

    @Test
    @DisplayName("开轮：落一行 RUNNING，计数先置 0，beginTime 取任务的开始时间")
    void startWritesRunningRow() {
        recorder.start(task(), platform());

        ArgumentCaptor<SyncRound> captor = ArgumentCaptor.forClass(SyncRound.class);
        verify(roundMapper).insert(captor.capture());
        SyncRound round = captor.getValue();
        assertEquals(SyncRound.RUNNING, round.getStatus());
        assertEquals(9001L, round.getTaskId());
        assertEquals("T7-1", round.getTaskSn());
        assertEquals(7L, round.getPlatformId());
        assertEquals("下级平台", round.getPlatformName());
        assertEquals(0, round.getDevRecv());
        assertNotNull(round.getBeginTime());
    }

    @Test
    @DisplayName("开轮写失败 → 只吞掉告警，不抛给调用方（否则整轮同步被台账拖垮）")
    void startSwallowsFailure() {
        when(roundMapper.insert(any(SyncRound.class))).thenThrow(new RuntimeException("表还没建"));

        recorder.start(task(), platform());
    }

    @Test
    @DisplayName("收尾：按 taskId 更新，未给 status 默认 DONE")
    void completeDefaultsToDone() {
        SyncRound patch = new SyncRound();
        patch.setTaskId(9001L);
        patch.setDevInsert(3);

        recorder.complete(patch);

        ArgumentCaptor<SyncRound> captor = ArgumentCaptor.forClass(SyncRound.class);
        verify(roundMapper).update(captor.capture(), any());
        assertEquals(SyncRound.DONE, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getEndTime());
        assertEquals(3, captor.getValue().getDevInsert());
    }

    @Test
    @DisplayName("失败：FAILED + 错误码 + 摘要，仍走同一条按 taskId 的更新")
    void failMarksFailedRound() {
        recorder.fail(9001L, "SYNC_STALL_TIMEOUT", "连续 75000ms 无新批次");

        ArgumentCaptor<SyncRound> captor = ArgumentCaptor.forClass(SyncRound.class);
        verify(roundMapper).update(captor.capture(), any());
        SyncRound round = captor.getValue();
        assertEquals(SyncRound.FAILED, round.getStatus());
        assertEquals("SYNC_STALL_TIMEOUT", round.getErrorCode());
        assertEquals("连续 75000ms 无新批次", round.getErrorMsg());
    }

    @Test
    @DisplayName("没有 taskId 的补丁直接忽略，不会去更新全表")
    void completeIgnoresPatchWithoutTaskId() {
        recorder.complete(new SyncRound());

        verify(roundMapper, org.mockito.Mockito.never()).update(any(SyncRound.class), any());
    }

    private static SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(9001L);
        task.setTaskSn("T7-1");
        task.setPlatformId(7L);
        task.setTenantId(1L);
        return task;
    }

    private static Platform platform() {
        Platform platform = new Platform();
        platform.setId(7L);
        platform.setName("下级平台");
        return platform;
    }
}
