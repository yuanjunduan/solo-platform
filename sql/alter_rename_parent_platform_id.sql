-- =============================================================================
-- 增量迁移：上级平台主键列改名  platform_id → parent_platform_id
--
-- 范围：gb_platform_group、gb_platform_share
--       这两张表的 platform_id 是 bigint，指向上级平台主键 gb_parent_platform.id
-- 不动：gb_device_channel.platform_id、gb_platform.platform_id
--       它们是 varchar，表示下级平台国标码，语义与本脚本无关
--
-- 特性：可重复执行；MySQL 8 的 RENAME COLUMN 会把索引/唯一键里的列一并改掉，
--       不需要 drop/create 索引；索引名（uk_platform_code 等）按约定保持不动
-- 顺序：若目标库尚未执行 alter_gb_device_channel_platform_id.sql，请先跑那一份
--       （它按旧列名建 uk_platform_channel，改名会自动跟过去）
-- 执行：mysql -h<host> -u<user> -p <库名> < alter_rename_parent_platform_id.sql
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 1. gb_platform_group.platform_id → parent_platform_id
-- -----------------------------------------------------------------------------
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_group' AND COLUMN_NAME = 'parent_platform_id'),
    'SELECT ''[skip] gb_platform_group.parent_platform_id 已存在'' AS msg',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_group' AND COLUMN_NAME = 'platform_id'),
       'ALTER TABLE gb_platform_group RENAME COLUMN platform_id TO parent_platform_id',
       'SELECT ''[warn] gb_platform_group 缺 platform_id 列，跳过（新库请直接跑建表脚本）'' AS msg')
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 列注释刷新：改名后把「上级平台主键」写明指向 gb_parent_platform.id；重复执行无副作用
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_group' AND COLUMN_NAME = 'parent_platform_id'),
    'ALTER TABLE gb_platform_group MODIFY COLUMN parent_platform_id bigint NOT NULL COMMENT ''上级平台主键 gb_parent_platform.id''',
    'SELECT ''[skip] gb_platform_group 无 parent_platform_id，注释跳过'' AS msg'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- -----------------------------------------------------------------------------
-- 2. gb_platform_share.platform_id → parent_platform_id
-- -----------------------------------------------------------------------------
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND COLUMN_NAME = 'parent_platform_id'),
    'SELECT ''[skip] gb_platform_share.parent_platform_id 已存在'' AS msg',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND COLUMN_NAME = 'platform_id'),
       'ALTER TABLE gb_platform_share RENAME COLUMN platform_id TO parent_platform_id',
       'SELECT ''[warn] gb_platform_share 缺 platform_id 列，跳过（新库请直接跑建表脚本）'' AS msg')
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND COLUMN_NAME = 'parent_platform_id'),
    'ALTER TABLE gb_platform_share MODIFY COLUMN parent_platform_id bigint NOT NULL COMMENT ''上级平台主键 gb_parent_platform.id''',
    'SELECT ''[skip] gb_platform_share 无 parent_platform_id，注释跳过'' AS msg'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- -----------------------------------------------------------------------------
-- 3. 执行结果自检：两表应只出现 parent_platform_id，且上级语义列只有它
-- -----------------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db
   AND TABLE_NAME IN ('gb_platform_group', 'gb_platform_share')
   AND COLUMN_NAME IN ('platform_id', 'parent_platform_id')
 ORDER BY TABLE_NAME, COLUMN_NAME;

-- 索引是否跟随改名（应看到 parent_platform_id 出现在这些索引的列里）
SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = @db
   AND TABLE_NAME IN ('gb_platform_group', 'gb_platform_share')
   AND COLUMN_NAME = 'parent_platform_id'
 ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

-- =============================================================================
-- 回滚（仅在需要退回旧列名时手工执行）
--   ALTER TABLE gb_platform_group RENAME COLUMN parent_platform_id TO platform_id;
--   ALTER TABLE gb_platform_share RENAME COLUMN parent_platform_id TO platform_id;
--   并同步回退代码（entity / mapper xml / service 引用）
-- =============================================================================
