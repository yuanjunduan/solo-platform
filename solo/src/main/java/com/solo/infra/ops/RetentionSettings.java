package com.solo.infra.ops;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据保留期配置（M7，依据 5.2 / 5.2.1）。
 *
 * <p>文档给的是上限口径：审计与轮次台账保留 1 年；播放记录属于量大且会闭合的业务流水，
 * 默认 180 天。所有天数 {@code <=0} 表示关闭该表回收（交给人工或分区 DROP）。</p>
 *
 * <p>分批删除而不是一条 DELETE：100 万行一次删会长时间持锁、撑大 binlog，
 * 分批让每批都是短事务，中断后下一轮还能续上。</p>
 */
@Data
@Component
public class RetentionSettings {

    @Value("${retention.enabled:true}")
    private boolean enabled;

    /** 审计保留天数（3.12.6 / 5.2：保留 1 年，可按合规调整） */
    @Value("${retention.audit-log-days:365}")
    private int auditLogDays;

    /** 播放记录保留天数 */
    @Value("${retention.play-record-days:180}")
    private int playRecordDays;

    /** 同步任务 / 明细 / 轮次台账保留天数（5.2：sync_round 保留 1 年） */
    @Value("${retention.sync-days:365}")
    private int syncDays;

    /** 单批删除行数；太小会拖慢一轮，太大会拉长长事务 */
    @Value("${retention.batch-size:5000}")
    private int batchSize;

    /** 单表单轮最多删多少批，防止首次上线一次删到天亮 */
    @Value("${retention.max-batches-per-run:200}")
    private int maxBatchesPerRun;
}
