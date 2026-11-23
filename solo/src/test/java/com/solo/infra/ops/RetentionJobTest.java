package com.solo.infra.ops;

import com.solo.infra.mapper.AuditLogMapper;
import com.solo.infra.mapper.ResPlayRecordMapper;
import com.solo.infra.mapper.SyncRecordMapper;
import com.solo.infra.mapper.SyncRoundMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留期回收单测（M7）：验证开关、分批、单批上限、失败隔离与删除顺序。
 */
class RetentionJobTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final ResPlayRecordMapper playRecordMapper = mock(ResPlayRecordMapper.class);
    private final SyncRecordMapper syncRecordMapper = mock(SyncRecordMapper.class);
    private final SyncTaskMapper syncTaskMapper = mock(SyncTaskMapper.class);
    private final SyncRoundMapper syncRoundMapper = mock(SyncRoundMapper.class);

    private RetentionSettings settings;

    @BeforeEach
    void setUp() {
        settings = new RetentionSettings();
        settings.setEnabled(true);
        settings.setAuditLogDays(365);
        settings.setPlayRecordDays(180);
        settings.setSyncDays(365);
        settings.setBatchSize(300);
        settings.setMaxBatchesPerRun(200);
    }

    @Test
    void shouldSkipAll_whenDisabled() {
        settings.setEnabled(false);

        int deleted = job().purgeExpired();

        assertThat(deleted).isZero();
        verify(auditLogMapper, never()).deleteBeforeBatch(any(), anyInt());
        verify(playRecordMapper, never()).deleteBeforeBatch(any(), anyInt());
    }

    @Test
    void shouldDeleteInBatches_untilBelowBatchSize() {
        when(auditLogMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(300, 300, 120);
        when(playRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncTaskMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRoundMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);

        int deleted = job().purgeExpired();

        assertThat(deleted).isEqualTo(720);
        verify(auditLogMapper, times(3)).deleteBeforeBatch(any(), anyInt());
    }

    @Test
    void shouldStopAtMaxBatchesPerRun() {
        settings.setMaxBatchesPerRun(2);
        when(auditLogMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(500);
        when(playRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncTaskMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRoundMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);

        int deleted = job().purgeExpired();

        assertThat(deleted).isEqualTo(1000);
        verify(auditLogMapper, times(2)).deleteBeforeBatch(any(), anyInt());
    }

    @Test
    void shouldSkipTable_whenRetentionDaysNonPositive() {
        settings.setAuditLogDays(0);
        when(playRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncTaskMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRoundMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);

        job().purgeExpired();

        verify(auditLogMapper, never()).deleteBeforeBatch(any(), anyInt());
    }

    @Test
    void shouldContinueOtherTables_whenOneTableFails() {
        when(auditLogMapper.deleteBeforeBatch(any(), anyInt()))
                .thenThrow(new IllegalStateException("lock wait timeout"));
        when(playRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(10);
        when(syncRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncTaskMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRoundMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);

        int deleted = job().purgeExpired();

        assertThat(deleted).isEqualTo(10);
        verify(playRecordMapper).deleteBeforeBatch(any(), anyInt());
    }

    @Test
    void shouldDeleteChildBeforeParent_forSyncTables() {
        when(auditLogMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(playRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRecordMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncTaskMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);
        when(syncRoundMapper.deleteBeforeBatch(any(), anyInt())).thenReturn(0);

        job().purgeExpired();

        InOrder order = inOrder(syncRecordMapper, syncTaskMapper, syncRoundMapper);
        order.verify(syncRecordMapper).deleteBeforeBatch(any(LocalDateTime.class), anyInt());
        order.verify(syncTaskMapper).deleteBeforeBatch(any(LocalDateTime.class), anyInt());
        order.verify(syncRoundMapper).deleteBeforeBatch(any(LocalDateTime.class), anyInt());
    }

    private RetentionJob job() {
        return new RetentionJob(settings, auditLogMapper, playRecordMapper,
                syncRecordMapper, syncTaskMapper, syncRoundMapper);
    }
}
