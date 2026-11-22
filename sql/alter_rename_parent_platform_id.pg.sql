-- =============================================================================
-- 增量迁移（PostgreSQL 版）：上级平台主键列改名  platform_id → parent_platform_id
--
-- 与 alter_rename_parent_platform_id.sql（MySQL 版）一一对应：
--   gb_platform_group / gb_platform_share 的 platform_id（bigint 上级平台主键）
--   改成 parent_platform_id；gb_device_channel.platform_id 与 gb_platform.platform_id
--   是下级平台国标码（varchar），不在本脚本范围。
--
-- 特性：可重复执行；PG 的 RENAME COLUMN 会自动更新索引/唯一约束里的列引用
-- 执行：psql -h<host> -U<user> -d <库名> -f alter_rename_parent_platform_id.pg.sql
-- =============================================================================

DO $$
BEGIN
    -- 1. gb_platform_group
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'gb_platform_group'
                  AND column_name = 'parent_platform_id') THEN
        RAISE NOTICE '[skip] gb_platform_group.parent_platform_id 已存在';
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'gb_platform_group'
                     AND column_name = 'platform_id') THEN
        ALTER TABLE gb_platform_group RENAME COLUMN platform_id TO parent_platform_id;
        RAISE NOTICE '[ok] gb_platform_group.platform_id -> parent_platform_id';
    ELSE
        RAISE NOTICE '[warn] gb_platform_group 缺 platform_id 列，跳过（新库请直接跑建表脚本）';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'gb_platform_group'
                  AND column_name = 'parent_platform_id') THEN
        EXECUTE 'COMMENT ON COLUMN gb_platform_group.parent_platform_id IS ''上级平台主键 gb_parent_platform.id''';
    END IF;

    -- 2. gb_platform_share
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'gb_platform_share'
                  AND column_name = 'parent_platform_id') THEN
        RAISE NOTICE '[skip] gb_platform_share.parent_platform_id 已存在';
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'gb_platform_share'
                     AND column_name = 'platform_id') THEN
        ALTER TABLE gb_platform_share RENAME COLUMN platform_id TO parent_platform_id;
        RAISE NOTICE '[ok] gb_platform_share.platform_id -> parent_platform_id';
    ELSE
        RAISE NOTICE '[warn] gb_platform_share 缺 platform_id 列，跳过（新库请直接跑建表脚本）';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'gb_platform_share'
                  AND column_name = 'parent_platform_id') THEN
        EXECUTE 'COMMENT ON COLUMN gb_platform_share.parent_platform_id IS ''上级平台主键 gb_parent_platform.id''';
    END IF;
END
$$;

-- 自检：两表应只出现 parent_platform_id
SELECT table_name, column_name, data_type, col_description((current_schema() || '.' || table_name)::regclass,
        (SELECT ordinal_position FROM information_schema.columns c
          WHERE c.table_schema = current_schema() AND c.table_name = t.table_name
            AND c.column_name = t.column_name)) AS column_comment
  FROM (SELECT table_name, column_name, data_type FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name IN ('gb_platform_group', 'gb_platform_share')
           AND column_name IN ('platform_id', 'parent_platform_id')) t
 ORDER BY table_name, column_name;

-- =============================================================================
-- 回滚（需要退回旧列名时手工执行）
--   ALTER TABLE gb_platform_group RENAME COLUMN parent_platform_id TO platform_id;
--   ALTER TABLE gb_platform_share RENAME COLUMN parent_platform_id TO platform_id;
-- =============================================================================
