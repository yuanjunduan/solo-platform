# Solo 平台运维手册（M7）

> 适用版本：M7（部署与运维 / 性能优化阶段）。
> 本手册只描述平台**已经实现**的能力与参数，不预设文档之外的功能。
> 需求与架构依据：`docs/多平台视频资源管理系统——需求梳理与总体架构设计 V1.0.md`（下称"设计文档"）、
> `docs/ARCHITECTURE_CONSTRAINTS.md`（下称"约束文档"）。

M7 落地的四条运维主线：

| 主线 | 解决什么 | 对应设计文档 |
|---|---|---|
| 健康探针 | 编排/负载均衡能分辨"进程活着"和"能接活" | 3.13.6 运行期自检 |
| 保留期回收 | 只追加的表无限增长会变成第二张大表 | 5.2 数据保留、5.2.1 备份预算 |
| 性能治理 | 慢查询可观测、导出可限流且必留痕 | 6.7 慢查询治理、3.12.5 / 3.12.6 |
| 部署与备份 | 环境变量化、容器化、可备份可演练 | 3.12.7 暴露面、5.2.1 |

---

## 1. 部署形态与前置条件

平台依赖四类外部组件，关系如下：

```
浏览器 ──▶ nginx(solo-ui) ──▶ solo(后端 :8080) ──┬─▶ PostgreSQL / MySQL
                                                 ├─▶ Redis
                                                 ├─▶ ZLMediaKit（媒体面，host 网络）
                                                 └─▶ RustFS / S3（录像与文件）
```

- **solo 后端**：Spring Boot 单体，HTTP 8080、SIP 5060/udp + 5060/tcp。
- **solo-ui**：Vue 构建 + nginx 静态托管，并把 `/api`、`/open-api` 反代到后端。
- **ZLMediaKit / S3 不在 `deploy/docker-compose.yml` 内**：ZLM 需要 host 网络与大量 RTP 端口，
  S3 常独立部署。它们各有参考编排：`zlm/docker-compose.yml`、`rustfs/docker-compose.yml`。

前置条件：

1. 宿主机时间同步（NTP）。开放平台签名校验对时间戳偏差敏感，SIP 注册也依赖时钟。
2. 端口放行：`8080`（或 UI_PORT）、**`5060/udp` 与 `5060/tcp` 都必须开**，
   以及 ZLM 的 HTTP/WebRTC 端口（默认 8088、8000/udp）和 S3 端口（默认 9000）。
3. 一个对设备与上级平台**可达**的宿主机 IP —— 填 `SOLO_IP`，**不能填容器内网 IP**，
   否则 SIP 的 Via/Contact 会指向不可达地址，注册成功但收不到消息。

---

## 2. 首次部署

### 2.1 容器编排（推荐）

```bash
cd deploy
cp .env.example .env        # 逐项改密码与 IP
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/api/health/ready
```

`deploy/.env.example` 中是全量变量与注释。**带 `:?` 的变量缺失会让 compose 直接拒绝启动**——
这是刻意设计：宁可起不来，也不要带着默认密码上线。至少必须改的六项：

| 变量 | 说明 |
|---|---|
| `SOLO_IP` | 宿主机对设备可达的 IP |
| `SOLO_SIP_ID` / `SOLO_SIP_DOMAIN` | 平台国标编码（20 位）与域（一般前 10 位 + `0000`） |
| `SOLO_SIP_PASSWORD` | 设备注册 Digest 密码 |
| `POSTGRES_PASSWORD` | 数据库口令 |
| `SOLO_JWT_SECRET` | `openssl rand -base64 48` 生成 |
| `SOLO_ZLM_SECRET` | 必须与 ZLM `config.ini` 的 `[api].secret` 完全一致 |

`sql/PostgreSQL_solo_db.sql` 挂在 `docker-entrypoint-initdb.d`，**只在数据目录为空时执行一次**。
它建全新建表，不能当升级脚本用（见 §3）。

### 2.2 非容器部署

