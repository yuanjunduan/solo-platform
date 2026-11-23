package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.support.SyncSessionRegistry;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 暂存残留的三道清理（3.13.4）。
 *
 * <p>收尾那条路径（成功清本轮+同域、失败只清历史）写在各自的收尾方法里；
 * 本类只负责另外两道不依赖收尾成功与否的兜底：</p>
 * <ol>
 *   <li><b>启动后延迟一次</b>：被 kill / 断电时收尾代码根本没机会执行，
 *       内存状态可以自愈，磁盘上的暂存不会；</li>
 *   <li><b>运行期周期</b>：收尾线程自身也可能异常退出，超过保留期的行按批回收。</li>
 * </ol>
 *
 * <p><b>必须独立线程 + 异常吞掉</b>：定时任务里抛一次未捕获异常，后续调度会永久停止
 * （{@code scheduleWithFixedDelay} 的经典坑），残留就再也不清了。所以每个入口都包了
 * {@code try/catch}，失败只告警。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncResidueCleaner {

    /** 单批删除行数：太大长时间持锁，太小往返多 */
    private static final int BATCH = 5_000;

    /** 单次运行最多删多少批（5000 × 200 = 100 万行），剩下的下一轮继续 */
    private static final int MAX_BATCHES = 200;

    private final SyncStagingMapper stagingMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncSessionRegistry sessionRegistry;
    private final SyncSettings settings;

    /**
     * 启动后延迟一次清上次运行遗留。
     *
     * <p>延迟 2 分钟是为了让超时兜底先跑：卡在 RUNNING 的轮次会被它判成 FAILED，
     * 这里再按"没有存活会话"补一刀，两边的口径就一致了。</p>
     *
     * @return 清掉的行数
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = Long.MAX_VALUE)
    public long cleanStartupLeftover() {
        if (!settings.isResidueStartupClean()) {
            log.info("[资源同步] 启动残留清理已关闭（sync.residue.startup-clean=false）");
            return 0;
        }
        try {
            // 孤儿：task 行已不存在或早已不是活跃态
            int orphans = stagingMapper.deleteOrphans();

            // 活跃态但本进程没有句柄：进程刚起，句柄不可能存在，只能是被 kill 的遗留
            int orphanActive = 0;
            List<SyncTask> active = taskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                    .in(SyncTask::getStatus, (Object[]) SyncTaskStatus.ACTIVE));
            for (SyncTask task : active) {
                if (sessionRegistry.contains(task.getId())) {
                    continue;
                }
                orphanActive += stagingMapper.deleteByTask(task.getId());
            }

            long total = (long) orphans + orphanActive;
            if (total > 0) {
                log.warn("[资源同步] 启动残留清理完成：孤儿 {} 行，无会话的活跃轮次 {} 行（共 {} 个任务）",
                        orphans, orphanActive, active.size());
            } else {
                log.info("[资源同步] 启动残留清理：无残留");
            }
            return total;
        } catch (RuntimeException e) {
            log.warn("[资源同步] 启动残留清理失败（不影响同步） err={}", e.getMessage());
            return 0;
        }
    }

    /**
     * 运行期周期清理：删 {@code create_time} 早于保留时长（默认 24h）的暂存行。
     *
     * <p>按批删到某一批不足 {@link #BATCH} 行为止；单次最多 {@link #MAX_BATCHES} 批，
     * 避免一次 GC 式的长事务把正在收批次的其他平台挡住。</p>
     *
     * @return 清掉的行数
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 3_600_000)
    public long cleanExpired() {
        try {
            LocalDateTime before = LocalDateTime.now()
                    .minus(Duration.ofMillis(settings.residueRetentionMs()));
            long total = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int deleted = stagingMapper.deleteExpiredBatch(before, BATCH);
                total += deleted;
                if (deleted < BATCH) {
                    break;
                }
            }
            if (total > 0) {
                log.info("[资源同步] 周期清理暂存 {} 行（保留 {} 小时）",
                        total, settings.getResidueRetentionHours());
            }
            return total;
        } catch (RuntimeException e) {
            log.warn("[资源同步] 周期清理暂存失败（下一轮继续） err={}", e.getMessage());
            return 0;
        }
    }
}
