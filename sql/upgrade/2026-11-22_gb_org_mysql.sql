-- ============================================================
--  增量升级（MySQL 8.0+）：本地组织树 gb_org + 资源域 domain
--  第 1 轮：把"本地域"从设备/通道里分出来，并给本地资源加组织归属
--  适用：已在运行、不能 DROP 重建的库
--  执行：一次性执行。重复执行时 CREATE TABLE IF NOT EXISTS / INSERT IGNORE 幂等，
--        ALTER TABLE ADD COLUMN 会报 Duplicate column name，可忽略后跳过该段
--  日期：2026-11-22
-- ============================================================

-- ------------------------------------------------------------
-- 1) 本地组织树
--    path 形如 /1/5/9/（首尾带斜杠），子树查询用 path LIKE '/1/5/%'，
--    不会误匹配 /1/50/；不加逻辑删除列，删除走"校验非空 + 物理删"
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_org
(
    id          bigint      NOT NULL COMMENT '主键ID',
    parent_id   bigint      NOT NULL DEFAULT 0 COMMENT '父组织主键，0=根',
    name        varchar(64) NOT NULL COMMENT '组织名称',
    code        varchar(20)          DEFAULT NULL COMMENT '组织国标编码（导入生成上级分组时写 215/216，可为空）',
    path        varchar(512) NOT NULL DEFAULT '' COMMENT '物化路径 /根id/.../自身id/，子树查询用 LIKE 前缀',
    level       int         NOT NULL DEFAULT 1 COMMENT '层级，根=1',
    sort        int         NOT NULL DEFAULT 0 COMMENT '同级排序',
    remark      varchar(255)         DEFAULT NULL COMMENT '备注',
    create_time datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_parent_name (parent_id, name),
    UNIQUE KEY uk_org_code (code),
    KEY        idx_org_path (path),
    KEY        idx_org_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='本地组织树（仅本地域资源归属；与面向上级的推送分组 gb_platform_group 无关）';

-- 预置根组织（id 固定 1，雪花 ID 不会与之冲突）
INSERT IGNORE INTO gb_org (id, parent_id, name, path, level, sort)
VALUES (1, 0, '默认组织', '/1/', 1, 0);

-- ------------------------------------------------------------
-- 2) 设备：所属域 + 所属本地组织
--    domain 值域 LOCAL / SUBORDINATE / REMOTE
--    org_id 仅对 LOCAL 域有意义，下级域的组织来自目录 215/216
-- ------------------------------------------------------------
ALTER TABLE gb_device
    ADD COLUMN domain varchar(16) NOT NULL DEFAULT 'LOCAL'
        COMMENT '所属域 LOCAL本地 / SUBORDINATE下级平台 / REMOTE第三方资源平台' AFTER source,
    ADD COLUMN org_id bigint DEFAULT NULL
        COMMENT '所属本地组织 gb_org.id；仅 LOCAL 域有值' AFTER domain,
    ADD KEY idx_domain_org (domain, org_id);

-- ------------------------------------------------------------
-- 3) 通道：组织归属覆盖值（NULL = 继承所属设备的 org_id）
--    有效组织统一口径：COALESCE(channel.org_id, device.org_id)
-- ------------------------------------------------------------
ALTER TABLE gb_device_channel
    ADD COLUMN org_id bigint DEFAULT NULL
        COMMENT '组织归属覆盖值 gb_org.id；NULL=继承所属设备的 org_id' AFTER platform_id,
    ADD KEY idx_org (org_id);

-- ------------------------------------------------------------
-- 4) 回填存量 domain
--    依据：国标编码第 11~13 位为类型段，200 = 平台（下级平台端点），
--    其余（118 NVR / 131 摄像机 / 132 摄像机等）都是设备
--    source 不能用来判断：本地新增与下级平台注册都可能是同一取值
-- ------------------------------------------------------------
UPDATE gb_device
SET domain = 'SUBORDINATE'
WHERE CHAR_LENGTH(device_id) = 20
  AND SUBSTRING(device_id, 11, 3) = '200';

-- ------------------------------------------------------------
-- 5) 校验
-- ------------------------------------------------------------
SELECT domain, COUNT(*) AS cnt FROM gb_device GROUP BY domain;
SELECT id, parent_id, name, path, level FROM gb_org ORDER BY id;