1. 建库：PostgreSQL 用 `sql/PostgreSQL_solo_db.sql`，MySQL 用 `sql/MySQL_solo_db.sql`。
2. 逐版本执行 `sql/upgrade/` 下的升级脚本（每个版本两份，按数据库方言二选一）。
3. 后端：`cd solo && mvn -o -DskipTests package`，`java -jar target/*.jar`。
   profile 只有三份：`local`（MySQL 方言，`application.yaml` 的默认值）、`pg`（生产用）、
   `mock`（无外部依赖的演示态）。生产显式指定 `--spring.profiles.active=pg`，
   并显式设置 §2.1 表中的环境变量。
4. 前端：`cd solo-ui && npm ci && npm run build`，把 `dist/` 交给 nginx；
   反代规则可直接参考 `solo-ui/nginx.conf`（注意 `proxy_buffering off`，见 §5.3）。

### 2.3 配置覆盖方式

`application-pg.yaml` 中所有环境相关项都是 `${ENV:默认值}` 形式，**默认值即本机开发口径**，
容器部署时由 `deploy/docker-compose.yml` 的环境变量覆盖。因此：

- 改配置优先用环境变量，不要 fork 一份 yaml；
- 环境变量清单见 `deploy/.env.example`，yaml 里的默认值可作对照。

---

## 3. 升级与回滚

升级固定五步，缺一不可：

1. **备份**：见 §6。升级前的那份备份单独留存，不要被 `KEEP_DAYS` 轮转掉。
2. **停写**：`docker compose stop solo-ui solo`（先停入口，避免升级中出现半旧半新的写入）。
3. **执行升级脚本**：`sql/upgrade/` 下按版本顺序。
   平台脚本遵循"可重复执行"约定（`INSERT ... ON CONFLICT` / `WHERE NOT EXISTS`），但仍要求先备份。
4. **起服务并验探针**：`docker compose up -d`，`curl -fsS .../api/health/ready` 必须 200。
5. **验业务面**：登录控制台 → 设备列表 → 点开一路视频 → 运行自检页（`/res/observability`）。

回滚边界（必须提前知道）：

- **只回滚应用、不回滚库**：新版本只做加列/加表时可行，旧版本会忽略新列。
- **涉及删列/改类型的脚本不可回滚**：只能"恢复库 + 回退应用"，
  恢复操作用 §6.3 的 `pg_restore --clean --if-exists`，并确认备份时间点。
- 回滚后立刻复查 `/api/health/ready` 与审计日志写入是否正常。

---

## 4. 健康探针

两个端点，**免登录**，路径已在 `WebMvcConfig` 的 JWT 放行清单内。

| 端点 | 语义 | 访问外部依赖 | 状态码 |
|---|---|---|---|
| `GET /api/health` | 存活 | 否（进程能响应即 UP） | 恒 200 |
| `GET /api/health/ready` | 就绪 | DB `isValid` + Redis 往返 + 读库内媒体节点状态 | 就绪 200 / 未就绪 **503** |

就绪响应带依赖明细，便于排障：

```json
{"code":200,"data":{"ready":true,"checkedAt":"...","cachedForMs":0,
  "dependencies":[
    {"name":"db","status":"UP","costMs":2},
    {"name":"redis","status":"UP","costMs":1},
    {"name":"media","status":"UP","detail":"库内在线节点 1/1（不主动探活 ZLM）","costMs":0}
  ]}}
```

判定口径与三个参数（`application.yaml`）：

| 参数 | 默认 | 含义 |
|---|---|---|
| `health.ready-cache-ms` | `5000` | 就绪结果缓存。秒级探针每次都开连接会把连接池探成瓶颈；`0` = 不缓存 |
| `health.ready-require-media` | `false` | 媒体节点是否参与就绪判定。默认不参与：ZLM 抖动时管理 API / 目录同步 / SSE 仍可用，不该把实例整体摘掉 |
| `health.db-timeout-seconds` | `2` | JDBC `isValid` 超时 |

两条纪律：

- **不主动探活 ZLM**。媒体节点状态取库里的巡检结果，探针保持"便宜且稳定"。
- **响应不带异常原文**。`SQLException` 常把连接串、库名带出来，细节只进服务端日志。

负载均衡/编排建议：

- 存活探针用于重启判定（`HEALTHCHECK` / `livenessProbe`），周期可短（10–15s）。
- 就绪探针用于摘流（`readinessProbe`），周期 ≥ 缓存时长，避免缓存失效带来的抖动。
- **媒体面故障不摘实例**：需要"ZLM 全挂也报警"时，看 `/res/observability` 页与 `[健康检查]` 日志，
  或把 `ready-require-media` 设为 `true`——但要清楚这是"媒体故障即整站摘流"的取舍。

