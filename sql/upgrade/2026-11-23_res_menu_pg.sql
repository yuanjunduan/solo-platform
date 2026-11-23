-- ============================================================
--  「资源中心」菜单与按钮权限（PostgreSQL）
--  与 2026-11-23_res_menu_mysql.sql 等价，仅去掉 FROM DUAL
--  依赖：sys_permission 自增主键；超级管理员 role_id = 1
--  日期：2026-11-23
-- ============================================================

-- 0) 清理本前缀的逻辑删除墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code LIKE 'res:%' AND deleted = 1;

-- 1) 一级菜单
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '资源中心', 'res', NULL, 1, 0, 7, 'IconHierarchy', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res');

-- 2) 二级菜单
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织管理', 'res:org', '/res/org', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'res' LIMIT 1),
       1, 'IconSitemap', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:org');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '设备管理', 'res:device', '/res/device', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'res' LIMIT 1),
       2, 'IconDeviceCctv', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:device');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '通道管理', 'res:channel', '/res/channel', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'res' LIMIT 1),
       3, 'IconList', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel');

-- 3) 按钮权限：组织
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织树查询', 'res:org:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:org' LIMIT 1), 1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:org:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织新增', 'res:org:add', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:org' LIMIT 1), 2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:org:add');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织编辑', 'res:org:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:org' LIMIT 1), 3, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:org:edit');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织删除', 'res:org:delete', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:org' LIMIT 1), 4, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:org:delete');

-- 4) 按钮权限：设备
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '设备查询', 'res:device:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:device' LIMIT 1), 1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:device:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '设备新增', 'res:device:add', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:device' LIMIT 1), 2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:device:add');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '设备编辑', 'res:device:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:device' LIMIT 1), 3, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:device:edit');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '设备删除', 'res:device:delete', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:device' LIMIT 1), 4, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:device:delete');

-- 5) 按钮权限：通道
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '通道查询', 'res:channel:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1), 1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '通道新增', 'res:channel:add', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1), 2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:add');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '通道编辑', 'res:channel:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1), 3, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:edit');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '通道删除', 'res:channel:delete', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1), 4, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:delete');

-- 6) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE p.permission_code LIKE 'res:%'
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 7) 校验：应为 16 条
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code LIKE 'res:%' OR permission_code = 'res'
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND (p.permission_code LIKE 'res:%' OR p.permission_code = 'res');
