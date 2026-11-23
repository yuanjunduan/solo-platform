package com.solo.domain.sync.service.impl;

import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncDeviceAlignmentMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import com.solo.infra.mapper.projection.PendingPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织缺失设备延迟重试（3.8.2）的四条时间规则。
 *
 * <p>这里只验证编排：什么时候让路、什么时候重走①②③④、什么时候该告警。
 * 真正的 SQL 由 {@code SyncAlignmentSqlTest} 守。</p>
 */
class OrgBackfillRetryServiceTest {

    private static final long PLATFORM_ID = 7L;

    private SyncOrgPendingMapper pendingMapper;
    private SyncDeviceAlignmentMapper deviceAlignment;
    private SyncTaskMapper taskMapper;
    private SyncSettings settings;
    private OrgBackfillRetryService service;

    @BeforeEach
    void setUp() {
        pendingMapper = mock(SyncOrgPendingMapper.class);
        deviceAlignment = mock(SyncDeviceAlignmentMapper.class);
        taskMapper = mock(SyncTaskMapper.class);
        settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "orgRetryDebounceSeconds", 30L);
        ReflectionTestUtils.setField(settings, "orgRetryMaxWaitMinutes", 10L);
        service = new OrgBackfillRetryService(pendingMapper, deviceAlignment, taskMapper, settings);
    }

    @Test
    @DisplayName("平台正在同步 → 直接让路，不碰归属（避免与 finish 抢同一行）")
    void retryYieldsWhilePlatformIsSyncing() {
        SyncTask active = new SyncTask();
        active.setId(9001L);
        active.setTaskSn("T7-1");
        when(taskMapper.selectActiveByPlatform(PLATFORM_ID)).thenReturn(active);

        int fixed = service.retry(PLATFORM_ID, LocalDateTime.now());

        assertEquals(0, fixed);
        verify(deviceAlignment, never()).refreshOrgFromPending(anyLong(), any(LocalDateTime.class));
        verify(pendingMapper, never()).deleteResolved(anyLong());
    }

    @Test
    @DisplayName("空闲平台 → 重走①②③④，出队后剩余为 0 即自然终止")
    void retryRefreshesThenDrainsQueue() {
        when(taskMapper.selectActiveByPlatform(PLATFORM_ID)).thenReturn(null);
        when(deviceAlignment.refreshOrgFromPending(anyLong(), any(LocalDateTime.class))).thenReturn(3);
        when(pendingMapper.deleteResolved(PLATFORM_ID)).thenReturn(2);
        when(pendingMapper.countByPlatform(PLATFORM_ID)).thenReturn(0L);

        int fixed = service.retry(PLATFORM_ID, LocalDateTime.now());

        assertEquals(3, fixed);
        verify(deviceAlignment).refreshOrgFromPending(anyLong(), any(LocalDateTime.class));
        verify(pendingMapper).deleteResolved(PLATFORM_ID);
        verify(pendingMapper, never()).selectOldest(anyLong());
    }

    @Test
    @DisplayName("仍归不了位 → 保持标记，等超上限才取最早登记时间算等待时长")
    void retryKeepsMarkerWhenStillMissing() {
        LocalDateTime now = LocalDateTime.now();
        when(taskMapper.selectActiveByPlatform(PLATFORM_ID)).thenReturn(null);
        when(pendingMapper.countByPlatform(PLATFORM_ID)).thenReturn(5L);
        when(pendingMapper.selectOldest(PLATFORM_ID)).thenReturn(now.minusMinutes(30));

        int fixed = service.retry(PLATFORM_ID, now);

        assertEquals(0, fixed);
        verify(pendingMapper).selectOldest(PLATFORM_ID);
    }

    @Test
    @DisplayName("扫描：一个平台报错不能拖累其他平台（定时任务不能整轮不跑）")
    void sweepIsolatesPerPlatformFailures() {
        PendingPlatform bad = platform(PLATFORM_ID);
        PendingPlatform good = platform(8L);
        when(pendingMapper.selectDuePlatforms(any(LocalDateTime.class))).thenReturn(List.of(bad, good));
        when(taskMapper.selectActiveByPlatform(PLATFORM_ID)).thenThrow(new RuntimeException("连接池超时"));
        when(taskMapper.selectActiveByPlatform(8L)).thenReturn(null);

        service.sweepDue();

        verify(deviceAlignment, never()).refreshOrgFromPending(eq(PLATFORM_ID), any(LocalDateTime.class));
        verify(deviceAlignment).refreshOrgFromPending(eq(8L), any(LocalDateTime.class));
        verify(pendingMapper).deleteResolved(8L);
    }

    private static PendingPlatform platform(Long id) {
        PendingPlatform p = new PendingPlatform();
        p.setPlatformId(id);
        return p;
    }
}