---

## 5. 日志与排障入口

### 5.1 文件与滚动

日志目录由 `LOG_DIR` 控制（默认 `logs`，容器内为 `/var/log/solo`，compose 已挂出到 `deploy/logs`）。

| 文件 | 内容 | 滚动策略 |
|---|---|---|
| `solo.log` | 业务链路：SIP、级联目录、点播、接口请求、慢 SQL、保留期、审计旁路告警 | 20MB/文件，保留 14 天，封顶 1GB |
| `scheduled.log` | 定时任务与周期对账：健康巡检、拉流代理对账、节点负载刷新 | 20MB/文件，保留 7 天，封顶 300MB |

分流规则（`logback-spring.xml` 的 `PERIODIC_RULES`）：

- 周期任务的 **INFO 及以下 → 只进周期日志**；
- **WARN / ERROR 两侧都写**，周期任务里的错误不会被藏进小文件；
- 按消息前缀分流的条目（如 `[拉流代理对账]`、`[推流] reconcile`）——**改日志文案时必须同步改这里**，
  否则规则静默失效。

### 5.2 关键字速查

| 关键字 | 含义 | 处置 |
|---|---|---|
| `[健康检查] 未就绪` | 探针判定失败，带 `db/redis/media` 状态 | 看同行的依赖状态，按 §9 定位 |
| `[慢SQL]` | 超过 `perf.slow-sql-ms` 的语句（只记 Mapper 方法 id、耗时、参数个数） | 见 §7.1 |
| `[慢SQL] 近 5 分钟新增` | 5 分钟增量汇总（含累计与峰值） | 判断是偶发还是集中出现 |
| `[保留期] xxx 清理 N 条` | 保留期回收成功 | 正常；连续多轮为 0 说明已追平 |
| `[保留期] xxx 回收失败` | 单表失败，下一轮继续（不影响其他表） | 多为锁等待/索引缺失，见 §7.3 |
| `触发限流` | 用户触发 `@RateLimiter`（导出 5 次/分/用户） | 属预期；若误伤看 §7.2 |
| `限流组件降级放行` | **Redis 异常**导致限流失效（含累计次数） | 优先修 Redis，限流只是失去保护 |
| `[审计] 线程池拒绝，本条丢弃` | 审计队列满，事件被丢 | 查慢 SQL / 库压力；审计可丢，业务不等 |
| `[审计] 写入失败` | 审计落库失败（旁路，不影响业务） | 查库连通性与表空间 |
| `[openapi接口调用]` | 开放平台每次调用（业务码 + 耗时） | 对应控制台「开放平台 → 调用日志」 |

### 5.3 反代两个必设项

`solo-ui/nginx.conf` 已内置，自建 nginx 时必须照抄：

- `/api`、`/open-api` 的 `proxy_buffering off`：**SSE（消息推送、告警）与 CSV 导出流式输出**
  一旦被 nginx 缓冲，前端表现为"卡住不出数据"。
- `proxy_read_timeout 1800s`：点播/回放是长连接，默认 60s 会把播放页掐断。

---

## 6. 备份与恢复

### 6.1 备份预算（设计文档 5.2.1）

- **只备份关系库**：`audit_log`、业务表、配置与权限全在库里。
- **对象存储里的录像/文件不进数据库备份**——它们走存储侧生命周期与版本控制，
  把几十 GB 录像塞进库里既压垮备份窗口，也压垮恢复演练。
- 建议：每日一次全量，保留 14 天（`KEEP_DAYS` 默认值），并至少每季度做一次**恢复演练**。

### 6.2 执行备份

Linux（推荐，cron 定时）：

```bash
# PostgreSQL
PGPASSWORD=xxx ./scripts/backup.sh
# MySQL
DB_KIND=mysql MYSQL_PWD=xxx DB_HOST=192.168.1.10 DB_USER=root ./scripts/backup.sh

# 每天 02:30
30 2 * * * cd /opt/solo-platform && PGPASSWORD=xxx ./scripts/backup.sh >> /var/log/solo/backup.log 2>&1
```

Windows 开发机：

