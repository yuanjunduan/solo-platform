# 实现进度台账

> 每个任务完成后必须更新本文件。跨轮次上下文被压缩时，靠这个文件接上，避免重复造轮子或擅改签名。
> 硬约束见 `docs/ARCHITECTURE_CONSTRAINTS.md`（C1–C28），架构原文见 `docs/多平台视频资源管理系统——需求梳理与总体架构设计 V1.0.md`。

---

## 1. 代码现状（遗留基线，不是目标架构）

现存代码服务于"对接多厂家平台"的旧需求，**与目标架构不是同一代**：

| 层 | 现状 |
|---|---|
| 表 | 遗留 `gb_*` 系列（`gb_org` / `gb_device` / `gb_device_channel` / `gb_platform` / `gb_platform_registry` / `gb_parent_platform` / `gb_platform_share` / `gb_record_*` / `gb_ptz_*` …）**继续保留**；目标架构的 `res_org` / `res_group` / `res_device` / `res_channel` / `platform` / `platform_credential` / `audit_log` 已在 M1 建好（`sql/upgrade/2026-11-23_res_core_*.sql`）。M2 起才会有 `out_*` / `sync_*` |
| 后端包 | 遗留 `com.solo.{aop, common, config, gb28181, media, onvif, openapi, system, tests, websocket}`；新增目标架构包 `com.solo.api.resource`、`com.solo.domain.{resource, platform, audit}`、`com.solo.infra.mapper` |
| gb28181 子包 | `auth / config / constant / controller / entity / mapper / processor / request / response / service / sip / subscribe / support / task / transmit / utils` |
| 前端 | `solo-ui`（Vue 3 + Vite），features 覆盖 access/auth/command/config/dict/file/filter/gb/log/media/message/online/onvif/openapi/permission/role/server/terminal/test/user。**资源中心页面（M1 后端已就绪）前端尚未建** |
| 技术栈 | Spring Boot 3 + MyBatis-Plus + MySQL 8 / PostgreSQL 双库（`databaseId` 双方言）+ Redis + ZLMediaKit |

**结论：M1 后端完成（T1.1–T1.6 全绿，24 用例）。** 新代码按 3.3.2 包边界新建，不往 `gb_*` 表叠加新语义；`gb_*` 与 `res_*` 并行存在，前者服务遗留国标链路，后者是目标架构的唯一资源底座。

---

## 2. 任务进度（按《AI 分步实现提示词手册》T 编号）

| T | 任务 | 状态 | 备注 |
|---|---|---|---|
| T1.1 | 工程骨架与分包 | ✅ | `api.resource` / `domain.{resource,platform,audit}` / `infra.mapper` 已建；其余包随所属模块建 |
| T1.2 | 资源三表 + 平台表 DDL | ✅ | `2026-11-23_res_core_mysql.sql` / `_pg.sql`：`res_org` / `res_group` / `res_device` / `res_channel` / `platform` / `platform_credential` / `audit_log` |
| T1.3 | 本地资源 CRUD + 物化路径 | ✅ | `OrgController` / `DeviceController` / `ChannelController`（`/api/res/{org,device,channel}`）+ 16 条权限码种子 |
| T1.4 | 只读守卫骨架 | ✅ | `ReadOnlyGuard`（NATIVE 可写，INBOUND 一律 403）+ 服务层校验 |
| T1.5 | 业务键原地 UPDATE 写入路径 | ✅ | `ResourceUpsertService`：命中 `(source_type, source_platform_id, code)` 即 UPDATE 同一行，id 稳定 |
| T1.6 | 登录 / RBAC / 审计基础 | ✅ | RBAC 复用遗留 `system` 包；新增 `AuditService` 异步旁路（`audit.enabled` 可关，写失败不回滚业务） |
| T2.1 | GB28181 注册与目录查询适配器 | ✅ | `InboundAdapter` SPI + `Gb28181InboundAdapter`（SIP Catalog 分片回调 → sink），协议路由 `InboundAdapterRegistry` |
| T2.2 | staging + 三段会话 | ✅ | `sync_staging` + `InboundSyncServiceImpl`（start/synching/finish、互斥、冷却、按"无进展"超时）+ `SyncStagingWriter` 分页写入 |
| T2.3 | 三条集合 SQL | ✅ | `Sync{Org,Group,Device,Channel}AlignmentMapper`：建树 / 新增 / 差异更新 / 消失 / 归属纠正；`SyncColumns` 同源生成 + 双方言分支 |
| T2.4 | 影子对比测试 | 🟡 | 谓词级已覆盖（`SyncGuardPredicateParityTest`：G2 分子与删除谓词逐字一致、无 XML 实体泄漏）；逐字段结果集 diff 需实库，见 §9.5 |
| T2.5 | 左树并列 + 入站播放 | ✅ | `/res/org` `/res/device` `/res/channel` 三页共用 `useResTree`；入站通道经 `POST /api/gb28181/play/inbound` 复用既有 invite |
| T2.6 | G1/G2 删除闸门 | ✅ | `SyncGuards` 纯函数 + `SyncMergeService` 在四条删除语句前各自裁决，`delete_skipped`/`guard_ratio` 入台账 |
| T2.7 | 消失软删与共享联动 | ✅ | 资源侧 STALE 软删；`ShareDisappearService.afterMerge` 在 `SyncMergeService.finish` 同事务内把 `out_share.ref_alive` 置 0（不删行）并入 outbox，见 §12 |
| T2.8 | 四步回填 + 延迟重试 | 🟡 | 四步回填在 `insertNew`/`refreshOrg` 里一次 JOIN 算完 ✅；延迟重试随 M2b |
| T2.9 | sync_round 台账 | ✅ | 由 `sync_task` 承载（进度/分级计数/闸门快照/指纹/errorCode）+ `sync_record` 单条留痕 + 同步中心页 |
| T2.10 | 残留清理 + 互斥冷却 | ✅ | `uk_active_platform` 互斥、`SyncCooldownStore` 冷却、`sweepTimeout` 无进展兜底、`SyncStagingMapper.deleteOrphans` |
| T2.11 | 报文快照（C 档，默认关） | ⬜ | 首版可缓 |
| T3.1 | SSE 事件流 + 游标续传 | ✅ | `/open-api/v1/events/stream`：查库轮询 + `Last-Event-ID` 续传 + `lagged` 背压（不跳号）+ 409/429 建连前判定，见 §11 |
| T3.2 | 全量快照接口 + scope 鉴权 | ✅ | `/open-api/v1/snapshot`：三类资源分页、scope 只能收紧、返回 `cursor` 与 SSE 对齐，见 §11 |
| T3.3 | 两形态降级 | ✅ | `EventShapeDecider` 五种降级规则 + `SyncMergeService` 收尾埋点（同事务），见 §11 |
| T4.1 | 共享编排 + 虚拟组织树 | ✅ | `out_group` / `out_share` + `ShareComposeService`（SUBTREE 规则、派生、剔除/恢复、一键生成 215/216 虚拟树），见 §12 |
| T4.2 | 编码映射 + 冲突就地处理 | ✅ | `out_code_map`（ACTIVE/TOMBSTONE，seq 平台内单调不复用）+ `OutCodeService` 分配与逆向解析，见 §12 |
| T4.3 | Outbox 投递器 | ✅ | `outbox` + `OutboxDispatchService` / `OutboundSipDelivery` / `SharePublishJob`（指数退避、retry-max→DEAD、平台租约） |
| T4.4 | publish_round 台账 + 对账 | ✅ | `publish_round` + `ShareRepublishService`（全量重推）与 `ShareReconcileService`（只补差异）；发布页可见 |
| T5.1 | 四道防环 | ✅ | `ShareGuard`（REVERSE / ORIGIN_CHAIN / HOP_LIMIT / SELF_DOMAIN）保存时与投递前各判一次；`ShareGuardTest` 8 例 |
| T5.2 | 两级媒体串联 | ✅ | **完成**：SDP 自建、两侧 SSRC 与 srcStream 关联、`by_src` Set 索引、僵尸清理、`sdpRewritten`、UDP/TCP-passive 端口 > 0、`c=` 地址校验与端口兜底、`lastRtpTime` + 源流巡检判活均已落地（见第 6、7 节） |
| T5.3 | 每跳独立互斥 | ✅ | `MediaHopGuard`：`in:{streamId}` / `out:{platformId}:{channelId}` 两条腿各自互斥 + 失败冷却（`hop-cooldown-sec`），入站接在 `GbPlayServiceImpl`、出站接在 `CascadeSendService`；状态跟随与同源关联见 §13 |
| T6.1 | 录像回放 | ✅ | 统一入口 `/{id}/playback/{query,start}` + `/playback/{stop,control}`：本地走设备 INVITE，入站交来源平台转发；见 §14 |
| T6.2 | 流复用与空闲回收 | ✅ | `GbIdleReclaimService` + 10s 巡检：READY 的 PLAY/PLAYBACK 连续无观看者超过 `gb28181.play.idle-reclaim-sec`（默认 120s）→ BYE + 关流 + 闭合播放记录；见 §14 |
| T6.3 | 云台 / 预置位 | ✅ | `/{id}/ptz/*` + `/{id}/presets*` 全部落到 `res_channel` 与 `res_channel_preset`，入站由来源平台转发；见 §14 |
| T6.4 | 可观测与批量导出 | ✅ | `GET /api/res/channel/export`、`/api/res/play-record/{page,export}`、`GET /api/res/observability/summary` + `/res/play-record` `/res/observability` 两页；见 §14 |

图例：⬜ 未开始 / 🟡 有遗留实现可参考，需按新约束重写 / ✅ 完成

---

## 3. 关键签名（后续步骤的输入，改前必须先通知）

### 3.1 目标架构表

M1 已建（DDL 见 `sql/upgrade/2026-11-23_res_core_mysql.sql` / `_pg.sql`，**手工执行，无 Flyway**）：
`res_org` / `res_group` / `res_device` / `res_channel` / `platform` / `platform_credential` / `audit_log`。
下列其余表已在 M2–M4 建好（出站五表见 `sql/upgrade/2026-11-24_share_core_mysql.sql` / `_pg.sql`）：

```
res_org      (id, source_type, source_platform_id, code, parent_id, path, name, status, ...)
             uk_src(source_type, source_platform_id, code); idx_parent, idx_path, idx_src
res_device   (id, source_type, source_platform_id, code, org_id, path, name, device_ip, vendor,
              model, channel_count, fingerprint, origin_platform_id, origin_code, origin_chain,
              hop_count, status, removed_at, ...)
res_channel  (id, source_type, source_platform_id, code, org_id, device_id, path, name,
              online_status, origin_platform_id, origin_code, origin_chain, hop_count,
              canonical_id, status, removed_at, ...)
             status 默认 ACTIVE；取值 ACTIVE/STALE/OFFLINE/REMOVED
             canonical_id 只做同源逻辑关联（0/空 = 组主），不物理合并行（见 §13）
out_group    (id, platform_id, code, group_type 215/216, name, parent_id, source_org_id, ...)
             uk(platform_id, source_org_id)   -- 一键生成的幂等键
out_share    (id, platform_id, group_id, ref_type, ref_id, scope_mode, derived, enabled,
              ref_alive, ref_online_status, operator, ...)
             -- ref_alive=0 表示资源已 REMOVED，不删行
             -- ref_online_status 是"上次推给上级的在线状态"基线，状态跟随靠它判真变化（见 §13）
out_code_map (id, platform_id, native_code, exposed_code, state, ...)  -- state: ACTIVE/TOMBSTONE
outbox       (id, aggregate_type, aggregate_id, type, payload, status, retry, last_error, ...)
platform_credential (..., cipher_suite DEFAULT 'NONE' + 5 个 GB35114 可空字段占位)
sync_task    (id, platform_id, status, progress, added/updated/stale/failed 计数, ...)
sync_staging (task_id, code, ...)             -- 本轮远端基准，不是旁路（C14）
sync_round   (task_sn, platform_id, begin/end_time, status, org_recv, dev_recv,
              org_insert, dev_insert, dev_update, dev_delete, dev_dup,
              delete_skipped, guard_ratio, error_code, error_msg, summary)
sync_round_snapshot (轮分区，默认关闭，只留 2 轮)
publish_round (目标平台 / 应推 / 实推 / 失败 / 是否降级 / 错误摘要)
res_play_record (id, channel_row_id, channel_code, channel_name, source_type, source_platform_id,
                 action PLAY|PLAYBACK, stream_id, actor_id, actor_name, client_ip,
                 result SUCCESS|FAILED, fail_reason, begin_time, end_time, duration_sec, ...)
                 -- 一次点播/回放请求一行；失败即闭合；成功在 stop / 空闲回收时闭合（见 §14）
res_channel_preset (id, channel_row_id, preset_index 1-255, name, ...)
                 uk(channel_row_id, preset_index)  -- 同编号覆盖该槽位
```

### 3.2 已有可复用组件（遗留代码，真实签名）

**`SdpBuilder`**（`com.solo.gb28181.transmit.cmd`，415 行）
```java
buildSendAnswer(platformId, sdpIp, sendPort, ssrc, upstreamTransport)  // 级联回 200 OK
buildPlay / buildDownload                                             // 点播 / 下载 offer
buildBroadcastAnswer / buildTalkAnswer / buildAudioRecvonlyOffer       // 广播 / 对讲
extractConnectionIp / extractMediaPort / extractSsrc / extractTransport
extractAudioPort / extractAudioTransport / extractAudioDirection / selectAudioPayload
normalizeTransport
PT_PS = 96 / PT_PCMA = 8
TRANSPORT_UDP / TRANSPORT_TCP_ACTIVE / TRANSPORT_TCP_PASSIVE
```

**`ZlmRestClient`**（`com.solo.media.client`，678 行）
```java
openRtpServer(server, streamId, tcpMode, port) -> actualPort
startSendRtp(server, app, stream, ssrc, dstIp, dstPort, udp, Integer srcPort) -> localPort   // null=ZLM 自选
startSendRtp(..., udp, Integer srcPort, pt, usePs, onlyAudio) -> localPort
startSendRtpPassive(server, app, stream, ssrc) -> localPort
startSendRtpPassive(..., udp, Integer srcPort, recvStreamId, closeDelayMs) -> localPort
stopSendRtp(server, app, stream, ssrc)      // 幂等，-500 can not find the stream 视为成功
getMediaList(server) -> List<JsonNode>   // 节点不可达返回空 List（吞异常）
getMediaListOrNull(server) -> List<JsonNode>|null  // null = 节点不可达，供判活 fail-open 用
// getMediaList 条目字段（实测 2024-08 构建）：schema/app/stream/bytesSpeed/readerCount/
// totalReaderCount/createStamp/aliveSecond，**没有 origin_stamp**；aliveSecond 是流创建时刻
exchangeWebRtcSdp(server, app, stream, type, offerSdp) -> answerSdp
```

