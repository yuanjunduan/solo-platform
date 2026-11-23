-- ============================================================
--  M5 同源关联（3.10.3）：通道"同源绑定 / 解除"按钮权限（MySQL）
--
--  为什么单独一个权限码：绑定改的是本地侧 canonical_id，不改入站镜像，
--  与 res:channel:edit 的语义（改通道属性、且入站行只读）不同；
--  合并成一个码会让"能给入站行建关联的人"同时拿到"改本地通道"的权利。
--
--  幂等：先清本码墓碑，再 NOT EXISTS 插入；可重复执行。
--  日期：2026-11-26
-- ============================================================

-- 0) 清理本码的逻辑删除墓碑（uk_permission_code 不区分 deleted）
DELETE FROM sys_permission WHERE permission_code = 'res:channel:bind' AND deleted = 1;

-- 1) 父菜单必须是已存在的「通道管理」
SET @menu_channel = (SELECT id FROM sys_permission WHERE permission_code = 'res:channel' LIMIT 1);

-- 2) 按钮权限：同源绑定（挂在通道管理下，排在查询/新增/编辑/删除之后）
INSERT INTO sys_permission
    (permission_name, permission_code, url, type, parent_id, sort, icon, status, deleted, create_time, update_time)
SELECT '同源绑定', 'res:channel:bind', NULL, 2, @menu_channel, 5, NULL, 1, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'res:channel:bind');

-- 3) 授权超级管理员（PermissionAspect 没有超管绕过，不授权则新接口 403）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM sys_permission p
WHERE p.permission_code = 'res:channel:bind'
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- 4) 校验：应看到 1 条权限，且 admin_granted = 1
SELECT id, permission_name, permission_code, type, parent_id, sort
FROM sys_permission
WHERE permission_code = 'res:channel:bind';

SELECT COUNT(*) AS admin_granted
FROM sys_role_permission rp
         JOIN sys_permission p ON p.id = rp.permission_id
WHERE rp.role_id = 1 AND p.permission_code = 'res:channel:bind';

-- 回滚：
--   DELETE FROM sys_role_permission WHERE permission_id IN
--       (SELECT id FROM sys_permission WHERE permission_code = 'res:channel:bind');
--   DELETE FROM sys_permission WHERE permission_code = 'res:channel:bind';
