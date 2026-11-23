package com.solo.domain.sync.service.impl;

import com.solo.domain.sync.model.SyncRoundSnapshot;
import com.solo.domain.sync.model.SyncStaging;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.SyncRoundSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 报文快照的异步写入（3.13.2，C 档：默认关闭）。
 *
 * <p><b>三条纪律，一条都不能松</b>：</p>
 * <ol>
 *   <li><b>旁路</b>：快照失败/丢弃绝不影响同步。写异常只 WARN，队列满直接丢；</li>
 *   <li><b>不阻塞主链路</b>：staging 写成功后把行浅拷贝进有界队列就返回，
 *       真正的入库发生在独立线程；</li>
 *   <li><b>收尾预留槽位</b>：普通批次最多用到 {@code queueSize}，队列里永远留一格
 *       给"轮次收尾"任务——否则收尾被丢，旧轮快照就再也清不掉（只增不减）。</li>
 * </ol>
 *
 * <p>关闭时（默认）所有入口第一步就返回，连队列都不碰，做到"零写入"。</p>
 */
@Slf4j
@Component
public class SyncSnapshotWriter implements DisposableBean {

    /** 单批删除行数：文档口径 5 万，太大长时间持锁 */
    private static final int CLEAN_BATCH = 50_000;

    /** 单次清理最多删多少批（5 万 × 200 = 1000 万行），剩下的下一轮继续 */
    private static final int CLEAN_MAX_BATCHES = 200;

    private final SyncRoundSnapshotMapper snapshotMapper;
    private final SyncSettings settings;
    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedBatches = new AtomicLong();

    public SyncSnapshotWriter(SyncRoundSnapshotMapper snapshotMapper, SyncSettings settings) {
        this.snapshotMapper = snapshotMapper;
        this.settings = settings;
        int capacity = Math.max(1, settings.getSnapshotQueueSize());
        // +1 是给收尾任务预留的那一格
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity + 1),
                runnable -> {
                    Thread thread = new Thread(runnable, "sync-snapshot");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 一批暂存写成功后调用：把行浅拷贝进队列就返回。
     *
     * <p>刻意在调用线程里做拷贝：{@link SyncStaging} 对象之后可能被复用或回收，
     * 交给异步线程则是共享可变状态。</p>
     */
    public void snapshotPage(List<SyncStaging> page) {
        if (!settings.isSnapshotEnabled() || page == null || page.isEmpty()) {
            return;
        }
        List<SyncRoundSnapshot> copies = copyOf(page);
        // 普通批次必须留一格给收尾
        submit(() -> write(copies), false);
    }

    /** 轮次收尾：清理该平台除最近 N 轮之外的快照 */
    public void roundFinished(Long platformId) {
        if (!settings.isSnapshotEnabled() || platformId == null) {
            return;
        }
        submit(() -> cleanup(platformId), true);
    }

    private void write(List<SyncRoundSnapshot> rows) {
        try {
            snapshotMapper.insertBatch(rows);
        } catch (RuntimeException e) {
            // 快照是旁路：写失败只告警，不重试、不向上抛
            log.warn("[资源同步] 快照写入失败（旁路，已丢弃该批 {} 行）err={}", rows.size(), e.getMessage());
        }
    }

    private void cleanup(Long platformId) {
        try {
            int keepRounds = Math.max(1, settings.getSnapshotKeepRounds());
            List<Long> keepTaskIds = snapshotMapper.selectRecentTaskIds(platformId, keepRounds);
            if (keepTaskIds == null || keepTaskIds.isEmpty()) {
                return;
            }
            int total = 0;
            for (int i = 0; i < CLEAN_MAX_BATCHES; i++) {
                int deleted = snapshotMapper.deleteExcessBatch(platformId, keepTaskIds, CLEAN_BATCH);
                total += deleted;
                if (deleted < CLEAN_BATCH) {
                    break;
                }
            }
            if (total > 0) {
                log.info("[资源同步] 快照清理 platformId={} 删除 {} 行（保留最近 {} 轮）",
                        platformId, total, keepRounds);
            }
        } catch (RuntimeException e) {
            log.warn("[资源同步] 快照清理失败（旁路） platformId={} err={}", platformId, e.getMessage());
        }
    }

    /**
     * 入队。
     *
     * <p>必须走 {@link ThreadPoolExecutor#execute} 而不是直接 {@code queue.offer}：
     * 直接塞队列永远不会创建核心线程，队列里的任务没人消费（"入队但永不落库"）。</p>
     *
     * @param forFinish true = 这是收尾任务，允许占用预留的那一格
     */
    private void submit(Runnable task, boolean forFinish) {
        int neededFree = forFinish ? 1 : 2;
        if (executor.getQueue().remainingCapacity() < neededFree) {
            countDrop();
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // 检查与执行之间被别的批次抢先占满
            countDrop();
        }
    }

    private void countDrop() {
        long n = droppedBatches.incrementAndGet();
        if (n % 100 == 1) {
            log.warn("[资源同步] 快照队列已满，累计丢弃 {} 个批次（旁路，不影响同步）", n);
        }
    }

    private static List<SyncRoundSnapshot> copyOf(List<SyncStaging> page) {
        List<SyncRoundSnapshot> copies = new ArrayList<>(page.size());
        for (SyncStaging row : page) {
            SyncRoundSnapshot copy = new SyncRoundSnapshot();
            // 直接借 staging 的雪花 id：全局唯一，省一次 id 生成，也让两边行能对上
            copy.setId(row.getId());
            copy.setTenantId(row.getTenantId());
            copy.setTaskId(row.getTaskId());
            copy.setPlatformId(row.getPlatformId());
            copy.setItemType(row.getItemType());
            copy.setCode(row.getCode());
            copy.setParentCode(row.getParentCode());
            copy.setName(row.getName());
            copy.setOnlineStatus(row.getOnlineStatus());
            copy.setDeviceCode(row.getDeviceCode());
            copy.setStreamType(row.getStreamType());
            copy.setCapability(row.getCapability());
            copy.setDeviceIp(row.getDeviceIp());
            copy.setVendor(row.getVendor());
            copy.setModel(row.getModel());
            copy.setGroupType(row.getGroupType());
            copy.setCreateTime(row.getCreateTime());
            copies.add(copy);
        }
        return copies;
    }

    @Override
    public void destroy() {
        // 快照可丢：停机不等队列排空
        executor.shutdownNow();
    }
}
