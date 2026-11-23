-- ============================================================
--  清理「逻辑删除墓碑」以释放唯一键（MySQL）
--  日期：2026-11-23
--
--  背景：sys_role.role_code / sys_user.username 等唯一键都不含 deleted 列，而实体上的
--        @TableLogic 只会把行标记成 deleted=1。于是：
--          1) 服务层查重（自动带 deleted=0）认为编码可用；
--          2) INSERT 撞上仍被墓碑占用的唯一键；
--          3) DuplicateKeyException 落到 GlobalExceptionHandler 兜底分支，
--             接口返回 HTTP 200 + code 500「系统异常，请联系管理员」。
--        表现为「删掉的编码/用户名永远不能再用」，且报错完全看不出原因。
--
--  处理：把墓碑物理删除，释放唯一键。墓碑本身没有保留价值——删除路径已清理
--        sys_user_role / sys_role_permission 关联，权限缓存也已 evictAll。
--
--  幂等：全部是 DELETE，可重复执行。
--  安全：只删 deleted=1 的行与已悬空的关联行，不 TOUCH 任何未删除数据；不做 TRUNCATE。
--  用法：mysql -h<host> -uroot -p <db> < 2026-11-23_purge_logic_deleted_mysql.sql
--        业务库为 solo_db（如有 solo_db2 镜像库需同样执行一遍）。
-- ============================================================

-- 0) 执行前快照（留档，便于事后核对被清掉了什么）
SELECT 'before' AS phase, 'sys_role' AS tbl, COUNT(*) AS tombstones FROM sys_role WHERE deleted = 1
UNION ALL SELECT 'before', 'sys_user', COUNT(*) FROM sys_user WHERE deleted = 1
UNION ALL SELECT 'before', 'sys_permission', COUNT(*) FROM sys_permission WHERE deleted = 1
UNION ALL SELECT 'before', 'sys_dict_type', COUNT(*) FROM sys_dict_type WHERE deleted = 1
UNION ALL SELECT 'before', 'sys_config', COUNT(*) FROM sys_config WHERE deleted = 1
UNION ALL SELECT 'before', 'sys_server', COUNT(*) FROM sys_server WHERE deleted = 1;

-- 1) 关联表孤儿兜底（正常删除路径已清；这里防历史/手工改动留下的悬空行）
DELETE ur FROM sys_user_role ur
    LEFT JOIN sys_user u ON u.id = ur.user_id
WHERE u.id IS NULL;

DELETE ur FROM sys_user_role ur
    LEFT JOIN sys_role r ON r.id = ur.role_id
WHERE r.id IS NULL;

DELETE rp FROM sys_role_permission rp
    LEFT JOIN sys_role r ON r.id = rp.role_id
WHERE r.id IS NULL;

DELETE rp FROM sys_role_permission rp
    LEFT JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.id IS NULL;

-- 2) 主表墓碑：角色与用户（服务层已在 create 时自动清同键墓碑，这里清历史遗留的全部）
DELETE FROM sys_role WHERE deleted = 1;
DELETE FROM sys_user WHERE deleted = 1;

-- 3) 按需清理其余同构表 —— 默认注释，确认影响后再逐条放开。
--    sys_permission：墓碑可能有未删除的子权限挂在 parent_id 上，删之前先确认
--                    SELECT * FROM sys_permission WHERE parent_id IN (SELECT id FROM sys_permission WHERE deleted = 1);
--    sys_dict_type ：字典数据按 dict_type 字符串关联，墓碑删除后其 dict_data 会成为孤立数据；
--                    且 recreate 同 dict_type 时旧 dict_data 会被新类型「继承」显示。
--    sys_config / sys_server：无子表引用，可直接放开（sys_server 的 server_id 被流媒体表按字符串引用，
--                    与墓碑行本身无关）。
-- DELETE FROM sys_permission WHERE deleted = 1;
-- DELETE FROM sys_dict_type WHERE deleted = 1;
-- DELETE FROM sys_config WHERE deleted = 1;
-- DELETE FROM sys_server WHERE deleted = 1;

-- 4) 校验：墓碑应为 0，关联表孤儿应为 0
SELECT 'after' AS phase, 'sys_role' AS tbl, COUNT(*) AS tombstones FROM sys_role WHERE deleted = 1
UNION ALL SELECT 'after', 'sys_user', COUNT(*) FROM sys_user WHERE deleted = 1
UNION ALL SELECT 'after', 'sys_permission', COUNT(*) FROM sys_permission WHERE deleted = 1
UNION ALL SELECT 'after', 'sys_dict_type', COUNT(*) FROM sys_dict_type WHERE deleted = 1
UNION ALL SELECT 'after', 'sys_config', COUNT(*) FROM sys_config WHERE deleted = 1
UNION ALL SELECT 'after', 'sys_server', COUNT(*) FROM sys_server WHERE deleted = 1;

SELECT 'orphan' AS phase, 'sys_user_role->user' AS rel,
       COUNT(*) AS cnt FROM sys_user_role ur LEFT JOIN sys_user u ON u.id = ur.user_id WHERE u.id IS NULL
UNION ALL SELECT 'orphan', 'sys_user_role->role',
       COUNT(*) FROM sys_user_role ur LEFT JOIN sys_role r ON r.id = ur.role_id WHERE r.id IS NULL
UNION ALL SELECT 'orphan', 'sys_role_permission->role',
       COUNT(*) FROM sys_role_permission rp LEFT JOIN sys_role r ON r.id = rp.role_id WHERE r.id IS NULL
UNION ALL SELECT 'orphan', 'sys_role_permission->perm',
       COUNT(*) FROM sys_role_permission rp LEFT JOIN sys_permission p ON p.id = rp.permission_id WHERE p.id IS NULL;