**会话对象**（Redis 主存，**不是表**）
```java
GbInviteSession {           // 来源侧：本平台 → 设备/下级
  streamId, serverId, deviceId, channelId, ssrc, rtpPort, transport, streamNumber,
  callId, fromTag, toTag, sessionType, status, flvUrl/wsFlvUrl/hlsUrl/rtcUrl,
  startTimeStr, endTimeStr, startTime, readyTime, stopTime, lastRtpTime, failReason
  // STATUS: INVITING / READY / STOPPED / TIMEOUT / FAILED
  // TYPE:   PLAY / PLAYBACK / DOWNLOAD / RECORD
}
CascadeSendSession {        // 上级侧：上级 → 本平台
  sendStreamId, serverId, platformDbId, platformServerGbId, localGbId, channelId,
  localChannelId, deviceId, srcApp, srcStream, dstIp, dstPort, ssrc, localSendPort,
  transport, callId, fromTag, toTag, status, createTime, sendTime, sdpRewritten
  // STATUS: WAIT_ACK / SENDING / STOPPED
  // isTcp() / isUpstreamActive()
}
```

**`CascadeSdpAddress`**（`com.solo.gb28181.support`，84 行，纯函数，无 Spring 依赖）
```java
resolve(sdpPublicIpOverride, streamIp, hostIp) -> String|null   // 显式配置非法时返回 null，不回退
isUsable(ip)   -> boolean    // 排除空 / 0.0.0.0 / 127.0.0.1 / ::1 / localhost
isPrivate(ip)  -> boolean    // RFC1918：10. / 172.16-31. / 192.168.
```

**`GbInviteLiveness`**（`com.solo.gb28181.support`，104 行，纯函数，无 Spring 依赖）
```java
enum Probe { FLOWING, WATCHED, SILENT, GONE, UNREACHABLE }
probe(mediaList, app, streamId) -> Probe   // mediaList=null 即 UNREACHABLE；同流多条取 max
isZombie(lastRtpTime, readyTime, now, timeoutSec) -> boolean  // lastRtpTime 为空退回 readyTime；
                                             // timeoutSec<=0 或时钟回拨（差值为负）一律 false
```

**Redis 索引**
```
cascade:send:{sendStreamId}          -> CascadeSendSession JSON
cascade:send:by_call:{callId}        -> sendStreamId
cascade:send:by_src:{srcStream}      -> Set<sendStreamId>   // 1 源流 : N 上级
cascade:send:all_active              -> Set<sendStreamId>
```

**`CascadeSendService`**（`com.solo.gb28181.service.impl`，625 行）
```java
onUpstreamInvite(request, platform, channelId, upstreamSdp)  // 上级 INVITE → 取流 → 建会话 → 回 200
onUpstreamAck(callId)                                        // ACK → startSendRtp
onUpstreamBye(session)
onSendRtpStopped(stream, ssrc)                               // ZLM 回调
sweepAckTimeout()                                            // WAIT_ACK 超时扫描
sweepZombieSources() -> int                                  // 源流巡检：判活 → 先停推再 BYE，返回清理条数
cleanup(session, byeUpstream)                                // stopSendRtp → 删会话 → BYE → 回收取流
```

### 3.3 M1 已落地签名（新包，改前必须先通知）

**REST**（`com.solo.api.resource`，权限码 `res:{org|device|channel}:{list|add|edit|delete}`）

```
GET    /api/res/org/tree?sourceType=          res:org:list
POST   /api/res/org                           res:org:add
PUT    /api/res/org/{id}                      res:org:edit
DELETE /api/res/org/{id}                      res:org:delete
POST   /api/res/device/page                   res:device:list
GET    /api/res/device/{id}                   res:device:list
POST   /api/res/device | PUT/{id} | DELETE/{id}
POST   /api/res/channel/page                  res:channel:list
GET    /api/res/channel/{id}                  res:channel:list
POST   /api/res/channel | PUT/{id} | DELETE/{id}
```

**服务**（`com.solo.domain.resource.service`）

```java
OrgService      { List<OrgTreeNode> listTree(String sourceType);
                  ResOrg create(OrgSaveRequest); void update(Long, OrgSaveRequest); void delete(Long); }
DeviceService   { Page<ResDevice> pageQuery(ResourcePageRequest); ResDevice detail(Long);
                  ResDevice create(DeviceSaveRequest); void update(Long, DeviceSaveRequest); void delete(Long); }
ChannelService  { Page<ResChannel> pageQuery(ResourcePageRequest); ResChannel detail(Long);
                  ResChannel create(ChannelSaveRequest); void update(Long, ChannelSaveRequest); void delete(Long); }
ResourceUpsertService { Long upsertOrg(ResOrg); Long upsertDevice(ResDevice); Long upsertChannel(ResChannel); }
                        // 业务键唯一入口：命中即 UPDATE 同一行，id 稳定（C3）
```

**审计**（`com.solo.domain.audit`）

```java
AuditService.record(action, targetType, targetId, detail)   // 异步旁路，audit.enabled=false 直接返回
AuditLog.TARGET_ORG / TARGET_DEVICE / TARGET_CHANNEL / TARGET_PLATFORM / TARGET_SHARE
```

**支撑层**（`com.solo.domain.resource.support`，纯函数，无 Spring 依赖）

```java
ResourcePath { ofRoot(id) -> "/id/"; ofChild(parentPath, id); levelOf(path);
               isUnder(ancestorPath, path); parentPathOf(path) }
ReadOnlyGuard{ requireWritable(sourceType, action)   // NATIVE 放行；其余一律 ServiceException(403) }
```

**Mapper 关键方法**（`com.solo.infra.mapper`）

```java
ResOrgMapper.selectByBizKey(sourceType, sourcePlatformId, code)
ResOrgMapper.rewriteSubtreePath(sourceType, sourcePlatformId, oldPrefix, newPrefix, now) -> int   // 含自身
ResOrgMapper.shiftSubtreeLevel(sourceType, sourcePlatformId, newPrefix, delta, now) -> int
ResOrgMapper.countByParentId(parentId)            // 不按状态过滤：REMOVED 子行仍占路径前缀
ResOrgMapper.countSiblingName(parentId, name, excludeId)
ResDeviceMapper.rewriteOrgPath(oldPrefix, newPrefix, now) -> int     // **不按来源过滤**
ResChannelMapper.rewriteOrgPath(oldPrefix, newPrefix, now) -> int    // **不按来源过滤**
ResDeviceMapper.countGroupByOrg() / ResChannelMapper.countGroupByOrg() -> List<OrgRefCount>(orgId, total)
```

**新增配置项**（`application.yaml`）

```yaml
audit:
  enabled: true    # false = 审计旁路整体关闭
```

**SQL 脚本**（手工执行，顺序即依赖顺序）

```
sql/upgrade/2026-11-23_res_core_mysql.sql     表结构（MySQL）
sql/upgrade/2026-11-23_res_core_pg.sql        表结构（PostgreSQL）
sql/upgrade/2026-11-23_res_menu_mysql.sql     资源中心菜单 + 16 条权限 + 授 role_id=1
sql/upgrade/2026-11-23_res_menu_pg.sql        同上（PG 版）
```

### 3.4 M2a 已落地签名（新包，改前必须先通知）

**表**（DDL 在 `2026-11-23_res_core_*.sql` 第 8–12 段，手工执行）

```
sync_task           一轮一行：status(PENDING/RUNNING/SYNCHING/SUCCESS/FAILED/CANCELLED)、progress、
                    recv_total、org/dev/ch_recv、added/updated/stale/failed、delete_skipped、guard_ratio、
                    org_missing_count、catalog_fingerprint、error_code/msg、begin/end_time
                    active_platform_id 为生成列：活跃态才有值，uk_active_platform 承担互斥（C24）
sync_staging        本轮远端基准：uk_task_item(task_id, item_type, code)，item_type=ORG/GROUP/DEVICE/CHANNEL
sync_record         单条变更留痕：change_type=ADD/UPDATE/STALE/REMOVE/RESTORE
platform_catalog_ver 目录指纹：platform_id 主键，fingerprint/item_count/last_task_id/last_success_at
device_org_binding  设备↔组织人工绑定：uk_device(source_platform_id, device_code)
```

**SPI**（`com.solo.domain.sync.spi`）

```java
InboundAdapter   { String protocol(); CatalogSession open(PlatformConn conn, CatalogSink sink); }
CatalogSink      { void onBatch(CatalogBatch batch); void onComplete(); void onError(String code, String msg); }
CatalogSession   { String ref(); void close(); }                       // 只负责"能中止"
CatalogBatch     record(List<CatalogItem> items, Integer expectedTotal, Integer declaredNum)
PlatformConn     record(Long tenantId, Long platformId, String code, String localDomain,
                        String host, Integer port, String syncProtocol)   // 不带 Platform 实体
InboundAdapterRegistry.resolve(String syncProtocol, String fallback) -> InboundAdapter
// 实现：Gb28181InboundAdapter（com.solo.gb28181.inbound）
```

**服务**（`com.solo.domain.sync.service` / `.support`）

```java
InboundSyncService      Long trigger(Long platformId, String triggerType);
                        void cancel(Long taskId); int sweepTimeout();
SyncMergeService        void finish(Long taskId)     // 单事务：建树→设备/通道→闸门→指纹→台账
SyncStagingWriter       void append(SyncTask task, String platformCode, CatalogBatch batch)
SyncCenterService       PageResult<SyncTask> pageTasks(SyncTaskQueryRequest);
                        SyncTaskDetail detail(Long taskId);
                        PageResult<SyncRecord> pageRecords(Long taskId, Long page, Long pageSize);
SyncCooldownStore       markFailed / remainingMs / assertReady
SyncSessionRegistry     put / close
InboundScaffoldService.ensure(Platform) -> Scaffold(platformCode, rootOrgId, unclassifiedOrgId, carrierDeviceId)
SyncGuards              emptyStagingIsFatal(staged, localRows) / allowStale(would, local, ratio, minLocal)
SyncSettings            stall-timeout-ms / cooldown-extra-ms / g2.min-local-rows /
                        staging.page-size / tree.max-depth（cooldownMs() = stall + extra）
```

**集合 SQL**（`com.solo.infra.mapper`，`databaseId` 双方言）

```java
SyncOrgAlignmentMapper      markMissing / countWouldStale / refreshParent / insertReady /
                            updateDiff / repairRoots / repairChildren
SyncGroupAlignmentMapper    同上（refreshParent/repair* 按 taskId 圈定）
SyncDeviceAlignmentMapper   markMissing / countWouldStale / insertNew / updateDiff /
                            refreshOrg / countOrgMissing / refreshChannelCount
SyncChannelAlignmentMapper  markMissing / countWouldStale / insertNew / updateDiff /
                            refreshOrg / countOrphanByMissingDevice / countOrgMissing
SyncColumns.ORG / GROUP / DEVICE / CHANNEL   // SET 与 WHERE 共用的可变列清单（C9）
```

**REST**

```
GET    /api/platform?keyword=&direction=                 platform:list
GET    /api/platform/{id}                                platform:list
POST   /api/platform                                     platform:add
PUT    /api/platform/{id}                                platform:edit     （编码不可改）
PUT    /api/platform/{id}/status?status=                 platform:edit
DELETE /api/platform/{id}                                platform:delete   （有入站资源/活跃轮次拒绝）
POST   /api/sync/task/page                               sync:list
GET    /api/sync/task/{id}                               sync:list
GET    /api/sync/task/{id}/records?page=&pageSize=        sync:list
POST   /api/sync/trigger/{platformId}                    sync:trigger
POST   /api/sync/cancel/{taskId}                         sync:cancel
POST   /api/gb28181/play/inbound                         （沿用 play 接口无权限注解的现状）
POST   /api/res/channel/page  → 返回 ChannelListItem（通道 + orgName + deviceName，按页批量补）
```

**前端**（`solo-ui/src`，约定式路由：menu.url → `pages/<url>/index.vue`）

```
features/res/useResTree.ts         左树：加载/拍平/展开/选中(null=全部)/totals/expandTo
features/res/ResOrgTreePanel.vue   纯展示左树（入站徽标 + 设备/通道计数 + footer slot）
features/res/InboundPlayPanel.vue  入站播放：startGbInboundPlayApi → GbPlayer，卸载即 stop
pages/res/{org,device,channel}/index.vue、pages/platform/index.vue、pages/sync/index.vue
api/{res,platform,sync}.ts + types/api/{res,platform,sync}.ts
permissions.ts 新增 RES_* / PLATFORM_* / SYNC_* 共 25 个常量；nav.ts 登记 IconHierarchy / IconRefresh
```

**M2a 增量 SQL 脚本**

```
sql/upgrade/2026-11-24_platform_registry_mysql.sql / _pg.sql   平台表补齐（方向位/同步策略/阈值）
sql/upgrade/2026-11-23_platform_sync_menu_mysql.sql / _pg.sql  平台接入 + 同步中心 8 条权限（含授 role_id=1）
sql/upgrade/2026-11-23_res_readonly_grants_mysql.sql / _pg.sql 只读四层第 4 层：DB 账号收回对 res_* 的写权限
sql/upgrade/2026-11-23_purge_logic_deleted_mysql.sql / _pg.sql 逻辑删除墓碑清理（uk_permission_code 不区分 deleted）
```

---

## 4. 已定决策（不要再翻案）

| # | 决策 | 出处 |
|---|---|---|
| D1 | **删除双轨开关**，单一路径 + 三重验证（方言单测 / 三档规模 / 影子对比），另留动作级 `sync.delete.mode=mark\|real` | 3.8.2 |
| D2 | `out_share` **不删行**，只置 `ref_alive=0`；上游重现自动置回 1 并复用自身墓碑码 | 3.8.4 / 3.9.5 |
| D3 | GB35114 **首版不要求**，建表即带 5 个可空字段占位，`cipher_suite='NONE'` | 附录 B #13 |
| D4 | M2b 拆 A/B/C 三档：A 必做（闸门/原地 UPDATE/同源生成）→ B 上线前（台账/回填/清理/互斥/自检）→ C 首版可缓（只剩报文快照） | 6.3 |
| D5 | 级联预分配端口被占时：**换端口重试（最多 3 次）+ WARN**，接受 SDP 端口与实际源端口不同（UDP 上级不校验源端口） | 本项目决定 |
| D6 | 回 200 OK 的 `c=` 地址非法（空 / 0.0.0.0 / 127.0.0.1 / ::1）→ **回 500 拒绝点播**，不回 200 后黑屏 | 本项目决定 |
| D7 | 会话是 **Redis 对象不是表**，加字段不需要 DDL | 3.11.5 |

---

## 5. 下一步待做

