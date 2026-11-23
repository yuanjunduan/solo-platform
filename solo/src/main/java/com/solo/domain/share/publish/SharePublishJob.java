package com.solo.domain.share.publish;

import com.solo.domain.share.service.ShareReconcileService;
import com.solo.domain.share.support.ShareSettings;
import com.solo.infra.mapper.OutShareMapper;
import com.solo.infra.mapper.OutboxMapper;
import com.solo.infra.mapper.PublishRoundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站后台任务：投递 / 对账 / 回收与僵尸轮次（3.9.5、6.5）。
 *
 * <p>三个任务彼此独立，互不等待：投递只关心"队列里有没有到点的"，对账只补差异，
 * 回收只删已投递的历史。把三者塞进一个方法会在"某一平台投递超时"时把对账一起饿死。</p>
 *
 * <p>与 {@code EventRetentionJob} 同一纪律（3.13.4）：定时任务里抛一次未捕获异常，
 * 后续调度会永久停止，所以每个入口都整体包 try/catch，失败只告警、下一轮继续。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePublishJob {

    private final OutboxDispatchService dispatchService;
    private final ShareReconcileService reconcileService;
    private final OutboxMapper outboxMapper;
    private final OutShareMapper outShareMapper;
    private final PublishRoundMapper roundMapper;
    private final ShareSettings settings;

    /**
     * 投递扫描：默认 30 秒一轮（{@code publish.interval-ms}）。
     * <p>没有待推事件时只走一次索引查询，成本接近零；有积压时按平台分片串行推进。</p>
     */
    @Scheduled(initialDelayString = "${publish.dispatch-initial-delay-ms:20000}",
            fixedDelayString = "${publish.interval-ms:30000}")
    public void dispatch() {
        try {
            int sent = dispatchService.dispatchPending(Math.max(1, settings.getPlatformBatch()));
            if (sent > 0) {
                log.info("[上级推送] 本轮投递完成 条数={}", sent);
            }
        } catch (RuntimeException e) {
            log.warn("[上级推送] 投递调度失败（下一轮继续） err={}", e.getMessage(), e);
        }
    }

    /**
     * 对账：默认 1 小时一轮（{@code publish.reconcile-interval-ms}）。
     * <p>只补差异不做全量重推；一个平台失败不影响其它平台。</p>
     */
    @Scheduled(initialDelayString = "${publish.reconcile-initial-delay-ms:120000}",
            fixedDelayString = "${publish.reconcile-interval-ms:3600000}")
    public void reconcile() {
        if (!settings.isReconcileEnabled()) {
            return;
        }
        try {
            List<Long> platformIds = outShareMapper.selectPlatformIds();
            for (Long platformId : platformIds) {
                try {
                    reconcileService.reconcile(platformId);
                } catch (RuntimeException e) {
                    log.warn("[上级共享] 对账失败 platform={} err={}（其余平台继续）",
                            platformId, e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[上级共享] 对账调度失败（下一轮继续） err={}", e.getMessage(), e);
        }
    }

    /**
     * 回收 + 僵尸轮次：默认每天一次。
     * <p>只删 {@code SENT} 且早于保留期的行，<b>并保留平台内最大号那一行</b>——
     * 号段回退会让上级把新事件判成旧包直接丢弃（{@link OutboxMapper#deleteSentBefore}）。</p>
     */
    @Scheduled(initialDelayString = "${publish.housekeeping-initial-delay-ms:300000}",
            fixedDelayString = "${publish.housekeeping-interval-ms:86400000}")
    public void housekeeping() {
        LocalDateTime now = LocalDateTime.now();
        try {
            LocalDateTime staleBefore = now.minus(Duration.ofMillis(
                    Math.max(60_000L, settings.getPlatformLockTtlMs())));
            int failedRounds = roundMapper.failStaleRunning(staleBefore, now);
            if (failedRounds > 0) {
                log.warn("[上级推送] 回收僵尸轮次 {} 个（早于 {}）", failedRounds, staleBefore);
            }

            LocalDateTime before = now.minusDays(Math.max(1, settings.getRetentionDays()));
            int purged = 0;
            for (Long platformId : outboxMapper.selectPlatformIds()) {
                long keep = outboxMapper.maxSeq(platformId);
                purged += outboxMapper.deleteSentBefore(platformId, keep, before);
            }
            if (purged > 0) {
                log.info("[上级推送] 回收已投递事件 {} 条（保留 {} 天，早于 {}）",
                        purged, settings.getRetentionDays(), before);
            }
        } catch (RuntimeException e) {
            log.warn("[上级推送] 回收调度失败（下一轮继续） err={}", e.getMessage(), e);
        }
    }
}
