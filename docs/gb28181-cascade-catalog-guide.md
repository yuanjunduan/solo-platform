# 国标 GB/T 28181 级联目录推送：流程与报文样例

> 面向：需要了解/对接「本平台向上级平台推送目录」的同学
> 参考实现：CSS `P-Base-V2.0`（Go）、voglander（Java）
> 本文所有报文样例均来自真实日志或按源码逐字段构造，可直接对照排查

---

## 1. 前置知识：目录体系

### 1.1 20 位国标编码

```
34020000002150000002
└──┬───┘└┬┘└──┬──┘
   │     │    └── 序号 7 位
   │     └─────── 类型段 3 位（第 11-13 位）
   └───────────── 域编码 10 位（中心编码 6 + 行业编码 2 + 类型 2）
```

| 类型段 | 含义 | 在目录树中的位置 |
|---|---|---|
| `200` | 平台/系统 | 根（本平台自身） |
| `215` | 业务分组 | **只能作根**，ParentID = 系统 ID |
| `216` | 虚拟组织 | 分支，ParentID = 父 215 或父 216 |
| `118` | 设备（NVR/IPC） | 叶/中间节点 |
| `132` | 通道 | 叶节点 |

### 1.2 层级规则（GB/T 28181-2016 附录 O）

```
业务分组(215) ── 根
    └── 虚拟组织(216) ── 分支
            └── 设备(118) / 通道(132) ── 叶
```

- 附录 O h) 规定设备/通道的 `ParentID` 为 `父设备ID/虚拟组织ID`（多个 ID 用 `/` 分隔），用于同时表达"归属设备"与"归属组织"。
- **本平台采用简化模型**：只推「分组 + 通道」两层，不推设备节点；通道的 `ParentID` 直接写它所在分组的编码。参见本文 2.1 说明。

### 1.3 目录 Item 的关键字段

| 字段 | 必选 | 说明 | 坑 |
|---|---|---|---|
| `DeviceID` | 是 | 节点国标编码（20 位） | 通道若有"对外推送码"会用推送码替代 |
| `Name` | 是 | 节点名称 | 含 `& < > " '` 必须转义，否则整包 XML 非法 |
| `CivilCode` | 是 | 行政区划码 | **必须等于 `ParentID`**，否则海康判为无效条目 |
| `Parental` | 否 | 1=目录节点（分组/设备），0=通道 | 全写 0 会让上级把分组当可点播设备 |
| `ParentID` | 是 | 父节点编码 | 根节点可省略或写平台 ID |
| `BusinessGroupID` | 否 | **仅 216 虚拟组织**：所属 215 的编码 | 缺失时上级无法把它挂到正确业务分组下 |
| `Status` | 是 | `ON` / `OFF` | |
| `Info/PTZType` | 否 | 云台类型：0 未知、1 球机、2 半球、3 枪机、4 遥控枪机 | **必须写在 `<Info>` 内**；写 0 会被当非视频通道 |
| `Event` | 否 | 仅变更通知用：`ADD` / `UPDATE` / `DEL` / `ON` / `OFF` | 全量快照里不该出现 |

### 1.4 目录有两条完全不同的语义

| | Query / Catalog **Response** | Catalog **NOTIFY** |
|---|---|---|
| 谁发起 | 上级发 Query，本平台应答 | 本平台主动发 |
| 语义 | **全量快照**（带 SumNum + 分片） | **增量事件**（一条一包，带 Event） |
| SN | 原样带回上级的 SN | 自己生成 |
| 上级行为 | 按全量重建/覆盖 | 只 upsert 出现过的节点，**不会删除**未出现的节点 |

> ⚠️ 这张表是理解所有"目录不同步"问题的核心：**NOTIFY 里"没出现"的节点，上级不会删。**

---

## 2. 本项目（solo-platform）实现

### 2.1 数据模型

| 表 | 作用 | 关键字段 |
|---|---|---|
| `gb_parent_platform` | 上级平台配置 | `server_gb_id`（上级编码）、`server_ip/port`、`character_set` |
| `gb_platform_group` | 推送分组树 | `code`（20 位 215/216）、`parent_id`（**本地主键**，非编码） |
| `gb_platform_share` | 通道挂载关系 | `platform_id` + `group_id` + `resource_id`(通道主键)、`exposed_code`（对外推送码） |
| `gb_device_channel` | 通道 | `channel_id`、`parental`、`status`、`ptz_type`… |

约束：`uk_platform_resource(platform_id, resource_id)` —— 同一通道在同一上级平台下**只能挂一个分组**。

### 2.2 三条推送链路