- [x] T1.1 工程骨架与分包
- [x] T1.2 资源三表 + 平台表 DDL
- [x] T1.3 本地资源 CRUD + 物化路径（后端）
- [x] T1.4 只读守卫骨架
- [x] T1.5 业务键原地 UPDATE 写入路径
- [x] T1.6 审计基础
- [x] M1 遗留：资源中心前端页面（`/res/org` `/res/device` `/res/channel`，含左树与入站播放）
- [x] **M2a（S1–S8）外部平台资源接入全链**（见 §9）
- [ ] M2a 收尾（需实库）：DDL 上库 + 影子对比逐字段 diff + 三档规模计时（见 §9.6）
- [x] **M2b（S1–S7）运行保障**（见 §10）
- [ ] M2b 待实库：DDL 上库 + 台账/快照的行数与时延验收（见 §10.5）
- [x] **M3（S1–S7）下级平台资源对外提供**（见 §11）
- [ ] M3 待实库：DDL 上库 + 断线续传/越权/背压/409/连接上限五项验收（见 §11.8）
- [x] **M4（S1–S7）上级资源共享：编排 / 编码映射 / Outbox / 对账 / 前端三页**（见 §12）
- [ ] M4 待实库：DDL 上库 + 菜单脚本 + 一轮真实推送与防环验收（见 §12.7）
- [x] **M5（S1–S6）多级级联与同源切换**：状态跟随 / 每跳互斥 / 跨级 Origin / 同源绑定与选路 / 一致性核对（见 §13）
- [ ] M5 待实库：补列与权限脚本上库 + 四项验收（见 §13.5）
- [x] **M6（S1–S7）视频业务完善**：回放 / 云台与预置位 / 播放记录 / 流复用与空闲回收 / CSV 导出 / 运行自检（见 §14）
- [ ] M6 待实库：两脚本上库 + 六项验收（见 §14.5）
- [x] **M7（S1–S7）部署与运维 / 性能优化**：健康探针 / 保留期回收 / 慢 SQL 与导出限流审计 / 带宽监控 / 容器化产物 / 运维手册与备份（见 §15）
- [ ] M7 待实机：镜像构建与编排校验 + 探针 503 语义 + 保留期分批 + 导出限流审计 + 备份恢复演练（见 §15.5）

---

## 6. 已完成：级联 SDP 加固 S0–S4（本轮）

背景：核对 3.11.5「回给上级的媒体地址必须是本平台地址」时发现三处缺口，按 S0→S4 落地。

| 步 | 内容 | 落点 |
|---|---|---|
| S0 | 固化断言：回给上级的 SDP **不出现**上级 `c=` 地址 | `SdpBuilderSendAnswerTest`（5 用例） |
| S1 | 消除 `m=video 0`：回 200 前预分配端口 + 换端口重试 + WARN | `CascadeSendService.onUpstreamInvite` / `onUpstreamAck`；`startSendRtpWithPortFallback` |
| S2 | 对外地址解析与校验，非法地址回 500 | `CascadeSdpAddress` + `CascadeSendService` 第 4 步 |
| S3 | `sdpRewritten` 字段 + 回 200 前置 `true` | `CascadeSendSession` |
| S4 | 文档回填：3.11.5 / 6.6 M5 / 6.8 风险 / 三份 yaml 配置项 | 见下 |

新增配置项（三份 yaml 均已加，留空即回退）：

```yaml
gb28181:
  cascade:
    sdp-public-ip:    # 回 200 OK 的 c=/o=；留空取 media.stream-ip → host-ip
```

新增测试（13 个全绿，均不依赖 Spring 上下文）：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `SdpBuilderSendAnswerTest` | 5 | `c=`/`o=` 用本平台地址、`y=` 保留上级 SSRC、三种 transport 的 `a=setup` |
| `CascadeSdpAddressTest` | 5 | 三级优先级、非法地址拒绝、显式配错不回退、RFC1918 判定 |
| `CascadeSendServicePortFallbackTest` | 3 | 复用预分配端口 / 端口占用换端口重试 / 非端口错误不重试 |

**已知覆盖度边界**：`onUpstreamInvite` 没有端到端测试（需 mock `SIPRequest`，JDK 25 + Mockito inline mock 不稳），`sdpRewritten=true` 靠「构造层单测 + 地址解析层单测 + 唯一调用点」三层保证，没有直接断言该字段为 true 的用例。

---

## 7. 已完成：源流判活与僵尸清理（T5.2 收尾）

背景：3.11.5 第 3 条要求"按源流判活"，但 `GbInviteSession` 没有 `lastRtpTime`，也没有巡检任务。按 Z0→Z4 落地。

**必须先说清的一处文档纠错**：3.11.5 原写"`lastRtpTime` 取 ZLM `getMediaList` 的 `origin_stamp`"，**该字段不存在**。核对 ZLMediaKit master 源码（`src/Common/MediaSource.h/.cpp`、`server/WebApi.cpp`）+ 本地 ZLM `index/api/version`（2024-08 / 046aaa3）确认：`getMediaList` 每项只有 `createStamp`、`aliveSecond`、`bytesSpeed`、`readerCount`/`totalReaderCount` 等；`aliveSecond` 取 `_ticker.createdTime()/1000`，是**流的创建时刻**而非最后收流时刻。因此改为**巡检采样 `bytesSpeed` 自行回写**。三份文档（3.11.5 / C21c / 本文件）已同步纠正。

| 步 | 内容 | 落点 |
|---|---|---|
| Z1 | `GbInviteSession.lastRtpTime` + `gb28181.cascade.rtp-timeout-sec`（三份 yaml） | 实体 + `Gb28181Properties.Cascade` |
| Z2 | 纯函数判定层 + 区分"节点不可达"的查询 | `GbInviteLiveness`；`ZlmRestClient.getMediaListOrNull` |
| Z3 | 巡检编排 + 定时触发（initialDelay 20s / fixedDelay 10s） | `CascadeSendService.sweepZombieSources`；`GbHealthScheduledTasks.sweepCascadeZombieSources` |
| Z4 | 单测（15 个）+ 文档回填 | 见下 |

新增配置项（三份 yaml 均已加）：

```yaml
gb28181:
  cascade:
    rtp-timeout-sec: 30    # 源流超过该时长无 RTP 判僵尸；设 0 或负数关闭巡检
```

设计要点：

- **宁可漏判不可误杀**：节点不可达（`UNREACHABLE`）、有本地观看者（`WATCHED`）、时钟回拨，三种情况一律放行。
- **同一路流取最大值**：ZLM 按 schema 各返回一条，`bytesSpeed` 与 readers 都要取 max，不能命中第一条就下结论。
- **只判 READY 的 PLAY / PLAYBACK**：INVITING 还没起流；DOWNLOAD / RECORD 是 TCP 文件流，本就没有连续 RTP。
- **先停推再 BYE**：复用 `cleanup(session, true)`，其顺序为 `stopSendRtp` → 删会话 → BYE 上级 → 无人观看才回收取流。来源侧不额外硬清，靠下一轮巡检自愈（硬清会掐掉有人正在看的流）。
- **写入节流**：`lastRtpTime` 推进不足 5s 不落 Redis，避免每轮重写整个会话 JSON。
- **平台级阈值暂缓**：`gb_parent_platform` 没有 `rtp_timeout_sec` 列，首版只有全局阈值。

新增测试（15 个全绿，均不依赖 Spring 上下文）：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `GbInviteLivenessTest` | 9 | 流消失 / 有码流 / 有观看者不判死 / 静默 / 不可达放行 / 同流取 max / 阈值边界 / 回退 readyTime / 关闭与时钟回拨 |
| `CascadeSendServiceZombieSweepTest` | 6 | 停推→删会话→BYE 的顺序（`InOrder`）/ 不可达不清 / 跳过 DOWNLOAD+RECORD / 无上级会话时回收来源侧 / 阈值内不清 / 有码流时刷新 `lastRtpTime` |

---

## 8. 已完成：M1 基础架构与本地资源（T1.1–T1.6）

**验证状态**：`mvn -o compile` BUILD SUCCESS；`ResourcePathTest`(5) + `ReadOnlyGuardTest`(3) + `ResourceUpsertServiceImplTest`(5) + `OrgServiceImplTest`(11) = **24 用例全绿**。

### 8.1 交付清单

| 层 | 文件 |
|---|---|
| DDL | `2026-11-23_res_core_mysql.sql` / `_pg.sql`；`2026-11-23_res_menu_mysql.sql` / `_pg.sql` |
| 领域模型 | `ResOrg` / `ResGroup` / `ResDevice` / `ResChannel` / `SourceType` / `ResourceStatus` / `Platform` / `PlatformCredential` / `AuditLog` |
| Mapper | `ResOrgMapper` / `ResDeviceMapper` / `ResChannelMapper` / `ResGroupMapper` / `PlatformMapper` / `PlatformCredentialMapper` / `AuditLogMapper` + 投影 `OrgRefCount` |
| 支撑层 | `ResourcePath`（路径推导与层级）、`ReadOnlyGuard`（NATIVE 可写 / 其余 403） |
| 服务 | `OrgService` / `DeviceService` / `ChannelService` / `ResourceUpsertService` 及 impl；`AuditService` |
| API | `OrgController` / `DeviceController` / `ChannelController` + `OrgSaveRequest` / `DeviceSaveRequest` / `ChannelSaveRequest` / `OrgTreeNode` / `ResourcePageRequest` |
| 配置 | `application.yaml`：`audit.enabled` |

### 8.2 实现要点

- **一次 INSERT，无空路径窗口**：先用 `IdWorker.getId()` 预生成 id 算 `path`，再 INSERT。参考实现 `GbOrgServiceImpl` 是"先插空 path 再回写"，存在"行已存在但 path=''"的中间态，本次不再沿用。
- **换父三步顺序不可换**：`rewriteSubtreePath`（旧→新，含自身）→ `shiftSubtreeLevel`（按新前缀圈定，delta = 新 level − 旧 level）→ `device/channel.rewriteOrgPath`。顺序调换会漏刷或把层级算错。
- **`rewriteOrgPath` 去掉了来源过滤**（与参考实现的关键差异）：设备的来源平台不一定等于所属组织的来源（同一组织下可挂多个下级推来的设备），按 `source_type` 圈定会漏刷留下脏前缀。id 全局唯一，只按 `org_path LIKE` 前缀就够。
- **清空字段用 wrapper**：`updateById` 跳过 null，`localName` / `parentId` 需要能置空，改用 `LambdaUpdateWrapper.set(...)` 并手动写 `update_time`（`update(null, wrapper)` 不触发 `MetaObjectHandler`）。
- **删除三层校验**：子组织 → 设备 → 通道，任一非空即拒；删除前过 `ReadOnlyGuard`。
- **审计旁路**：`record()` 在提交线程同步取 `UserHolderUtils` / 客户端 IP（异步线程拿不到请求上下文），再投 `logExecutor`；写失败与队列满都只 WARN，绝不影响业务事务。
- **upsert 的 createTime 处理**：命中已有行时 `incoming.setCreateTime(null)` —— `updateById` 跳过 null，正好等于"保留首建时间"；同时强制 `incoming.setId(existing.getId())`，防止调用方传入的 id 把 UPDATE 打到不存在的行。

### 8.3 踩过的坑（后续会重复遇到）

