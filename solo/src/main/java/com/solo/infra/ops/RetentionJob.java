package com.solo.infra.ops;

import com.solo.infra.mapper.AuditLogMapper;
import com.solo.infra.mapper.ResPlayRecordMapper;
import com.solo.infra.mapper.SyncRecordMapper;
import com.solo.infra.mapper.SyncRoundMapper;
import com.solo.infra.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 保留期回收（M7，依据 5.2 / 5.2.1）。
 *
 * <p><b>为什么必须有</b>：审计、播放记录、同步明细都是"只追加"的表，
 * 没有回收就是无限增长——文档 5.2.1 明确把它们列为"不清理会变成第二张大表"的对象。
 * 这里只做按时间的保留期删除，不改任何业务字段；审计的业务语义仍是"只追加、不提供修改接口"。</p>
 *
 * <p><b>失败语义</b>：与 3.13.4 同一纪律——定时任务抛一次未捕获异常后续调度会永久停止。
 * 所以每张表各自 try/catch，一张失败不影响其他表，下一轮继续。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionJob {

    private final RetentionSettings settings;
    private final AuditLogMapper auditLogMapper;
    private final ResPlayRecordMapper playRecordMapper;
    private final SyncRecordMapper syncRecordMapper;
    private final SyncTaskMapper syncTaskMapper;
    private final SyncRoundMapper syncRoundMapper;

    /**
     * 每天一轮，启动后延迟 5 分钟（先让应用把这轮请求接住）。
     *
     * @return 本轮各表合计删除行数
     */
    @Scheduled(initialDelayString = "${retention.startup-delay-ms:300000}",
            fixedDelayString = "${retention.interval-ms:86400000}")
    public int purgeExpired() {
        if (!settings.isEnabled()) {
            log.debug("[保留期] 已关闭（retention.enabled=false）");
            return 0;
        }
        int total = 0;
        total += drain("audit_log", settings.getAuditLogDays(), auditLogMapper::deleteBeforeBatch);
        total += drain("res_play_record", settings.getPlayRecordDays(), playRecordMapper::deleteBeforeBatch);
        // 明细先于主体：同一时间窗口下先删子表，查询历史时不会出现"有明细没任务"的孤儿
        total += drain("sync_record", settings.getSyncDays(), syncRecordMapper::deleteBeforeBatch);
        total += drain("sync_task", settings.getSyncDays(), syncTaskMapper::deleteBeforeBatch);
        total += drain("sync_round", settings.getSyncDays(), syncRoundMapper::deleteBeforeBatch);
        if (total > 0) {
            log.info("[保留期] 本轮共清理 {} 条", total);
        }
        return total;
    }

    /**
     * 单表分批删除：删到不足一批（说明已清空）或触及单轮批数上限为止。
     */
    private int drain(String table, int days, BatchDeleter deleter) {
        if (days <= 0) {
            return 0;
        }
        int batchSize = Math.max(100, settings.getBatchSize());
        int maxBatches = Math.max(1, settings.getMaxBatchesPerRun());
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        try {
            int deleted = 0;
            for (int i = 0; i < maxBatches; i++) {
                int rows = deleter.deleteBefore(before, batchSize);
                deleted += rows;
                if (rows < batchSize) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("[保留期] {} 清理 {} 条（保留 {} 天，早于 {}）", table, deleted, days, before);
            }
            return deleted;
        } catch (RuntimeException e) {
            log.warn("[保留期] {} 回收失败（下一轮继续） err={}", table, e.getMessage());
            return 0;
        }
    }

    /** 与 mapper 方法签名对齐，便于按表传入 */
    @FunctionalInterface
    interface BatchDeleter {
        int deleteBefore(LocalDateTime before, int limit);
    }
}
