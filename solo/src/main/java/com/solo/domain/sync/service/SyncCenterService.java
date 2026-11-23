package com.solo.domain.sync.service;

import com.solo.api.sync.dto.SyncTaskDetail;
import com.solo.api.sync.dto.SyncTaskQueryRequest;
import com.solo.common.PageResult;
import com.solo.domain.sync.model.SyncRecord;
import com.solo.domain.sync.model.SyncTask;

/**
 * 同步中心的只读查询。
 *
 * <p>与 {@link InboundSyncService} 分开：那个是状态机（会改数据），这个是台账查询（只读）。
 * 混在一个类里，"查列表"迟早被塞进"顺手触发一次"的逻辑。</p>
 */
public interface SyncCenterService {

    PageResult<SyncTask> pageTasks(SyncTaskQueryRequest req);

    /** @throws com.solo.common.ServiceException 任务不存在 */
    SyncTaskDetail detail(Long taskId);

    PageResult<SyncRecord> pageRecords(Long taskId, Long page, Long pageSize);
}