| 坑 | 现象 | 处理 |
|---|---|---|
| MP 3.5.15 `BaseMapper.insert/updateById` 有 `T` 与 `Collection<T>` 两个重载 | 测试里 `verify(mapper).insert(any())` 编译报"引用不明确" | 换成 `any(ResOrg.class)` 类型化匹配器 |
| 纯 Mockito 环境无 MP 启动期元数据 | `LambdaUpdateWrapper.set(ResOrg::getName, ...)` 抛 `can not find lambda cache for this entity` | 测试类 `@BeforeAll` 里 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResOrg.class)` |
| `UserHolderUtils` 依赖 `RequestContextHolder` | 在异步线程取值抛 `IllegalStateException` | 在提交线程同步取好，随任务带过去 |

### 8.4 未完成 / 边界

- **DDL 尚未在库上执行**：4 个 `sql/upgrade/2026-11-23_res_*.sql` 需手工跑（MySQL 或 PG 二选一 + 对应菜单脚本）。
- **前端页面未做**：`/res/org` `/res/device` `/res/channel` 三页，后端接口与 16 条权限码已就绪。
- **无集成测试**：现有 24 用例都是纯函数 / Mockito 单测，SQL 真实行为（`REPLACE` 前缀重写、`LIKE` 圈定范围）靠 DDL 脚本与联调验证。
- **`res_group` 只建表建模型**，没有 CRUD —— 分组服务于 M4 上级共享的虚拟组织树，届时一并做。

---

## 9. 已完成：M2a 外部平台资源接入（S1–S8）

**验证状态**：后端 `mvn -o -q compile` 通过；本轮新增 **21 个用例全绿**（`SyncMergeServiceTest` 6 / `InboundSyncServiceImplTest` 11 / `SyncGuardPredicateParityTest` 4），连同 M1 的 24 例与 `SyncAlignmentSqlTest` 3 例回归无失败；前端 `npx vue-tsc --noEmit` **0 error**（三个资源页 + 平台页 + 同步中心页全部通过类型检查）。

### 9.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S1 | 同步表 DDL | `2026-11-23_res_core_*.sql` 第 8–12 段：`sync_task` / `sync_staging` / `sync_record` / `platform_catalog_ver` / `device_org_binding` |
| S2 | 领域模型 + Mapper | `domain.sync.model`（`SyncTask` / `SyncStaging` / `CatalogItem` / `CodeHop` / `SyncColumns` / `MutableColumn` / `SyncTaskStatus`）+ `infra.mapper.Sync*Mapper` |
| S3 | 四类集合 SQL 同源生成 | `Sync{Org,Group,Device,Channel}AlignmentMapper`；`SyncColumns` 一份清单同时喂 `SET` 与 `WHERE`；`SyncAlignmentSqlTest` 守方言与列集 |
| S4 | InboundAdapter SPI + GB28181 | `spi.{InboundAdapter,CatalogSink,CatalogSession,CatalogBatch,PlatformConn,InboundAdapterRegistry}`；`Gb28181InboundAdapter` 把 Catalog 分片回调进 sink |
| S5 | 三段会话服务 | `InboundSyncServiceImpl`（trigger / 批次 ACK / onComplete / onError / cancel / sweepTimeout）+ `SyncStagingWriter`（分页写、进度按库计数）+ `InboundScaffoldService` |
| S6 | 只读四层收口 | `@ResourceWrite` 切面 + `ReadOnlyGuard` + `ResourceWriteInspector`（warn/strict/off）+ `2026-11-23_res_readonly_grants_*.sql`（回收 DB 写权限） |
| S7 | 平台接入 + 同步中心 + 左树 + 入站播放 | `PlatformController/Service`、`SyncCenterController/Service`、`/res/{org,device,channel}` 三页 + `/platform` + `/sync`、`POST /api/gb28181/play/inbound` |
| S8 | 端到端单测 + 文档回填 | 本节 + 3 个新测试类 + 修复 §9.5 的注解实体泄漏 |

### 9.2 合并顺序与两道闸门（`SyncMergeService.finish`）

1. 先置 `SYNCHING`（`updateIfActive` 争锁，只有一个收尾者能拿到）；
2. 统计暂存条数与四类本地基数 → **G1**：暂存 0 行且本地有数据 = 整轮失败（`SYNC_EMPTY_STAGING`），绝不按空集标消失；
3. 建组织树 → 建分组树 → 设备新增/更新/归属纠正 → 通道新增/更新/归属纠正（通道靠 `JOIN res_device` 取载体，缺载体计入 `failed_count`）；
4. **G2**：四类各自先用与 `markMissing` 完全相同谓词的 `countWouldStale` 算分子，再决定是否标消失；跳过只影响删除，新增/更新照常；
5. 指纹（`fingerprintSum` + 条数）写 `platform_catalog_ver`，`delete_skipped` / `guard_ratio` / `org_missing_count` 全部入 `sync_task`；
6. 删暂存。整个 finish 是**一个事务**，任一步抛错整体回滚。

### 9.3 三处自行决断（当时的取舍，后续不要翻案时先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **平台载体设备**：每个入站平台补一行 `res_device(code = platform.code)` 作为通道的取流载体 | GB28181 下级目录没有"设备层"，而 `res_channel.device_id` 是 NOT NULL 且通道内连接 `res_device`；补一行后通道挂得上、点播时 SIP 目标正好是平台编码。代价：该行必须从消失判断里排除（`d.code <> platformCode`） |
| 2 | **暂存写入按页批量 + 方言用 `databaseId` 分支** | PG 参数上限 65535，22 列 × 500 行很安全；方言差异集中在 SQL 层，服务层不感知。代价：`updateDiff` 两套写法必须同源生成（由 `SyncColumns` + 方言单测守住） |
| 3 | **finish 在 `onComplete` 里同步执行** | 语义最简单：收齐即合并，失败即 FAILED，无需额外状态机与投递器。代价：百万级目录是一个长事务，M2b 规模测试要量化并决定是否改异步 |

**入站播放的决断**：没有复制一套邀请流程，而是把 `GbPlayServiceImpl.start()` 的邀请主体抽成 `invite(device, deviceId, channelId, …)`，新增 `startInbound` 按 `res_channel.id → source_platform_id → platform.code` 反查（`platform.code` 即 `gb_device.deviceId`），复用同一套 SSRC / openRtpServer / INVITE / 流就绪等待。`session.deviceId = platform.code`，所以 stop / BYE / 超时清扫天然共用。

### 9.4 新增单测（21 例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `SyncMergeServiceTest` | 6 | G1 整轮失败；正常一轮的合并顺序（建两棵树 → 批量更新 → 设备 → 通道 → 消失）与计数/指纹/版本表回填；G2 只跳过设备删除且 `delete_skipped=1`；编码为空回落 `localDomain`；轮次已结束早退；平台无编码无域报错 |
| `InboundSyncServiceImplTest` | 11 | start 建 RUNNING 任务 + 会话登记；批次进暂存；onComplete 交给合并并摘会话；停用平台/已有活跃轮次/唯一键冲突三种拒绝；open 失败不进冷却；onError 与合并失败都 FAILED + 冷却 + 删暂存；cancel 与已结束轮次；无进展超时清扫；"先完成、后登记"的补摘竞态 |
| `SyncGuardPredicateParityTest` | 4 | 四类集合 `countWouldStale` 与 `markMissing` 的 WHERE 逐字一致；只动 ACTIVE 行；平台码排除只在组织/设备；注解不泄漏 XML 实体 |

### 9.5 本轮修掉的缺陷：4 条注解 SQL 泄漏 XML 实体

`SyncOrgAlignmentMapper` / `SyncDeviceAlignmentMapper` 的 `markMissing` 与 `countWouldStale` 没有包 `<script>`，注解里的 `&lt;&gt;` 不会被 MyBatis 解码，**真库执行时会原样发出 `d.code &lt;&gt; ?` 直接语法错误**。之前的方言单测只解析 `updateDiff`（本身有 `<script>`），所以漏了。修法：四条语句统一补 `<script>` 包裹；`SyncGuardPredicateParityTest.annotationsDoNotLeakXmlEntities` 从此守住这类泄漏。

### 9.6 待实库验收（M2a 收尾清单）

- **DDL 尚未上库**：`2026-11-23_res_core_*`（表）→ `2026-11-24_platform_registry_*`（平台列补齐）→ 菜单/权限脚本 → `2026-11-23_res_readonly_grants_*`（回收 DB 写权限）。
- **影子对比逐字段 diff**：同一份目录报文分别走"内存比对"与集合 SQL，逐字段比较结果集。当前已覆盖不需要库的那一半（方言 / 列集同源 / G2 谓词一致），剩下的一半需要库环境。
- **三档规模（10 / 50 / 100 万）**：量 `finish` 的耗时、锁等待与参数上限；同步验证"同一批数据连跑两次，第二次影响行数 0"。
- **1 万通道全量 < 5 分钟且可取消**：走真实下级平台联调。
- **断连重发只补失败批次**：现为批次级 ACK（写库成功才算收下），重连后的重发策略随 M2b。
- **动作级开关 `sync.delete.mode=mark|real`**（D1）尚未实现：M2a 固定为 mark（只标 STALE）。→ **M2b 已实现，见 §10**。
- **T2.11 报文快照**（C 档，默认关）未做。→ **M2b 已实现，见 §10**。

---

## 10. 已完成：M2b 运行保障（S1–S7）

**验证状态**：后端 `mvn -o test -Dtest='!SoloApplicationTests'` → **219 用例全绿**（本轮新增 24 例）；前端 `npx vue-tsc --noEmit` → **0 error**。`SoloApplicationTests.contextLoads` 是环境依赖用例（需真实 servlet 容器 `ServerContainer` + DB + SIP 栈），与 M2b 无关，保持原样未改。

### 10.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S1 | 轮次台账（3.13.1）：一轮一行，`delete_skipped` 与当时的 `guard_ratio` 可回查 | `2026-11-23_sync_runtime_*.sql` 第 1 段；`SyncRound` / `SyncRoundMapper` / `SyncRoundRecorder`；`InboundSyncServiceImpl.trigger` 开轮、`fail` 收尾、`SyncMergeService` 合并收尾；`SyncTaskDetail.round` + `SyncCenterServiceImpl.detail` 回查 |
| S2 | 删除闭环（3.8.2 / 3.8.4）：`sync.delete.mode=mark\|real`；STALE/REMOVED 留痕；**重现即恢复** | `SyncSettings.realDelete()`；四个 AlignmentMapper 的 `markRemoved` / `restoreActive`；`SyncRecordMapper.record{Org,Group,Device,Channel}`；`SyncMergeService` 恢复段（排在所有插入之前）+ 紧贴标记写留痕 + `audit` 汇总 `SYNC_DELETE` / `SYNC_MARK_STALE` / `SYNC_RESTORE` |
| S3 | 4 步回填修正 + 组织缺失延迟重试（3.8.2） | `SyncDeviceAlignmentMapper.refreshOrg` 改为 CASE 重算 `org_missing` 并三列比较（原实现用 `COALESCE` 恒非空却硬写 0，把标记抹掉了）；新增 `refreshOrgFromPending`；`SyncOrgPending` / `SyncOrgPendingMapper` / `PendingPlatform`；`OrgBackfillRetryService`（30s 扫描、活跃轮次让路、`deleteResolved` 终止、`max-wait` 告警）；`capture` 在 finish 事务内、`trigger` 先清旧队列 |
| S4 | 三道残留清理（3.13.4） | ①成功清本轮+同域（`deleteByPlatform`）、失败只清历史保留本轮（`deleteHistory`）；②`SyncResidueCleaner.cleanStartupLeftover` 启动后延迟 2 分钟补一刀（原来 `deleteOrphans` 是死代码）；③`cleanExpired` 每小时按 5000/批回收超保留期行 |
| S5 | 运行期自检（3.13.6） | `SyncSelfCheckReporter`：`ApplicationReadyEvent` 打一次 INFO —— Hikari 池参数（url 去密码段）、PG `statement_timeout`/`lock_timeout`/`idle_in_transaction_session_timeout`、MySQL `MAX_EXECUTION_TIME`/`innodb_lock_wait_timeout`、闸门/明细上限/保留轮数/审计开关 |
| S6 | 报文快照（3.13.2，C 档默认关） | `SyncRoundSnapshot` + `SyncRoundSnapshotMapper`（分批 UPSERT、`selectRecentTaskIds`、`deleteExcessBatch` 双方言）+ `SyncSnapshotWriter`（单线程有界队列、队列满丢弃、**收尾预留一格**、只留最近 N 轮）；`SyncStagingWriter` 每页写成功后入队、`SyncMergeService` 收尾触发清老轮 |
| S7 | 单测补齐 + 回填 | 新增 4 个测试类 19 例、扩 `SyncMergeServiceTest` +5 例；本节 + §5 清单 + `application.yaml` 补 `residue.startup-clean` 注释 |

### 10.2 本轮顺带修掉的四个缺陷（前两个在 M2b 范围内，后两个是回归时撞见的陈旧测试）

| 缺陷 | 根因 | 修法 |
|---|---|---|
| `refreshOrg` 把 `org_missing=1` 抹成 0 | SET 用 `COALESCE(o1.id, bo.id, o3.id, 0)` 恒非空，且 WHERE 只比 `org_id`/`org_path`，标记被静默清掉 | 派生表用 `CASE WHEN ... THEN 1 ELSE 0 END` 重算，SET 直接取派生值，WHERE 加第三列比较（PG `IS DISTINCT FROM` / MySQL `<=>`） |
| 快照"入队但永不落库" | `submit()` 直接 `queue.offer(task)`，从不调用 `executor.execute(...)` → 核心线程永远不会被创建，队列无人消费 | 改为"先查剩余容量（保住预留格）再 `execute`"，`RejectedExecutionException` 计入丢弃 |
| `DigestServerAuthenticationHelperTest` 7 例 `UnfinishedStubbing` | `when(mock.getResponse()).thenReturn(response(pwd))`，`response()` 内部又碰 `requestUri` 这个 mock | 先把期望值算到局部变量再进 `when(...)`，并用 `lenient()`（部分用例在读取前就被别的校验拦下） |
| `GbRecordFileServiceImplTest` 断言与实现漂移 | 实现已改为"对象删不掉就保留元数据并抛错可重试"，测试仍断言"照删" | 用例改名为 `deleteKeepsRowWhenObjectDeleteFails`，断言抛 `ServiceException` 且不删行（批量清理走 `deleteStorageQuietly` 才吞异常） |

### 10.3 新增配置项（`application.yaml` 已列全）

```yaml
sync:
  delete.mode: mark            # real = 消失行置 REMOVED（等 M4 共享联动后再切）
  residue:
    retention-hours: 24
    startup-clean: true        # 多实例滚动重启请设 false
  org-retry:
    debounce-seconds: 30
    max-wait-minutes: 10
  snapshot:
    enabled: false
    keep-rounds: 2
    queue-size: 1024
```

### 10.4 新增单测（24 例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `SyncRoundRecorderTest` | 5 | 开轮落 RUNNING + 计数归零；开轮写失败只告警；收尾默认 DONE；失败带错误码；无 taskId 不更新全表 |
| `SyncMergeServiceTest`（新增 5，共 11） | +5 | mark 模式只标 STALE 且留痕紧贴标记；real 模式落 REMOVED + `SYNC_DELETE`；恢复早于新增；组织缺失登记早于清暂存；台账 `delete_skipped` + `guard_ratio` 可回查 |
| `OrgBackfillRetryServiceTest` | 4 | 活跃轮次让路；空闲平台重走①②③④并出队；仍归不了位保持标记；扫描时单平台报错不拖累其他平台 |
| `SyncResidueCleanerTest` | 5 | 启动清理开关关闭零动作；删孤儿 + 只清"活跃但无会话"；启动失败吞异常；过期清理按批删到不足一批；周期清理失败吞异常 |
| `SyncSnapshotWriterTest` | 5 | 关闭零写入；开启时调用线程拷贝、快照线程落库；收尾只留最近 N 轮；队列满丢弃且预留格留给收尾；落库异常只告警 |

### 10.5 待实库验收（M2b 收尾清单）

- **DDL 尚未上库**：`sql/upgrade/2026-11-23_sync_runtime_mysql.sql` 或 `_pg.sql`（`sync_round` / `sync_round_snapshot` / `sync_org_pending` 三张表）。
- **台账回查**：跑一轮 mark 与一轮 real，确认 `sync_round.delete_skipped` / `guard_ratio` / `result` 与 `sync_task` 一致；STALE/REMOVED 行能在 `sync_record` 按 `task_id` 查到。
- **恢复闭环**：标记后让对端重新下发同一条，确认先 `ACTIVE` 再走更新（不会撞 `NOT EXISTS` 判"已存在"）。
- **延迟重试**：人为让某设备 `parent_code` 缺组织，确认 30s 后 `refreshOrgFromPending` 把它归位、`sync_org_pending` 行消失。
- **三道清理**：`kill -9` 一次收尾中的进程再启动，确认 2 分钟后暂存被清；把 `retention-hours` 调成 1、改一行 `create_time` 到过去，确认小时级任务按 5000/批回收。
- **快照**：`snapshot.enabled=true` 跑两轮，确认总量回落并在收尾后只留最近 2 轮；`queue-size=1` 压测确认丢弃只告警。

---

## 11. 已完成：M3 下级平台资源对外提供（S1–S7）

**验证状态**：后端 `mvn -o test -Dtest='!SoloApplicationTests'` → **250 用例全绿**（本轮新增 31 例）；`mvn -o compile` BUILD SUCCESS；前端 `npx vue-tsc --noEmit` → **0 error**。`SoloApplicationTests.contextLoads` 仍为环境依赖用例，未改。

### 11.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S1 | 事件流 DDL（双方言） | `2026-11-23_event_stream_mysql.sql` / `_pg.sql`：`change_event`（自增 `seq` 即游标）/ `event_subscription`（`uk_key(app_id, topic)`） |
| S2 | 实体 + 发布服务（含降级判定） | `ChangeEvent` / `EventSubscription` / `EventSettings` / `EventShape` / `EventShapeDecider` / `ChangeEventPublisher`（同事务写库 + `EventSignal.raise`）/ `ChangeEventMapper` / `ChangeEventReadMapper` |
| S3 | 同步收尾埋点 | `SyncEventEmitter`；`SyncMergeService.finish` 在 `writeLedger` 之后、`pendingMapper.capture` 之前调用（写库前定范围、写库后回读明细两条硬约束都落在这一行） |
| S4 | SSE 端点 | `OpenEventController`（`/open-api/v1/events/stream`）+ `EventStreamService` + `EventSubscriptionService` + `EventScope` + `EventSignal` + `EventRetentionJob` |
| S5 | 全量快照 | `OpenSnapshotController`（`/open-api/v1/snapshot`）+ `ResourceSnapshotService` + `ResourceSnapshotMapper` + `SnapshotRow` / `ResourceSnapshotPage` |
| S6 | 订阅管理 CRUD + 前端页 | `OpenSubscriptionController`（`/api/openapi/subscription`）+ `OpenSubscriptionService`、`pages/openapi/subscription/index.vue` + `features/openapi/OpenSubscriptionInspector.vue`、`2026-11-23_event_sub_menu_*.sql` |
| S7 | 单测 + 回填 | 5 个新测试类 31 例（见 §11.5）+ 本节 + `application.yaml` 增 `event:` 段 |

### 11.2 SSE 为什么是"查库轮询"而不是"Redis 扇出"

游标的唯一事实源是 `change_event.seq`。用发布订阅做投递，补偿逻辑（谁没收到、重连从哪补）最后仍要回查库；既然如此就把"查库 + 推进游标"做成唯一路径：

- **多实例天然一致**：每个实例各按自己的游标查、各推，`last_event_id` 只进不退（`advanceCursor` 带 `last_event_id < #{seq}`）。
- **进程内 `EventSignal` 只是加速器**：`notifyAll` 唤醒所有挂住的连接；信号丢了最多慢一个 `event.idle-poll-ms`，不会丢事件。也让"信号早于事务提交可见"这种时序问题变得无害。
- **线程模型**：一连接一虚拟线程，阻塞在"查库 / `signal.await`"上；上限由 `event.max-connections` 显式卡住（到顶 429），不靠线程池排队。

