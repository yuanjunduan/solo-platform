package com.solo.domain.event.service;

import com.solo.domain.event.support.EventSettings;
import com.solo.infra.mapper.ChangeEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 事件保留期回收（{@code event.retention-days}，默认 7 天，与 outbox 口径一致）。
 *
 * <p>回收就是"让游标窗口前移"：被删掉的 seq 之后再也补不回来，所以超过窗口的连接
 * 必须返回 409 + 快照地址（{@link EventStreamService#open}）。保留期与
 * "客户端最多能离线多久"是同一个数——把它调小就等于缩短断线补拉能力。</p>
 *
 * <p>与 3.13.4 的清理纪律一致：定时任务里抛一次未捕获异常后续调度会永久停止，
 * 所以整个方法包 try/catch，失败只告警、下一轮继续。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventRetentionJob {

    private final ChangeEventMapper eventMapper;
    private final EventSettings settings;

    /**
     * 每小时一次：事件量按"日 1 万条"估算（5.2.1），单条 DELETE 足够；
     * 真长成百万级再按批删（对齐 sync_round_snapshot 的做法）。
     *
     * @return 清掉的事件条数
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 3_600_000)
    public int purgeExpired() {
        if (!settings.isRetentionEnabled()) {
            return 0;
        }
        try {
            LocalDateTime before = LocalDateTime.now()
                    .minusDays(Math.max(1, settings.getRetentionDays()));
            int deleted = eventMapper.deleteBefore(before);
            if (deleted > 0) {
                log.info("[资源事件] 回收过期事件 {} 条（保留 {} 天，早于 {}）",
                        deleted, settings.getRetentionDays(), before);
            }
            return deleted;
        } catch (RuntimeException e) {
            log.warn("[资源事件] 过期事件回收失败（下一轮继续） err={}", e.getMessage());
            return 0;
        }
    }
}
