-- ============================================================
--  「订阅管理」菜单与按钮权限（PostgreSQL）
--  与 2026-11-23_event_sub_menu_mysql.sql 等价：
--  去掉 FROM DUAL 与 SET @var，父子关系改用子查询内联
--  依赖：sys_permission 自增主键；超级管理员 role_id = 1
--  日期：2026-11-23
--
--  为什么必须同批插 sys_role_permission：PermissionAspect 没有超管绕过，
--  只插 sys_permission 不授权，所有人调新接口都是 403。
-- ============================================================

-- 0) 清理墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code LIKE 'openapi:subscription%' AND deleted = 1;

-- 1) 二级菜单：订阅管理（挂在「开放平台」下，与应用管理、调用日志并列）
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '订阅管理', 'openapi:subscription', '/openapi/subscription', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'openapi' LIMIT 1),
       3, 'IconBroadcast', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'openapi:subscription');

-- 2) 按钮权限
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '订阅查询', 'openapi:subscription:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'openapi:subscription' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'openapi:subscription:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '订阅编辑', 'openapi:subscription:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'openapi:subscription' LIMIT 1),
       2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'openapi:subscription:edit');

-- 3) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE p.permission_code LIKE 'openapi:subscription%'
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 4) 校验：应看到 3 条（1 菜单 + 2 按钮），且 admin_granted = 3
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code LIKE 'openapi:subscription%'
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND p.permission_code LIKE 'openapi:subscription%';
