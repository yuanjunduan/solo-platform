-- ============================================================
--  「资源共享」菜单与按钮权限（PostgreSQL）
--  与 2026-11-24_share_menu_mysql.sql 等价：
--  去掉 FROM DUAL 与 SET @var，父子关系改用子查询内联
--  依赖：2026-11-24_share_core_pg.sql（表已建）；超级管理员 role_id = 1
--  日期：2026-11-24
--
--  为什么必须同批插 sys_role_permission：PermissionAspect 没有超管绕过，
--  只插 sys_permission 不授权，所有人调新接口都是 403。
-- ============================================================

-- 0) 清理墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code LIKE 'share:%' AND deleted = 1;

-- 1) 一级菜单：资源共享
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '资源共享', 'share', NULL, 1, 0, 8, 'IconShare2', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share');

-- 2) 二级菜单：共享编排 / 共享总览 / 推送记录
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享编排', 'share:compose', '/share/compose', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'share' LIMIT 1),
       1, 'IconShare', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享总览', 'share:overview', '/share/overview', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'share' LIMIT 1),
       2, 'IconList', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送记录', 'share:publish', '/share/publish', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'share' LIMIT 1),
       3, 'IconUpload', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish');

-- 3) 按钮权限：共享编排
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '编排查询', 'share:compose:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:compose' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '编排提交', 'share:compose:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:compose' LIMIT 1),
       2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:compose:edit');

-- 4) 按钮权限：共享总览
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '共享查询', 'share:overview:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:overview' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '撤销共享', 'share:overview:edit', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:overview' LIMIT 1),
       2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:overview:edit');

-- 5) 按钮权限：推送记录
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送查询', 'share:publish:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:publish' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '推送重试', 'share:publish:retry', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'share:publish' LIMIT 1),
       2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'share:publish:retry');

-- 6) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE (p.permission_code LIKE 'share:%' OR p.permission_code = 'share')
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 7) 校验：应看到 10 条（1 + 3 + 6），且 admin_granted = 10
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code LIKE 'share:%' OR permission_code = 'share'
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND (p.permission_code LIKE 'share:%' OR p.permission_code = 'share');
