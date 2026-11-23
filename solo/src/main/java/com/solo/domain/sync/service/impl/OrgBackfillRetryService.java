package com.solo.domain.sync.service.impl;

import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncDeviceAlignmentMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import com.solo.infra.mapper.projection.PendingPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组织缺失设备的延迟重试（3.8.2）。
 *
 * <p>场景：对端目录里设备的 {@code parent_code} 指向的组织本轮还没下发（或人工绑定刚建），
 * 设备会落到 {@code [未分类]} 并置 {@code org_missing=1}。等下一轮同步再修太慢，
 * 所以收尾时把"谁没归属 + 它的父码"登记进 {@code sync_org_pending}，
 * 延迟 N 秒后只对这些设备重走①~④。</p>
 *
 * <p><b>四条时间规则都落在这里</b>：</p>
 * <ul>
 *   <li>触发：收尾后队列非空；</li>
 *   <li>防抖：登记时间距今不足 {@code debounce-seconds} 的平台先不动，
 *       避免组织正在同批下发时白跑；</li>
 *   <li>强制：排队超过 {@code max-wait-minutes} 直接跑并告警，不无限等；</li>
 *   <li>终止：全部归属成功 → {@code deleteResolved} 清空队列，扫描自然不再调度。
 *       仍归不了位的保持标记，等下一轮全量兜底（不在这里死循环重试）。</li>
 * </ul>
 *
 * <p><b>为什么不在工作线程里 sleep</b>：同步收尾占着 staging，延迟重试要读组织表，
 * 二者抢的是同一批行；用定时扫描"到点才动手"，既不会拖长收尾事务，也不会因进程重启丢计划
 * （队列在库里，重启后扫描照样捡起来）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgBackfillRetryService {

    private final SyncOrgPendingMapper pendingMapper;
    private final SyncDeviceAlignmentMapper deviceAlignment;
    private final SyncTaskMapper taskMapper;
    private final SyncSettings settings;

    /**
     * 周期扫描到期平台。
     *
     * <p>必须先看有没有活跃轮次：本轮正在收数据时去改归属，
     * 会和 finish 的 {@code refreshOrg} 抢同一行，谁后写谁生效，结果不可预期。
     * 本轮收尾后会自己再 {@code capture} 一次，所以让路不会漏。</p>
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 30_000)
    public void sweepDue() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusSeconds(settings.getOrgRetryDebounceSeconds());
        List<PendingPlatform> due = pendingMapper.selectDuePlatforms(threshold);
        for (PendingPlatform platform : due) {
            try {
                retry(platform.getPlatformId(), now);
            } catch (RuntimeException e) {
                // 定时任务不能因为一个平台报错就整轮不跑
                log.warn("[资源同步] 组织回填重试失败 platformId={} err={}",
                        platform.getPlatformId(), e.getMessage());
            }
        }
    }

    /**
     * 对单个平台重走①~④。
     *
     * @return 被修正归属/标记的设备数；平台正在同步时直接返回 0（让路）
     */
    public int retry(Long platformId, LocalDateTime now) {
        SyncTask active = taskMapper.selectActiveByPlatform(platformId);
        if (active != null) {
            log.debug("[资源同步] 平台正在同步，跳过组织回填重试 platformId={} task={}",
                    platformId, active.getTaskSn());
            return 0;
        }

        int fixed = deviceAlignment.refreshOrgFromPending(platformId, now);
        int cleared = pendingMapper.deleteResolved(platformId);
        long left = pendingMapper.countByPlatform(platformId);

        if (fixed > 0 || cleared > 0) {
            log.info("[资源同步] 组织回填重试完成 platformId={} 修正={} 出队={} 剩余={}",
                    platformId, fixed, cleared, left);
        }
        if (left > 0) {
            LocalDateTime oldest = pendingMapper.selectOldest(platformId);
            Duration waited = oldest == null ? Duration.ZERO : Duration.between(oldest, now);
            if (waited.toMinutes() >= settings.getOrgRetryMaxWaitMinutes()) {
                log.warn("[资源同步] 组织缺失已等待 {} 分钟仍无法归属 platformId={} 剩余={}（保持标记，等下一轮全量）",
                        waited.toMinutes(), platformId, left);
            }
        }
        return fixed;
    }
}
