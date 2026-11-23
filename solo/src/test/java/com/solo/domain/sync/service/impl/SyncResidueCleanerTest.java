package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.support.SyncSessionRegistry;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三道残留清理里的后两道（3.13.4）：启动补刀 + 运行期过期回收。
 *
 * <p>两条硬要求都在这里钉住：开关关闭时"一行不动"，以及任何异常都必须被吞掉——
 * 定时任务抛一次未捕获异常，后续调度就永久停了。</p>
 */
class SyncResidueCleanerTest {

    private static final long PLATFORM_ID = 7L;

    private SyncStagingMapper stagingMapper;
    private SyncTaskMapper taskMapper;
    private SyncSessionRegistry sessionRegistry;
    private SyncSettings settings;
    private SyncResidueCleaner cleaner;

    @BeforeEach
    void setUp() {
        stagingMapper = mock(SyncStagingMapper.class);
        taskMapper = mock(SyncTaskMapper.class);
        sessionRegistry = mock(SyncSessionRegistry.class);
        settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "residueStartupClean", true);
        ReflectionTestUtils.setField(settings, "residueRetentionHours", 24L);
        cleaner = new SyncResidueCleaner(stagingMapper, taskMapper, sessionRegistry, settings);
    }

    @Test
    @DisplayName("启动清理开关关闭 → 一行不动（多实例滚动重启必须能关掉）")
    void startupCleanSkipsWhenDisabled() {
        ReflectionTestUtils.setField(settings, "residueStartupClean", false);

        long cleaned = cleaner.cleanStartupLeftover();

        assertEquals(0, cleaned);
        verifyNoInteractions(stagingMapper);
    }

    @Test
    @DisplayName("启动补刀：先删孤儿，再对'活跃但无会话'的轮次逐个清暂存；有会话的不碰")
    void startupCleanRemovesOrphansAndDeadSessions() {
        when(stagingMapper.deleteOrphans()).thenReturn(7);
        SyncTask alive = task(9001L);
        SyncTask dead = task(9002L);
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(alive, dead));
        when(sessionRegistry.contains(9001L)).thenReturn(true);
        when(sessionRegistry.contains(9002L)).thenReturn(false);
        when(stagingMapper.deleteByTask(9002L)).thenReturn(3);

        long cleaned = cleaner.cleanStartupLeftover();

        assertEquals(10, cleaned);
        verify(stagingMapper, never()).deleteByTask(9001L);
        verify(stagingMapper).deleteByTask(9002L);
    }

    @Test
    @DisplayName("启动补刀失败 → 只告警返回 0，不能把异常抛给调度器")
    void startupCleanSwallowsFailure() {
        when(stagingMapper.deleteOrphans()).thenThrow(new RuntimeException("表被锁"));

        assertEquals(0, cleaner.cleanStartupLeftover());
    }

    @Test
    @DisplayName("运行期过期清理：按批删到不足一批为止（5000、5000、10 共 3 次）")
    void expiredCleanBatchesUntilShort() {
        when(stagingMapper.deleteExpiredBatch(any(LocalDateTime.class), anyInt()))
                .thenReturn(5_000, 5_000, 10);

        long cleaned = cleaner.cleanExpired();

        assertEquals(10_010, cleaned);
        verify(stagingMapper, times(3)).deleteExpiredBatch(any(LocalDateTime.class), eq(5_000));
    }

    @Test
    @DisplayName("运行期过期清理失败 → 吞掉异常，下一轮继续")
    void expiredCleanSwallowsFailure() {
        when(stagingMapper.deleteExpiredBatch(any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("超时"));

        assertEquals(0, cleaner.cleanExpired());
    }

    private static SyncTask task(Long id) {
        SyncTask task = new SyncTask();
        task.setId(id);
        task.setPlatformId(PLATFORM_ID);
        task.setTaskSn("T7-" + id);
        return task;
    }
}
