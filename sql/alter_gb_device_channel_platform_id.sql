-- =============================================================================
-- 增量迁移：gb_device_channel 增加「来源下级平台」维度
--
-- 背景：通道页以「下级平台」为第一维度筛选与统计，gb_device_channel 需要
--       platform_id 记录该行目录由哪个接入平台（Catalog 注册方）上报。
--
-- 适用：MySQL 5.7 / 8.x（库名自动取 DATABASE()，solo_db2 等自定义库名均可）
-- 特性：可重复执行——列/索引已存在时自动跳过，只做一次回填
-- 执行：mysql -h<host> -u<user> -p <库名> < alter_gb_device_channel_platform_id.sql
--       或直接在 IDEA / Navicat 控制台整段执行
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 1. gb_device_channel.platform_id
-- -----------------------------------------------------------------------------
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_device_channel' AND COLUMN_NAME = 'platform_id'),
    'SELECT ''[skip] gb_device_channel.platform_id 已存在'' AS msg',
    'ALTER TABLE gb_device_channel
       ADD COLUMN platform_id varchar(50) NOT NULL DEFAULT ''''
         COMMENT ''来源接入平台国标ID（发起 Catalog 的注册方，多下级平台据此区分通道归属）''
         AFTER device_id'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- -----------------------------------------------------------------------------
-- 2. 联合索引 idx_platform(platform_id, parental)：平台维度分页与状态统计走索引
-- -----------------------------------------------------------------------------
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_device_channel' AND INDEX_NAME = 'idx_platform'),
    'SELECT ''[skip] gb_device_channel.idx_platform 已存在'' AS msg',
    'ALTER TABLE gb_device_channel ADD KEY idx_platform (platform_id, parental)'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- -----------------------------------------------------------------------------
-- 3. 历史数据回填：当前是一个平台直连上报，device_id 即来源平台编码
--    若将来 device_id 细化为子设备码，这里不要盲跑，需按 device_id 关联 gb_device 归属
-- -----------------------------------------------------------------------------
UPDATE gb_device_channel SET platform_id = device_id WHERE platform_id = '';
SELECT ROW_COUNT() AS backfilled_channel_rows;


-- =============================================================================
-- 4. 关联变更（同一批改造）：gb_platform_share 改为业务键
--    (platform_id, device_id, channel_id)，resource_id 仅作回滚兼容。
--    目标库若已同步过本段，全部语句会自动跳过，可安全重复执行。
-- =============================================================================

-- 4.1 业务键列
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND COLUMN_NAME = 'device_id'),
    'SELECT ''[skip] gb_platform_share.device_id 已存在'' AS msg',
    'ALTER TABLE gb_platform_share
       ADD COLUMN device_id varchar(50) NOT NULL DEFAULT ''''
         COMMENT ''通道所属设备国标ID（业务键）''
         AFTER group_id'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND COLUMN_NAME = 'channel_id'),
    'SELECT ''[skip] gb_platform_share.channel_id 已存在'' AS msg',
    'ALTER TABLE gb_platform_share
       ADD COLUMN channel_id varchar(50) NOT NULL DEFAULT ''''
         COMMENT ''通道国标码（业务键，上级暴露码见 exposed_code）''
         AFTER device_id'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4.2 唯一键 uk_platform_channel(platform_id, device_id, channel_id)
SET @sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'gb_platform_share' AND INDEX_NAME = 'uk_platform_channel'),
    'SELECT ''[skip] gb_platform_share.uk_platform_channel 已存在'' AS msg',
    'ALTER TABLE gb_platform_share ADD UNIQUE KEY uk_platform_channel (platform_id, device_id, channel_id)'
);
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4.3 旧共享记录按 resource_id(通道主键) 回填业务键；主键已悬空的补不到，
--     由 CatalogShareServiceImpl.resolveChannels() 在下次挂载时自愈
UPDATE gb_platform_share s
  JOIN gb_device_channel c ON c.id = s.resource_id
   SET s.device_id = c.device_id,
       s.channel_id = c.channel_id
 WHERE s.device_id = '' OR s.channel_id = '';
SELECT ROW_COUNT() AS backfilled_share_rows;


-- =============================================================================
-- 5. 执行结果自检
-- =============================================================================
SELECT COUNT(*)                  AS channel_total_rows,
       SUM(platform_id = '')     AS channel_empty_platform_id,
       COUNT(DISTINCT platform_id) AS channel_distinct_platforms
  FROM gb_device_channel;