### 11.3 三条硬要求在代码里的落点

| 要求 | 实现 |
|---|---|
| 游标不能跳号 | 每条事件（含 `REFRESH_ALL`）帧头都是 `id: seq`；`lagged` 提示**故意不带 id**，它只是提醒不是事件 |
| 背压不阻塞生产者 | 单连接一次拉 `batch-size` 条，发完再拉下一批；落后超过 `queue-size` 时插一条 `lagged`（同一段落后只提示一次），**绝不跳过事件** |
| 超窗口 409 | `resolveStartCursor` 在**建连前**判定：`<=0` 从 `maxSeq` 开始（首连不回放历史）、`> maxSeq` 409、`effective+1 < minSeq` 409；判不过直接抛 `EventStreamRejectedException`，不会"先 200 再断流" |

游标三选一：`effective = max(客户端 Last-Event-ID, 服务端 last_event_id)`——客户端乱传更小的值不会让服务端重复回放。`Last-Event-ID` 请求头与 `lastEventId` 查询参数双通道（查询参数参与 HMAC 签名，请求头不参与）。

### 11.4 三处自行决断（后续复核先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **先判降级再回读明细** | 树重建 / 删被跳过 / 超阈值时直接发 `REFRESH_ALL`，一条明细都不回读——5 万条的回读本身就是一次全表扫 |
| 2 | **快照 scope 只能收紧，且保留请求里更窄的那个** | 越界请求 403（不静默给空，3.12.5）；请求 `/1/5/9/` 且订阅限 `/1/5/` 时按 `/1/5/9/` 查，别把它悄悄放宽回订阅上限 |
| 3 | **订阅不提供手工新建/删除** | 订阅由"首次调 SSE"自动创建（`loadOrCreate` + `DuplicateKeyException` 回查），生命周期跟应用走；凭空造一条没有 appKey 的订阅无法鉴权。管理端只做：看列表、改 scope 与启停、游标清零（= 跳到最新，不回放历史） |

`scope` 即授权：推送前按 `platformId` + `orgPath` 前缀**再过一次**（`EventScope.accepts`），不继承建订阅那一刻的结论；`REFRESH_ALL` 一律放行（它只说"去拉快照"，快照自己还会按 scope 裁剪）。

### 11.5 新增单测（31 例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `EventShapeDeciderTest` | 6 | 五条降级规则；`detailMax` 边界（等于阈值仍是明细）；无变化一律 NONE |
| `EventScopeTest` | 6 | 平台过滤 / 事件无平台视为全局放行 / 子树前缀不吞兄弟（`/1/5/` vs `/1/50/`）/ 无定位的明细拒发 / `REFRESH_ALL` 放行 / 脏路径规范化 / 标签口径 |
| `SyncEventEmitterTest` | 6 | 无变化零动作；5 万条只发 1 条 `REFRESH_ALL` 且不回读；树重建、删被跳过均降级；小轮次明细的 ADDED/UPDATED/REMOVED 判定（STALE 先判）；明细回读为空降级 |
| `EventStreamServiceTest` | 6 | 首连从 `maxSeq` 起（不回放）；服务端游标更大时以服务端为准；游标超前 409；`effective+1 < minSeq` 409 且边界值放行；409 携带带 scope 的 `snapshotUrl`；连接数到顶 429 |
| `ResourceSnapshotServiceTest` | 7 | 请求放宽平台 / 放宽组织均 403 且不打库；请求收紧时保留更窄值；`size` 默认 100 与上限 500；越界页不打库；`hasNext`；`SnapshotType.of` 默认与非法值 |

### 11.6 新增配置（`application.yaml` 已列全）

```yaml
event:
  detail-max: 500              # 超过即降级成 REFRESH_ALL
  retention-days: 7
  retention-enabled: true      # 每小时任务按天回收
  batch-size: 200
  queue-size: 1000             # 落后超过即插 lagged 提示（不跳号）
  idle-poll-ms: 2000
  stream-timeout-ms: 1800000
  heartbeat-ms: 15000
  max-connections: 64
```

### 11.7 新增接口（改签名前必须先通知）

```
GET  /open-api/v1/events/stream                   @RequireScope("gb:event:subscribe")   SSE
GET  /open-api/v1/snapshot?type=&platformId=&orgPath=&page=&size=
POST /api/openapi/subscription/page                  openapi:subscription:list
POST /api/openapi/subscription/update                openapi:subscription:edit  @OperationLog
POST /api/openapi/subscription/{id}/reset-cursor     openapi:subscription:edit  @OperationLog
```

菜单 `/openapi/subscription`（`IconBroadcast`，挂在「开放平台」下 sort=3）+ 两个按钮权限，见 `sql/upgrade/2026-11-23_event_sub_menu_mysql.sql` / `_pg.sql`（含 `sys_role_permission` 授权）。

### 11.8 待实库验收（M3 收尾清单）

- **DDL 尚未上库**：`2026-11-23_event_stream_mysql.sql`（或 `_pg.sql`）→ `2026-11-23_event_sub_menu_*.sql`。
- **验收标准①**：断线 1 分钟内重连，用返回的 `Last-Event-ID` 续传，确认 0 条丢失（对照 `change_event` 计数）。
- **验收标准②**：用 scope 限 `/1/5/` 的订阅去请求 `/1/` 的快照，确认 403 且响应体带 `SCOPE_DENIED`。
- **验收标准③**：1000 事件/秒灌 `change_event`，确认 SSE 连接不断、`lagged` 只提示不跳号，且同步事务不被阻塞。
- **验收标准④**：把 `retention-days` 调成 1、删掉早期事件，用旧游标连流，确认 409 且响应体带 `snapshotUrl` / `minSeq` / `maxSeq`。
- **验收标准⑤**：一轮 5 万条变更的同步只产生 1 行 `REFRESH_ALL`，且 `seq` 连续无空洞。
- **连接上限**：把 `event.max-connections` 调成 2，确认第 3 条连接回 429 `STREAM_LIMIT_EXCEEDED`。

---

## 12. 已完成：M4 上级资源共享（S0–S7）

**验证状态**：后端 `mvn -o -q -DskipTests compile` BUILD SUCCESS；`mvn -o test` → **Tests run: 286, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS**（M4 新增 35 例，见 §12.6；唯一跳过项见 §12.8）；前端 `npx vue-tsc --noEmit` **0 error**、`npx vite build` 成功（仅有既有的动态/静态导入混用与 chunk >500kB 告警）。

### 12.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S1 | 出站五表 DDL + 菜单权限（双方言） | `2026-11-24_share_core_{mysql,pg}.sql`（`out_group` / `out_share` / `out_code_map` / `outbox` / `publish_round`）、`2026-11-24_share_menu_{mysql,pg}.sql`（3 菜单 + 6 按钮） |
| S2 | 实体 / Mapper + 纯函数支撑层 | `domain.share.model`（`OutGroup` / `OutShare` / `OutCodeMap` / `OutboxEvent` / `PublishRound` + 四个常量类）、`infra.mapper.Out*`、`domain.share.support.{ExposedCode, ShareGuard, ShareStatusMerge, PublishDegradeDecider, ShareScopeResolver, SharePayloads, ShareSettings, ShareEvent, SharePreview}` |
| S3 | 共享编排 | `ShareComposeService`：SUBTREE 规则、派生重算、剔除/恢复（`enabled=0` + 抑制行）、一键生成 215/216 虚拟树、预览（纯计算不分配码）；`ShareOutboxWriter` 同事务写 outbox |
| S4 | 编码映射 | `OutCodeService`：`ensureGroupCode` / `ensureChannelCodes`（批量一次锁平台行）/ `tombstone` / `restore` / `resolve`（上级 INVITE 反查，查不到拒绝） |
| S5 | Outbox 投递 + 台账 + 对账 + 消失联动 | `OutboxDispatchService`（离线 PAUSED、超量降级快照、DELETE 不并入快照、RUNNING 跳过、退避/DEAD）、`OutboundSipDelivery`、`SharePublishJob`（投递/对账/回收三个独立调度）、`ShareReconcileService`、`ShareRepublishService`（人工重试）、`ShareDisappearService.afterMerge` |
| S5b | 上级 INVITE / 控制 / 状态反查切 `out_*` | `CascadeSendService.findSharedChannel`、`CatalogQueryMessageHandler`、`DeviceControlMessageHandler`、`DeviceStatusQueryMessageHandler` 全部改走 `ShareCatalogService.resolveForPlay` / `loadCatalogItems`；`gb_platform_share` 只剩遗留只读 |
| S6 | REST + 前端三页 | `ShareComposeController` / `ShareOverviewController` / `SharePublishController`（28 端点，见 §12.4）；`pages/share/{compose,overview,publish}/index.vue` + `api/share.ts` + `types/api/share.ts` + `permissions.ts` 10 个 `SHARE*` 常量 |
| S7 | 单测 + 回填 | 6 个测试类 35 例（§12.6）+ 本节；顺带修 `SyncMergeServiceTest` 构造器与 `SoloApplicationTests` |

### 12.2 出站五表的不变量

- **目标平台就是 `gb_parent_platform`**（本平台注册的上级），不是入站 `platform` 表；防环比对一律用国标码，跨两张 id 空间。
- `out_group`：`uk(platform_id, source_org_id)` 是一键生成的幂等键；节点码 `215/216` 一经分配不随移动变化。
- `out_share`：**不删行**。`ref_alive=0` 表示上游已消失/撤销，重现时置回 1 并复用自身墓碑码；`enabled=0` 是"剔除"，优先级高于 SUBTREE 规则。
- `out_code_map`：`ACTIVE` / `TOMBSTONE`；`seq` 平台内单调，**删除不复用**；墓碑只在推送成功后置（C12 第 4 步）。本地资源码透传，入站资源码重编号为 `域10+类型3+序号7`。
- `outbox`：`uk(platform_id, seq_no)`，同一平台序号连续；`PENDING/SENT/FAILED/DEAD/PAUSED`。
- `publish_round`：`RUNNING/DONE/FAILED/SKIPPED`；`FAILED/SKIPPED` 也要留痕，否则"为什么这轮没推"无从查；超过 `platform-lock-ttl-ms` 的 `RUNNING` 由回收任务置 FAILED。

### 12.3 硬约束落点

| 约束 | 实现 |
|---|---|
| C15 事件与业务同事务 | `ShareComposeServiceImpl` 每个写方法在自己的事务里改 `out_group`/`out_share`/`out_code_map`，并由 `ShareOutboxWriter` 同事务写 `outbox` |
| C18 超量不逐条 | `PublishDegradeDecider`：`planned > detail-max` → 先发一次全量快照收口 backlog；**删除行仍逐条发**（快照是 upsert，合并等于吞掉撤销） |
| C20 四道防环 | `ShareGuard`：REVERSE / ORIGIN_CHAIN / HOP_LIMIT（`hop+1 > maxHop`，`maxHop<=0` 取 3）/ SELF_DOMAIN；保存时拒绝，投递前再判一次（计入 `skipped`） |
| C24 平台级互斥 | `publish_round.countRunning(platformId) > 0` 即跳过，多实例靠 DB 不靠进程内锁 |
| 退避 | `ShareSettings.backoffMs`：`base<<min(n-1,10)`，`base=max(retry-backoff-ms,1000)`，封顶 600000；超过 `retry-max` 置 DEAD 等人工 |
| 号段不回退 | 回收只删 `SENT` 且早于保留期，**保留平台内最大 seq 那一行** |
| 离线不判失败 | 平台禁用/离线 → 队列 `PAUSED`（不消耗 retry），恢复在线自动 `resume` 且序号不变 |
| 撤销不逐条（3.9.5） | `revokePlatform` 全部置死 + 清树 + 只发一条 `REFRESH`；`revokeChannel` 只置 `ref_alive=0` + `REVOKING` + DELETE |

### 12.4 新增接口（28 个，改签名前必须先通知）

```
GET    /api/share/compose/platforms                 share:compose:list
GET    /api/share/compose/groups                    share:compose:list
POST   /api/share/compose/groups/generate           share:compose:edit
GET    /api/share/compose/org/tree                  share:compose:list
POST   /api/share/compose/channel/page              share:compose:list
POST   /api/share/compose/items/page                share:compose:list
POST   /api/share/compose/preview                   share:compose:list
POST   /api/share/compose/rules                     share:compose:edit
DELETE /api/share/compose/rules                     share:compose:edit
POST   /api/share/compose/channels                  share:compose:edit
POST   /api/share/compose/devices                   share:compose:edit
POST   /api/share/compose/exclude                   share:compose:edit
POST   /api/share/compose/restore                   share:compose:edit

GET    /api/share/overview/platforms                share:overview:list
POST   /api/share/overview/items/page               share:overview:list
POST   /api/share/overview/exclude                  share:overview:edit
POST   /api/share/overview/restore                  share:overview:edit
POST   /api/share/overview/revoke                   share:overview:edit
POST   /api/share/overview/revoke-platform/{id}     share:overview:edit
POST   /api/share/overview/rebuild/{id}             share:overview:edit
POST   /api/share/overview/reconcile/{id}           share:overview:edit

GET    /api/share/publish/platforms                 share:publish:list
POST   /api/share/publish/outbox/page               share:publish:list
GET    /api/share/publish/outbox/counts             share:publish:list
POST   /api/share/publish/round/page                share:publish:list
GET    /api/share/publish/round/{id}                share:publish:list
POST   /api/share/publish/retry                     share:publish:retry
POST   /api/share/publish/dispatch/{id}              share:publish:retry
```

`GET /api/share/publish/platforms` 是为发布页单独加的：它只需要 `share:publish:list`，不该为了拿平台列表去调 `share:compose:list` 的接口。

前端三个菜单 `/share/compose`、`/share/overview`、`/share/publish`（`IconShare` / `IconList` / `IconUpload`），页面文件即路由，无需改 `router.ts`。