```powershell
$env:PGPASSWORD='xxx'; .\scripts\backup.ps1
.\scripts\backup.ps1 -Kind mysql -HostName 192.168.1.10 -User root -Password xxx
```

可覆盖变量：`DB_KIND=pg|mysql`、`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、
`BACKUP_DIR`（默认 `./backups`）、`KEEP_DAYS`（默认 14）。

脚本的两个关键设计：

- **备份完立刻自校验**：PG 用 `pg_restore --list` 验证归档目录可读，MySQL 用 `gzip -t` 验证压缩头。
  **坏包比没包更危险**——它会让人以为有备份。
- **失败自动删半成品**：`trap` 在非零退出时删掉本次文件，避免盘里躺着恢复不了的文件。

输出格式：

| 数据库 | 命令要点 | 产物 |
|---|---|---|
| PostgreSQL | `pg_dump -Fc --no-owner --no-privileges` | `<db>-<时间戳>.dump` |
| MySQL | `mysqldump --single-transaction --quick --routines --triggers --events` + `gzip` | `<db>-<时间戳>.sql.gz` |

`--single-transaction` 保证一致性快照且**不锁业务表**；`--routines/--triggers/--events`
保证存储过程与事件调度器不丢。

### 6.3 恢复

**先恢复到临时库验证，再决定是否覆盖生产**——这是本节最重要的建议。

```bash
# PostgreSQL：恢复到新库
createdb -h 127.0.0.1 -U postgres solo_restore
pg_restore -h 127.0.0.1 -U postgres -d solo_restore --no-owner --no-privileges -j 4 solo_db-20261123-023000.dump

# 确认无误后覆盖生产（会先 DROP 同名对象）
pg_restore -h 127.0.0.1 -U postgres -d solo_db --clean --if-exists --no-owner -j 4 solo_db-20261123-023000.dump

# MySQL
gunzip -c solo_db-20261123-023000.sql.gz | mysql -h 127.0.0.1 -u root -p solo_restore
```

恢复后必做三件事：

1. `curl -fsS http://127.0.0.1:8080/api/health/ready` → 200；
2. 登录控制台，确认账号/权限/菜单在（权限码与角色表是升级脚本维护的，容易漏）；
3. 看 `solo.log` 是否有启动期异常（SIP 栈、Redis 连接、ZLM 初始化）。

> 恢复期间 Redis 里的 SSRC 池、nonce、节点负载仍是"恢复前"的状态；
> 若差异较大，清掉相关键（或直接清库）让平台重建，避免与库内会话表不一致。

---

## 7. 性能治理

### 7.1 慢 SQL

| 参数 | 默认 | 含义 |
|---|---|---|
| `perf.slow-sql-ms` | `1000` | 超过该耗时记一条慢 SQL；`<=0` 关闭 |
| `perf.slow-sql-warn-per-minute` | `20` | 每分钟最多打多少条 WARN，超出只计数 |

实现纪律（`infra/ops/SlowSqlInterceptor` + `SlowSqlStats`）：

- 只拦 MyBatis `Executor` 的 4 参 `query` 与 `update`，**不记 SQL 原文**，
  只记 Mapper 方法 id、耗时、参数个数——慢 SQL 日志本身不能成为泄露或注入点；
- WARN 按分钟限流，另有 5 分钟增量汇总（`[慢SQL] 近 5 分钟新增 ...`），
  防止一次慢查询风暴把日志盘写满；
- 阈值调优建议：先按 1000ms 观察一周，若 `[慢SQL]` 集中在固定几个 Mapper，
  针对它们加索引或改分页，**不要靠调高阈值掩盖**。

### 7.2 导出限流与审计

两个导出端点（`/api/res/channel/export`、`/api/res/play-record/export`）：

- **限流**：`@RateLimiter`，每用户 **5 次 / 分钟**，Redis Lua 滑动窗口。
  key 由"前缀 + 类名.方法名 + 用户"拼成，两个端点天然互不影响。
- **降级**：Redis 故障时限流**放行**并打 `限流组件降级放行`（含累计次数）。
  这是刻意取舍——限流是保护措施，不能因为保护组件挂了就阻断业务；但必须监控该日志。
- **上限**：单次最多 **50000 行**，超量请走离线抽取。
- **必留痕**：每次导出写一条审计 `EXPORT_CHANNEL` / `EXPORT_PLAY_RECORD`，
  含行数与上限（设计文档 3.12.6）。

