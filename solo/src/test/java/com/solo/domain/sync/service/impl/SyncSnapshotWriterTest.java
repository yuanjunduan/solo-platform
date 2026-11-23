package com.solo.domain.sync.service.impl;

import com.solo.domain.sync.model.SyncRoundSnapshot;
import com.solo.domain.sync.model.SyncStaging;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncRoundSnapshotMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 报文快照异步写入（3.13.2 / 3.13.3）的三条纪律。
 *
 * <p>关闭时零写入；开启时主链路只入队、落库在独立线程；
 * 队列满时丢弃而不是阻塞；收尾任务必须能独占预留的那一格。</p>
 */
class SyncSnapshotWriterTest {

    private static final long PLATFORM_ID = 7L;
    private static final long TASK_ID = 9001L;

    private SyncRoundSnapshotMapper snapshotMapper;
    private SyncSettings settings;
    private SyncSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(SyncRoundSnapshotMapper.class);
        settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "snapshotQueueSize", 8);
        ReflectionTestUtils.setField(settings, "snapshotKeepRounds", 2);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.destroy();
        }
    }

    @Test
    @DisplayName("默认关闭 → 批次与收尾都是零写入，连队列都不碰")
    void disabledWritesNothing() {
        ReflectionTestUtils.setField(settings, "snapshotEnabled", false);
        writer = new SyncSnapshotWriter(snapshotMapper, settings);

        writer.snapshotPage(page(3));
        writer.roundFinished(PLATFORM_ID);

        verifyNoInteractions(snapshotMapper);
    }

    @Test
    @DisplayName("开启 → 行在调用线程拷贝、落库在快照线程（借 staging 的雪花 id 对行）")
    void enabledWritesPageOnWorker() {
        ReflectionTestUtils.setField(settings, "snapshotEnabled", true);
        writer = new SyncSnapshotWriter(snapshotMapper, settings);
        List<SyncStaging> page = page(3);

        writer.snapshotPage(page);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SyncRoundSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper, timeout(3_000)).insertBatch(captor.capture());
        List<SyncRoundSnapshot> written = captor.getValue();
        assertEquals(3, written.size());
        assertEquals(100L, written.get(0).getId());
        assertEquals(TASK_ID, written.get(0).getTaskId());
        assertEquals("ORG", written.get(0).getItemType());
        // 入队即拷贝：之后改原对象不影响已入队数据
        page.get(0).setCode("MUTATED");
        assertEquals("C100", written.get(0).getCode());
    }

    @Test
    @DisplayName("轮次收尾 → 只保留最近 N 轮的 task_id，其余按 5 万一批删")
    void roundFinishedCleansBeyondKeep() {
        ReflectionTestUtils.setField(settings, "snapshotEnabled", true);
        writer = new SyncSnapshotWriter(snapshotMapper, settings);
        List<Long> keep = List.of(9002L, 9001L);
        when(snapshotMapper.selectRecentTaskIds(PLATFORM_ID, 2)).thenReturn(keep);
        when(snapshotMapper.deleteExcessBatch(eq(PLATFORM_ID), anyList(), anyInt())).thenReturn(0);

        writer.roundFinished(PLATFORM_ID);

        verify(snapshotMapper, timeout(3_000)).selectRecentTaskIds(PLATFORM_ID, 2);
        verify(snapshotMapper, timeout(3_000)).deleteExcessBatch(eq(PLATFORM_ID), eq(keep), eq(50_000));
    }

    @Test
    @DisplayName("队列满 → 丢弃新批次而不是阻塞同步；预留的一格仍留给收尾")
    void dropsWhenQueueIsFull() throws Exception {
        ReflectionTestUtils.setField(settings, "snapshotEnabled", true);
        ReflectionTestUtils.setField(settings, "snapshotQueueSize", 1); // 实际容量 = 1 + 1
        writer = new SyncSnapshotWriter(snapshotMapper, settings);

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        when(snapshotMapper.insertBatch(anyList())).thenAnswer(inv -> {
            entered.countDown();
            gate.await(5, TimeUnit.SECONDS);
            return 1;
        });

        writer.snapshotPage(page(1));   // 被工作线程立刻取走并卡在 insertBatch
        entered.await(5, TimeUnit.SECONDS);
        writer.snapshotPage(page(1));   // 占掉队列里第 1 格
        writer.snapshotPage(page(1));   // 剩余容量 1 < 2 → 丢弃

        gate.countDown();

        // 只有前两批真正落库（第三批被丢弃）
        verify(snapshotMapper, timeout(3_000).times(2)).insertBatch(anyList());
        verify(snapshotMapper, after(300).times(2)).insertBatch(anyList());

        // 收尾任务仍能入队（预留格子没被普通批次吃掉）
        writer.roundFinished(PLATFORM_ID);
        verify(snapshotMapper, timeout(3_000)).selectRecentTaskIds(PLATFORM_ID, 2);
    }

    @Test
    @DisplayName("落库异常 → 旁路丢弃只告警，不向上抛（不能影响同步）")
    void swallowsWriteFailure() {
        ReflectionTestUtils.setField(settings, "snapshotEnabled", true);
        writer = new SyncSnapshotWriter(snapshotMapper, settings);
        when(snapshotMapper.insertBatch(anyList())).thenThrow(new RuntimeException("唯一键冲突"));

        writer.snapshotPage(page(2));
        writer.roundFinished(PLATFORM_ID);

        verify(snapshotMapper, timeout(3_000)).insertBatch(anyList());
    }

    private static List<SyncStaging> page(int rows) {
        List<SyncStaging> list = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            SyncStaging row = new SyncStaging();
            row.setId(100L + i);
            row.setTenantId(1L);
            row.setTaskId(TASK_ID);
            row.setPlatformId(PLATFORM_ID);
            row.setItemType("ORG");
            row.setCode("C100");
            row.setCreateTime(LocalDateTime.now());
            list.add(row);
        }
        return list;
    }
}
