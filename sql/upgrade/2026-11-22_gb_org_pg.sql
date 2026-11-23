-- ============================================================
--  增量升级（PostgreSQL 14+）：本地组织树 gb_org + 资源域 domain
--  第 1 轮：把"本地域"从设备/通道里分出来，并给本地资源加组织归属
--  适用：已在运行、不能 DROP 重建的库
--  执行：一次性执行；本脚本全部语句幂等（IF NOT EXISTS / ON CONFLICT）
--  日期：2026-11-22
-- ============================================================

-- ------------------------------------------------------------
-- 1) 本地组织树
--    path 形如 /1/5/9/（首尾带斜杠），子树查询用 path LIKE '/1/5/%'，
--    不会误匹配 /1/50/；不加逻辑删除列，删除走"校验非空 + 物理删"
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_org
(
    id          bigint NOT NULL,
    parent_id   bigint NOT NULL DEFAULT 0,
    name        varchar(64) NOT NULL,
    code        varchar(20) DEFAULT NULL,
    path        varchar(512) NOT NULL DEFAULT '',
    level       int NOT NULL DEFAULT 1,
    sort        int NOT NULL DEFAULT 0,
    remark      varchar(255) DEFAULT NULL,
    create_time timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE gb_org IS '本地组织树（仅本地域资源归属；与面向上级的推送分组 gb_platform_group 无关）';
COMMENT ON COLUMN gb_org.id IS '主键ID';
COMMENT ON COLUMN gb_org.parent_id IS '父组织主键，0=根';
COMMENT ON COLUMN gb_org.name IS '组织名称';
COMMENT ON COLUMN gb_org.code IS '组织国标编码（导入生成上级分组时写 215/216，可为空）';
COMMENT ON COLUMN gb_org.path IS '物化路径 /根id/.../自身id/，子树查询用 LIKE 前缀';
COMMENT ON COLUMN gb_org.level IS '层级，根=1';
COMMENT ON COLUMN gb_org.sort IS '同级排序';
COMMENT ON COLUMN gb_org.remark IS '备注';
COMMENT ON COLUMN gb_org.create_time IS '创建时间';
COMMENT ON COLUMN gb_org.update_time IS '更新时间';
CREATE UNIQUE INDEX IF NOT EXISTS uk_gb_org_parent_name ON gb_org (parent_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gb_org_code ON gb_org (code);
CREATE INDEX IF NOT EXISTS idx_gb_org_path ON gb_org (path);
CREATE INDEX IF NOT EXISTS idx_gb_org_parent ON gb_org (parent_id);

-- 预置根组织（id 固定 1，雪花 ID 不会与之冲突）
INSERT INTO gb_org (id, parent_id, name, path, level, sort)
VALUES (1, 0, '默认组织', '/1/', 1, 0) ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 设备：所属域 + 所属本地组织
-- ------------------------------------------------------------
ALTER TABLE gb_device ADD COLUMN IF NOT EXISTS domain varchar(16) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE gb_device ADD COLUMN IF NOT EXISTS org_id bigint DEFAULT NULL;
COMMENT ON COLUMN gb_device.domain IS '所属域 LOCAL本地 / SUBORDINATE下级平台 / REMOTE第三方资源平台';
COMMENT ON COLUMN gb_device.org_id IS '所属本地组织 gb_org.id；仅 LOCAL 域有值';
CREATE INDEX IF NOT EXISTS idx_gb_device_domain_org ON gb_device (domain, org_id);

-- ------------------------------------------------------------
-- 3) 通道：组织归属覆盖值（NULL = 继承所属设备的 org_id）
--    有效组织统一口径：COALESCE(channel.org_id, device.org_id)
-- ------------------------------------------------------------
ALTER TABLE gb_device_channel ADD COLUMN IF NOT EXISTS org_id bigint DEFAULT NULL;
COMMENT ON COLUMN gb_device_channel.org_id IS '组织归属覆盖值 gb_org.id；NULL=继承所属设备的 org_id';
CREATE INDEX IF NOT EXISTS idx_gb_device_channel_org ON gb_device_channel (org_id);

-- ------------------------------------------------------------
-- 4) 回填存量 domain
--    依据：国标编码第 11~13 位为类型段，200 = 平台（下级平台端点），
--    其余（118 NVR / 131 摄像机 / 132 摄像机等）都是设备
--    source 不能用来判断：本地新增与下级平台注册都可能是同一取值
-- ------------------------------------------------------------
UPDATE gb_device
SET domain = 'SUBORDINATE'
WHERE length(device_id) = 20
  AND substring(device_id, 11, 3) = '200';

-- ------------------------------------------------------------
-- 5) 校验
-- ------------------------------------------------------------
SELECT domain, COUNT(*) AS cnt FROM gb_device GROUP BY domain;
SELECT id, parent_id, name, path, level FROM gb_org ORDER BY id;
