-- ============================================================================
-- 2026-11-24  平台登记表 + gb_device 唯一键按域隔离 + 端点域判定（PostgreSQL）
--
-- 与 2026-11-24_platform_registry_mysql.sql 等价，仅方言差异：
--   * 唯一索引用 DROP INDEX IF EXISTS / CREATE UNIQUE INDEX IF NOT EXISTS
--   * 冲突处理用 ON CONFLICT (direction, platform_gb_id) DO UPDATE
--
-- 适用：PostgreSQL 12+。本机无 PG 实例，脚本按方言编写，需在 PG 环境实测。
-- 幂等：可重复执行。
-- ============================================================================

-- 0) 前置检查：必须返回 0 行
SELECT domain, device_id, COUNT(*) AS cnt
FROM gb_device
GROUP BY domain, device_id
HAVING COUNT(*) > 1;

-- 1) 平台登记表
CREATE TABLE IF NOT EXISTS gb_platform_registry
(
    id                     bigint NOT NULL,
    platform_gb_id         varchar(50) NOT NULL,
    direction              varchar(16) NOT NULL DEFAULT 'DOWNSTREAM',
    kind                   varchar(16) NOT NULL DEFAULT 'PLATFORM',
    name                   varchar(255) DEFAULT NULL,
    sip_ip                 varchar(64) DEFAULT NULL,
    sip_port               int DEFAULT NULL,
    online_status          smallint DEFAULT 0,
    last_catalog_sync_time timestamp(0) DEFAULT NULL,
    channel_count          int NOT NULL DEFAULT 0,
    group_count            int NOT NULL DEFAULT 0,
    enabled                smallint NOT NULL DEFAULT 1,
    remark                 varchar(255) DEFAULT NULL,
    create_time            timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE gb_platform_registry IS '级联平台登记簿：左树第二层根节点、同步进度与通道计数';
COMMENT ON COLUMN gb_platform_registry.platform_gb_id IS '平台/接入端点国标码，等于 gb_device_channel.device_id（发起 Catalog 的端点）';
COMMENT ON COLUMN gb_platform_registry.direction IS '级联方向 DOWNSTREAM下级 / UPSTREAM上级 / REMOTE第三方资源平台';
COMMENT ON COLUMN gb_platform_registry.kind IS '端点类型 PLATFORM平台（11-13位=200）/ DEVICE接入设备或网关';
COMMENT ON COLUMN gb_platform_registry.online_status IS '在线状态 0离线 1在线 2未注册';
COMMENT ON COLUMN gb_platform_registry.last_catalog_sync_time IS '最后Catalog同步时间';
COMMENT ON COLUMN gb_platform_registry.channel_count IS '通道数（parental=0）';
COMMENT ON COLUMN gb_platform_registry.group_count IS '分组数（parental=1）';
COMMENT ON COLUMN gb_platform_registry.enabled IS '1启用 0停用（停用后左树不展示）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_direction_gb_id ON gb_platform_registry (direction, platform_gb_id);
CREATE INDEX IF NOT EXISTS idx_registry_direction ON gb_platform_registry (direction, enabled);

-- 2) gb_device 唯一键：uk_device_id(device_id) → uk_domain_device(domain, device_id)
DROP INDEX IF EXISTS uk_device_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_device ON gb_device (domain, device_id);

-- 3) 回填下级（及接入设备）登记行
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
ON CONFLICT (direction, platform_gb_id) DO UPDATE
SET channel_count          = EXCLUDED.channel_count,
    group_count            = EXCLUDED.group_count,
    last_catalog_sync_time = EXCLUDED.last_catalog_sync_time,
    online_status          = EXCLUDED.online_status,
    sip_ip                 = EXCLUDED.sip_ip,
    sip_port               = EXCLUDED.sip_port;

-- 4) 回填上级登记行
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
ON CONFLICT (direction, platform_gb_id) DO UPDATE
SET name          = EXCLUDED.name,
    sip_ip        = EXCLUDED.sip_ip,
    sip_port      = EXCLUDED.sip_port,
    online_status = EXCLUDED.online_status,
    enabled       = EXCLUDED.enabled;

-- 5) 端点域判定
UPDATE gb_device
SET domain = 'SUBORDINATE',
    org_id = NULL
WHERE SUBSTRING(device_id, 11, 3) = '200'
  AND domain = 'LOCAL';

-- 6) 校验
SELECT 'registry DOWNSTREAM' AS item, COUNT(*)::bigint AS cnt
FROM gb_platform_registry WHERE direction = 'DOWNSTREAM'
UNION ALL SELECT 'distinct catalog sources', COUNT(DISTINCT device_id) FROM gb_device_channel
UNION ALL SELECT 'registry UPSTREAM', COUNT(*) FROM gb_platform_registry WHERE direction = 'UPSTREAM'
UNION ALL SELECT 'parent platforms', COUNT(*) FROM gb_parent_platform
UNION ALL SELECT 'devices LOCAL', COUNT(*) FROM gb_device WHERE domain = 'LOCAL'
UNION ALL SELECT 'devices SUBORDINATE', COUNT(*) FROM gb_device WHERE domain = 'SUBORDINATE'
UNION ALL SELECT 'devices REMOTE', COUNT(*) FROM gb_device WHERE domain = 'REMOTE';

-- 索引确认：应存在 uk_domain_device，且不存在 uk_device_id
SELECT indexname FROM pg_indexes WHERE tablename = 'gb_device' ORDER BY indexname;

-- 回滚（如确需退回）：
--   DROP INDEX IF EXISTS uk_domain_device;
--   CREATE UNIQUE INDEX uk_device_id ON gb_device (device_id);   -- 仅在无跨域重复时可行
--   UPDATE gb_device SET domain = 'LOCAL' WHERE domain = 'SUBORDINATE' AND SUBSTRING(device_id, 11, 3) = '200';
--   DROP TABLE gb_platform_registry;
