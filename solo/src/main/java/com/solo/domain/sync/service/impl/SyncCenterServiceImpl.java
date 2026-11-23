package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.sync.dto.SyncTaskDetail;
import com.solo.api.sync.dto.SyncTaskQueryRequest;
import com.solo.common.PageResult;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.sync.model.PlatformCatalogVer;
import com.solo.domain.sync.model.SyncRecord;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.service.SyncCenterService;
import com.solo.infra.mapper.PlatformCatalogVerMapper;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncRecordMapper;
import com.solo.infra.mapper.SyncRoundMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步中心查询实现。
 *
 * <p>分页用 MyBatis-Plus 的 {@link Page}：轮次台账按 {@code (platform_id, create_time)} 有索引，
 * 深翻页不做优化（运维不会翻到第 100 页）。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncCenterServiceImpl implements SyncCenterService {

    /** 详情抽屉里最多带 200 条变更留痕：再多应该翻页看，不该塞进一个响应体 */
    private static final int DETAIL_RECORD_LIMIT = 200;

    private final SyncTaskMapper taskMapper;
    private final SyncRecordMapper recordMapper;
    private final SyncRoundMapper roundMapper;
    private final PlatformMapper platformMapper;
    private final PlatformCatalogVerMapper catalogVerMapper;

    @Override
    public PageResult<SyncTask> pageTasks(SyncTaskQueryRequest req) {
        LambdaQueryWrapper<SyncTask> w = new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getCreateTime)
                .orderByDesc(SyncTask::getId);
        if (req.getPlatformId() != null) {
            w.eq(SyncTask::getPlatformId, req.getPlatformId());
        }
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            w.eq(SyncTask::getStatus, req.getStatus().trim());
        }
        if (req.getTriggerType() != null && !req.getTriggerType().isBlank()) {
            w.eq(SyncTask::getTriggerType, req.getTriggerType().trim());
        }
        Page<SyncTask> page = taskMapper.selectPage(new Page<>(nvl(req.getPage()), nvl(req.getPageSize())), w);
        return new PageResult<>(page.getTotal(), page.getRecords());
    }

    @Override
    public SyncTaskDetail detail(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException(404, "同步任务不存在：" + taskId);
        }
        SyncTaskDetail detail = new SyncTaskDetail();
        detail.setTask(task);
        detail.setActive(SyncTaskStatus.isActive(task.getStatus()));
        detail.setRound(roundMapper.selectByTaskId(taskId));

        Platform platform = platformMapper.selectById(task.getPlatformId());
        if (platform != null) {
            detail.setPlatformName(platform.getName());
            detail.setPlatformCode(platform.getCode());
        }
        PlatformCatalogVer ver = catalogVerMapper.selectById(task.getPlatformId());
        if (ver != null) {
            detail.setLastFingerprint(ver.getFingerprint());
        }
        detail.setRecords(recordMapper.selectList(new LambdaQueryWrapper<SyncRecord>()
                .eq(SyncRecord::getTaskId, taskId)
                .orderByAsc(SyncRecord::getCreateTime)
                .last("limit " + DETAIL_RECORD_LIMIT)));
        return detail;
    }

    @Override
    public PageResult<SyncRecord> pageRecords(Long taskId, Long page, Long pageSize) {
        Page<SyncRecord> result = recordMapper.selectPage(new Page<>(nvl(page), nvl(pageSize)),
                new LambdaQueryWrapper<SyncRecord>()
                        .eq(SyncRecord::getTaskId, taskId)
                        .orderByAsc(SyncRecord::getCreateTime));
        List<SyncRecord> rows = result.getRecords();
        return new PageResult<>(result.getTotal(), rows);
    }

    private static long nvl(Long value) {
        return value == null || value <= 0 ? 1L : value;
    }
}
