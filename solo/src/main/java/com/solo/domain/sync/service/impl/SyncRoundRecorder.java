package com.solo.domain.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.solo.domain.platform.model.Platform;
import com.solo.domain.sync.model.SyncRound;
import com.solo.domain.sync.model.SyncTask;
import com.solo.infra.mapper.SyncRoundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 轮次台账的写入（3.13.1）。
 *
 * <p><b>刻意吞掉异常只告警</b>：台账是运维辅助表，不是业务数据。如果因为
 * "升级 SQL 还没跑"就把一次已经合并成功的全量同步整轮回滚，代价远大于少一行台账。
 * 与 staging 的失败语义相反——那边是主链路，这边是旁证。</p>
 *
 * <p>三处调用：{@code trigger} 落 RUNNING，{@code finish} 落 DONE，
 * {@code markFailed / cancel} 落 FAILED。按 {@code task_id} 更新，不新增第二行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncRoundRecorder {

    private static final int TEXT_MAX = 512;

    private final SyncRoundMapper roundMapper;

    /** 开轮：先落 RUNNING 一行，收尾时补执行事实 */
    public void start(SyncTask task, Platform platform) {
        try {
            SyncRound round = new SyncRound();
            round.setTenantId(task.getTenantId() == null ? 1L : task.getTenantId());
            round.setTaskId(task.getId());
            round.setTaskSn(task.getTaskSn());
            round.setPlatformId(task.getPlatformId());
            round.setPlatformName(platform == null ? null : platform.getName());
            round.setBeginTime(task.getBeginTime() == null ? LocalDateTime.now() : task.getBeginTime());
            round.setStatus(SyncRound.RUNNING);
            round.setOrgRecv(0);
            round.setDevRecv(0);
            round.setChRecv(0);
            round.setOrgInsert(0);
            round.setDevInsert(0);
            round.setDevUpdate(0);
            round.setDevDelete(0);
            round.setDevDup(0);
            round.setDeleteSkipped(0);
            round.setGuardRatio(java.math.BigDecimal.ZERO);
            round.setOrgMissing(0);
            roundMapper.insert(round);
        } catch (RuntimeException e) {
            log.warn("[资源同步] 轮次台账开轮写入失败（旁证，不影响同步） taskId={} err={}",
                    task.getId(), e.getMessage());
        }
    }

    /** 收尾：按 taskId 补执行事实（DONE 或 FAILED） */
    public void complete(SyncRound patch) {
        if (patch.getTaskId() == null) {
            return;
        }
        try {
            if (patch.getStatus() == null) {
                patch.setStatus(SyncRound.DONE);
            }
            if (patch.getEndTime() == null) {
                patch.setEndTime(LocalDateTime.now());
            }
            patch.setSummary(truncate(patch.getSummary()));
            int updated = roundMapper.update(patch, new LambdaUpdateWrapper<SyncRound>()
                    .eq(SyncRound::getTaskId, patch.getTaskId()));
            if (updated == 0) {
                log.warn("[资源同步] 台账行缺失，收尾未落盘 taskId={}（开轮写入可能已失败）", patch.getTaskId());
            }
        } catch (RuntimeException e) {
            log.warn("[资源同步] 轮次台账收尾写入失败（旁证，不影响同步） taskId={} err={}",
                    patch.getTaskId(), e.getMessage());
        }
    }

    /** 失败/取消：只补错误与结束时间，计数保持开轮或中途回填的值 */
    public void fail(Long taskId, String errorCode, String message) {
        SyncRound patch = new SyncRound();
        patch.setTaskId(taskId);
        patch.setStatus(SyncRound.FAILED);
        patch.setErrorCode(truncate(errorCode));
        patch.setErrorMsg(truncate(message));
        patch.setSummary(truncate("失败：" + (errorCode == null ? "SYNC_FAILED" : errorCode)));
        patch.setEndTime(LocalDateTime.now());
        complete(patch);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX);
    }
}
