package com.solo.domain.resource.service;

import com.solo.api.resource.dto.ObservabilitySummaryResponse;

/**
 * 运行自检（M6）：把散在 Redis 会话、ZLM 节点、播放记录里的运行期状态聚合成一个只读快照，
 * 供"运行自检"页展示。不做清理、不改状态。
 */
public interface ObservabilityService {

    ObservabilitySummaryResponse summary();
}