### 12.5 自行决断（复核前先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **目标平台 = `gb_parent_platform`** | 入站 `platform` 是"我下级的平台"，出站对象是"我的上级"。两张表语义相反，混用会让防环与目录推送对错人 |
| 2 | **预览不分配编码** | 分配会烧掉一个永不复用的序号，用户点开看一眼就白烧一批（C16）；因此预览里"需重新编号"只是估算 |
| 3 | **撤销只在总览页，编排页只有剔除/恢复** | 撤销是不可逆动作（墓碑），放在"每日配置"页容易误点；编排页的剔除可随时恢复 |
| 4 | **剔除不删行**：已有行 `enabled=0`，尚无行插一条抑制行 | 否则"先剔除、后被规则派生"会重新出现，用户以为剔除失效 |
| 5 | **一键生成以 `source_org_id` 幂等** | 反复点只更新名称与父节点，不新建节点；节点码不变，上级不会看到分组反复重建 |
| 6 | **整平台撤销只发一条 REFRESH** | 万级通道逐条 DELETE 是自己打自己；上级重拉一次目录即对齐 |
| 7 | **对账只在没有在途事件时补** | 队列里已有该资源的事件时再补 ADD，上级会收到重复 ADD（目录出现两个同码节点，比漏推更难查） |
| 8 | **S5b 全部切到 `out_*` 三表** | 上级点播/控制/状态查询必须只看"已共享且可见"的通道；继续读 `gb_platform_share` 会出现"撤销了上级还能点" |

### 12.6 新增配置与单测

配置（`application.yaml` 已列全；带默认值的调度延迟项未写入 yaml）：

```yaml
publish:
  detail-max: 500              # 超过即降级为一次全量快照
  retry-max: 5                 # 超过置 DEAD
  retry-backoff-ms: 30000      # 指数退避基数，封顶 10 分钟
  batch-size: 100
  max-hop: 3
  interval-ms: 30000
  platform-lock-ttl-ms: 300000
  reconcile-enabled: true
  reconcile-interval-ms: 3600000
  retention-days: 7
```

另有 `publish.platform-batch:20`、`dispatch-initial-delay-ms:20000`、`reconcile-initial-delay-ms:120000`、`housekeeping-initial-delay-ms:300000`、`housekeeping-interval-ms:86400000` 由 `ShareSettings` / `@Scheduled` 默认值提供。

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `ExposedCodeTest` | 7 | 20 位组成与补零、SEQ 越界、`segOf` 段码（CHANNEL 131 / DEVICE 200 / GROUP 216、ORG 拒绝）、宽松解析失败返回 -1、`belongsTo` |
| `ShareGuardTest` | 8 | 同 id / 同国标码 REVERSE、来源链 id 与码命中、`hop=3&max=3` 拒绝与 `hop=2` 放行、`maxHop<=0` 取默认、SELF_DOMAIN 只拦入站、null 放行、文案带回下一步 |
| `ShareStatusMergeTest` | 6 | 六态 rank、未知按 PENDING、`worst` 取最差、空集合与 null 元素返回 null、`fromOutbox` 含 PAUSED→PENDING |
| `PublishDegradeDeciderTest` | 5 | `planned<=0`→NONE、树重建→REFRESH、`detailMax>0 && planned>detailMax`→REFRESH、边界等于阈值→DETAIL、`detailMax<=0` 关闭降级 |
| `ShareScopeResolverTest` | 5 | 路径规范化、子树前缀不吞兄弟（`/1/5/` vs `/1/50/`）、root 覆盖全部、空 scope |
| `ShareSettingsTest` | 4 | 指数增长、600000 封顶、`retryCount<=0` 视同首次、基数 <1000 夹到 1000 |

### 12.7 待实库验收（M4 收尾清单）

- **DDL 尚未上库**：`2026-11-24_share_core_mysql.sql`（或 `_pg.sql`）→ `2026-11-24_share_menu_*.sql`。
- **一轮真实推送**：编排一个组织 → 生成虚拟树 → 提交 → 等 `SharePublishJob` 或手工「立即推送」，确认 `outbox` 全部 `SENT`、`publish_round` 的 planned/sent 与实际条数一致、上级目录里能看到 215/216 分组与重编号通道。
- **墓碑不复用**：剔除并推送成功 → 确认 `out_code_map` 该行为 `TOMBSTONE`；再恢复 → 确认复用同一个暴露码而不是新序号。
- **防环**：构造来源链包含目标上级的通道，确认保存时被拒、投递前计入 `skipped`，且上级目录没有出现自己。
- **降级**：把 `detail-max` 调成 1 并制造多条积压，确认只发 1 条 `REFRESH` 快照 + 删除仍逐条，`publish_round.degraded=1`。
- **退避 / DEAD**：断开上级，确认 `retry` 递增且 `next_retry_at` 间隔按指数放大；`retry-max` 用尽后置 `DEAD`，手工重试可复活。
- **离线挂起**：禁用上级平台，确认队列为 `PAUSED` 且不消耗 retry；恢复启用后自动继续且序号不变。
- **对账**：手工删掉上级目录里几个节点，跑 `reconcile`，确认只补差异、在途事件不重复补。
- **平台互斥**：两实例同时 dispatch，确认同一平台不会并发（`countRunning` 拦住，轮次为 SKIPPED）。
- **回收**：`retention-days=1` + 手工把 `create_time` 改早，确认只删 `SENT` 且保留平台内最大 seq。

### 12.8 为什么 `SoloApplicationTests` 被 `@Disabled`

`@SpringBootTest` 默认 `webEnvironment=MOCK` 没有 Servlet 容器，`WebSocketConfig.serverEndpointExporter()` 初始化时必然抛 `ServerContainer not available`；改成 `RANDOM_PORT` 会真实绑定 SIP 5060 与默认 profile 的 MySQL。上下文装配交给部署启动冒烟验证，离线单测只覆盖纯函数与 Mockito 部分。**这不是掩盖失败**：该用例此前也从未通过过（M3 起即为环境依赖项）。

---

## 13. 已完成：M5 多级级联与同源切换（S0–S6）

**验证状态**：后端 `mvn -o -q -DskipTests compile` BUILD SUCCESS；`mvn -o test` → **Tests run: 327, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS**（M4 收尾时 286，本轮净增 41 例，跳过项仍是 `SoloApplicationTests`，原因见 §12.8）；前端 `npx vue-tsc --noEmit` **0 error**、`npx vite build` 成功。

### 13.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S0 | 读文档 6.6 / 3.10 出分步计划 | 本节 |
| S1 | 状态跟随（上游离线 → 上级收到 OFF） | `ShareStatusFollowService(Impl)`；`OutShare.refOnlineStatus` + 瞬时字段 `storedStatus`；`OutShareMapper.selectStatusChangedBySource` / `updateRefOnlineStatus`；在 `SyncMergeService.finish` 同事务调用（L243）；补列脚本 `2026-11-25_share_status_follow_{mysql,pg}.sql` |
| S2 | 每跳独立互斥与冷却 | `support.MediaHopGuard`（`in:{streamId}` / `out:{platformId}:{channelId}`，`tryAcquire`/`release`/`cooldown`/`cooldownRemainingMs`/`clearCooldown`）；入站腿接 `GbPlayServiceImpl`（L276），出站腿接 `CascadeSendService`（L150 争抢、L597 失败冷却）；配置 `gb28181.*.hop-cooldown-sec: 3`（三份 yaml） |
| S3 | 跨级映射：出站 Origin* 恒带 + 入站解析落链 | `PlatformCatalogXml` 恒写 `OriginPlatformId`/`OriginCode`；`CatalogResponseMessageHandler` 解析入站；`Gb28181CatalogTap` 透传优先 + 平台码反查 id + `origin_chain` 拼接；`SharePayloads` / `ShareCatalogServiceImpl` / `OutboxDispatchService` 把库 id 换成国标码出站；`ResChannel`/`ResDevice`/`SyncStaging`/`CatalogItem` 落 `originPlatformId`/`originCode` |
| S4 | 同源关联与播放选路 | `CanonicalChannelService(Impl)`（`group`/`bind`/`unbind`/`playCandidates`/`afterMerge`）、纯函数 `support.CanonicalRouteOrder`；`ResChannelMapper` 同源查询；`ChannelController` 三个端点；权限脚本 `2026-11-26_channel_source_bind_{mysql,pg}.sql`；前端 `/res/channel` 多选绑定 + "同源 N" + 来源组对话框，`InboundPlayPanel` 换源提示，`GbPlayResult.sourceChannelRowId` |
| S5 | 双向一致性核对 + 并发防护 | `ResChannelMapper.updateCanonical` 增加 `expectedCanonicalId` CAS（`COALESCE(canonical_id,0)=#{expected}`），影响 0 行 → 409 整组回滚；`countCanonicalAnomalies` 一条 LEFT JOIN 统计四类异常（只报不改）；`CanonicalAnomaly` 投影 + `CanonicalChannelService.checkIntegrity()`；`GET /api/res/channel/sources/integrity`；`afterMerge` 收尾调用一次只 WARN |
| S6 | 全量回归 + 本节回填 | `mvn -o test` 327 例全绿；`vue-tsc` / `vite build` 通过；§2 / §3.1 / §5 同步 |

### 13.2 新增接口与权限码

```
GET    /api/res/channel/{id}/sources          res:channel:list
POST   /api/res/channel/sources/bind          res:channel:bind
POST   /api/res/channel/sources/unbind        res:channel:bind
GET    /api/res/channel/sources/integrity     res:channel:list
```

`res:channel:bind` 由 `2026-11-26_channel_source_bind_*.sql` 插入（菜单「同源绑定」，挂在 `res:channel` 下 sort=5）并授权 role_id=1，脚本幂等可重复执行。`sources/integrity` 与 `{id}/sources` 段数与字面量都不同，不会误匹配；`sources/bind` 用的是 POST，与 GET 的 `{id}/sources` 也不冲突。

### 13.3 自行决断（复核前先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **一致性核对只报不改** | 3.10.3 明确"不允许系统自动解除（只能人工）"；四类异常只计数 + WARN，修复走人工解除/重绑 |
| 2 | **并发用 CAS 而不是进程锁** | 读-改-写之间用户可能在另一个页签改过；`expectedCanonicalId` 不匹配即 409 整组回滚。代价：并发操作用户要刷新重试 |
| 3 | **列表不物理合并同源行** | 合并会改 id、撕断审计与播放会话；改为行内"同源 N"徽标 + 来源组对话框 |
| 4 | **L2 码链自动关联暂缓** | 判定依赖 `code_chain`，该列全仓未落库；先做 L1（`origin_platform_id`+`origin_code` 完全相同）与人工 L3/L4 |
| 5 | **自动关联挂在 `finish` 之后、独立 try/catch** | 关联失败不能把一轮同步判 FAILED；只补"全组无任何指针且组主未被引用"的组，人工绑过的组整组跳过 |
| 6 | **混源允许绑定，但选路只挑 INBOUND** | 同源组里可以有本地行（且本地行优先当组主，上级抖动时组里仍有一条自己能支配的链路），实际取流必须走入站通道 |
| 7 | **播放选路只在"准备阶段"换源** | INVITE 已发出后失败不换源，避免上级收到两个 200 后拿到错流；选路本身不改 `canonical_id` |
| 8 | **状态跟随首次只落基线不发事件** | 首轮的 ADD 已带当轮 `<Status>`，补发是重复；只看当前状态也无法区分"一直离线"与"刚刚离线" |

### 13.4 新增单测

5 个测试类（表中为 `@Test` 计数，surefire 净增 41 例）：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `ShareStatusFollowServiceImplTest` | 7 | 首见只落基线不发事件、真变化发 ON/OFF、降级成 REFRESH 算 1 条、STALE/剔除/撤销排除、同值不推、基线推进 |
| `MediaHopGuardTest` | 8 | in/out key 组成、同 key 互斥、`release` 幂等、释放后可再取、冷却期内挡住、冷却到期自愈、`clearCooldown`、`millis<=0` 关闭 |
| `Gb28181CatalogTapTest` | 7 | 上游 Origin* 透传优先、缺失回落自身码、平台码反查 id、码未登记落 0、链拼接去重、直连上级补链、hop 计算 |
| `CanonicalRouteOrderTest` | 5 | 用户点选优先、在线优先、hop 少优先、hop 缺失排后、REMOVED 剔除 + id 兜底 |
| `CanonicalChannelServiceImplTest` | 15 | 组主选取（本地优先、时间+id）、bind 三类拒绝、unbind 成员/换主/单独行、afterMerge 只补空组与组主被引用跳过、playCandidates 剔 REMOVED、CAS 冲突 409 且不写审计、integrity 计数映射与全 0、通道不存在 |

断流场景由既有 `MediaHopGuardTest` 与 `RecordPlanReconcilerTest`（断流重拉）覆盖；本节没有新增长时间挂起的集成测试（需真实 ZLM 与上级 SIP）。

### 13.5 待实库验收（M5 收尾清单）

- **脚本上库**：已建库环境执行 `2026-11-25_share_status_follow_{mysql,pg}.sql`（`out_share.ref_online_status`）与 `2026-11-26_channel_source_bind_{mysql,pg}.sql`（权限码）；顺手确认 `res_channel.canonical_id` 与 `idx_canonical` 存在（M1 基线脚本已含）。
- **状态跟随首轮不推 OFF**：升级后首轮同步只落基线（`ref_online_status` 由 NULL 变当前值），`outbox` 不新增 ON/OFF；第二轮把设备断电再同步，确认上级收到 OFF 且基线同步推进。
- **每跳互斥与冷却**：同一入站流并发两次点播，只允许一个在飞（另一个按既有语义拒绝）；制造一次失败，确认 `hop-cooldown-sec` 内快速失败、成功后立即恢复可用。
- **跨级 Origin**：三级链路 A→B→C，C 的目录里带的是 A 的 `OriginPlatformId`/`OriginCode`；把中间平台记录删掉，确认退化为自身码而不是空值。
- **同源绑定与并发**：两条入站 + 一条本地绑定成组，确认组主本地优先、成员指针指向组主、`audit_log` 有 `BIND_CANONICAL`；解除组主后新组主选出且成员指针改指；两个页签同时操作同一成员，后提交者收到 409「请刷新后重试」且整组回滚。
- **换源与提示**：让组主通道在 ZLM 侧无源流，点播另一成员，确认实际取流行与请求行不同（`sourceChannelRowId` 回传）且前端出现换源提示；INVITE 阶段失败不换源。
- **一致性核对**：手工造自指 / 孤儿 / 两级指针 / 组主 REMOVED 各一行，`GET /sources/integrity` 四类计数与 `total()` 对得上，并确认系统**不会**自动修。

### 13.6 已知偏差 / 边界

- **L2 码链关联未做**（见 §13.3 第 4 条），`code_chain` 需先落库才能做前缀匹配。
- **同源行在列表里仍各占一行**；`canonical_id` 的去重只体现在"资源总量"口径（`COUNT(DISTINCT COALESCE(NULLIF(canonical_id,0), id))`），组织树计数与分页总数仍按行。
- **`MediaHopGuard` 是进程内锁**，多实例部署时同一跳仍可能各飞一次；跨实例靠设备/上级侧串行与冷却兜底，没有做 Redis 分布式互斥。
- **状态跟随只覆盖 `CHANNEL`**：组织/设备没有 `online_status`，无对应事件。
- **一致性核对不做修复**，也不对异常行做屏蔽：异常组在播放选路时按"无指针"处理（走自身）。

