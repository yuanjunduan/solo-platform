package com.solo.domain.sync.spi;

import com.solo.domain.sync.model.CatalogItem;

import java.util.List;

/**
 * 适配器向上层投递目录的回调口（3.7.3 的 {@code CatalogListener} + {@code Progress} 合并版）。
 *
 * <p>三段会话里，适配器只负责 {@code synching} 段的"送数据"；{@code start}/{@code finish}、
 * 超时判定、冷却窗口、互斥锁都在同步服务里（C10：会话状态存 DB/Redis，不放进程内变量）。</p>
 */
public interface CatalogSink {

    /**
     * 收到一批。
     * <p><b>返回即视为该批已落库</b>（批级 ACK）：实现要在返回前把本批写进 {@code sync_staging}。
     * 抛异常 = 这一批失败，本轮随之失败（C14：staging 是主链路，写失败不能吞）。</p>
     */
    void onBatch(CatalogBatch batch);

    /** 本轮目录已收齐（对端告知的 SumNum 已到，或对端明确回空目录） */
    void onComplete();

    /**
     * 本轮在协议层失败（发送失败 / 对端异常 / 解析中断）。
     *
     * @param errorCode 大写短语，直接落 {@code sync_task.error_code}
     */
    void onError(String errorCode, String message);
}
