package com.solo.domain.share.support;

import com.solo.domain.share.model.PublishEventType;
import com.solo.domain.share.model.ShareRefType;

import java.util.List;
import java.util.Map;

/**
 * 待写入 outbox 的一条出站事件（内存形态）。
 *
 * <p>与 {@code OutboxEvent} 分开：实体带 id/seq/status 等落库字段，而调用方只关心
 * "哪个节点发生了什么"。序号由 {@code ShareOutboxWriter} 在同一把平台锁里统一分配，
 * 调用方传不进来也就不可能乱序。</p>
 *
 * @param eventType   ADD / UPDATE / DELETE / REFRESH
 * @param refType     GROUP / CHANNEL / DEVICE
 * @param refId       资源主键（REFRESH 为 null）
 * @param refCode     资源原始码（REFRESH 用 "*"）
 * @param exposedCode 节点暴露码（REFRESH 为 null）
 * @param payload     组装报文所需快照
 */
public record ShareEvent(String eventType, String refType, Long refId,
                         String refCode, String exposedCode, Map<String, Object> payload) {

    /** 归并事件：让上级重新拉一次目录（C18 的降级形态） */
    public static ShareEvent refresh(String reason) {
        return new ShareEvent(PublishEventType.REFRESH, ShareRefType.GROUP, null, "*", null,
                Map.of("kind", "REFRESH", "reason", reason == null ? "" : reason));
    }

    /**
     * 批量删除：C12 第 3 步的"一个平台一条 DELETE"。
     *
     * <p>payload 里的 {@code items} 按<b>叶子 → 根</b>排好序（通道在前、分组在后且深的在前）：
     * 上级对目录是增量 upsert，先删父节点会让子节点变成孤儿。</p>
     */
    public static ShareEvent batchDelete(List<Map<String, Object>> items) {
        return new ShareEvent(PublishEventType.DELETE, ShareRefType.CHANNEL, null, "*", null,
                Map.of("kind", "CHANNEL_BATCH", "items", items == null ? List.of() : items));
    }
}
