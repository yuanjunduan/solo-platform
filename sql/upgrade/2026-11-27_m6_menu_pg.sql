-- ============================================================
--  M6 视频业务完善：菜单与按钮权限（PostgreSQL）
--  与 2026-11-27_m6_menu_mysql.sql 等价，仅去掉 FROM DUAL 与 @变量
--  幂等：先清墓碑，再 NOT EXISTS 插入；可重复执行
--  日期：2026-11-27
-- ============================================================

-- 0) 清理逻辑删除墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission
WHERE permission_code IN ('res:channel:play', 'res:channel:playback', 'res:channel:ptz', 'res:channel:export',
                          'res:play-record', 'res:play-record:list', 'res:play-record:export',
                          'res:observability', 'res:observability:view')
  AND deleted = 1;

-- 1) 通道管理下新增四个按钮
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '实时播放', 'res:channel:play', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1),
       5, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:play');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '启动回放', 'res:channel:playback', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1),
       6, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:playback');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '云台控制', 'res:channel:ptz', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1),
       7, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:ptz');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '导出通道', 'res:channel:export', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1),
       8, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:export');

-- 2) 二级菜单：播放记录 + 两个按钮
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '播放记录', 'res:play-record', '/res/play-record', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'res' LIMIT 1),
       4, 'IconHistory', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:play-record');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '记录查询', 'res:play-record:list', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:play-record' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:play-record:list');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '导出记录', 'res:play-record:export', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:play-record' LIMIT 1),
       2, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:play-record:export');

-- 3) 二级菜单：运行自检 + 查询按钮
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '运行自检', 'res:observability', '/res/observability', 1,
       (SELECT id FROM sys_permission WHERE permission_code = 'res' LIMIT 1),
       5, 'IconActivity', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:observability');

INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '自检查看', 'res:observability:view', NULL, 2,
       (SELECT id FROM sys_permission WHERE permission_code = 'res:observability' LIMIT 1),
       1, NULL, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:observability:view');

-- 4) 授权超级管理员
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE p.permission_code IN ('res:channel:play', 'res:channel:playback', 'res:channel:ptz', 'res:channel:export',
                            'res:play-record', 'res:play-record:list', 'res:play-record:export',
                            'res:observability', 'res:observability:view')
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 5) 校验
SELECT id, permission_name, permission_code, url, type, parent_id, sort, icon
FROM sys_permission
WHERE permission_code IN ('res:channel:play', 'res:channel:playback', 'res:channel:ptz', 'res:channel:export',
                          'res:play-record', 'res:play-record:list', 'res:play-record:export',
                          'res:observability', 'res:observability:view')
ORDER BY parent_id, sort, id;

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1
  AND p.permission_code IN ('res:channel:play', 'res:channel:playback', 'res:channel:ptz', 'res:channel:export',
                            'res:play-record', 'res:play-record:list', 'res:play-record:export',
                            'res:observability', 'res:observability:view');

-- 回滚：
--   DELETE FROM sys_role_permission WHERE permission_id IN
--       (SELECT id FROM sys_permission WHERE permission_code IN
--        ('res:channel:play','res:channel:playback','res:channel:ptz','res:channel:export',
--         'res:play-record','res:play-record:list','res:play-record:export',
--         'res:observability','res:observability:view'));
--   DELETE FROM sys_permission WHERE permission_code IN
--        ('res:channel:play','res:channel:playback','res:channel:ptz','res:channel:export',
--         'res:play-record','res:play-record:list','res:play-record:export',
--         'res:observability','res:observability:view');
