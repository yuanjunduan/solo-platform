-- ============================================================
--  「资源共享」菜单与按钮权限（MySQL）
--  依赖：2026-11-24_share_core_*.sql（表已建）
--  幂等：先清理本前缀墓碑，再逐条 NOT EXISTS；可重复执行
--  日期：2026-11-24
-- ============================================================

-- 0) 清理本前缀的逻辑删除墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code LIKE 'share:%' AND deleted = 1;

-- 1) 一级菜单：资源共享
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '资源共享', 'share', NULL, 1, 0, 8, 'IconShare2', 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share');

SET @menu_share = (SELECT id FROM sys_permission WHERE permission_code = 'share' LIMIT 1);

-- 2) 二级菜单：一屏三段 / 共享总览 / 推送记录
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享编排', 'share:compose', '/share/compose', 1, @menu_share, 1, 'IconShare', 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享总览', 'share:overview', '/share/overview', 1, @menu_share, 2, 'IconList', 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送记录', 'share:publish', '/share/publish', 1, @menu_share, 3, 'IconUpload', 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish');

SET @menu_compose = (SELECT id FROM sys_permission WHERE permission_code = 'share:compose' LIMIT 1);
SET @menu_overview = (SELECT id FROM sys_permission WHERE permission_code = 'share:overview' LIMIT 1);
SET @menu_publish = (SELECT id FROM sys_permission WHERE permission_code = 'share:publish' LIMIT 1);

-- 3) 按钮权限：共享编排
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '编排查询', 'share:compose:list', NULL, 2, @menu_compose, 1, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '编排提交', 'share:compose:edit', NULL, 2, @menu_compose, 2, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose:edit');

-- 4) 按钮权限：共享总览（撤销 / 剔除）
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享查询', 'share:overview:list', NULL, 2, @menu_overview, 1, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '撤销共享', 'share:overview:edit', NULL, 2, @menu_overview, 2, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview:edit');

-- 5) 按钮权限：推送记录（重试 / 对账）
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送查询', 'share:publish:list', NULL, 2, @menu_publish, 1, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送重试', 'share:publish:retry', NULL, 2, @menu_publish, 2, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish:retry');

-- 6) 授权超级管理员（PermissionAspect 没有超管绕过）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE (p.permission_code LIKE 'share:%' OR p.permission_code = 'share')
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 7) 校验：应看到 1 + 3 + 6 = 10 条
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code LIKE 'share:%' OR permission_code = 'share'
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND (p.permission_code LIKE 'share:%' OR p.permission_code = 'share');
