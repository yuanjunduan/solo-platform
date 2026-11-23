package com.solo.domain.sync.service;

/**
 * 入站同步的三段会话（3.8 3.8.2）。
 *
 * <p>{@code start} 与 {@code synching} 由适配器回调驱动（适配器不直接依赖本接口），
 * {@code finish} 在批次收齐时自动进入；状态机与超时兜底也在这里。</p>
 */
public interface InboundSyncService {

    /**
     * 发起一轮全量同步（start 段）。
     *
     * <p>同步返回轮次 id，真正的数据在适配器推送批次时写入。调用方会立刻拿到轮次以展示进度。</p>
     *
     * @param platformId  platform 表主键
     * @param triggerType MANUAL / SCHEDULED / NOTIFY
     * @return 轮次 id
     * @throws com.solo.common.ServiceException 平台忙 / 冷却中 / 对端未注册 / 协议不支持
     */
    Long trigger(Long platformId, String triggerType);

    /** 取消：轮次置 CANCELLED 并丢弃暂存，资源不动 */
    void cancel(Long taskId);

    /**
     * 超时兜底：活跃轮次里"连续无进展"超过阈值的判失败。
     *
     * @return 本轮收尾的轮次数
     */
    int sweepTimeout();
}
