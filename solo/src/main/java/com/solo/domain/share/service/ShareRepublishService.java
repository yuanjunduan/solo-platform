package com.solo.domain.share.service;

import java.util.List;

/**
 * 出站事件的人工干预（4.2 推送记录页）。
 *
 * <p>与 {@link ShareComposeService} 分开：那边改"共享声明"，这边只改"已经排队的报文状态"。
 * 两者混在一起会让"重试"看起来像一次共享变更，日志与审计都会串。</p>
 */
public interface ShareRepublishService {

    /**
     * 把 FAILED/DEAD 事件放回待推队列。
     *
     * @return 实际重置的条数（已发送或待推中的行不受影响，可能小于入参）
     */
    int retry(List<Long> eventIds);
}
