-- ============================================================================
-- 索引复核（M7 / 6.7 慢查询治理）—— PostgreSQL
--
-- 与 index_review_mysql.sql 同构，差异只在系统目录表与 EXPLAIN 语法：
--   · MySQL 的 information_schema.STATISTICS  -> pg_indexes + pg_class；
--   · MySQL 的 EXPLAIN                        -> EXPLAIN (ANALYZE false, VERBOSE false)；
--   · PG 的 DELETE 没有 LIMIT 子句，所以保留期回收的语句形态只能是
--     "DELETE ... WHERE id IN (SELECT id FROM (... LIMIT n) t)"，与 RetentionJob 一致。
--
-- 用法
--   psql "<conn>" -f sql/ops/index_review_pg.sql
--
-- 判定口径
--   与 MySQL 脚本相同：任何一行 status = MISSING 都要处理；大量扫描要看 rows/actual rows。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 期望索引 vs 实际索引（名字对齐 sql/upgrade/*_pg.sql）
-- ---------------------------------------------------------------------------
SELECT e.tbl AS table_name,
       e.idx AS expected_index,
       e.cols AS expected_columns,
       COALESCE(s.actual, 'MISSING') AS status
FROM (VALUES
    ('audit_log',       'idx_audit_create_time',        'create_time'),
    ('audit_log',       'idx_audit_target',             'target_type, target_id'),
    ('audit_log',       'idx_audit_operator_time',      'operator_id, create_time'),
    ('res_play_record', 'idx_play_record_time',         'begin_time'),
    ('res_play_record', 'idx_play_record_channel',      'channel_row_id, begin_time'),
    ('res_play_record', 'idx_play_record_actor',        'actor_id, begin_time'),
    ('res_play_record', 'idx_play_record_stream',       'stream_id'),
    ('sync_task',       'idx_sync_task_platform_time',  'platform_id, create_time'),
    ('sync_task',       'uk_sync_task_active',          'active_platform_id（唯一，互斥用）'),
    ('sync_record',     'idx_sync_record_time',         'create_time'),
    ('sync_record',     'idx_sync_record_item',         'item_type, code'),
    ('sync_round',      'idx_sync_round_platform_time', 'platform_id, begin_time'),
    ('change_event',    'idx_change_event_tenant_seq',  'tenant_id, seq'),
    ('change_event',    'idx_change_event_platform_seq','platform_id, seq'),
    -- 保留期回收（M7）：纯时间过滤用不上 (platform_id, create_time) 复合索引（最左前缀）
    ('sync_task',       'idx_sync_task_create_time',    'create_time（保留期回收需要）'),
    ('sync_round',      'idx_sync_round_begin_time',    'begin_time（保留期回收需要）')
) AS e(tbl, idx, cols)
LEFT JOIN (SELECT tablename, indexname AS actual FROM pg_indexes WHERE schemaname = current_schema()) s
       ON s.tablename = e.tbl AND s.actual = e.idx
ORDER BY e.tbl, e.idx;

-- ---------------------------------------------------------------------------
-- 2) 保留期回收缺失索引的建议语句（先看上一节是否报 MISSING 再执行）
--    PG 建索引默认不阻塞写入（CONCURRENTLY），代价是慢，长事务会拖住它。
-- ---------------------------------------------------------------------------
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sync_task_create_time ON sync_task (create_time);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sync_round_begin_time ON sync_round (begin_time);

-- ---------------------------------------------------------------------------
-- 3) 关键路径 EXPLAIN（只取计划，不真跑；确认走 Index Scan 而不是 Seq Scan）
-- ---------------------------------------------------------------------------

-- 3.1 播放记录分页：按通道 + 时间倒序（3.11.6）
EXPLAIN (ANALYZE false, COSTS true)
SELECT id, begin_time, actor_name, result
FROM res_play_record
WHERE channel_row_id = 1 AND begin_time >= '2026-01-01 00:00:00'
ORDER BY begin_time DESC
LIMIT 20;

-- 3.2 播放记录分页：按操作者 + 时间倒序
EXPLAIN (ANALYZE false, COSTS true)
SELECT id, begin_time, channel_code, result
FROM res_play_record
WHERE actor_id = '1' AND begin_time >= '2026-01-01 00:00:00'
ORDER BY begin_time DESC
LIMIT 20;

-- 3.3 审计分页：时间倒序（3.12.6）
EXPLAIN (ANALYZE false, COSTS true)
SELECT id, action, target_type, operator_name, create_time
FROM audit_log
WHERE create_time >= '2026-01-01 00:00:00'
ORDER BY create_time DESC
LIMIT 20;

-- 3.4 同步任务互斥判定（C24）
EXPLAIN (ANALYZE false, COSTS true)
SELECT id FROM sync_task WHERE active_platform_id = 1;

-- 3.5 事件流游标拉取（3.11.7）
EXPLAIN (ANALYZE false, COSTS true)
SELECT seq, item_type, code, change_type, shape, create_time
FROM change_event
WHERE seq > 0
ORDER BY seq
LIMIT 100;

-- 3.6 保留期回收（M7）：形态与 RetentionJob 完全一致
EXPLAIN (ANALYZE false, COSTS true)
DELETE FROM audit_log WHERE id IN (
    SELECT id FROM (SELECT id FROM audit_log WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN (ANALYZE false, COSTS true)
DELETE FROM res_play_record WHERE id IN (
    SELECT id FROM (SELECT id FROM res_play_record WHERE begin_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN (ANALYZE false, COSTS true)
DELETE FROM sync_task WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_task WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN (ANALYZE false, COSTS true)
DELETE FROM sync_record WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_record WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN (ANALYZE false, COSTS true)
DELETE FROM sync_round WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_round WHERE begin_time < '2026-01-01 00:00:00' LIMIT 5000) t);

-- ---------------------------------------------------------------------------
-- 4) 索引使用情况（可选）：idx_scan = 0 说明统计以来没被扫描过，按月复核
-- ---------------------------------------------------------------------------
SELECT relname AS table_name,
       indexrelname AS index_name,
       idx_scan,
       pg_size_pretty(pg_relation_size(i.indexrelid)) AS index_size
FROM pg_stat_user_indexes i
JOIN pg_class c ON c.oid = i.relid
WHERE c.relname IN ('audit_log', 'res_play_record', 'sync_task', 'sync_record',
                    'sync_round', 'change_event')
ORDER BY idx_scan ASC, relname, indexrelname;

-- ---------------------------------------------------------------------------
-- 5) 表体积（5.2 容量预算：保留期是否真的在起作用）
-- ---------------------------------------------------------------------------
SELECT relname AS table_name,
       n_live_tup AS live_rows,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
       pg_size_pretty(pg_indexes_size(c.oid))        AS index_size
FROM pg_class c
JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE c.relname IN ('audit_log', 'res_play_record', 'sync_task', 'sync_record',
                    'sync_round', 'change_event', 'sync_staging')
ORDER BY pg_total_relation_size(c.oid) DESC;
