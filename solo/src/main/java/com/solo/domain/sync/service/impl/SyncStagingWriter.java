package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.sync.model.CatalogItem;
import com.solo.domain.sync.model.SyncItemType;
import com.solo.domain.sync.model.SyncStaging;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * synching 阶段：把一批目录项写进暂存表，并刷新轮次进度。
 *
 * <p><b>这是主链路，没有任何降级分支</b>：写失败就往外抛，由上层把整轮判失败（C14）。
 * 与它相对的审计/快照才是"丢了也没关系"的旁路。</p>
 *
 * <p>落库前做一处归一化：GB28181 下级平台的通道没有设备层，{@code device_code} 为空时
 * 补成平台载体设备编码，否则合并阶段的内连接会把它们全部漏掉（表现为"通道数永远是 0"）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncStagingWriter {

    private final SyncStagingMapper stagingMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncSnapshotWriter snapshotWriter;
    private final SyncSettings settings;

    public void append(SyncTask task, String platformCode, CatalogBatch batch) {
        List<SyncStaging> rows = toRows(task, platformCode, batch.items());
        int pageSize = settings.getStagingPageSize();
        for (int from = 0; from < rows.size(); from += pageSize) {
            List<SyncStaging> page = rows.subList(from, Math.min(from + pageSize, rows.size()));
            stagingMapper.insertBatch(page);
            // 快照是旁路：默认关闭；开启时也只做一次浅拷贝入队，不参与主链路成败（3.13.2）
            snapshotWriter.snapshotPage(page);
        }
        refreshProgress(task, batch.expectedTotal());
    }

    /** 计数以库里为准（重发批次会覆盖而不是累加），所以每批都回查一次三个 count */
    private void refreshProgress(SyncTask task, Integer expectedTotal) {
        Long taskId = task.getId();
        int org = (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.ORG);
        int group = (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.GROUP);
        int device = (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.DEVICE);
        int channel = (int) stagingMapper.countByTaskAndType(taskId, SyncItemType.CHANNEL);
        int received = org + group + device + channel;

        SyncTask patch = new SyncTask();
        patch.setId(taskId);
        patch.setStatus(SyncTaskStatus.SYNCHING);
        patch.setRecvTotal(received);
        patch.setOrgRecv(org);
        patch.setDevRecv(device);
        patch.setChRecv(channel);
        if (expectedTotal != null && expectedTotal > 0) {
            patch.setExpectedTotal(expectedTotal);
            patch.setProgress(Math.min(99, received * 100 / expectedTotal));
        }
        if (taskMapper.updateIfActive(patch) == 0) {
            // 轮次已被取消/判失败：这批不能继续写，否则会往一个死掉的轮次里堆数据
            throw new ServiceException("SYNC_NOT_ACTIVE: 轮次已结束，丢弃后续批次 taskId=" + taskId);
        }
        task.setRecvTotal(received);
        task.setOrgRecv(org);
        task.setDevRecv(device);
        task.setChRecv(channel);
    }

    private static List<SyncStaging> toRows(SyncTask task, String platformCode, List<CatalogItem> items) {
        LocalDateTime now = LocalDateTime.now();
        List<SyncStaging> rows = new ArrayList<>(items.size());
        for (CatalogItem item : items) {
            if (item.getCode() == null || item.getCode().isBlank() || item.getType() == null) {
                continue;
            }
            SyncStaging row = new SyncStaging();
            row.setId(IdWorker.getId());
            row.setTenantId(task.getTenantId() == null ? 1L : task.getTenantId());
            row.setTaskId(task.getId());
            row.setPlatformId(task.getPlatformId());
            row.setItemType(item.itemType());
            row.setCode(item.getCode());
            row.setParentCode(blankToNull(item.getParentCode()));
            row.setName(item.getName() == null || item.getName().isBlank() ? item.getCode() : item.getName());
            row.setOnlineStatus(item.getStatus() == null ? "ON" : item.getStatus());
            row.setDeviceCode(blankToNull(item.getDeviceCode() == null && SyncItemType.CHANNEL.equals(item.itemType())
                    ? platformCode : item.getDeviceCode()));
            row.setStreamType(blankToNull(item.getStreamType()));
            row.setCapability(blankToNull(item.getCapability()));
            row.setMediaHint(blankToNull(item.getMediaHint()));
            row.setDeviceIp(blankToNull(item.getDeviceIp()));
            row.setVendor(blankToNull(item.getVendor()));
            row.setModel(blankToNull(item.getModel()));
            row.setGroupType(item.groupType());
            row.setOriginPlatformId(item.getOriginPlatformId() == null ? 0L : item.getOriginPlatformId());
            row.setOriginCode(item.getOriginCode() == null ? "" : item.getOriginCode());
            row.setOriginChain(item.getOriginChain() == null ? "" : item.getOriginChain());
            row.setHopCount(item.getHopCount() == null ? 0 : item.getHopCount());
            row.setCreateTime(now);
            rows.add(row);
        }
        return rows;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
