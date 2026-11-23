-- ============================================================================
-- 2026-11-24  平台登记表 + gb_device 唯一键按域隔离 + 端点域判定
--
-- 背景：
--   1) 通道页/设备页的左树需要"平台/目录来源"这一层，但 gb_platform 只是接入
--      黑名单（platform_id + enabled），gb_parent_platform 只存上级，没有一张能
--      承载 下级/上级/第三方 的登记簿，故新建 gb_platform_registry。
--   2) gb_device.device_id 原为全局唯一键。引入多域共存后同一国标码可能同时出现
--      在本地与下级镜像中，必须改为 (domain, device_id)。
--   3) REGISTER 上来的端点此前一律 DEFAULT 'LOCAL'，导致下级平台被当作本地设备
--      展示。按国标编码第 11-13 位 = 200 判定为平台端点，落 SUBORDINATE。
--
-- 适用：MySQL 8.0；solo_db 与 solo_db2 都要执行。
-- 幂等：可重复执行（建表 IF NOT EXISTS、索引切换查 information_schema、回填走
--       ON DUPLICATE KEY UPDATE、域判定只动 LOCAL 行）。
-- 顺序：0 前置检查 → 1 建表 → 2 唯一键切换 → 3 回填下级 → 4 回填上级
--       → 5 端点域判定 → 6 校验
-- ============================================================================

-- 0) 前置检查：必须返回 0 行。有重复说明同域内已有脏数据，先人工处理，
--    否则第 2 步建唯一键会失败。
SELECT domain, device_id, COUNT(*) AS cnt
FROM gb_device
GROUP BY domain, device_id
HAVING COUNT(*) > 1;