误伤处置：确认是正常运维导表时，可让用户分流到不同账号，或临时调整注解参数后重新发布；
**不要**用清 Redis 限流键当作常规手段（会丢失滑动窗口，反而在高峰被打穿）。

### 7.3 保留期与索引

`retention.*`（三份 yaml 均已配置）：

| 参数 | pg 默认 | 含义 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `audit-log-days` | `365` | 审计保留天数 |
| `play-record-days` | `180` | 播放记录保留天数 |
| `sync-days` | `365` | `sync_task` / `sync_record` / `sync_round` 保留天数 |
| `batch-size` | `5000` | 单批删除行数（下限 100） |
| `max-batches-per-run` | `200` | 单轮最多批数 → 单表单轮最多约 100 万行 |
| `startup-delay-ms` | `300000` | 启动后延迟 5 分钟再跑第一轮 |
| `interval-ms` | `86400000` | 每天一轮 |

行为约定：

- 五张表各自 `try/catch`，**一张失败不影响其他表**，下一轮继续（定时任务抛未捕获异常会永久停调）；
- 顺序是"明细先于主体"（`sync_record` 先于 `sync_task`），避免出现"有明细没任务"的孤儿窗口；
- 某张表天数 `<=0` **只关该表**；`enabled=false` 全关（交给分区 DROP 或人工）。

**已知索引缺口**：`sync_task` 与 `sync_round` 目前**没有以时间列打头的索引**
（`create_time` / `begin_time`），保留期删除与按时间查询会退化成全表扫。
用 `sql/ops/index_review_{mysql,pg}.sql` 复核：脚本会把这类缺口标成 `MISSING` 并给出注释态的
`ALTER` / `CREATE INDEX CONCURRENTLY` 建议。生产建索引注意：

- MySQL 用 `ALGORITHM=INPLACE, LOCK=NONE`，避开写入阻塞；
- PostgreSQL 必须 `CREATE INDEX CONCURRENTLY`（不能放在事务里），且失败会留下 invalid 索引需手工删。

容量口径：审计 365 天、播放记录 180 天、同步明细 365 天是**当前默认**。
上线前按真实日增量估算（行数 × 行宽），并对照磁盘与备份窗口做一次预算；
需要缩短时改 `retention.*` 即可，无需改代码。

---

## 8. 安全运维

| 项 | 要求 | 落点 |
|---|---|---|
| 传输加密 | 对外必须 HTTPS；HSTS 在 TLS 终止处开启 | `solo-ui/nginx.conf` 中 HSTS 行为注释态，需显式启用 |
| 代理信任 | `OPENAPI_TRUSTED_PROXIES` 只填可信反代地址/网段 | 容器内互调填 Docker 网段（如 `172.16.0.0/12`） |
| 实例身份 | 容器以非 root（uid 10001）运行 | `solo/Dockerfile` |
| SIP 口令 | strong random，禁用 `123456` 类弱口令 | `SOLO_SIP_PASSWORD` |
| JWT 密钥 | `openssl rand -base64 48`，换掉默认值 | `SOLO_JWT_SECRET` |
| 审计 | 只追加，**无修改接口**；保留 365 天 | `audit_log` + §7.3 |
| 暴露面 | 管理 API 不对公网直开；ZLM/S3 端口只对必要来源放开 | 网络策略 |

关于审计：`audit_log` 只追加、删除仅由保留期任务执行——**任何"手工改审计记录"的操作都不在平台能力内**，
需要保全证据时应先备份审计表再操作。

---

## 9. 常见故障处置