```
┌─ A. 上级 Query/Catalog ────────────────────────────────┐
│  上级  ──MESSAGE(Query/Catalog)──►  CatalogQueryMessageHandler
│        ◄──MESSAGE(Response, 分片)── PlatformCatalogXml.buildResponse
│  SumNum = 全量总数；每片 Num = catalog-page-size（默认 3）
└────────────────────────────────────────────────────────┘

┌─ B. 人工「推送目录」按钮 ───────────────────────────────┐
│  UI ──► CatalogShareServiceImpl.pushCatalog()
│        ──► PlatformCommander.sendNotifyCatalogList()
│        ──► 一包 NOTIFY，SumNum = 全量条目数，无 Event
└────────────────────────────────────────────────────────┘

┌─ C. 挂载/卸载/删分组 ──────────────────────────────────┐
│  业务动作 ──删/增 DB 行──► 发布 ShareChangedEvent
│             ──(异步 @Async gb28181BizExecutor)──►
│  ShareChangedEventListener：检查平台启用 + 活跃 Catalog 订阅
│             ──► loadSharedCatalogItems() 取当前全量
│             ──► 一包 NOTIFY（与 B 相同）
└────────────────────────────────────────────────────────┘
```

### 2.3 变更与撤销：已事件化（ADD / DEL）

> 2026-11-19 已按 CSS 的事件模型改造完前五步：**挂载发 `Event=ADD`（每次挂载都先按根→叶补一遍父分组链）、卸载/清空/删分组发 `Event=DEL`、分组改名发 `Event=UPDATE`、换父/改码走「DEL 旧 + ADD 新」**。
> 剩余：发送失败重试、兜底全量分片、历史脏节点清理。

| 操作 | DB 动作 | 上报动作 | 状态 |
|---|---|---|---|
| 挂载通道 `addShares` | 插入 share 行 | 分组链 `ADD`（根 → 叶，每次都补）→ 通道 `ADD` + 全量兜底 | ✅ 已实现 |
| 卸载通道 `removeShare` | **先取节点** → 删 share 行 | 逐条 `Event=DEL` + 全量兜底 | ✅ 已实现 |
| 清空分组 `clearShares` | **先取节点** → 删该组 share | 组内通道逐条 `Event=DEL` | ✅ 已实现 |
| 清空平台 `clearAllSharesByPlatform` | **先取节点** → 删全部 share | 逐条 `Event=DEL` | ✅ 已实现 |
| 删分组 `deleteGroup` | **先取节点** → 删组 + 联动删组内 share | 通道逐条 `DEL` 后再发分组 `DEL`（空分组也发） | ✅ 已实现 |
| 新建分组 `createGroup` | 插入分组行 | 不发（空分组不推是对的） | — 无需处理 |
| 改名 `updateGroup`（只名称变） | 更新分组行 | 一包 `Event=UPDATE`（`ParentID` 不变）+ 全量兜底 | ✅ 已实现 |
| 换父 `updateGroup`（`parentId` 变） | 更新分组行 | `DEL` 旧位置 → 新父链 `ADD`（根 → 叶，含自身）+ 全量兜底 | ✅ 已实现 |
| 改码 `updateGroup`（`code` 变） | 更新分组行 | 旧子树 `DEL`（通道 → 子分组深→浅 → 自身）→ 新父链 `ADD` → 新子树 `ADD` | ✅ 已实现 |

**改代码时必须守住的 7 条约束：**

1. **先取数据再删行**：`loadRevokeItems()` 必须在 `shareMapper.delete()` 之前调用。删完 share 只剩 resourceId，通道编码/名称/父分组码都拿不到，DEL 报文就构造不出来了。
2. **DEL 用完整 Item**：带 `Name`/`ParentID`/`CivilCode`/`Status`，上级靠这些定位节点；极简 Item 在部分上级上会被忽略。
3. **与会话口径一致**：`parentId` = 所在分组 GB 码，`DeviceID` 优先 `exposed_code`，与全量推送完全相同。
4. **事件不依赖订阅**：直接走 `PlatformCommander`，订阅过期期间的变更也不会静默丢失；原有全量兜底仍受订阅限制。
5. **一批撤销必须串行**：`sendCatalogEventNotifyBatch` 把整批节点放进同一个 `gb28181-biz` 任务按序发送。逐条调单发方法（各自丢线程池）会打乱「叶子→根」顺序。
6. **分组永远父在前**，两种推送形态都要守：
   - 增量：挂载通道时，该分组到根的整条链每次都按根 → 叶发 `ADD`（不再用「该组是否已有挂载」推断），与通道 `ADD` 进同一次 `sendCatalogEventNotifyBatch`。本地无法可靠判断上级是否收到过（UDP 丢包 / 上级重建目录 / 早期只推过全量），而 NOTIFY 是增量 upsert，重复 `ADD` 幂等——宁可多推一个分组，也不能让通道的 `ParentID` 指向上级未知节点。
   - 全量：`loadSharedCatalogItems` 先按**分组层级深度升序**（同深度再按 code），再排通道。只按 code 升序是错的：子分组序号比父小（父 `…2160000009`、子 `…2160000002`）时子会跑到父前面，上级同样会当孤儿丢弃。
7. **先删后加必须同一序列**：分组换父/改码是「`DEL` 旧位置 → `ADD` 新父链」，两类事件只能进同一次 `sendCatalogEventSequence`（`PlatformCommander.CatalogEvent` 列表）。分两次提交线程池会让 `ADD` 跑到 `DEL` 前面，节点被删掉后再也上不来。

**仍需处理的问题（后续阶段）：**

- 发送失败无重试（阶段 6）
- 每次变更额外推一包全量兜底，节点多时需分片（阶段 6）

### 2.4 报文渲染规则（已对齐 CSS / voglander）

