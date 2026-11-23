-- ============================================================
--  「平台接入」「同步中心」菜单与按钮权限（PostgreSQL）
--  与 2026-11-23_platform_sync_menu_mysql.sql 等价：
--  去掉 FROM DUAL 与 SET @var，父子关系改用子查询内联
--  依赖：sys_permission 自增主键；超级管理员 role_id = 1
--  日期：2026-11-23
--
--  为什么必须同批插 sys_role_permission：PermissionAspect 没有超管绕过，
--  只插 sys_permission 不授权，所有人调新接口都是 403。
-- ============================================================

-- 0) 清理墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code LIKE 'platform:%' AND deleted = 1;
DELETE FROM sys_permission WHERE permission_code LIKE 'sync:%' AND deleted = 1;

-- 1) 一级菜单：平台接入
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '平台接入', 'platform', NULL, 1, 0, 8, 'IconPlugConnected', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'platform');

-- 2) 二级菜单：平台列表 / 同步中心
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '平台列表', 'platform:list', '/platform', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'platform' LIMIT 1),
       1, 'IconServer2', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'platform:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '同步中心', 'sync:list', '/sync', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'platform' LIMIT 1),
       2, 'IconRefresh', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'sync:list');

-- 3) 按钮权限：平台接入
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '平台新增', 'platform:add', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'platform:list' LIMIT 1), 1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'platform:add');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '平台编辑', 'platform:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'platform:list' LIMIT 1), 2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'platform:edit');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '平台删除', 'platform:delete', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'platform:list' LIMIT 1), 3, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'platform:delete');

-- 4) 按钮权限：同步中心
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '发起同步', 'sync:trigger', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'sync:list' LIMIT 1), 1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'sync:trigger');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '取消同步', 'sync:cancel', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'sync:list' LIMIT 1), 2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'sync:cancel');

-- 5) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE (p.permission_code LIKE 'platform%' OR p.permission_code LIKE 'sync:%')
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 6) 校验：应看到 1 + 2 + 5 = 8 条，且 admin_granted = 8
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code LIKE 'platform%' OR permission_code LIKE 'sync:%'
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND (p.permission_code LIKE 'platform%' OR p.permission_code LIKE 'sync:%');
