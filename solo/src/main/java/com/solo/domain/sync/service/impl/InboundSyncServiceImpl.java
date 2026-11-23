package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.resource.service.CanonicalChannelService;
import com.solo.domain.sync.model.SyncTask;
import com.solo.domain.sync.model.SyncTaskStatus;
import com.solo.domain.sync.service.InboundSyncService;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.spi.CatalogSession;
import com.solo.domain.sync.spi.CatalogSink;
import com.solo.domain.sync.spi.InboundAdapter;
import com.solo.domain.sync.spi.InboundAdapterRegistry;
import com.solo.domain.sync.spi.PlatformConn;
import com.solo.domain.sync.support.InboundScaffoldService;
import com.solo.domain.sync.support.SyncCooldownStore;
import com.solo.domain.sync.support.SyncSessionRegistry;
import com.solo.domain.sync.support.SyncSettings;
import com.solo.infra.mapper.PlatformMapper;
import com.solo.infra.mapper.SyncOrgPendingMapper;
import com.solo.infra.mapper.SyncStagingMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三段会话的状态机（3.8.2 / C10）。
 *
 * <pre>
 * trigger ──插入 sync_task(RUNNING)──▶ 适配器 open ──批次──▶ staging（SYNCHING）
 *                                                        └──收齐──▶ finish（单事务合并）──▶ SUCCESS
 *                 └──无进展超时 / 适配器报错──▶ FAILED + 冷却
 * </pre>
 *
 * <p>四件事刻意做在这里而不是适配器里：互斥（DB 唯一键）、冷却（Redis）、超时（按 update_time 无进展）、
 * 失败收尾。适配器换厂商时这四条不用重写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundSyncServiceImpl implements InboundSyncService {

    private static final Pattern ERROR_CODE = Pattern.compile("^([A-Z][A-Z0-9_]{2,63}):");
    private static final int ERROR_MSG_MAX = 512;

    private final PlatformMapper platformMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncStagingMapper stagingMapper;
    private final InboundAdapterRegistry adapterRegistry;
    private final InboundScaffoldService scaffoldService;
    private final SyncCooldownStore cooldownStore;
    private final SyncSessionRegistry sessionRegistry;
    private final SyncStagingWriter stagingWriter;
    private final SyncMergeService mergeService;
    private final CanonicalChannelService canonicalChannelService;
    private final SyncRoundRecorder roundRecorder;
    private final SyncOrgPendingMapper pendingMapper;
    private final SyncSettings settings;

    @Override
    public Long trigger(Long platformId, String triggerType) {
        Platform platform = platformMapper.selectById(platformId);
        if (platform == null) {
            throw new ServiceException(404, "平台不存在：" + platformId);
        }
        if (Platform.STATUS_DISABLED.equals(platform.getStatus())) {
            throw new ServiceException("平台已禁用，不能发起同步");
        }
        cooldownStore.assertReady(platformId);

        SyncTask active = taskMapper.selectActiveByPlatform(platformId);
        if (active != null) {
            throw new ServiceException("SYNC_ALREADY_RUNNING: 平台已有活跃轮次 " + active.getTaskSn());
        }

        // 协议解析与脚手架都在建任务之前：前置条件不满足时不该留下一条 FAILED 记录
        InboundAdapter adapter = adapterRegistry.resolve(platform.getSyncProtocol(), platform.getProtocol());
        InboundScaffoldService.Scaffold scaffold = scaffoldService.ensure(platform);

        LocalDateTime now = LocalDateTime.now();
        SyncTask task = new SyncTask();
        task.setId(IdWorker.getId());
        task.setTenantId(platform.getTenantId() == null ? 1L : platform.getTenantId());
        task.setTaskSn("T" + platformId + "-" + System.currentTimeMillis());
        task.setPlatformId(platformId);
        task.setTriggerType(triggerType == null || triggerType.isBlank() ? "MANUAL" : triggerType);
        task.setStatus(SyncTaskStatus.RUNNING);
        task.setProgress(0);
        task.setExpectedTotal(0);
        task.setRecvTotal(0);
        task.setOrgRecv(0);
        task.setDevRecv(0);
        task.setChRecv(0);
        task.setDeleteSkipped(0);
        task.setBeginTime(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // uk_active_platform：并发触发被数据库挡下（C24 靠约束而不是先查后插）
            throw new ServiceException("SYNC_ALREADY_RUNNING: 该平台正在同步", e);
        }
        // 台账先落 RUNNING：收尾时补执行事实（3.13.1 的"每轮一行"）
        roundRecorder.start(task, platform);

        // 新一轮开始即合并重试计划（3.8.2）：本轮收尾会按实际状态重新登记，
        // 旧队列先作废；清失败不能挡住同步，扫描端还有活跃轮次让路兜底
        try {
            pendingMapper.deleteByPlatform(platformId);
        } catch (RuntimeException e) {
            log.warn("[资源同步] 清理组织回填队列失败 platformId={} err={}", platformId, e.getMessage());
        }

        TaskCatalogSink sink = new TaskCatalogSink(task, scaffold.platformCode());
        PlatformConn conn = new PlatformConn(platform.getTenantId(), platformId, scaffold.platformCode(),
                platform.getLocalDomain(), platform.getHost(), platform.getPort(),
                platform.getSyncProtocol() == null ? platform.getProtocol() : platform.getSyncProtocol());

        CatalogSession session;
        try {
            session = adapter.open(conn, sink);
        } catch (RuntimeException e) {
            // 前置条件失败（未注册/离线/已运行）：不进冷却窗口，用户改完配置就能重试
            markFailed(task, errorCodeOf(e), e.getMessage(), false);
            throw e instanceof ServiceException serviceException
                    ? serviceException
                    : new ServiceException(errorCodeOf(e) + ": " + e.getMessage(), e);
        }
        sessionRegistry.put(task.getId(), session);
        sink.attachSession(session);

        log.info("[资源同步] 开始轮次 taskId={} platformId={} protocol={} taskSn={}",
                task.getId(), platformId, adapter.protocol(), task.getTaskSn());
        return task.getId();
    }

    @Override
    public void cancel(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null || !SyncTaskStatus.isActive(task.getStatus())) {
            return;
        }
        SyncTask patch = new SyncTask();
        patch.setId(taskId);
        patch.setStatus(SyncTaskStatus.CANCELLED);
        patch.setErrorCode("SYNC_CANCELLED");
        patch.setErrorMsg("用户取消");
        patch.setEndTime(LocalDateTime.now());
        taskMapper.updateIfActive(patch);
        sessionRegistry.close(taskId);
        roundRecorder.fail(taskId, "SYNC_CANCELLED", "用户取消");

        // 失败/取消收尾：清同域历史残留，保留本轮现场（3.13.4）
        try {
            stagingMapper.deleteHistory(task.getPlatformId(), taskId);
        } catch (RuntimeException e) {
            log.warn("[资源同步] 取消后清理历史暂存失败 taskId={} err={}", taskId, e.getMessage());
        }
        log.info("[资源同步] 轮次已取消 taskId={}", taskId);
    }

    @Override
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public int sweepTimeout() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMillis(settings.getStallTimeoutMs()));
        List<SyncTask> stalled = taskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                .in(SyncTask::getStatus, (Object[]) SyncTaskStatus.ACTIVE)
                .lt(SyncTask::getUpdateTime, threshold));

        int swept = 0;
        for (SyncTask task : stalled) {
            markFailed(task, "SYNC_STALL_TIMEOUT",
                    "连续 " + settings.getStallTimeoutMs() + "ms 无新批次，判超时", true);
            sessionRegistry.close(task.getId());
            swept++;
        }
        if (swept > 0) {
            log.warn("[资源同步] 超时兜底收尾 {} 个轮次", swept);
        }
        return swept;
    }

    private void markFailed(SyncTask task, String errorCode, String message, boolean cooldown) {
        SyncTask patch = new SyncTask();
        patch.setId(task.getId());
        patch.setStatus(SyncTaskStatus.FAILED);
        patch.setErrorCode(errorCode);
        patch.setErrorMsg(truncate(message));
        patch.setEndTime(LocalDateTime.now());
        taskMapper.updateIfActive(patch);
        roundRecorder.fail(task.getId(), errorCode, message);

        // 失败收尾：只清同域历史残留，本轮暂存留着当现场（3.13.4）。
        // 删本轮会让"为什么这一轮把设备全标成消失"永远查不出来。
        try {
            stagingMapper.deleteHistory(task.getPlatformId(), task.getId());
        } catch (RuntimeException e) {
            log.warn("[资源同步] 失败后清理历史暂存失败 taskId={} err={}", task.getId(), e.getMessage());
        }
        if (cooldown) {
            cooldownStore.markFailed(task.getPlatformId());
        }
        log.warn("[资源同步] 轮次失败 taskId={} platformId={} code={} msg={}",
                task.getId(), task.getPlatformId(), errorCode, message);
    }

    /** 从 "CODE: 详细说明" 里取错误码；没有前缀就用统称 */
    private static String errorCodeOf(Throwable e) {
        String message = e.getMessage();
        if (message != null) {
            Matcher matcher = ERROR_CODE.matcher(message);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "SYNC_FAILED";
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MSG_MAX ? message : message.substring(0, ERROR_MSG_MAX);
    }

    /**
     * 适配器回调 → 暂存 / 合并 / 失败收尾。
     *
     * <p>会话句柄可能先于 {@code trigger} 里的登记就位（SIP 响应在别的线程），
     * 所以这里用一个 {@code done} 标志兜住"先完成、后登记"的竞态，避免句柄残留在表里。</p>
     */
    private final class TaskCatalogSink implements CatalogSink {

        private final SyncTask task;
        private final String platformCode;
        private volatile CatalogSession session;
        private volatile boolean done;

        private TaskCatalogSink(SyncTask task, String platformCode) {
            this.task = task;
            this.platformCode = platformCode;
        }

        void attachSession(CatalogSession catalogSession) {
            this.session = catalogSession;
            if (done) {
                sessionRegistry.close(task.getId());
            }
        }

        @Override
        public void onBatch(CatalogBatch batch) {
            stagingWriter.append(task, platformCode, batch);
        }

        @Override
        public void onComplete() {
            done = true;
            try {
                mergeService.finish(task.getId());
                // L1 自动关联（3.10.3）：全局身份完全相同 → 自动建立同源组。
                // 放在合并之后（能看见本轮新插入的行）、失败只告警——关联是增值能力，
                // 不该让一轮已经成功的同步因为关联写失败而回滚成失败。
                try {
                    int linked = canonicalChannelService.afterMerge(task.getPlatformId());
                    if (linked > 0) {
                        log.info("[资源同步] L1 自动关联通道 {} 条 platformId={}", linked, task.getPlatformId());
                    }
                } catch (RuntimeException e) {
                    log.warn("[资源同步] L1 自动关联失败 platformId={} 原因={}",
                            task.getPlatformId(), e.getMessage());
                }
            } catch (RuntimeException e) {
                markFailed(task, errorCodeOf(e), e.getMessage(), true);
            } finally {
                sessionRegistry.close(task.getId());
            }
        }

        @Override
        public void onError(String errorCode, String message) {
            done = true;
            markFailed(task, errorCode == null ? "SYNC_FAILED" : errorCode, message, true);
            sessionRegistry.close(task.getId());
        }
    }
}