| 字段 | 本项目规则 | 代码位置 |
|---|---|---|
| `CivilCode` | 分组和通道**一律写 `parentCode`**（= 本条 Item 的 ParentID） | `PlatformCatalogXml.appendItem` |
| 空值字段 | 值为 null/空白时**整段省略**（空标签会被上级当非法节点） | `PlatformCatalogXml.appendTag` |
| `PTZType` | 写在 `<Info>` 内；取不到或为 0 时按 **1**（球机）兜底 | `PlatformCatalogXml.appendPtzInfo` |
| 厂商/型号/归属/IP | 通道为空时从 `gb_device` 按 `deviceId` 回填，有值才写 | `CatalogShareServiceImpl.fillChannelMeta` |
| 分组 `Manufacturer/Model/Owner` | 固定 `Solo` / `Group` / `Solo` | `PlatformCatalogXml.appendItem` |
| `BusinessGroupID` | 仅 216 输出，取沿父链上溯的第一个 215 编码 | `CatalogShareServiceImpl.findBusinessGroupCode` |

`ParentID` 解析（`resolveParentCode`）：item 的 `parentId` 为 `"0"`（根哨兵）时兜底写本平台编码，否则原样写父节点编码。

### 2.5 报文样例（真实日志，2026-11-18 18:17:01）

#### 2.5.1 主动推送 / 变更通知（NOTIFY，全量快照）

请求行与头：

```
NOTIFY sip:37000020002000000100@192.168.106.35:7100 SIP/2.0
From: <sip:34020000002000000001@3402000000>;tag=...
To: <sip:37000020002000000100@192.168.106.35:7100>
Event: catalog
Subscription-State: active
Content-Type: Application/MANSCDP+xml
Content-Length: 1728
```

报文体（长度 1728，GB2312 字节）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>317375</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>3</SumNum>
<DeviceList Num="3">
<Item>
<DeviceID>34020000002150000002</DeviceID>
<Name>solo测试2</Name>
<Manufacturer>Solo</Manufacturer>
<Model>Group</Model>
<Owner>Solo</Owner>
<CivilCode>34020000002000000001</CivilCode>
<Parental>1</Parental>
<ParentID>34020000002000000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
</Item>
<Item>
<DeviceID>34020000001320000102</DeviceID>
<Name>楼顶云台东105-通道1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>HK-PTZ</Model>
<Owner>solo</Owner>
<CivilCode>34020000002150000002</CivilCode>
<Address>192.168.60.105</Address>
<Parental>0</Parental>
<ParentID>34020000002150000002</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.105</IPAddress>
<Port>8000</Port>
<Status>ON</Status>
<Longitude>0.0</Longitude>
<Latitude>0.0</Latitude>
<Info>
<PTZType>1</PTZType>
</Info>
</Item>
<Item>
<DeviceID>34020000001320000103</DeviceID>
<Name>海康IPC-直连-60.2-1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>20220831CCCHK49329378</Model>
<Owner>solo</Owner>
<CivilCode>34020000002150000002</CivilCode>
<Address>192.168.60.2</Address>
<Parental>0</Parental>
<ParentID>34020000002150000002</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.2</IPAddress>
<Port>8000</Port>
<Status>ON</Status>
<Longitude>0.0</Longitude>
<Latitude>0.0</Latitude>
<Info>
<PTZType>3</PTZType>
</Info>
</Item>
</DeviceList>
</Notify>
```

阅读要点：
- `SumNum=3` 是**全量总条数**，`Num="3"` 是本包条数（分片时两者不同）。
- 分组 `CivilCode` = `ParentID` = 平台编码；通道 `CivilCode` = `ParentID` = 分组编码。
- `Event` 不出现 —— 这是快照，不是事件。

#### 2.5.2 上级查询应答（Response，分片）

上级发 `Query/Catalog` 后，本平台按 `catalog-page-size`（默认 3）分片应答，每片 `SumNum` 都写**全量总数**：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>Catalog</CmdType>
<SN>11640</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>3</SumNum>
<DeviceList Num="3">
<Item>
<DeviceID>34020000002150000002</DeviceID>
...（Item 结构与 2.5.1 完全相同）
</Item>
</DeviceList>
</Response>
```
- `SN` / `DeviceID` **原样带回上级查询报文里的值**。
- 上级收齐 `SumNum` 条后结束；未收齐可重发 Query。

#### 2.5.3 挂载（Event=ADD）与撤销（Event=DEL）—— 2026-11-19 实测

挂载通道 103 到 `solo测试分组`（2160000001）：