-- 1) 平台登记表
CREATE TABLE IF NOT EXISTS gb_platform_registry
(
    id                     bigint      NOT NULL COMMENT '主键ID',
    platform_gb_id         varchar(50) NOT NULL COMMENT '平台/接入端点国标码，等于 gb_device_channel.device_id（发起 Catalog 的端点）',
    direction              varchar(16) NOT NULL DEFAULT 'DOWNSTREAM' COMMENT '级联方向 DOWNSTREAM下级 / UPSTREAM上级 / REMOTE第三方资源平台',
    kind                   varchar(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '端点类型 PLATFORM平台（11-13位=200）/ DEVICE接入设备或网关',
    name                   varchar(255)         DEFAULT NULL COMMENT '展示名称',
    sip_ip                 varchar(64)          DEFAULT NULL COMMENT '信令IP',
    sip_port               int                  DEFAULT NULL COMMENT '信令端口',
    online_status          tinyint              DEFAULT 0 COMMENT '在线状态 0离线 1在线 2未注册',
    last_catalog_sync_time datetime             DEFAULT NULL COMMENT '最后Catalog同步时间',
    channel_count          int         NOT NULL DEFAULT 0 COMMENT '通道数（parental=0）',
    group_count            int         NOT NULL DEFAULT 0 COMMENT '分组数（parental=1）',
    enabled                tinyint     NOT NULL DEFAULT 1 COMMENT '1启用 0停用（停用后左树不展示）',
    remark                 varchar(255)         DEFAULT NULL COMMENT '备注',
    create_time            datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_direction_gb_id (direction, platform_gb_id),
    KEY                    idx_registry_direction (direction, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) gb_device 唯一键：uk_device_id(device_id) → uk_domain_device(domain, device_id)
SET @has_old := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'gb_device' AND index_name = 'uk_device_id');
SET @ddl := IF(@has_old > 0, 'ALTER TABLE gb_device DROP INDEX uk_device_id', 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_new := (SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'gb_device' AND index_name = 'uk_domain_device');
SET @ddl := IF(@has_new = 0, 'ALTER TABLE gb_device ADD UNIQUE KEY uk_domain_device (domain, device_id)', 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 回填下级（及接入设备）登记行：来源取自目录数据自身，与以前 /sources 聚合口径一致
INSERT INTO gb_platform_registry
    (id, platform_gb_id, direction, kind, name, sip_ip, sip_port, online_status,
     last_catalog_sync_time, channel_count, group_count, enabled)
SELECT s.rn,
       s.device_id,
       'DOWNSTREAM',
       CASE WHEN SUBSTRING(s.device_id, 11, 3) = '200' THEN 'PLATFORM' ELSE 'DEVICE' END,
       COALESCE(NULLIF(d.name, ''), NULLIF(d.report_name, ''), s.device_id),
       d.ip,
       d.port,
       COALESCE(d.status, 0),
       d.last_catalog_sync_time,
       s.channel_count,
       s.group_count,
       1
FROM (
    SELECT device_id,
           ROW_NUMBER() OVER (ORDER BY device_id) AS rn,
           SUM(CASE WHEN COALESCE(parental, 0) = 1 THEN 0 ELSE 1 END) AS channel_count,
           SUM(CASE WHEN COALESCE(parental, 0) = 1 THEN 1 ELSE 0 END) AS group_count
    FROM gb_device_channel
    GROUP BY device_id
) s
LEFT JOIN (
    SELECT device_id,
           MAX(name) AS name,
           MAX(report_name) AS report_name,
           MAX(ip) AS ip,
           MAX(port) AS port,
           MAX(status) AS status,
           MAX(last_catalog_sync_time) AS last_catalog_sync_time
    FROM gb_device
    GROUP BY device_id
) d ON d.device_id = s.device_id
ON DUPLICATE KEY UPDATE
    channel_count          = VALUES(channel_count),
    group_count            = VALUES(group_count),
    last_catalog_sync_time = VALUES(last_catalog_sync_time),
    online_status          = VALUES(online_status),
    sip_ip                 = VALUES(sip_ip),
    sip_port               = VALUES(sip_port);

-- 4) 回填上级登记行（上级只作级联关系与展示，本平台不拉取其目录）
INSERT INTO gb_platform_registry
    (id, platform_gb_id, direction, kind, name, sip_ip, sip_port, online_status, enabled)
SELECT 1000000 + ROW_NUMBER() OVER (ORDER BY p.server_gb_id),
       p.server_gb_id,
       'UPSTREAM',
       'PLATFORM',
       p.name,
       p.server_ip,
       p.server_port,
       COALESCE(p.status, 0),
       COALESCE(p.enabled, 1)
FROM gb_parent_platform p
ON DUPLICATE KEY UPDATE
    name          = VALUES(name),
    sip_ip        = VALUES(sip_ip),
    sip_port      = VALUES(sip_port),
    online_status = VALUES(online_status),
    enabled       = VALUES(enabled);

-- 5) 端点域判定：国标码第 11-13 位 = 200 视为平台端点 → SUBORDINATE，且不再挂本地组织
UPDATE gb_device
SET domain = 'SUBORDINATE',
    org_id = NULL
WHERE SUBSTRING(device_id, 11, 3) = '200'
  AND domain = 'LOCAL';

-- 6) 校验：登记行数应与目录来源数一致，各域设备数应可解释
SELECT 'registry DOWNSTREAM' AS item, COUNT(*) AS cnt FROM gb_platform_registry WHERE direction = 'DOWNSTREAM'
UNION ALL SELECT 'distinct catalog sources', COUNT(DISTINCT device_id) FROM gb_device_channel
UNION ALL SELECT 'registry UPSTREAM', COUNT(*) FROM gb_platform_registry WHERE direction = 'UPSTREAM'
UNION ALL SELECT 'parent platforms', COUNT(*) FROM gb_parent_platform
UNION ALL SELECT 'devices LOCAL', COUNT(*) FROM gb_device WHERE domain = 'LOCAL'
UNION ALL SELECT 'devices SUBORDINATE', COUNT(*) FROM gb_device WHERE domain = 'SUBORDINATE'
UNION ALL SELECT 'devices REMOTE', COUNT(*) FROM gb_device WHERE domain = 'REMOTE';

-- 索引确认：应只看到 uk_domain_device(domain, device_id)，看不到 uk_device_id
SHOW INDEX FROM gb_device;

-- 回滚（如确需退回，按序执行；表数据可保留）：
--   ALTER TABLE gb_device DROP INDEX uk_domain_device;
--   ALTER TABLE gb_device ADD UNIQUE KEY uk_device_id (device_id);   -- 仅在 (domain,device_id) 无跨域重复时可行
--   UPDATE gb_device SET domain = 'LOCAL' WHERE domain = 'SUBORDINATE' AND SUBSTRING(device_id, 11, 3) = '200';
--   DROP TABLE gb_platform_registry;
