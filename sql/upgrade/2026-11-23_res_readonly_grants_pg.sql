-- ============================================================
-- 资源只读收口：第四层（数据库权限）—— PostgreSQL
-- 3.6.5 / 需求硬约束 C2：INBOUND 资源不可被用户改删
--
-- 【执行时机】手工执行，且必须先把 M1 的硬删接口改成软删。
--   res_org / res_group / res_device / res_channel 的 DELETE 一旦收回，
--   `OrgService.delete()` 这类 deleteById 会立刻报 permission denied。
--   顺序：① 业务代码改软删 → ② 再执行本脚本。
--
-- 【占位符】把 :app_user 换成应用连接池使用的真实账号（不是超级用户）。
--   用 psql 时可直接：psql -v app_user=solo_app -f 本文件
--   注意 :app_user 在 psql 里是变量语法，替换后不要再带引号。
--
-- 【为什么不启用 RLS】
--   RLS（FOR UPDATE USING source_type='NATIVE'）看着最彻底，但同步合并跑在
--   同一个应用账号上，INBOUND 行的 UPDATE 会被一并拦掉——主链路直接断。
--   要做 RLS 必须给同步单独建角色并 BYPASSRLS，属于 M2b 的运维改造，
--   M2a 只做到"收回 DELETE + 保留 UPDATE（由前三层保证只改 NATIVE）"。
-- ============================================================

-- 1) 收回四张资源表的硬删权限（软删走 UPDATE status，不受影响）
REVOKE DELETE ON res_org, res_group, res_device, res_channel FROM :app_user;

-- 2) 保留读与写；写权限必须保留——同步合并与用户 CRUD 都靠它
GRANT SELECT, INSERT, UPDATE ON res_org, res_group, res_device, res_channel TO :app_user;

-- 3) 序列/雪花由应用生成，不需要 USAGE；这里显式确认不授予 DELETE
--    （GRANT 是全量覆盖语义，重复执行本脚本不会把 DELETE 加回来）

-- 4) 自查：应返回空集（若返回行，说明还有别的角色持有 DELETE）
SELECT grantee, table_name, privilege_type
FROM information_schema.table_privileges
WHERE table_name IN ('res_org', 'res_group', 'res_device', 'res_channel')
  AND privilege_type = 'DELETE';

-- ============================================================
-- 回滚（排障时临时放开）：
--   GRANT DELETE ON res_org, res_group, res_device, res_channel TO :app_user;
-- 回滚后必须记一条运维记录：此时只读收口只剩前三层在守。
-- ============================================================
