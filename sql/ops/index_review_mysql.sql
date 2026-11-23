-- ============================================================================
-- 索引复核（M7 / 6.7 慢查询治理）—— MySQL 8
--
-- 用途
--   1) 核对"关键路径查询所依赖的索引"是否真的存在（把清单当代码评审的对象，而不是凭记忆）；
--   2) 给出这些查询的 EXPLAIN，人工确认走的是期望索引、type 不是 ALL；
--   3) 缺索引只输出建议 ALTER 语句（注释态），由人择时执行 —— 建索引会锁表/占用 IO，
--      放在脚本里自动跑等于把运维事故写进流水线。
--
-- 用法
--   mysql -u<user> -p <db> < sql/ops/index_review_mysql.sql
--
-- 判定口径
--   · 任何一行 status = MISSING 都要处理：要么补索引，要么在本文档写明"该查询已下线"。
--   · EXPLAIN 出现 type=ALL 且 rows 上万，即视为不达标。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 期望索引 vs 实际索引
--    名字对齐 DDL（sql/upgrade/*.sql）；仅为"复核事实"，不改 DDL 定义。
-- ---------------------------------------------------------------------------
SELECT e.tbl AS table_name,
       e.idx AS expected_index,
       e.cols AS expected_columns,
       COALESCE(s.actual, 'MISSING') AS status
FROM (SELECT 'audit_log'       AS tbl, 'idx_create_time'         AS idx, 'create_time'                    AS cols
      UNION ALL SELECT 'audit_log',       'idx_target',              'target_type, target_id'
      UNION ALL SELECT 'audit_log',       'idx_operator_time',       'operator_id, create_time'
      UNION ALL SELECT 'res_play_record', 'idx_play_record_time',    'begin_time'
      UNION ALL SELECT 'res_play_record', 'idx_play_record_channel', 'channel_row_id, begin_time'
      UNION ALL SELECT 'res_play_record', 'idx_play_record_actor',   'actor_id, begin_time'
      UNION ALL SELECT 'res_play_record', 'idx_play_record_stream',  'stream_id'
      UNION ALL SELECT 'sync_task',       'idx_platform_time',       'platform_id, create_time'
      UNION ALL SELECT 'sync_task',       'uk_active_platform',      'active_platform_id（唯一，互斥用）'
      UNION ALL SELECT 'sync_record',     'idx_time',                'create_time'
      UNION ALL SELECT 'sync_record',     'idx_item',                'item_type, code'
      UNION ALL SELECT 'sync_round',      'idx_platform_time',       'platform_id, begin_time'
      UNION ALL SELECT 'change_event',    'idx_tenant_seq',          'tenant_id, seq'
      UNION ALL SELECT 'change_event',    'idx_platform_seq',        'platform_id, seq'
      -- 保留期回收（M7）：DELETE ... WHERE create_time/begin_time < ? 需要"时间列打头"的索引，
      -- (platform_id, create_time) 这类复合索引对纯时间过滤无效（最左前缀不满足）。
      UNION ALL SELECT 'sync_task',       'idx_create_time',         'create_time（保留期回收需要）'
      UNION ALL SELECT 'sync_round',      'idx_begin_time',          'begin_time（保留期回收需要）'
      ) e
LEFT JOIN (SELECT DISTINCT TABLE_NAME AS tbl, INDEX_NAME AS actual
           FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()) s
       ON s.tbl = e.tbl AND s.actual = e.idx
ORDER BY e.tbl, e.idx;

-- ---------------------------------------------------------------------------
-- 2) 保留期回收缺失索引的建议语句（先看上一节是否报 MISSING 再执行）
--    单表千万级以下，加索引通常秒级；更大规模用 pt-online-schema-change 或
--    ALGORITHM=INPLACE, LOCK=NONE。
-- ---------------------------------------------------------------------------
-- ALTER TABLE sync_task  ADD INDEX idx_create_time (create_time), ALGORITHM=INPLACE, LOCK=NONE;
-- ALTER TABLE sync_round ADD INDEX idx_begin_time  (begin_time),  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 3) 关键路径 EXPLAIN（参数用常量占位，只为看执行计划形态）
-- ---------------------------------------------------------------------------

-- 3.1 播放记录分页：按通道 + 时间倒序（3.11.6）
EXPLAIN SELECT id, begin_time, actor_name, result
FROM res_play_record
WHERE channel_row_id = 1 AND begin_time >= '2026-01-01 00:00:00'
ORDER BY begin_time DESC
LIMIT 20;

-- 3.2 播放记录分页：按操作者 + 时间倒序
EXPLAIN SELECT id, begin_time, channel_code, result
FROM res_play_record
WHERE actor_id = '1' AND begin_time >= '2026-01-01 00:00:00'
ORDER BY begin_time DESC
LIMIT 20;

-- 3.3 审计分页：时间倒序（3.12.6）
EXPLAIN SELECT id, action, target_type, operator_name, create_time
FROM audit_log
WHERE create_time >= '2026-01-01 00:00:00'
ORDER BY create_time DESC
LIMIT 20;

-- 3.4 同步任务互斥判定（C24）：同平台只允许一个活跃任务
EXPLAIN SELECT id FROM sync_task WHERE active_platform_id = 1;

-- 3.5 事件流游标拉取（3.11.7）：Last-Event-ID 之后的正序增量
EXPLAIN SELECT seq, item_type, code, change_type, shape, create_time
FROM change_event
WHERE seq > 0
ORDER BY seq
LIMIT 100;

-- 3.6 保留期回收的删除范围（M7）：与 RetentionJob 的语句形态保持一致（派生表包一层，
--     MySQL 才允许"删的同时子查询同表"）；LIMIT 保证单批有界。
EXPLAIN DELETE FROM audit_log WHERE id IN (
    SELECT id FROM (SELECT id FROM audit_log WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN DELETE FROM res_play_record WHERE id IN (
    SELECT id FROM (SELECT id FROM res_play_record WHERE begin_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN DELETE FROM sync_task WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_task WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN DELETE FROM sync_record WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_record WHERE create_time < '2026-01-01 00:00:00' LIMIT 5000) t);
EXPLAIN DELETE FROM sync_round WHERE id IN (
    SELECT id FROM (SELECT id FROM sync_round WHERE begin_time < '2026-01-01 00:00:00' LIMIT 5000) t);

-- ---------------------------------------------------------------------------
-- 4) 索引使用情况（可选，需 performance_schema 开启；重启后计数清零，看的是"近一周期"）
--    没被用到的索引不是"坏索引"：可能是低频查询、也可能是写放大来源，按月复核一次。
-- ---------------------------------------------------------------------------
SELECT OBJECT_NAME                AS table_name,
       INDEX_NAME                 AS index_name,
       COUNT_STAR                 AS use_count
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE OBJECT_SCHEMA = DATABASE()
  AND INDEX_NAME IS NOT NULL
ORDER BY use_count ASC, table_name, index_name
LIMIT 50;

-- ---------------------------------------------------------------------------
-- 5) 表体积（决定保留期是否真的在起作用，5.2 容量预算）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME,
       TABLE_ROWS,
       ROUND(DATA_LENGTH / 1024 / 1024, 1)  AS data_mb,
       ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS index_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('audit_log', 'res_play_record', 'sync_task', 'sync_record',
                     'sync_round', 'change_event', 'sync_staging')
ORDER BY DATA_LENGTH DESC;
