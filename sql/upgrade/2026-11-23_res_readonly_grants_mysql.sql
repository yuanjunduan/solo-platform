-- ============================================================
-- 资源只读收口：第四层（数据库权限）—— MySQL 8
-- 3.6.5 / 需求硬约束 C2：INBOUND 资源不可被用户改删
--
-- 【执行时机】手工执行，且必须先把 M1 的硬删接口改成软删。
--   res_* 的 DELETE 收回后，`OrgService.delete()` 这类 deleteById 会立刻报
--   "DELETE command denied"。顺序：① 业务代码改软删 → ② 再执行本脚本。
--
-- 【占位符】把 @app_host / 'app_user' 换成应用数据源用的真实账号（不要用 root）。
--
-- 【为什么不启用 RLS】
--   MySQL 没有行级安全策略；同账号下无法区分"同步写的 INBOUND"与"用户写的 NATIVE"。
--   因此第四层只收 DELETE，UPDATE 的边界靠前三层（注解 / 服务守卫 / 拦截器）守。
--   要做到数据库级强制，需要给同步单独建账号，属于 M2b 的运维改造。
-- ============================================================

-- 1) 收回四张资源表的硬删权限（软删走 UPDATE status，不受影响）
REVOKE DELETE ON solo_db.res_org FROM 'app_user'@'@app_host';
REVOKE DELETE ON solo_db.res_group FROM 'app_user'@'@app_host';
REVOKE DELETE ON solo_db.res_device FROM 'app_user'@'@app_host';
REVOKE DELETE ON solo_db.res_channel FROM 'app_user'@'@app_host';

-- 2) 保留读与写：同步合并与用户 CRUD 都靠 UPDATE
GRANT SELECT, INSERT, UPDATE ON solo_db.res_org TO 'app_user'@'@app_host';
GRANT SELECT, INSERT, UPDATE ON solo_db.res_group TO 'app_user'@'@app_host';
GRANT SELECT, INSERT, UPDATE ON solo_db.res_device TO 'app_user'@'@app_host';
GRANT SELECT, INSERT, UPDATE ON solo_db.res_channel TO 'app_user'@'@app_host';
FLUSH PRIVILEGES;

-- 3) 自查：应返回空集（若返回行，说明还有别的账号/主机组合持有 DELETE）
SELECT GRANTEE, TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE
FROM information_schema.TABLE_PRIVILEGES
WHERE TABLE_SCHEMA = 'solo_db'
  AND TABLE_NAME IN ('res_org', 'res_group', 'res_device', 'res_channel')
  AND PRIVILEGE_TYPE = 'DELETE';

-- ============================================================
-- 回滚（排障时临时放开）：
--   GRANT DELETE ON solo_db.res_org TO 'app_user'@'@app_host';  -- 其余三张同理
-- 回滚后必须记一条运维记录：此时只读收口只剩前三层在守。
-- ============================================================
