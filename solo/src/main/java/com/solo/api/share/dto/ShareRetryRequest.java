package com.solo.api.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 人工重试请求（推送记录页）。
 *
 * <p>只接受出站事件 id：前端按行勾选，后端按 id 精确重置，
 * 不做"按平台全部重试"——那会把不该重发的 PENDING 也一起放回队列。</p>
 */
@Data
@Schema(description = "出站事件重试请求")
public class ShareRetryRequest {

    @Schema(description = "出站事件 id 列表（outbox.id）")
    private List<Long> ids;
}
