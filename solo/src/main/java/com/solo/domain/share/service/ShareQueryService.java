package com.solo.domain.share.service;

import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.api.share.dto.ShareQueryRequest;
import com.solo.api.share.dto.ShareView;
import com.solo.common.PageResult;
import com.solo.domain.share.model.OutGroup;
import com.solo.domain.share.model.OutShare;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.domain.share.model.PublishRound;
import com.solo.infra.mapper.projection.OutboxStatusCount;

import java.util.List;

/**
 * 共享三页的只读查询（4.2 / 3.9.6）。
 *
 * <p>与 {@link ShareComposeService} 分开：那边只写、这边只读。
 * 写方法都在事务里改状态并发出站事件，读方法绝不该被误包进写事务，
 * 否则一次分页查询会跟着共享变更一起拿平台锁。</p>
 */
public interface ShareQueryService {

    /**
     * 全部上级平台 + 汇总卡片。
     *
     * <p>汇总状态取"最差"（{@code ShareStatusMerge}）：9 条 SYNCED + 1 条 DEAD 必须显示 DEAD，
     * 否则用户会在毫无预警的情况下丢掉一个通道。</p>
     */
    List<ShareView.Target> listTargets();

    /** 该上级的虚拟组织树（扁平列表，前端按 parentId 组树） */
    List<OutGroup> listGroups(Long platformId);

    /** 共享项分页（含平台名/组名/资源名） */
    PageResult<OutShare> pageItems(ShareQueryRequest.Item query);

    /** 编排页"选择资源"：分页通道并回填对本平台的共享/剔除/暴露码 */
    PageResult<ShareView.ChannelOption> pageChannelOptions(Long platformId, ResourcePageRequest query);

    /** 出站事件分页 */
    PageResult<OutboxEvent> pageOutbox(ShareQueryRequest.Outbox query);

    /** 出站事件按状态计数（推送记录页顶部四个数字） */
    List<OutboxStatusCount> outboxCounts(Long platformId);

    /** 推送轮次分页 */
    PageResult<PublishRound> pageRounds(ShareQueryRequest.Round query);

    /** 轮次详情（推送记录页的"这一轮到底发了什么"） */
    PublishRound roundDetail(Long id);
}
