-- ============================================================
--  「本地组织树」菜单与按钮权限（MySQL）
--  依赖：gb28181 一级菜单 id = 5；超级管理员 role_id = 1
--  幂等：全部 INSERT 带 NOT EXISTS 判断，可重复执行
--  日期：2026-11-22
-- ============================================================

-- 1) 二级菜单：组织管理（插在 设备管理(sort=2) / 通道管理(sort=3) 之后）
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织管理', 'gb28181:org', '/gb28181/org', 1, 5, 4, 'IconSitemap', 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org');

SET @menu_id = (SELECT id FROM sys_permission WHERE permission_code = 'gb28181:org' LIMIT 1);

-- 2) 按钮权限
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织列表查询', 'gb28181:org:list', NULL, 2, @menu_id, 1, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织新增', 'gb28181:org:add', NULL, 2, @menu_id, 2, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org:add');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织编辑', 'gb28181:org:edit', NULL, 2, @menu_id, 3, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org:edit');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '组织删除', 'gb28181:org:delete', NULL, 2, @menu_id, 4, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org:delete');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '资源组织归属调整', 'gb28181:org:assign', NULL, 2, @menu_id, 5, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'gb28181:org:assign');

-- 3) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE p.permission_code LIKE 'gb28181:org%'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 4) 校验
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission WHERE permission_code LIKE 'gb28181:org%' ORDER BY id;
SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1 AND p.permission_code LIKE 'gb28181:org%';
