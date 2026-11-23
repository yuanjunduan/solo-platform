package com.solo.domain.resource.service;

import com.solo.api.resource.dto.PlayRecordQueryRequest;
import com.solo.common.PageResult;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResPlayRecord;

import java.util.List;

/**
 * 播放 / 回放请求记录（M6）。
 *
 * <p><b>可丢不可断</b>：写失败只 WARN，绝不让"点播成功但记录没写进去"变成"点播失败"。
 * 这与审计的定位一致；差别是审计走异步线程，本表在请求线程同步写（量小、要能立刻查到）。</p>
 */
public interface PlayRecordService {

    /** 起播成功：记一行 SUCCESS，streamId 用于后续闭合 */
    void recordSuccess(ResChannel row, String action, String streamId);

    /** 起播失败：记一行 FAILED + 原因（被拒/超时/来源离线都会走到这里） */
    void recordFailed(ResChannel row, String action, String failReason);

    /** 按流 id 闭合未结束的成功记录；返回闭合条数 */
    int closeByStreamId(String streamId);

    PageResult<ResPlayRecord> page(PlayRecordQueryRequest query);

    /** 导出用：按同一条件取，最多 limit 条，按开始时间升序 */
    List<ResPlayRecord> listForExport(PlayRecordQueryRequest query, int limit);
}