```
[通道推送] 新增事件下发 platformId=1 分组:34020000002160000001 节点:1
[级联目录] 事件推送 ... Event:ADD 节点:34020000001320000103 父:34020000002160000001 SN:803886
[SIP应答] NOTIFY → status=200 reason=OK callId=dc9cc2ce...@192.168.118.7
```

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>803886</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000001320000103</DeviceID>
<Name>海康IPC-直连-60.2-1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>20220831CCCHK49329378</Model>
<Owner>solo</Owner>
<CivilCode>34020000002160000001</CivilCode>
<Address>192.168.60.2</Address>
<Parental>0</Parental>
<ParentID>34020000002160000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.2</IPAddress>
<Port>8000</Port>
<Status>ON</Status>
<Longitude>0.0</Longitude>
<Latitude>0.0</Latitude>
<Info>
<PTZType>3</PTZType>
</Info>
<Event>ADD</Event>
</Item>
</DeviceList>
</Notify>
```

卸载同一条通道：

```
[通道推送] 卸载 platformId=1 groupId=2122985804564389889 resourceId=9103
[通道推送] 撤销事件下发 platformId=1 节点:1
[级联目录] 事件推送 ... Event:DEL 节点:34020000001320000103 父:34020000002160000001 SN:754972
[SIP应答] NOTIFY → status=200 reason=OK callId=f4dff1d8...@192.168.118.7
```

报文与上面**逐字段相同**，只把 `<Event>ADD</Event>` 换成 `<Event>DEL</Event>`、`SN` 换成 754972。

实测结论：两包均 `SumNum=1`、`Num="1"`，`CivilCode` = `ParentID` = 父分组编码 `34020000002160000001`，海康均回 `200 OK`。
每包事件之后还有一包全量兜底 NOTIFY（`SumNum=5/6`），同样回 200。

#### 2.5.4 删分组：通道 DEL → 分组 DEL（严格叶子 → 根）—— 2026-11-19 08:53:50 实测

删除 `solo测试分组`（2160000001，组内有 1 条通道 102）：

```
[通道推送] 撤销事件下发 platformId=1 节点:2
[推送分组] 删分组 platformId=1 groupId=2122985804564389889 联动清理挂载=1 撤销节点=2（通道 1 + 分组 1）
[级联目录] 事件推送 ... Event:DEL 节点:34020000001320000102 父:34020000002160000001 SN:361803   ← 叶子先
[级联目录] 事件推送 ... Event:DEL 节点:34020000002160000001 父:34020000002150000001 SN:680786   ← 根后
[SIP应答] NOTIFY → status=200 reason=OK callId=503f2cf5...@192.168.118.7
[SIP应答] NOTIFY → status=200 reason=OK callId=11ba0e0a...@192.168.118.7
```

包 1（通道，SN=361803）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>361803</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000001320000102</DeviceID>
<Name>楼顶云台东105-通道1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>HK-PTZ</Model>
<Owner>solo</Owner>
<CivilCode>34020000002160000001</CivilCode>
<Address>192.168.60.105</Address>
<Parental>0</Parental>
<ParentID>34020000002160000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.105</IPAddress>
<Port>8000</Port>
<Status>ON</Status>
<Longitude>0.0</Longitude>
<Latitude>0.0</Latitude>
<Info>
<PTZType>1</PTZType>
</Info>
<Event>DEL</Event>
</Item>
</DeviceList>
</Notify>
```

包 2（分组，SN=680786）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>680786</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000002160000001</DeviceID>
<Name>solo测试分组</Name>
<Manufacturer>Solo</Manufacturer>
<Model>Group</Model>
<Owner>Solo</Owner>
<CivilCode>34020000002150000001</CivilCode>
<Parental>1</Parental>
<ParentID>34020000002150000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
<BusinessGroupID>34020000002150000001</BusinessGroupID>
<Event>DEL</Event>
</Item>
</DeviceList>
</Notify>
```

要点：

- 两包都在同一个 `gb28181-biz-1` 线程内串行发出（`.071` → `.072`），这是 `sendCatalogEventNotifyBatch` 的作用；若逐条调单发方法各自丢线程池，顺序无法保证。
- 通道包 `Parental=0`，`CivilCode` = `ParentID` = 所属分组编码；分组包 `Parental=1`，`CivilCode` = `ParentID` = 父分组编码（`2150000001` 主控制中心），216 节点还必带 `BusinessGroupID`。
- 两包均回 `200 OK`；`.138` 的第三包是全量兜底（删后只剩 `solo测试2` + 101，目录条目 2 条）。
- 空分组删除同样发分组 DEL（日志为 `撤销节点=1（通道 0 + 分组 1）`）：上级对全量快照只做 upsert，不会因为"快照里没有"而回删。

#### 2.5.5 首次挂载到某分组：分组链 ADD（根 → 叶）→ 通道 ADD —— 2026-11-19 09:00:56 实测

新建 `solo测试3`（2160000003，父 `主控制中心` 2150000001）后挂载通道 102。该分组此前没有任何挂载，`needGroupChain = true`，先补发分组链再发通道，三包在同一任务内串行：

```
[通道推送] 新增事件下发 platformId=1 分组:34020000002160000003(含父链 2 个分组) 节点:3
[级联目录] 事件推送 ... Event:ADD 节点:34020000002150000001 父:0 SN:338946   ← 根分组
[级联目录] 事件推送 ... Event:ADD 节点:34020000002160000003 父:34020000002150000001 SN:574733   ← 叶分组
[级联目录] 事件推送 ... Event:ADD 节点:34020000001320000102 父:34020000002160000003 SN:538168   ← 通道
[SIP应答] NOTIFY → status=200 ×3
```

包 1（根分组，SN=338946）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>338946</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000002150000001</DeviceID>
<Name>主控制中心</Name>
<Manufacturer>Solo</Manufacturer>
<Model>Group</Model>
<Owner>Solo</Owner>
<CivilCode>34020000002000000001</CivilCode>
<Parental>1</Parental>
<ParentID>34020000002000000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
<Event>ADD</Event>
</Item>
</DeviceList>
</Notify>
```