---

## 14. 已完成：M6 视频业务完善（S1–S7）

**验证状态**：后端 `mvn -o -q -DskipTests compile` BUILD SUCCESS；`mvn -o test` → **Tests run: 341, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS**（M5 收尾 327，本轮净增 14 例：`GbIdleReclaimServiceTest` 10 + `ObservabilityServiceImplTest` 4；跳过项仍是 `SoloApplicationTests`，原因见 §12.8）；前端 `npx vue-tsc --noEmit` **0 error**、`npx vite build` 成功。

### 14.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S0 | 读 3.11.3 / 3.11.4 / 3.11.6 与云台、可观测章节，出 M6 分步计划 | 本节 |
| S1 | 业务表 DDL + 菜单权限（双方言） | `sql/upgrade/2026-11-27_m6_business_{mysql,pg}.sql`（`res_play_record` / `res_channel_preset`）；`2026-11-27_m6_menu_{mysql,pg}.sql`（`res:play-record[:list\|:export]`、`res:observability[:view]`、`res:channel:{play,playback,ptz,export}`，菜单 `/res/play-record`、`/res/observability` 挂 `res` 下 sort 4/5） |
| S2 | 回放统一入口 + 播放记录模型 | `ChannelPlaybackController`（`/{id}/playback/query\|start`、`/playback/stop\|control`）、`ChannelPlaybackService(Impl)`、`gb28181.support.ChannelEndpointResolver`（NATIVE→设备 INVITE；INBOUND→来源平台转发）、`ResPlayRecord` + `ResPlayRecordMapper`、`PlayRecordService(Impl)`（`recordSuccess` / `recordFailed` / `closeByStreamId` / `page` / `listForExport`） |
| S3 | 云台与预置位统一到 `res_channel` | `ChannelPtzController`（`/{id}/ptz/{move,focus,iris,wiper,drag-zoom,home-position}`、`/{id}/presets` CRUD + `call`）、`ChannelPtzService(Impl)`、`ResChannelPreset` + `ResChannelPresetMapper`（同编号覆盖槽位；删除先通知设备再删本地） |
| S4 | 实时点播统一入口 + 埋点 + 前端 | `ChannelLiveController`（`/{id}/play/start`、`/channel/play/stop`）、`ChannelLiveService(Impl)`（同一路流多人复用）；起播成功写一行播放记录、stop 按 `streamId` 闭合；`/res/channel` 行操作改为 监控/回放/云台 三按钮 + `ChannelVideoDialog` 三段面板（`ChannelLivePanel` / `ChannelPlaybackPanel` / `ChannelPtzPanel`），新建 `/res/play-record` 页 |
| S5 | 流复用与空闲回收 + 流统计 | `GbInviteSession.lastReaderTime`；`GbIdleReclaimService.reap()` + `GbHealthScheduledTasks.reapIdleInviteSessions`（initialDelay 30s / fixedDelay 10s）；配置 `gb28181.play.idle-reclaim-sec`（默认 120，≤0 关闭，三份 yaml 均已加）；回收时同步 `closeByStreamId` 闭合播放记录 |
| S6 | CSV 导出 + 运行自检 | `ChannelController GET /export`（复用 `pageList`，BOM + CSV 转义，上限 5 万行）；`PlayRecordController`（`/page` / `/export`）；`ObservabilityController` + `ObservabilityService(Impl)` + `ObservabilitySummaryResponse`；新建 `/res/observability` 页 |
| S7 | 单测 + 本节 + 全量回归 | 2 个新测试类 14 例；341 例全绿；`vue-tsc` / `vite build` 通过；§2 / §3.1 / §5 同步 |

### 14.2 新增接口与权限码

```
POST   /api/res/channel/{id}/play/start                 res:channel:play
POST   /api/res/channel/play/stop                       res:channel:play
POST   /api/res/channel/{id}/playback/query             res:channel:playback
POST   /api/res/channel/{id}/playback/start              res:channel:playback
POST   /api/res/channel/playback/stop                   res:channel:playback
POST   /api/res/channel/playback/control                res:channel:playback
POST   /api/res/channel/{id}/ptz/move|focus|iris|wiper|drag-zoom|home-position
                                                        res:channel:ptz
GET    /api/res/channel/{id}/presets                    res:channel:ptz
POST   /api/res/channel/{id}/presets                    res:channel:ptz
POST   /api/res/channel/{id}/presets/{presetId}/call    res:channel:ptz
DELETE /api/res/channel/{id}/presets/{presetId}         res:channel:ptz
GET    /api/res/channel/export                          res:channel:export
GET    /api/res/play-record/page                        res:play-record:list
GET    /api/res/play-record/export                      res:play-record:export
GET    /api/res/observability/summary                   res:observability:view
```

停止与回放控制**只认 `streamId`、不带通道 id**：同源选路后实际取流行可能与请求行不同；起播返回的 `streamId` 才是后续唯一凭据。

### 14.3 自行决断（复核前先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **回放 / 云台全部挂到 `/api/res/channel/{id}/...`**，不在 `gb28181` 下再开一套平行接口 | 本地与入站必须是同一套语义，前端只认 `res_channel.id`；入站由来源平台转发，差异收在 `ChannelEndpointResolver` 里。代价：`gb28181` 既有接口保留，短时间内两套入口并存 |
| 2 | **"有没有人看"只看 ZLM reader + 级联/广播消费**，不另建播放器心跳上报 | ZLM 的 `readerCount`/`totalReaderCount` 是唯一事实来源；级联推流与语音广播不体现在 reader 上，单独排除。代价：HLS 播放器断开后 ZLM 仍可能短暂计数 |
| 3 | **节点不可达一律放行**（`getMediaListOrNull=null`） | 一次网络抖动不能把所有会话当"没人看"清掉；代价是多占几分钟带宽 |
| 4 | **从未有观看者按 `readyTime` 起算**，有观看者按 `lastReaderTime` 起算 | 只按 `lastReaderTime` 会让"点播后一直没人打开播放器"永远回收不掉 |
| 5 | **播放记录一次请求一行，失败即闭合；成功在 stop / 空闲回收时闭合** | SUCCESS 的含义是"指令被受理并起流"，不等真解码出画面；不引入流级长事务。代价：设备侧无 RTP 但仍 READY 的极端情况会记成成功 |
| 6 | **导出一律同步 + 5 万行上限 + BOM** | 首版不做异步任务队列；超过走离线抽取。BOM 是为了 Excel 直接打开不乱码 |
| 7 | **预置位以编号为槽位，同编号覆盖** | 设备侧 CVO 语义就是按编号存；本地只镜像名称，删除先通知设备成功再删行，避免"本地没了设备还在" |
| 8 | **`ChannelVideoDialog` 切 tab 即整体卸载** | 卸载即停流，杜绝"切到云台后视频还在后台拉流"；代价：来回切换会重新 INVITE（同源流复用仍在 ZLM 层） |

### 14.4 新增单测（14 例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `GbIdleReclaimServiceTest` | 10 | 超阈值无读者回收（BYE + 闭合播放记录、不回写）；有读者只推进 `lastReaderTime`；多 schema 取 max 不误判；级联消费视为观众且不问节点；节点不可达放行；阈值内不回收；只判 READY 的 PLAY/PLAYBACK；`idle-reclaim-sec<=0` 整轮关闭；5s 写节流；`streamHasReader` 先看外部消费 |
| `ObservabilityServiceImplTest` | 4 | 同流多 schema 聚合（readers/bytesSpeed/aliveSecond 取 max、按 readers 倒序）；节点不可达标记且流列表为空；级联消费标记 `externalConsumer`；播放记录统计异常降级为 0 |

### 14.5 待实库验收（M6 收尾清单）

- **脚本上库**：`2026-11-27_m6_business_{mysql,pg}.sql` + `2026-11-27_m6_menu_{mysql,pg}.sql`（按数据库方言二选一；菜单与权限码幂等可重复执行，已授权 role_id=1）。
- **10 人一路流**：同一通道开 10 个页签点播，确认 ZLM 只有一路 `rtp` 流、`readerCount/totalReaderCount` 递增；全部关闭后 120s 内会话被 BYE + 关流，再次点播能重新起流。
- **回放可拖动倍速**：本地通道查一段录像 → 起播 → 暂停/继续 → 拖 seek → 0.25×/2×/4×；播放记录里出现一行 PLAYBACK 且 stop 后 `end_time`/`duration_sec` 有值。入站通道回放由来源平台返回录像。
- **云台 < 1s**：按住方向键设备起转、松开立即停；聚焦/光圈点一次 500ms 自动 stop；预置位设置/调用/删除与设备侧编号一致；看守位开启后超时自动回位。
- **播放记录可检索**：按通道编码 / 操作者 / 动作 / 结果 / 时间区间组合查询；导出 CSV 用 Excel 直接打开中文不乱码、行数与条件一致（≤5 万行）。
- **运行自检**：停掉一个 ZLM 节点后页面显示"不可达"且流列表为空；该节点上的会话本轮不被回收；`idle-reclaim-sec` 等阈值与 yaml 一致。

### 14.6 已知偏差 / 边界

- **空闲回收阈值只有全局**（`gb28181.play.idle-reclaim-sec`），没有平台 / 通道级覆盖。
- **回放控制能力取决于设备**：`pause/resume/seek/speed` 原样下发，设备不支持时平台如实报失败；倒放、关键帧跳转未做。
- **超时路径不闭合播放记录**：只有 stop 与空闲回收会闭合；会话超时（INVITING 阶段）本就没有成功记录，但设备异常导致的 READY→超时行会停在"未结束"。
- **运行自检每次请求都会向每个节点拉一次 `getMediaList`**，节点多/慢时响应会变长，首版未加缓存与并发上限。
- **通道导出在请求线程内一次性组装 5 万行**，没有分页游标；超大导出需离线抽取。
- **预置位名称只存本地**：设备侧只有编号与视角，换设备/重置后名称仍在但视角可能已变。

---

## 15. 已完成：M7 部署与运维 / 性能优化（S1–S7）

M7 不是新业务，只把设计文档里**已经写死的运维条款**落地：3.13.6 运行期自检、5.2 保留期、5.2.1 备份预算、6.7 慢查询治理、6.8 带宽监控、3.12.5 导出限流、3.12.6 导出必审、3.12.7 暴露面。**本轮没有新增权限码，也没有新增业务接口**。

**验证状态**：后端 `mvn -o test` → **Tests run: 363, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS**（M6 为 341，净增 22 例；跳过项仍是 `SoloApplicationTests`，原因见 §12.8）；前端 `npx vue-tsc --noEmit` **0 error**，`npx vite build` 成功（仅既有的 `tabler-icons-vue` chunk > 500kB 警告，与 M7 无关）。

### 15.1 分步落地

| 步 | 内容 | 落点 |
|---|---|---|
| S0 | 读 3.12.5–3.12.7 / 5.2 / 5.2.1 / 6.7 / 6.8 / 3.13.6，出 M7 七步计划 | 本节 |
| S1 | 健康探针：存活 + 就绪（DB / Redis / 媒体节点） | `api/health/{HealthController,HealthService,dto/{HealthStatus,ReadinessStatus}}`；`WebMvcConfig` 免登录放行；`application.yaml` 的 `health.*`；`HealthServiceTest` 7 例 |
| S2 | 保留期回收：5 张只追加表按天分批删除 | `infra/ops/{RetentionSettings,RetentionJob}`；`AuditLogMapper` / `ResPlayRecordMapper` / `SyncRecordMapper` / `SyncTaskMapper` / `SyncRoundMapper` 的 `deleteBeforeBatch`；三份 yaml 的 `retention.*`；`RetentionJobTest` 6 例 |
| S3 | 性能治理：慢 SQL 可观测 + 导出限流与审计 + 索引复核脚本 | `infra/ops/{SlowSqlStats,SlowSqlInterceptor}`（`MybatisPlusConfig` 注册）；`perf.*`；`ChannelController.export` / `PlayRecordController.export` 加 `@RateLimiter` + `auditService.record(EXPORT_*)`；`AuditLog.TARGET_PLAY_RECORD`；`sql/ops/index_review_{mysql,pg}.sql`；`SlowSqlStatsTest` 8 例 |
| S4 | 带宽监控：汇总字段 + 自检页卡片 | `ObservabilitySummaryResponse.bandwidth`（`BandwidthStats` / `BandwidthStreamItem`）；`ObservabilityServiceImpl.summarizeBandwidth`（`TOP_BANDWIDTH_STREAMS=5`）+ `toMbps`；`/res/observability` 新增「出流带宽」卡片与 `mbpsText` / `hourVolumeText`；`ObservabilityServiceImplTest` +1 例 |
| S5 | 部署产物：镜像 / 编排 / 反代 / 环境变量样例 | `solo/Dockerfile` + `.dockerignore`；`solo-ui/Dockerfile` + `.dockerignore` + `nginx.conf`；`deploy/docker-compose.yml` + `deploy/.env.example`；`application-pg.yaml` 全部改 `${ENV:默认值}`；`application.yaml` 的 `openapi.trusted-proxies`；README「### 6. 容器化部署（可选）」 |
| S6 | 运维手册 + 备份脚本 + README 入口 | `docs/OPERATIONS.md`（11 节：部署升级 / 探针 / 日志 / 保留期 / 备份恢复 / 性能 / 安全 / 故障处置 / 检查清单 / 产物索引）；`scripts/backup.sh`、`scripts/backup.ps1` |
| S7 | 全量回归 + 台账回填 | 363 例全绿；`vue-tsc` / `vite build` 通过；本节；§5 待做清单同步 |

### 15.2 新增接口与配置项

```
GET  /api/health         存活探针（免登录，恒 200，不碰 DB/Redis/ZLM）
GET  /api/health/ready   就绪探针（免登录；DB 或 Redis 不可用 → 503，响应体仍带依赖明细）
```

```yaml
health:                            # 3.13.6 运行期自检
  ready-cache-ms: 5000             # 就绪结果缓存；0 = 不缓存
  ready-require-media: false       # 媒体节点是否参与就绪判定（默认不参与）
  db-timeout-seconds: 2            # JDBC isValid 超时

perf:                              # 6.7 慢查询治理
  slow-sql-ms: 1000                # <=0 关闭；只记 Mapper 方法 id / 耗时 / 参数个数
  slow-sql-warn-per-minute: 20     # WARN 限流，另有 5 分钟增量汇总

retention:                         # 5.2 保留期（三份 yaml 均已配置）
  enabled: true
  audit-log-days: 365
  play-record-days: 180
  sync-days: 365
  batch-size: 5000
  max-batches-per-run: 200         # 单表单轮上限约 100 万行
  startup-delay-ms: 300000
  interval-ms: 86400000
```

环境变量清单见 `deploy/.env.example`（带 `:?` 的项缺失会让 compose 拒绝启动）。**默认值仍等于原本机口径**，容器部署只做覆盖，不改变开发环境行为。