| 现象 | 首查 | 常见原因与处置 |
|---|---|---|
| `/api/health/ready` 返回 503，`db=DOWN` | `solo.log` 的 `[健康检查] db 探测失败` | 库未起/口令错/连接池耗尽；`docker compose ps` 看 postgres 健康态 |
| 503 且 `redis=DOWN` | `[健康检查] redis 探测失败` | Redis 不可达或需密码；注意此时限流已降级放行 |
| 200 但 `media` 为 DOWN | `/res/observability` 页、节点健康巡检日志 | 正常现象（默认不参与就绪）；确认 ZLM 地址与 `[api].secret` |
| 设备注册不上 | SIP 抓包 5060/**udp** | `SOLO_IP` 是否可达、5060 tcp 与 udp 是否都放行、平台编码/域是否与设备侧一致 |
| 级联上级平台收不到目录 | `solo.log` 中级联目录链路 | 上级平台编码/域、`SOLO_SIP_PUBLIC_IP`（NAT）、注册状态 |
| 点播 30 秒后断开 | nginx `proxy_read_timeout` | 未设 1800s，长连接被反代掐断 |
| 消息/告警不刷新、导出没反应 | nginx `proxy_buffering` | 未关缓冲，SSE 与 CSV 流被攒住 |
| 页面能开、接口 502 | `docker compose logs solo` | 后端未起或端口映射错；先看 `/api/health` |
| 审计有 `线程池拒绝，本条丢弃` | 慢 SQL 列表、库负载 | 库被拖慢导致审计队列堆积；先治慢查询 |
| 保留期每轮都 `回收失败` | `sql/ops/index_review_*.sql` | 索引缺口导致全表扫超时/锁等待；补 `create_time` / `begin_time` 索引 |
| 录像/回放文件缺失 | S3 连通性、桶名、生命周期 | `SOLO_S3_*` 配置；对象存储侧生命周期是否提前删除 |
| 限流频繁 `触发限流` | 用户导出行数与频率 | 属预期；改用离线抽取或拆分账号，见 §7.2 |

---

## 10. 变更检查清单

**上线前**

- [ ] `deploy/.env.example` 复制为 `.env`，六项必改变量全部替换（§2.1）
- [ ] `SOLO_IP` 是设备可达 IP；5060/udp 与 5060/tcp 都放行
- [ ] `SOLO_ZLM_SECRET` / `SOLO_ZLM_SERVER_ID` / hook secret 与 ZLM 侧一致
- [ ] 已按版本顺序执行 `sql/upgrade/`，且已备份
- [ ] 反代已设 `proxy_buffering off` 与长超时；HTTPS 与 HSTS 已启用
- [ ] `/api/health/ready` 返回 200，且三条依赖都为 UP
- [ ] 登录 → 设备列表 → 点开一路视频 → 运行自检页，四步走通

**日常**

- [ ] 每日备份成功（日志无 `失败`，目标目录有新文件）
- [ ] `[慢SQL]` 无突然放大；`限流组件降级放行` 计数不增长
- [ ] `[保留期]` 有清理记录且未持续报 `回收失败`
- [ ] 磁盘使用率与 `solo.log` / `scheduled.log` 滚动正常
- [ ] `/res/observability` 出流带宽与在线节点数在预期范围

**每季度**

- [ ] 备份恢复演练（恢复到临时库，验探针与登录）
- [ ] `sql/ops/index_review_*.sql` 复核索引缺口与表体积
- [ ] 保留期天数与磁盘容量重新做预算（§7.3）
- [ ] 密钥轮换评估（SIP / JWT / S3 / ZLM）

---

## 11. 相关产物索引

| 类型 | 路径 |
|---|---|
| 容器编排 | `deploy/docker-compose.yml`、`deploy/.env.example` |
| 镜像 | `solo/Dockerfile`、`solo-ui/Dockerfile`、`solo-ui/nginx.conf` |
| 备份 | `scripts/backup.sh`、`scripts/backup.ps1` |
| M7 验收脚本 | `scripts/verify_m7.sh`（隔离栈 `solo-verify`：起栈 / 探针 / 保留期 / 导出限流 / 备份恢复） |
| 索引复核 | `sql/ops/index_review_mysql.sql`、`sql/ops/index_review_pg.sql` |
| 建库/升级 | `sql/MySQL_solo_db.sql`、`sql/PostgreSQL_solo_db.sql`、`sql/upgrade/` |
| 媒体与存储 | `zlm/docker-compose.yml`、`rustfs/docker-compose.yml` |
| 架构依据 | `docs/多平台视频资源管理系统——需求梳理与总体架构设计 V1.0.md`、`docs/ARCHITECTURE_CONSTRAINTS.md` |

参数总表在 `solo/src/main/resources/application.yaml`（含注释），
环境相关默认值在 `application-pg.yaml`；**改配置请用环境变量覆盖，不要改默认值**（§2.3）。