包 2（叶分组，SN=574733）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>574733</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000002160000003</DeviceID>
<Name>solo测试3</Name>
<Manufacturer>Solo</Manufacturer>
<Model>Group</Model>
<Owner>Solo</Owner>
<CivilCode>34020000002150000001</CivilCode>
<Parental>1</Parental>
<ParentID>34020000002150000001</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Status>ON</Status>
<BusinessGroupID>34020000002150000001</BusinessGroupID>
<Event>ADD</Event>
</Item>
</DeviceList>
</Notify>
```

包 3（通道，SN=538168）：

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>538168</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000001320000102</DeviceID>
<Name>楼顶云台东105-通道1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>HK-PTZ</Model>
<Owner>solo</Owner>
<CivilCode>34020000002160000003</CivilCode>
<Address>192.168.60.105</Address>
<Parental>0</Parental>
<ParentID>34020000002160000003</ParentID>
<SafetyWay>0</SafetyWay>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.105</IPAddress>
<Port>8000</Port>
<Status>ON</Status>
<Longitude>0.0</Longitude>
<Latitude>0.0</Latitude>
<Info>
<PTZType>1</PTZType>
</Info>
<Event>ADD</Event>
</Item>
</DeviceList>
</Notify>
```

要点：

- 三包同在 `gb28181-biz-1` 线程，时间戳 `.280 → .281 → .282`；**SN 是 `nextSn()` 随机值（10w–99w），不递增**，判断顺序只能看时间戳与线程。
- 根分组 item 的 `parentId` 是根哨兵 `"0"`，报文层 `resolveParentCode` 兜底写本平台编码，`CivilCode` 跟随 ParentID。
- 216 节点带 `BusinessGroupID = 2150000001`；通道 `ParentID` = 新分组编码。
- 三包均回 `200 OK`，之后一包兜底全量（目录条目 5）。
- 挂到**已有通道**的分组时 `needGroupChain = false`，只发通道那一包。

#### 2.5.6 分组改名 / 换父 / 改码 —— 2026-11-19 实测

`updateGroup` 先拍旧快照（`code`/`name`/`parentId`），`updateById` 后交给 `notifyGroupUpdated` 展开事件序列，三条分支：

| 变更 | 事件序列（同一 `gb28181-biz` 任务内保序） | 实测 |
|---|---|---|
| 仅改名 | `UPDATE` 自身（`ParentID` 不变） | 1 包，SN=647944 |
| 换父 | `DEL` 自身（旧 `ParentID`）→ `ADD` 新父链（根→叶，含自身） | 3 包，SN=484496 / 700464 / 163621 |
| 改码 | `DEL` 子树（通道 → 子分组深→浅 → 自身，均用旧 `code`）→ `ADD` 新父链（根→叶，含自身新 `code`）→ `ADD` 子分组（浅→深）→ `ADD` 通道 | 5 包，SN=628988 / 102370 / 241317 / 547964 / 734438 |

三个场景都在事件之后紧跟一包**无 `Event` 的全量兜底**，且每包 `[SIP应答] NOTIFY` 均回 `200 OK`。

**改名**（`09:12:43`）：`solo测试3` → `solo测试3改名`。一包 `Event=UPDATE`，`DeviceID`=`34020000002160000003`、`ParentID`=`CivilCode`=`34020000002150000001` 均保持不变，`<Event>` 位于 Item 末元素。

**换父**（`09:26:24`，`主控制中心` → `solo测试2`，事件数 3，同线程 `.447 → .448`）：

| 顺序 | SN | Event | 节点 | ParentID |
|---|---|---|---|---|
| 1 | 484496 | DEL | `34020000002160000003` | `34020000002150000001`（旧父） |
| 2 | 700464 | ADD | `34020000002150000002`（新父本身） | `34020000002000000001`（根哨兵兜底） |
| 3 | 163621 | ADD | `34020000002160000003` | `34020000002150000002`（新父） |

**改码**（`10:28:27`，`34020000002160000004` → `34020000002160000005`，事件数 5，同线程 `.713 → .720`）：

| 顺序 | SN | Event | 节点 | ParentID / CivilCode |
|---|---|---|---|---|
| 1 | 628988 | DEL | 通道 `34020000001320000102` | `34020000002160000004`（旧 code） |
| 2 | 102370 | DEL | 分组 `34020000002160000004`（旧 code） | `34020000002150000002`（父未变） |
| 3 | 241317 | ADD | `34020000002150000002` | `34020000002000000001` |
| 4 | 547964 | ADD | 分组 `34020000002160000005`（新 code） | `34020000002150000002` |
| 5 | 734438 | ADD | 通道 `34020000001320000102` | `34020000002160000005`（新 code） |

海康侧复核：改名后上级名称同步；换父后分组出现在 `solo测试2` 下、通道随之移动、顶层无残留；改码后分组码切换为 `…2160000005`、通道 102 仍挂在它下面，无孤儿节点。

