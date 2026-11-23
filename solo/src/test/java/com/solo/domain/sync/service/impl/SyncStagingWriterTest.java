package com.solo.domain.sync.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.sync.model.CatalogItem;
import com.solo.domain.sync.model.CatalogItemType;
import com.solo.domain.sync.model.SyncItemType;
import com.solo.domain.sync.model.SyncStaging;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * synching 阶段写暂存的行为用例。
 *
 * <p>重点在三条容易在联调里才发现的地方：<b>分页</b>（一次塞几千行会撞 SQL 参数上限）、
 * <b>通道补载体设备码</b>（不补则合并阶段内连接把它们全丢掉）、
 * <b>死轮次拒写</b>（否则取消后还在堆数据）。</p>
 */
class SyncStagingWriterTest {

    private SyncStagingMapper stagingMapper;
    private SyncTaskMapper taskMapper;
    private SyncSnapshotWriter snapshotWriter;
    private SyncStagingWriter writer;
    private SyncTask task;

    @BeforeEach
    void setUp() {
        stagingMapper = mock(SyncStagingMapper.class);
        taskMapper = mock(SyncTaskMapper.class);
        snapshotWriter = mock(SyncSnapshotWriter.class);

        SyncSettings settings = new SyncSettings();
        ReflectionTestUtils.setField(settings, "stagingPageSize", 2);

        writer = new SyncStagingWriter(stagingMapper, taskMapper, snapshotWriter, settings);

        task = new SyncTask();
        task.setId(9001L);
        task.setPlatformId(7L);
        task.setTenantId(1L);
        task.setStatus(SyncTaskStatus.RUNNING);

        when(taskMapper.updateIfActive(any(SyncTask.class))).thenReturn(1);
    }

    @Test
    @DisplayName("通道的 device_code 为空 → 补平台载体设备码；非通道项不补")
    void fallsBackDeviceCodeToPlatformForChannel() {
        CatalogItem channel = item("34020000001320000001", CatalogItemType.CHANNEL);
        CatalogItem device = item("34020000001320000002", CatalogItemType.DEVICE);

        writer.append(task, "34020000002000000001", new CatalogBatch(List.of(channel, device), 2, 2));

        List<SyncStaging> rows = captureRows(1);
        assertEquals("34020000002000000001", rows.get(0).getDeviceCode());
        assertNull(rows.get(1).getDeviceCode());
    }

    @Test
    @DisplayName("对端已给 device_code → 保留对端值，不被平台码覆盖")
    void keepsExplicitDeviceCode() {
        CatalogItem channel = item("34020000001320000001", CatalogItemType.CHANNEL);
        channel.setDeviceCode("34020000001320000099");

        writer.append(task, "34020000002000000001", new CatalogBatch(List.of(channel), 1, 1));

        assertEquals("34020000001320000099", captureRows(1).get(0).getDeviceCode());
    }

    @Test
    @DisplayName("超过一页 → 按 pageSize 分多次写入")
    void splitsIntoPages() {
        List<CatalogItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item("3402000000132000000" + i, CatalogItemType.CHANNEL));
        }

        writer.append(task, "34020000002000000001", new CatalogBatch(items, 5, 5));

        // 5 行 / 每页 2 行 → 2 + 2 + 1
        ArgumentCaptor<List<SyncStaging>> captor = ArgumentCaptor.forClass(List.class);
        verify(stagingMapper, times(3)).insertBatch(captor.capture());
        List<Integer> sizes = captor.getAllValues().stream().map(List::size).toList();
        assertEquals(List.of(2, 2, 1), sizes);
    }

    @Test
    @DisplayName("进度以库里计数为准，expectedTotal 折算百分比且封顶 99")
    void refreshesProgressFromDbCounts() {
        when(stagingMapper.countByTaskAndType(9001L, SyncItemType.ORG)).thenReturn(3L);
        when(stagingMapper.countByTaskAndType(9001L, SyncItemType.GROUP)).thenReturn(0L);
        when(stagingMapper.countByTaskAndType(9001L, SyncItemType.DEVICE)).thenReturn(1L);
        when(stagingMapper.countByTaskAndType(9001L, SyncItemType.CHANNEL)).thenReturn(0L);

        writer.append(task, "34020000002000000001",
                new CatalogBatch(List.of(item("1", CatalogItemType.ORG)), 10, 1));

        ArgumentCaptor<SyncTask> captor = ArgumentCaptor.forClass(SyncTask.class);
        verify(taskMapper).updateIfActive(captor.capture());
        SyncTask patch = captor.getValue();
        assertEquals(SyncTaskStatus.SYNCHING, patch.getStatus());
        assertEquals(4, patch.getRecvTotal());
        assertEquals(3, patch.getOrgRecv());
        assertEquals(1, patch.getDevRecv());
        assertEquals(40, patch.getProgress());
        // 回填到入参 task，供同轮后续批次复用
        assertEquals(4, task.getRecvTotal());
    }

    @Test
    @DisplayName("轮次已结束（updateIfActive 返回 0）→ 抛 SYNC_NOT_ACTIVE")
    void rejectsWhenTaskNoLongerActive() {
        when(taskMapper.updateIfActive(any(SyncTask.class))).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> writer.append(task,
                "34020000002000000001", new CatalogBatch(List.of(item("1", CatalogItemType.ORG)), 1, 1)));
        assertTrue(ex.getMessage().startsWith("SYNC_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("编码或类型缺失的条目被丢弃，不写库")
    void skipsItemsWithoutCodeOrType() {
        CatalogItem noCode = new CatalogItem();
        noCode.setType(CatalogItemType.ORG);
        CatalogItem noType = new CatalogItem();
        noType.setCode("34020000001320000001");

        writer.append(task, "34020000002000000001", new CatalogBatch(List.of(noCode, noType), 2, 2));

        verify(stagingMapper, never()).insertBatch(anyList());
        verify(stagingMapper, never()).insertBatch(any());
    }

    private CatalogItem item(String code, CatalogItemType type) {
        CatalogItem item = new CatalogItem();
        item.setCode(code);
        item.setName("节点-" + code);
        item.setType(type);
        return item;
    }

    private List<SyncStaging> captureRows(int expectedCalls) {
        ArgumentCaptor<List<SyncStaging>> captor = ArgumentCaptor.forClass(List.class);
        verify(stagingMapper, times(expectedCalls)).insertBatch(captor.capture());
        return captor.getValue();
    }
}
