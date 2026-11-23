package com.solo.openapi.service;

import com.solo.common.PageResult;
import com.solo.domain.event.model.EventSubscription;
import com.solo.openapi.request.OpenSubscriptionPageRequest;
import com.solo.openapi.request.OpenSubscriptionSaveRequest;

/**
 * 对外订阅管理端（6.4 / Q10）。
 *
 * <p>订阅由开放应用调 SSE 时自动创建（{@code uk_key(app_id, topic)} 兜住唯一性），
 * 管理端只负责三件事：看有哪些订阅、改 scope 与启停、把游标清零重来。
 * 不提供"手工新建/删除"——订阅的生命周期跟着应用走，凭空造一条没有 appKey 的订阅无法鉴权。</p>
 */
public interface OpenSubscriptionService {

    PageResult<EventSubscription> page(OpenSubscriptionPageRequest req);

    /**
     * 编辑 scope / 启停 / 备注。
     *
     * <p>两个 scope 字段为空即"不裁剪"，所以这里必须能把它们改回空（显式 SET）。</p>
     */
    void updateScope(OpenSubscriptionSaveRequest req);

    /**
     * 游标清零：等价于"从当前最大游标重新开始"，不是回放历史。
     * <p>用途是数据修复后让订户跳到最新位置；要补历史一律走快照接口。</p>
     */
    void resetCursor(Long id);
}