要点：

- **改码为何连子树一起重发**：通道与子分组的 `ParentID` 指向父分组的 `code`，父 `code` 一变它们全成孤儿；DEL 阶段还要把直挂本组 / 直接子级的 `ParentID` 改回旧 `code`，才能定位上级旧位置。
- **换父不重发子树**：子节点 `ParentID` 指向本组 `code`（未变），上级树里整枝跟着移动。
- **新父链缺节点自动补齐**：复用 `loadGroupChainItems`，与首次挂载同口径。
- 三条分支都会在事件之后触发一次全量兜底（`ShareChangedEvent`），漏收时靠快照自愈。
- **DEL 节点必须用副本**：`notifyGroupUpdated` 在同一个事务里先 DEL 再 ADD、两处都要查同一批通道，MyBatis 一级缓存返回的是同一批对象引用——ADD 阶段 `setParentId(新 code)` 会把 DEL 那份一起覆盖回去，而报文是在 `gb28181-biz` 线程异步拼的，真正发送时读到的已经是新码。首轮实测（`09:30:00`）就踩到：DEL 通道的 `ParentID` 发成了新码 `34020000002160000004`。修法是先 `copyChannel` 浅拷贝再改 `ParentID`，`10:28:27` 复测已正确使用旧 code。
- UI 编辑表单（`ShareDirectoryDialog.vue`）已带**父分组下拉**：仅编辑态显示；215 固定为根并禁用；216 可挂 215/216，选项按树深缩进，**排除自身及其所有后代**（避免成环）；切类型时自动纠正父级（→215 强制根，→216 自动挑第一个可用父）。改名/换父/改码三种场景均可直接在页面操作，改名与改码也能覆盖同一事件链路。

---

## 3. CSS（P-Base-V2.0）实现

### 3.1 架构：CMS 事件驱动 + 双缓存

```
CMS ──gRPC 变更事件(ADD/UPDATE/DELETE/ONLINE/OFFLINE)──►
        GbPlatform.addMsg(OrgDeltaMsgType / DevDeltaMsgType)
                │
                ▼  单线程消息队列（syncDataManager.processSyncData）
        ┌───────────────┬──────────────────┬───────────────────┐
        │ 更新全量缓存   │ 单条 NOTIFY 推送  │ 更新增量重试缓存    │
        │ (upsert/delete)│ (SumNum=1)       │ (600s 超时/3万条)  │
        └───────────────┴──────────────────┴───────────────────┘
```

- **全量缓存**（`orgs` / `devices`）：只服务于上级 `Query/Catalog` 的应答；
- **增量缓存**（`orgDelta` / `deviceDelta`）：只服务于失败重试，发送成功即出队；
- **注册前**（`registerUntilSuccess`）：`clearAllBeforeRegister()` 清空增量缓存 → `activeGetTotalData()` 拉一遍全量 → 再注册。

> 关键：CSS **从不主动推全量 NOTIFY**，全量只在上级 Query 时给出。

### 3.2 增量推送：一条变更一包

`gb28181_syncdata.go`：

```go
req := &manscdp.CatalogNotify{
    Header: manscdp.Header{CmdType: "Catalog", SN: generateSerialNo(), DeviceID: p.PlatformSn},
    SumNum: 1,                                  // 总条数恒为 1
}
item.Event = convertEventStringToGb(o.OperateType)
req.Devices.Num = "1"                           // 本包条数恒为 1
```

事件映射（`gb28181_utils.go:58`）：

| CMS 动作 | 下发 `<Event>` | 说明 |
|---|---|---|
| ADD | `ADD` | 新增节点 |
| UPDATE | `UPDATE` | 改名/换父/状态变化 |
| DELETE | `DEL` | **删除必须显式发，上级不会因为"快照里没有"而删** |
| ONLINE | `ON` | 仅设备，Item 只带 DeviceID + Event |
| OFFLINE | `OFF` | 同上 |

**Item 字段分两种粒度**：

- **组织（分组）**：极简，只带 `DeviceID` / `Name` / `ParentID` / `Event`
- **设备（通道）**：全字段（`convertDevCatalogItemToGb`），末尾附 `Event`

### 3.3 撤销：组织 / 通道删除

```
CMS 收到 DELETE
  → updateOrgTotalData / updateDevTotalData：先从全量缓存里删掉
  → sendOrgDeltaData / sendDevDeltaData：立即发一包 SumNum=1 的 NOTIFY
  → 失败则进增量缓存，每轮循环重试，直到上级回包成功
```

要点：

- **逐条发、不攒包**：参考实现就是这么做的，单个节点失败不影响其余，报文也能带上各自的 `Name`/`ParentID`；
- **删除顺序必须「叶子 → 根」**：先删父会让子节点变孤儿，部分上级会忽略对孤儿节点的后续删除；
- 删除报文里**带 `ParentID`**：上级靠它定位节点属于哪棵树。

### 3.4 报文样例（按源码逐字段构造）