### 15.3 自行决断（复核前先看这里）

| # | 决断 | 理由 / 代价 |
|---|---|---|
| 1 | **不引入 Actuator，自建两个探针端点** | 离线依赖受限；响应字段与判定口径要可控，也少一条依赖。代价：没有 JVM / 线程池 / 数据源池内建指标 |
| 2 | **就绪默认不要求媒体节点，且不主动探活 ZLM** | 管理 API、目录同步、SSE 在 ZLM 抖动时仍可用，不该整体摘实例；探针必须"便宜且稳定"，不能等一个可能超时的 HTTP。代价：ZLM 全挂时仍报就绪，要靠 `/res/observability` 与日志发现 |
| 3 | **就绪结果缓存 5s** | 秒级探针每次都开连接会把连接池探成瓶颈。代价：故障最多晚 5s 被摘流 |
| 4 | **慢 SQL 不记 SQL 原文**，只记方法 id / 耗时 / 参数个数 + 每分钟 20 条限流 | 日志本身不能成为泄露面或注入点，也要防止慢查询风暴写爆日志盘。代价：排障需靠 Mapper 方法名回查语句 |
| 5 | **导出限流在 Redis 故障时降级放行** | 限流是保护措施，保护组件挂了不应阻断业务。代价：故障期间限流失效，必须监控 `限流组件降级放行` 关键字 |
| 6 | **保留期每天一轮、单表 try/catch、单表单轮上限 100 万行** | 定时任务抛未捕获异常会永久停调，所以每表独立；分批避免长事务与主从延迟。代价：积压多时需多轮才追平 |
| 7 | **容器化只做"环境变量覆盖、默认值不变"** | 降低回归风险，开发机与容器走同一份 yaml。代价：yaml 可读性下降，需靠 `.env.example` 对照 |
| 8 | **备份脚本自带校验（`pg_restore --list` / `gzip -t`）并在失败时删半成品** | 坏包比没包更危险——它会让人以为有备份。代价：多花一次读盘时间 |
| 9 | **索引缺口只出脚本、不自动建索引** | 生产建索引要择时（MySQL `LOCK=NONE`、PG `CONCURRENTLY`），不能藏在启动逻辑里 |

### 15.4 新增单测（22 例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `HealthServiceTest` | 7 | DB+Redis 正常即就绪；DB 失败不就绪且**不泄漏连接串**；Redis 失败不就绪；`ready-require-media` 开关两种取值；缓存窗口内不重复探测；`ready-cache-ms=0` 每次真探；存活探针返回服务名/版本/启动时间/uptime |
| `RetentionJobTest` | 6 | `enabled=false` 全关；分批删到不足一批即停；触及单轮批数上限即停；某表天数 `<=0` 只关该表；单表异常不阻断其他表；`sync_record` 先于 `sync_task` |
| `SlowSqlStatsTest` | 8 | 阈值内只计总数；超阈值记慢条目（峰值 / 参数数 / 最近 20 条与顺序）；WARN 每分钟限流但计数照常；`slow-sql-ms<=0` 关闭；5 分钟汇总无新增时静默；拦截器快慢两条路径各自记录/不记录 |
| `ObservabilityServiceImplTest` | +1 | 带宽汇总：1.75MB/s → 14.0 Mbps、按码率倒序取 top5、`bytesSpeed<=0` 只计观看者不计带宽、`externalConsumer` 透传、`totalReaders` 合计 |

### 15.5 待实机验收（M7 收尾清单）

> 本机**没有 docker**，S5 的镜像构建与 `docker compose config` 未跑过；只对 compose 与 `application-pg.yaml` 做了 `yaml.safe_load` 结构校验（services 四件套、占位符可解析）。以下必须在有 docker / 实库的机器上补。
> 已提供一次性验收脚本 `scripts/verify_m7.sh`：用隔离项目 `solo-verify` 起栈（容器 `solo-verify-*`、卷独立、UI 走 18081、库只回环 55432），自动跑下面 1–6 项的自动化部分，输出 `PASS/FAIL/SKIP` 并以退出码反映结果。
> 跑法：`bash scripts/verify_m7.sh`（全流程，首次含镜像构建）；也可 `up|probe|retention|export|bandwidth|backup|logs|down` 单跑一项。前置：Docker + compose v2、8080 与 5060(tcp+udp) 空闲、首次构建需联网、宿主同时有 `pg_dump`/`pg_restore`（缺则备份步骤退化为容器内 dump）。
> 仍需人工的只有：带宽口径换算（需真实 ZLM 多路播放）、SSE/CSV 的 `proxy_buffering` 行为、`sync_task`/`sync_round` 补索引前后的 `EXPLAIN` 对比、`backup.ps1` 的 Windows 存盘格式。
> 脚本本身的审查结论（已修掉哪些真机坑、哪些仍需实机确认、以及 marker 与端口等已知取舍）见 **15.5.1**。

- **镜像与编排**：`docker compose -f deploy/docker-compose.yml config` 无告警；`up -d --build` 后四容器健康；`solo-ui` 反代 `/api` 与 `/open-api` 通；SSE（消息推送）与 CSV 下载"边产生边到达"（验证 `proxy_buffering off` 生效）。
- **探针语义**：正常时两个端点均 200；`docker compose stop postgres` → `/api/health/ready` 503 且 `db=DOWN`，`/api/health` 仍 200；恢复后 5s 内回到 200（缓存窗口）。
- **保留期**：造过期数据（把某行时间改到窗口外），临时把 `retention.interval-ms` 调小观察分批日志；核对五张表删除时间点一致；`sync_task` / `sync_round` 补索引前后用 `sql/ops/index_review_*.sql` 对比 `EXPLAIN`（预期由全表扫转 range）。
- **导出限流与审计**：同一账号 1 分钟内第 6 次导出被拒并提示"导出过于频繁"；`audit_log` 出现 `EXPORT_CHANNEL` / `EXPORT_PLAY_RECORD` 且 detail 含行数；停 Redis 后导出恢复可用但日志出现 `限流组件降级放行`。
- **带宽口径**：多路播放时，页面「出流带宽」总 Mbps ≈ ZLM 各流 `bytesSpeed` 之和 × 8 / 1e6（2 位小数）；小时流量按 3600s 折算。
- **备份与恢复**：`scripts/backup.sh` 对真实库跑通（产物自校验通过）；按 `docs/OPERATIONS.md` §6.3 恢复到临时库后能启动并过探针、能登录；`KEEP_DAYS` 轮转按预期清理。
- **Windows 侧**：`scripts/backup.ps1` 需以 **UTF-8 with BOM** 保存（PS 5.1 按 ANSI 读取无 BOM 的中文脚本会报乱码语法错误），已按此存盘并通过 `ParseFile` 语法校验。

#### 15.5.1 验收脚本审查结论（静态复核；本机仍无 docker）

对 `scripts/verify_m7.sh` 做过两轮逐行审查，针对"真机上会直接中断或误判"的问题全部改完；以下按类记录，便于日后改脚本时不再踩回去。

**A. 已修掉：会让整轮中止或给出假结论**

| # | 坑 | 真机上的表现 | 修法 |
|---|---|---|---|
| 1 | 端口检查用 `dc ps -q solo` 判断本栈 | 中断后残留的验证栈被当成"端口被占"，复跑直接中止 | 改用 `dc ps -aq`：本栈存在则跳过端口检查 |
| 2 | `down` 等子命令把新随机库口令写回 `.verify-m7.env` | 下次 `up` 拿错口令连老卷，卡满 420s 超时才报错，且完全指不到原因 | 非 `up\|all` 动作改用临时 env，绝不覆盖持久文件 |
| 3 | 补跑的升级脚本是写死的几份，与 `sql/upgrade/` 实际内容脱钩 | 新增迁移不会自动跟上；验收依赖的 4 表 2 权限散在 `res_core` / `sync_runtime` / `m6_business` / `m6_menu` 四份里，漏一份就是缺表或缺权限码 | 按文件名全量补跑 16 份 `*_pg.sql` + `zz_verify_sql_marker` 幂等记账 |
| 4 | `2026-11-23_res_readonly_grants_pg.sql` 含 psql 变量 `:app_user` | 自动执行必报语法错 | 排除并提示需 `-v app_user=…` 手工执行 |
| 5 | 导出步骤前不自愈 nginx | solo 重建换 IP 后第 3/4 次导出 502，看起来像"限流没生效" | `step_export` 开头、子命令分发、重建 solo 后都调 `ensure_ui_up` |
| 6 | 恢复后行数直接跟"比对时刻的源库"比 | dump 与比对之间源库仍有变动（其他客户端写入、保留期删除），两者天然不等 → 偶发假 FAIL | 改比 `[dump 前, dump 后]` 区间 |
| 7 | 清理临时库 `DROP DATABASE` 无 `FORCE` | 残留连接使 DROP 失败、CREATE 撞"已存在"，错报成"建临时库失败" | 加 `WITH (FORCE)` |
| 8 | `pg_stable` 只看 `pg_isready` | 首次初始化 SQL 还没提交就判就绪，后续 psql 全空 | 要求 `count(sys_user) >= 1` 且连续三次通过 |
| 9 | `all` 有 FAIL 仍执行 `down` | 现场（容器/卷/日志）被删，无法复查 | 失败保留栈，提示手工 `down` |
| 10 | 升级脚本逐条提交 + 失败不写 marker | 中途失败留半成品，复跑踩着半成品重放整份 | 每份加 `--single-transaction`，失败整份回滚 |
| 11 | 事务判定只匹配 `BEGIN` | 把 `begin_time` 列名也算进来，4/16 份静默退化为逐条提交 | 必须匹配整条 `BEGIN;`（实测 16 份全部走 `-1`） |
| 12 | `assert_verify_schema` 只查 `sys_permission` 有码、不查 role 授权 | 权限码在而授权不在时门槛放行，导出首条就 403，被误读成限流或反代故障（`-1` 之后只剩两类来源：旧脚本留下的半成品卷、将来某份带 `BEGIN` 而退回逐条提交的脚本） | 加 `grant_exists`（`role_id=1` join），缺失项分 `perm:` / `grant:` |
| 13 | `ensure_ui_up` 把"nginx 还没起来"当"缓存旧 IP" | 首次 `up` 后立刻 restart，打断 nginx 启动 | 先等容器 running，running 才 restart；失败打印 `dc ps` + nginx 日志 |
| 14 | 行数区间假定源库只增不减 | 保留期在我们注入的 120s 周期里删行，`after < before` 使区间倒置 → 恒 FAIL | lo/hi 归一化后再判 |
| 15 | 空值守卫拼成 `"$dst$lo$hi"` 再判 | 中间/末位为空时拼出纯数字逃过守卫，`[ -ge ]` 抛 `integer expression expected` | 三个值逐个校验 |
| 16 | `backup.sh` 返回 0 但目录里没有产物仍记 PASS | 坏包被当成"有备份" | 产物非空才 PASS，否则 FAIL |

**B. 已修掉：环境相关（不中断，但会误导）**：`JAVA_OPTS` 整串覆盖时丢了 `-Duser.timezone=Asia/Shanghai`；容器无 LANG 导致 psql 走 `SQL_ASCII` 把中文写成乱码，在 `psql_q`/`psql_run`/`psql_file` 三个包装上统一加 `-e PGCLIENTENCODING=UTF8`（`restored_count` 与临时库 DROP/CREATE 没加，它们只读数字、不写中文）；Git Bash 下 `/tmp/...` 参数被改写成宿主机路径，加 `hostpath()`；`die` 补"栈还开着怎么收拾"；`wait_ready` 补 hint 说明等的是什么；保留期轮询在 psql 空输出时提前退出而不是空转 120s；`mkdir/chown logs` 只在 `up\|all` 做。

**C. 仍需实机确认（脚本覆盖不到或只能 SKIP）**

1. 16 份 `*_pg.sql` 是否一次全绿、`zz_verify_sql_marker` 是否为 15 条记录（排除 `:app_user` 那份）——这是本轮改动最大、最该先验的一条。
2. 带宽口径：总 Mbps ≈ 各流 `bytesSpeed` 之和 × 8 / 1e6（需真实 ZLM + 多路播放）。
3. SSE 与 CSV 的 `proxy_buffering off` 实际行为（需真实播放/大导出观察）。
4. `sync_task` / `sync_round` 补索引前后的 `EXPLAIN` 对比（预期全表扫 → range）。
5. `scripts/backup.ps1` 的 Windows 存盘格式（需真机跑一次）。

**D. 已知取舍（不是缺陷，但要知道）**
- marker 按**文件名**记账、不按内容：改了某个"已应用"的脚本本轮不会重放，要复现真实升级必须 `VERIFY_RESET=1 bash scripts/verify_m7.sh down` 重建。
- 一份历史升级脚本失败会让整轮变红、栈也不 down（有意为之：升级失败不该是绿的）；需人工判断它与 M7 断言是否相关。
- 验证栈一旦存在，端口检查与"线上栈在跑"的检查都会整体跳过（`dc ps -aq` 非空即 return）：此时端口冲突只能靠 compose 自己的报错发现。
- 8080 / 5060 无法换端口（与服务端 `server.port`、SIP 配置耦合），跑之前必须确保空闲。
- 静态复核覆盖 `bash -n`、`--help` 冒烟、16 份 SQL 事务安全性、`check_restored_count` 10 个边界；**docker 路径仍未真实执行**。

### 15.6 已知偏差 / 边界

- **无 docker 环境**，镜像未实际构建；`solo/Dockerfile` 基于 `maven:3.9-eclipse-temurin-21` + `eclipse-temurin:21-jre-jammy`，离线构建需预热 `~/.m2` 或改用 `MAVEN_ARGS` 指向内网仓库。
- **就绪探针不含 JVM / 连接池指标**（见 15.3 #1）；需要时再引入 Actuator，属于新增依赖决策。
- **慢 SQL 无原文、无执行计划**；定位靠 Mapper 方法 id，`EXPLAIN` 需人工按 `sql/ops/index_review_*.sql` 跑。
- **保留期是 DELETE 不是分区 DROP**：`audit_log` 等五表未分区，超大表回收期会有明显 IO；单表单轮 100 万行上限意味着积压多时要多轮追平。
- **`sync_task.create_time` / `sync_round.begin_time` 仍无时间打头索引**：保留期删除与按时间查询会全表扫，脚本已标 `MISSING` 并给建议 DDL，需运维择时执行。
- **compose 不含 ZLM 与 S3**：ZLM 需要 host 网络与 RTP 端口段，S3 常独立部署；两者用 `.env` 指向外部地址。
- **备份仅覆盖关系库**：对象存储里的录像/文件走存储侧生命周期；脚本未内置异地复制与加密，需在存储侧或调度层补。
- **HSTS 在 `solo-ui/nginx.conf` 中为注释态**：需在 TLS 终止处显式启用，避免纯 HTTP 环境下把自己锁死。
- **限流只作用于两个导出端点**：其余接口未接 `@RateLimiter`，开放平台侧有独立的 QPS / 日调用量限流。