#### 3.4.1 组织新增 / 变更 / 删除

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>881001</SN>
<DeviceID>34020000002000000001</DeviceID>      <!-- 本级域编码 -->
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000002150000002</DeviceID>
<Name>solo测试2</Name>
<ParentID>34020000002000000001</ParentID>
<Event>DEL</Event>                             <!-- ADD / UPDATE / DEL -->
</Item>
</DeviceList>
</Notify>
```

#### 3.4.2 通道删除（全字段 + Event）

```xml
<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>Catalog</CmdType>
<SN>881002</SN>
<DeviceID>34020000002000000001</DeviceID>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000001320000103</DeviceID>
<Name>海康IPC-直连-60.2-1</Name>
<Manufacturer>Hikvision</Manufacturer>
<Model>20220831CCCHK49329378</Model>
<Owner>solo</Owner>
<CivilCode>34020000002150000002</CivilCode>    <!-- = ParentID -->
<Address>192.168.60.2</Address>
<Parental>0</Parental>
<ParentID>34020000002150000002</ParentID>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<IPAddress>192.168.60.2</IPAddress>
<Status>ON</Status>
<Longitude>0</Longitude>
<Latitude>0</Latitude>
<Info>
<PTZType>3</PTZType>
</Info>
<Event>DEL</Event>
</Item>
</DeviceList>
</Notify>
```

#### 3.4.3 通道上下线（最小报文）

```xml
<Item>
<DeviceID>34020000001320000103</DeviceID>
<Event>OFF</Event>
</Item>
```

#### 3.4.4 全量应答（上级 Query 时）

`sumNum = 组织数 + 设备数 + 1`（那个 +1 是域节点）。先单独发一包域节点：

```xml
<Response>
<CmdType>Catalog</CmdType>
<SN>上级原 SN</SN>
<DeviceID>上级原 DeviceID</DeviceID>
<SumNum>N</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>34020000002000000001</DeviceID>
<Name>CSS</Name>
<CivilCode>37</CivilCode>
<Manufacturer>Sdses</Manufacturer>
<Model>CSS</Model>
<Owner>Sdses</Owner>
<RegisterWay>1</RegisterWay>
<Secrecy>0</Secrecy>
<Address>34020000002000000001</Address>
</Item>
</DeviceList>
</Response>
```

再按 `CatalogItemNum` 分片发组织 + 设备，每片 `SumNum` 都写总数。

### 3.5 失败重试

| 参数 | 值 | 含义 |
|---|---|---|
| `DeltaCacheTimeout` | 600s | 增量缓存项超时丢弃 |
| `MaxDeltaCache` | 30000 | 缓存上限，超出整体清空 |
| 轮询间隔 | `LessDelayTime` | 每秒检查一次待发缓存 |

---

## 4. 差异对比

| 维度 | 本项目 solo-platform | CSS P-Base-V2.0 | 影响 |
|---|---|---|---|
| 触发方式 | 业务方法里埋点（挂载/卸载/清空） | CMS gRPC 事件 | solo 无外部事件源，只能自己算 |
| 推送粒度 | 通道与分组均「1 条 1 包」；全量快照退为兜底 | **1 条 1 包** | 已对齐 ✅ |
| `<Event>` 字段 | 通道 ADD/DEL、分组 ADD/UPDATE/DEL 均已输出 | ADD/UPDATE/DEL/ON/OFF | 已对齐（差分组 ON/OFF） |
| 撤销/删除 | 已发 `Event=DEL`（删分组/挂载/卸载均 2026-11-19 实测 200） | 显式 `Event=DEL` | 已对齐 ✅ |
| 全量 NOTIFY | 有（按钮 + 每次变更兜底） | **没有**，全量只走 Query 应答 | solo 多一条兜底通道 |
| Query 应答 | 分片，`SumNum`=总数，Item 同 NOTIFY | 分片，先单发域节点包 | 基本一致 |
| 域节点 Item | 不输出（分组 ParentID 直接写平台码） | 单独一包 | 差异，需实测上级是否强制要求 |
| `CivilCode` | = `ParentID` | = `ParentSn` | 一致 ✅ |
| `PTZType` | `<Info>` 内，缺省 1 | `<Info>` 内，缺省 3 | 一致 ✅ |
| 空值字段 | 整段省略 | 整段省略 | 一致 ✅ |
| 失败重试 | 无（发送失败即丢） | 增量缓存重试 | solo 链路更脆弱 |
| 订阅依赖 | 事件推送不依赖；兜底全量依赖 | 不依赖，主动发 | 事件已解耦 ✅，兜底仍受限 |
| 分组建/改 | 删组/改名/换父/改码均发事件；建组不推（空分组不推是对的） | CMS 都会推 | 已对齐 ✅ |
| 全量条目取舍 | 只推"有通道的分组+祖先" | 缓存里有什么推什么 | 空分组处理不同 |

---

## 5. 改造建议（P1：对齐 CSS 的单条事件推送）

| 步骤 | 改动 | 状态 |
|---|---|---|
| 1 | `PlatformCatalogXml` 支持 `<Event>` + `buildEventNotify` | ✅ 已完成 |
| 2 | `PlatformCommander.sendCatalogEventNotify()`：`SumNum=1`、独立 Call-ID | ✅ 已完成 |
| 3 | `addShares` 发 `ADD`；`removeShare`/`clearShares`/`clearAll` 发 `DEL` | ✅ 已完成（2026-11-19 实测 200） |
| 4 | `PlatformGroupServiceImpl.deleteGroup`：通道 `DEL` → 分组 `DEL`（整批串行，叶子 → 根） | ✅ 已完成 |
| 4b | `updateGroup`：改名 `UPDATE`、换父/改码 `DEL` 旧 + `ADD` 新 | ✅ 已完成 |
| 5 | 挂载通道时先补发分组链 `ADD`（根 → 叶），再发通道 `ADD` | ✅ 已完成 |
| 5b | 父永远在前：挂载时整条链每次都补（去「首次挂载」推断），全量快照改按层级深度排序 | ✅ 已完成（2026-11-19） |
| 6 | 「推送目录」改逐条 `ADD`，全量升为可选；兜底全量分片 | ⬜ 阶段 6 |
| 7 | 历史残留清理：旧脏分组（`22222222...` 前缀）补发 `DEL` | ⬜ 阶段 7 |

补充建议：

- **失败重试**：可复用 `ShareChangedEvent` 链路加一个待确认队列（参考 CSS 的 `orgDelta` 思想）；
- **订阅依赖**：不建议在无订阅时静默跳过，至少落一条 WARN 并在页面提示"上级未订阅，变更未上报"。

---

## 6. 排障速查

| 现象 | 先查什么 | 判断 |
|---|---|---|
| 上级目录里看不到节点 | SIP 是否回 200；`CivilCode` 是否 = `ParentID`；有无空标签 | 回 200 但看不到 → 字段问题（CivilCode/空标签） |
| 节点在但父组织不对 | `ParentID` 是否等于目标分组编码；`BusinessGroupID`（216）是否正确 | 编码前缀不属于本域也会被判无效 |
| 推送了但没变化 | 报文体里有没有该节点的 Item；有没有 `<Event>` | 全量快照缺节点 ≠ 删除 |
| 撤销后上级还有 | 有没有发 `Event=DEL` | 挂载/卸载/清空/删分组均已发 DEL 且回 200 |
| 分组改名后上级还是旧名 | 有没有发 `Event=UPDATE`；兜底全量里 `Name` 是否已是新值 | 改名只发 UPDATE，不重推分组链 |
| 改码后旧位置没删干净 | DEL 通道那包的 `ParentID` 是不是新 code | 发成了新 code → 同一事务里 ADD 阶段覆盖了缓存中的同一对象，DEL 那批必须 `copyChannel` 副本 |
| 推送根本没发出 | 有没有活跃 Catalog 订阅（`ShareChangedEventListener` 会跳过） | 看日志 `share 变化但无活跃 Catalog 订阅` |

**报文在哪看**：`logs/solo.log`，搜关键字

```
[通道推送] 主动推送              ← 推送入口 + 目录结构摘要
[通道推送] 新增事件下发 / 撤销事件下发 ← 事件化入口，含分组编码与节点数
[通道推送] 分组改名 UPDATE / 分组变更推送 ← 改名与换父/改码入口，含事件条数
[级联订阅] Catalog NOTIFY 推送   ← 兜底全量：目标平台、SN、结构摘要
[级联订阅] Catalog NOTIFY 报文   ← 完整报文原文（超 4KB 截断）
[级联目录] 事件推送 / 事件报文    ← 单条 ADD/DEL 报文原文
[SIP应答] NOTIFY → status=...    ← 上级对该包的响应码（自 2026-11-19 起记录）
```

**结构摘要行示例**（最快核对层级的方式）：

```
结构:215:solo测试2(34020000002150000002)←根 | 132:海康IPC-直连-60.2-1(34020000001320000103)←34020000002150000002
```

格式为 `类型:名称(DeviceID)←ParentID`，`←根` 表示 ParentID 兜底为本平台编码。

---

## 附：参考实现代码位置

| 内容 | 位置 |
|---|---|
| CSS 增量推送/撤销 | `CSS/P-Base-V2.0/service/gb28181/gb28181_syncdata.go`（`sendOrgDeltaData` / `sendDevDeltaData`） |
| CSS 事件映射 | 同上仓 `gb28181_utils.go:58 convertEventStringToGb` |
| CSS Item 字段 | 同上仓 `gb28181_utils.go:135 convertDevCatalogItemToGb` |
| CSS 全量应答 | 同上仓 `gb28181_message.go:86 catalogResponse` |
| CSS 注册前清缓存 | 同上仓 `gb28181_register.go:84 clearAllBeforeRegister` |
| voglander 目录组装 | `voglander-master/.../cascade/CascadeCatalogAssembler.java` |
| voglander 删除通知 | 同上目录 `CascadeManscdpXml.catalogEventNotify`（含海康实测注释） |
| 本项目渲染 | `solo/src/main/java/com/solo/gb28181/transmit/cmd/PlatformCatalogXml.java` |
| 本项目取数 | `solo/src/main/java/com/solo/gb28181/service/impl/CatalogShareServiceImpl.java` |
| 本项目变更监听 | `solo/src/main/java/com/solo/gb28181/event/catalog/ShareChangedEventListener.java` |
