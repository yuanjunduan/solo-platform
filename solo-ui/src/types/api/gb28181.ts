/** 国标 GB28181 模块类型（当前覆盖：设备管理、设备通道 CRUD） */
import type { PageRequest } from './common'

/**
 * 资源域：资源从哪来。
 * - LOCAL 本地：本地组织下维护，带 orgId，可推送到上级
 * - SUBORDINATE 下级：SIP 级联从下级平台目录同步来的镜像，不带 orgId
 * - REMOTE 第三方资源平台：HTTP 拉取的共享资源（后续实装）
 */
export type GbDomain = 'LOCAL' | 'SUBORDINATE' | 'REMOTE'

/** 域分段控件取值：undefined 表示全部 */
export type GbDomainFilter = GbDomain | undefined

/** 组织过滤里的"未归属"哨兵（与后端约定 -1） */
export const GB_ORG_UNASSIGNED = '-1'

/** 目录同步进度快照（内嵌在来源卡片里） */
export interface GbCatalogSync {
  /** IDLE 从未同步 / RUNNING 进行中 / SUCCESS 收齐 / FAILED 超时或异常 */
  status: 'IDLE' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  received: number
  total: number
  percent: number
  /** yyyy-MM-dd HH:mm:ss */
  startedAt?: string | null
  lastUpdateAt?: string | null
  finishedAt?: string | null
  errorMsg?: string | null
  duplicateCount: number
}

/** 本地组织（资源归属树节点；与推送分组 gb_platform_group 无关） */
export interface GbOrg {
  /** 雪花 id，字符串传递避免精度丢失 */
  id: string
  /** 父组织 id；0 表示挂在根之外（不推荐，正常都挂在根下） */
  parentId: string
  name: string
  /** 20 位国标编码，可空 */
  code?: string | null
  /** 物化路径 /根id/.../自身id/ */
  path: string
  level: number
  sort: number
  remark?: string | null
  createTime?: string
  updateTime?: string
}

/** 组织树节点（带直接设备/通道数） */
export interface GbOrgTreeNode extends GbOrg {
  directDeviceCount?: number
  directChannelCount?: number
  children?: GbOrgTreeNode[]
}

/** 组织新增/编辑请求 */
export interface GbOrgSaveRequest {
  parentId?: string | null
  name: string
  code?: string | null
  sort?: number
  remark?: string | null
}

/** 批量调整资源组织归属请求 */
export interface GbOrgAssignRequest {
  /** 目标组织 id；'-1' 或空 = 清空（设备变未归属，通道回落跟随设备） */
  orgId?: string | null
  deviceIds?: string[]
  channelIds?: string[]
}

/** 调整组织归属结果 */
export interface GbOrgAssignResult {
  /** 目标组织名称；清空时为 null */
  orgName?: string | null
  devices: number
  channels: number
}


/** 设备通道目录同步状态 */
export interface GbSyncStatus {
  deviceId: string
  /** IDLE / RUNNING / SUCCESS / FAILED */
  status: 'IDLE' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  /** 已收到通道 / 目录项累计 */
  received: number
  /** 设备通告的 SumNum，0=设备空目录 */
  total: number
  /** 0-100 */
  percent: number
  /** 毫秒时间戳 */
  startedAtMs: number
  lastUpdateAtMs: number
  /** yyyy-MM-dd HH:mm:ss，status != RUNNING 时存在 */
  finishedAt?: string
  /** partial / 失败原因 */
  errorMsg?: string
  duplicateCount: number
}

/** /sync 接口返回（同步触发响应） */
export interface GbSyncTriggerResponse {
  /** TRIGGERED：本次抢到锁；ALREADY_RUNNING：锁失败，已有同步在进行 */
  result: 'TRIGGERED' | 'ALREADY_RUNNING'
  deviceId: string
  /** 当前 status JSON 字符串（结构同 GET /sync-status），前端可解析 */
  status: string
}

/** 设备列表分页查询参数 */
export interface GbDevicePageRequest extends PageRequest {
  deviceId?: string
  name?: string
  /** 设备状态：1 在线，0 离线，2 未注册 */
  status?: number
  /** 数据来源：1 系统新增，2 表格导入，3 设备注册 */
  source?: number
  /** 资源域；不传=全部 */
  domain?: GbDomain
  /** 所属本地组织 id（传 -1 = 未归属）；一旦传了就只查 LOCAL 域 */
  orgId?: string
  /** 按组织过滤时是否含子孙组织，默认 true */
  includeSubtree?: boolean
  /** 端点精确过滤（左树选中平台节点时用）；传了就只查该端点自身 */
  platformId?: string
}

/** 国标设备 */
export interface GbDevice {
  id: string
  /** 国标编码（20 位） */
  deviceId: string
  /** 设备上报原始名称（DeviceInfo 响应） */
  reportName?: string
  /** 用户自定义显示名称（展示优先级高于 reportName） */
  name?: string
  manufacturer?: string
  model?: string
  firmware?: string
  sipDomain?: string
  ip?: string
  /** 收流IP覆盖；留空使用注册源IP，双栈内网互通时填设备内网IP */
  recvIp?: string
  port?: number
  localIp?: string
  hostAddress?: string
  /** 传输协议：UDP / TCP */
  transport?: string
  expires?: number
  /** 字符编码：GB2312 / UTF-8 */
  charset?: string
  /** 1 在线，0 离线，2 未注册 */
  status: number
  registerTime?: string
  keepaliveTime?: string
  lastCatalogSyncTime?: string
  /** 目录订阅周期（秒），0=不订阅 */
  subscribeCycleForCatalog?: number
  /** 数据来源：1 系统新增，2 表格导入，3 设备注册 */
  source?: number
  /** 资源域（通道随设备走，不由通道自身存储） */
  domain?: GbDomain
  /** 所属本地组织 id；仅 LOCAL 域有值 */
  orgId?: string | null
  createTime: string
  updateTime: string
}

/** 设备下挂通道（新增设备向导第二步编辑用，仅需两个字段） */
export interface GbDeviceChannelDraft {
  /** 通道国标编码（20 位） */
  channelId: string
  /** 通道名称 */
  name: string
}

/** 新增设备请求体：设备信息 + 一次性带多条通道 */
export interface GbDeviceSaveRequest {
  device: Partial<GbDevice>
  channels: GbDeviceChannelDraft[]
}

/** 设备存活探测结果（异步：仅汇报已发起/跳过，结果需刷新列表查看） */
export interface GbDeviceDetectResult {
  /** 已发起探测台数（状态异步更新） */
  detecting: number
  /** 跳过台数（无网络地址，无法探测） */
  skipped: number
  /** 每台设备处理明细 */
  items: Array<{ id: string; deviceId: string; result: 'detecting' | 'skipped' }>
}

/* ----------------------- 设备通道 ----------------------- */

/** 通道列表分页查询参数 */
export interface GbDeviceChannelPageRequest extends PageRequest {
  /** 所属设备/来源端点国标编码（精确匹配 device_id） */
  deviceId?: string
  /** 历史参数（等价于 deviceId，后端按 platform_id 过滤，仅兼容旧调用） */
  platformId?: string
  /** 通道国标编码 */
  channelId?: string
  /** 通道名称 */
  name?: string
  /** ON 在线 / OFF 离线 / UNREGISTERED 未注册 */
  status?: string
  /** 父分组国标编码（后端 LIKE 'value%'） */
  parentId?: string
  /** 资源类型：0 通道 / 1 分组；不传=全部 */
  parental?: number
  /** 数据来源：1 系统新增，2 表格导入，3 设备注册 */
  source?: number
  /** 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机（仅对通道生效） */
  ptzType?: number
  /** 资源域（继承所属设备）；不传=全部 */
  domain?: GbDomain
  /** 所属组织 id（自身 orgId 优先，为空继承设备）；-1=未归属；不传=不过滤 */
  orgId?: string
  /** 按组织过滤时是否含子孙组织，默认 true */
  includeSubtree?: boolean
  /** 物化路径前缀（左树选中镜像分组时用），命中该分组及其所有子分组 */
  pathPrefix?: string
}

/** 国标资源目录项（通道 + 分组合一） */
export interface GbDeviceChannel {
  id: string
  /** 所属设备国标编码（关联 gb_device.device_id）；通道与分组均必填 */
  deviceId?: string
  /** 来源接入平台国标编码（发起 Catalog 的注册方；多下级平台时据此区分通道归属，现阶段与 deviceId 同值） */
  platformId?: string
  /** catalog item 20 位国标码：分组 215业务分组/216虚拟组织；通道 131摄像机/132IPC */
  channelId: string
  /** 设备上报原始名称（仅通道） */
  reportName?: string
  /** 用户自定义显示名称（展示优先级高于 reportName） */
  name?: string
  manufacturer?: string
  model?: string
  /** 设备归属 */
  owner?: string
  /** 行政区域编码 */
  civilCode?: string
  /** 所属警区 */
  block?: string
  /** 安装地址 */
  address?: string
  /** 0 通道（叶子）/ 1 分组（容器） */
  parental?: number
  /** ON 在线 / OFF 离线 / UNREGISTERED 未注册 */
  status?: string
  longitude?: number
  latitude?: number
  /** GCJ-02 / BD-09 冗余副本（历史数据可能为 null） */
  longitudeGcj02?: number | null
  latitudeGcj02?: number | null
  longitudeBd09?: number | null
  latitudeBd09?: number | null
  /** 入参坐标系（仅写时用，DB 不存）：WGS84 / GCJ02 / BD09 */
  coordSystem?: string
  /** 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机 */
  ptzType?: number
  /** 父 catalog item 国标编码（指向同表 channel_id）；"0"=根 */
  parentId?: string
  /** 数据来源：1 系统新增，2 表格导入，3 设备注册 */
  source?: number
  /** 组织归属覆盖值；null=继承所属设备的 orgId */
  orgId?: string | null
  /** 有效组织 id（自身 orgId 优先，否则继承设备；null=未归属） */
  effectiveOrgId?: string | null
  /** 资源域（通道无独立域列，一律继承所属设备；REMOTE=第三方资源平台，仅目录） */
  domain?: GbDomain | null
  createTime?: string
  updateTime?: string
}

/** 国标目录树节点：分组（parental=1）通过 children 挂下级分组/通道，通道为叶子 */
export interface GbDeviceChannelTreeNode extends GbDeviceChannel {
  children?: GbDeviceChannelTreeNode[]
  /** 该节点（含所有子分组）下的通道总数；通道节点为 null */
  subtreeChannelCount?: number | null
  /** 是否还有直接下级（控制展开箭头）；通道节点为 false */
  hasChildren?: boolean
}

/** 目录树懒加载一层（GET /api/gb28181/device-channel/tree/children） */
export interface GbChannelTreeLevel {
  /** 当前节点国标码；空串表示设备根 */
  orgCode: string
  /** 当前节点组织路径；设备根为 /设备码 */
  treePath: string
  /** 当前节点（含所有子分组）下的通道总数 */
  subtreeChannelCount: number
  /** 直接下级分组数 */
  childGroupCount: number
  /** 直接下级通道数 */
  childChannelCount: number
  /** 直接下级节点（已带各自子树通道数与可展开标记，不含更下层） */
  children: GbDeviceChannelTreeNode[]
}

/** 选中组织下的通道分页请求（POST /api/gb28181/device-channel/subtree/page） */
export interface GbChannelSubtreePageRequest extends PageRequest {
  /** 所属设备国标编码 */
  deviceId: string
  /** 选中的组织国标码；不传=设备根 */
  orgCode?: string
  /** 是否包含子组织下的通道：true=整棵子树 */
  includeSubtree?: boolean
  /** 通道名称/国标码关键字 */
  keyword?: string
  /** ON 在线 / OFF 离线 / UNREGISTERED 未注册 */
  status?: string
}

/** 国标设备状态统计行（GET /api/gb28181/device/status-count） */
export interface GbDeviceStatusCount {
  /** 1 在线 / 0 离线 / 2 未注册 */
  status: number
  count: number
}

/** 国标通道状态统计行（GET /api/gb28181/device-channel/status-count） */
export interface GbChannelStatusCount {
  /** 0 通道 / 1 分组 */
  parental: number
  /** 通道：ON / OFF / UNREGISTERED；分组固定 ON */
  status: string
  count: number
}

/**
 * 目录来源（GET /api/gb28181/device-channel/sources）。
 * 来源 = 推过目录的端点编码（gb_device_channel.device_id），由目录数据自身聚合，
 * 与 gb_platform（接入禁用名单）无关。
 */
export interface GbCatalogSource {
  /** 来源端点国标编码 */
  deviceId: string
  /** 展示名称（自定义名 > 设备上报名；为空时前端回退编码） */
  name?: string
  /** PLATFORM=下级平台（编码第 11-13 位 200）/ DEVICE=设备或网关 */
  kind: 'PLATFORM' | 'DEVICE'
  /** 资源域；端点不在 gb_device 里时按 SUBORDINATE */
  domain: GbDomain
  /** 端点在线状态：1 在线 / 0 离线 / 2 未注册；无 gb_device 行时为空 */
  onlineStatus?: number | null
  /** 最近一次目录同步时间 */
  lastCatalogSyncTime?: string | null
  /** 目录条目总数（分组 + 通道） */
  itemCount: number
  /** 分组数 */
  groupCount: number
  /** 通道数 */
  channelCount: number
  /** 在线通道数 */
  onlineChannelCount: number
  /** 离线通道数 */
  offlineChannelCount: number
  /** 目录同步进度快照；从未同步时 status=IDLE */
  sync?: GbCatalogSync
}

/** 设备报警（报警中心，POST /api/gb28181/alarm/page 行项） */
export interface GbDeviceAlarm {
  id: string
  /** 报警所属设备国标 ID */
  deviceId: string
  /** 报警通道国标 ID */
  channelId: string
  /** 通道名称（落库时冗余） */
  channelName: string
  /** 报警级别 1~4（1 最严重） */
  alarmPriority: number
  /** 报警方式原始码（如 "2"，可为组合码 "12"） */
  alarmMethod: string
  /** 报警方式描述（如"设备报警"） */
  alarmMethodDesc: string
  /** 报警类型子码（设备报警时的具体类型，如移动侦测） */
  alarmType: string
  /** 设备上报的报警发生时间 */
  alarmTime: string
  /** 报警内容描述 */
  alarmDescription: string
  longitude?: number | null
  latitude?: number | null
  /** 处置状态：0 未处理、1 已确认、2 已处置、3 误报 */
  handleStatus: number
  handleRemark: string
  handleUser: string
  handleTime: string
  /** 平台收到报警时间 */
  receiveTime: string
  createTime: string
}

/** 报警分页查询参数（POST /api/gb28181/alarm/page） */
export interface GbDeviceAlarmPageRequest extends PageRequest {
  deviceId?: string
  channelId?: string
  alarmPriority?: number
  handleStatus?: number
  startTime?: string
  endTime?: string
}

/** 报警处置请求（POST /api/gb28181/alarm/handle） */
export interface GbDeviceAlarmHandleRequest {
  id: string
  /** 1 已确认、2 已处置、3 误报 */
  handleStatus: number
  handleRemark?: string
}

/** 设备移动位置点（GPS 轨迹，POST /api/gb28181/position/page 行项） */
export interface GbMobilePosition {
  id: string
  /** 设备国标 ID */
  deviceId: string
  /** 设备名称（落库时冗余） */
  deviceName: string
  /** 设备上报的定位时间 */
  positionTime: string
  /** 经度（WGS84） */
  longitude?: number | null
  /** 纬度（WGS84） */
  latitude?: number | null
  /** 海拔（米） */
  altitude?: number | null
  /** 速度（设备原值，km/h 或 m/s 视厂商而定） */
  speed?: number | null
  /** 方向角 0~359，正北为 0 顺时针 */
  direction?: number | null
  /** 平台收到报文时间 */
  receiveTime: string
  createTime: string
}

/** 移动位置分页 / 轨迹查询参数（/position/page、/position/track） */
export interface GbMobilePositionPageRequest extends PageRequest {
  deviceId?: string
  startTime?: string
  endTime?: string
}

/** 云台预置位（GET /api/gb28181/ptz/preset/list/{channelDbId}） */
export interface GbPtzPreset {
  id: string
  /** 通道数据库主键（gb_device_channel.id） */
  channelDbId: string
  /** 预置位编号（设备侧编号，从 1 开始） */
  presetId: number
  /** 预置位名称（如"大门入口"） */
  name: string
  createTime: string
  updateTime: string
}

/** 云台巡航路线（GET /api/gb28181/ptz/cruise/list/{channelDbId}） */
export interface GbPtzCruise {
  id: string
  /** 通道数据库主键（gb_device_channel.id） */
  channelDbId: string
  /** 路线编号（设备侧编号，通道内从 1 开始） */
  cruiseId: number
  /** 路线名称 */
  name: string
  /** 巡航速度 1~255 */
  speed: number
  /** 默认停留秒数 */
  staySeconds: number
  createTime: string
  updateTime: string
}

/** 巡航点（路线内的一个预置位停靠） */
export interface GbPtzCruisePoint {
  id: string
  /** 关联 gb_ptz_cruise.id */
  cruiseId: string
  /** 预置位编号（对应 gb_ptz_preset.preset_id） */
  presetId: number
  /** 预置位名称冗余 */
  presetName: string
  /** 单点停留秒数，null 用路线默认 */
  staySeconds?: number | null
  /** 巡航顺序，从 0 开始 */
  sortOrder: number
  createTime: string
}

/** 巡航路线详情（GET /api/gb28181/ptz/cruise/{id}） */
export interface GbPtzCruiseDetail {
  cruise: GbPtzCruise
  points: GbPtzCruisePoint[]
}

/** 新建巡航路线请求（POST /api/gb28181/ptz/cruise/save） */
export interface GbPtzCruiseSaveRequest {
  channelDbId: string
  name: string
  speed: number
  staySeconds: number
  points: Array<{ presetId: number; staySeconds?: number | null }>
}

/* ----------------------- 点播 / 云台 ----------------------- */

/** 开始点播请求 */
export interface GbPlayStartRequest {
  deviceId: string
  channelId: string
  /** 收流模式，默认 UDP */
  transport?: 'UDP' | 'TCP-passive' | 'TCP-active'
  /** 码流 token：空/不传主码流，非空子码流 */
  streamNumber?: string
}

/** 停止点播请求 */
export interface GbPlayStopRequest {
  streamId: string
}

/** 点播结果（前端按播放器能力挑一个 URL） */
export interface GbPlayResult {
  streamId: string
  app: string
  mediaServerId: string
  ssrc: string
  rtpPort: number
  transport: string
  /** 码流 token：空主码流，非空子码流 */
  streamNumber?: string
  flvUrl?: string
  wsFlvUrl?: string
  hlsUrl?: string
  rtcUrl?: string
  /**
   * 实际取流的 res_channel.id（仅入站点播返回）。
   * 同源组内发生选路切换时，它会与请求行不同——提示用户"已换源"。
   */
  sourceChannelRowId?: string
}

/* ----------------------- 语音广播 ----------------------- */

/** 开始语音广播请求 */
export interface GbBroadcastStartRequest {
  deviceId: string
  channelId: string
  /** broadcast=单向喊话（默认），talk=双向对讲 */
  mode?: 'broadcast' | 'talk'
}

/** 停止语音广播请求 */
export interface GbBroadcastStopRequest {
  streamId: string
}

/** WebRTC SDP 交换请求（浏览器 offer → ZLM answer，平台代理转发） */
export interface GbBroadcastWebRtcRequest {
  streamId: string
  sdp: string
}

/** 开始广播结果：rtcPushUrl 仅用于展示/排查，推流 SDP 走后端代理接口交换 */
export interface GbBroadcastResult {
  streamId: string
  app: string
  mediaServerId: string
  rtcPushUrl: string
}

/**
 * 云台方向/变倍指令。
 * 方向：up/down/left/right/upleft/upright/downleft/downright；
 * 变倍：zoomin/zoomout；停止：stop。
 */
export type GbPtzCommand =
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'upleft'
  | 'upright'
  | 'downleft'
  | 'downright'
  | 'zoomin'
  | 'zoomout'
  | 'stop'

/** 镜头聚焦方向 */
export type GbPtzFocusDirection = 'near' | 'far' | 'stop'

/** 镜头光圈方向 */
export type GbPtzIrisDirection = 'open' | 'close' | 'stop'

/** 拉框缩放：坐标均为视频画面像素值（不是窗口像素） */
export interface GbDragZoomRequest {
  action: 'in' | 'out'
  /** 播放窗口长度像素 */
  length: number
  /** 播放窗口宽度像素 */
  width: number
  midPointX: number
  midPointY: number
  /** 拉框长度像素 */
  lengthX: number
  /** 拉框宽度像素 */
  lengthY: number
}

/** 看守位设置：关闭时只传 enabled=false */
export interface GbHomePositionRequest {
  enabled: boolean
  /** 自动归位时间（秒），开启时用 */
  resetTime?: number
  /** 预置位编号，开启时用 */
  presetIndex?: number
}

/* ----------------------- 播放器运行信息（Jessibuca 事件上抛） ----------------------- */

/** 视频流信息（Jessibuca videoInfo 事件） */
export interface GbVideoInfo {
  width: number
  height: number
  /** 编码名，如 H.264 / H.265 */
  encType?: string
  /** 编码代码：10=H.264，12=H.265 */
  encTypeCode?: number
}

/** 音频流信息（Jessibuca audioInfo 事件） */
export interface GbAudioInfo {
  numOfChannels: number
  sampleRate: number
  encType?: string
  /** 10=AAC，7=G.711A，8=G.711U */
  encTypeCode?: number
}

/** 播放统计（Jessibuca stats 事件） */
export interface GbPlayerStats {
  /** 帧率 */
  fps: number
  /** 视频字节/秒 */
  vbps: number
  /** 音频字节/秒 */
  abps: number
  /** 缓冲毫秒 */
  buf: number
}

/* ----------------------- 录像回放 ----------------------- */

/** 录像段（RecordInfo 查询结果） */
export interface GbRecordItem {
  channelId: string
  name?: string
  filePath?: string
  address?: string
  /** ISO-8601 起始，如 2026-06-30T08:00:00 */
  startTime?: string
  endTime?: string
  fileSize?: number
  type?: string
  recorderId?: string
  secrecy?: number
}

/** 录像查询请求 */
export interface GbRecordQueryRequest {
  deviceId: string
  channelId: string
  /** yyyy-MM-dd HH:mm:ss */
  startTime: string
  endTime: string
}

/** 回放控制动作 */
export type GbPlaybackAction = 'pause' | 'resume' | 'seek' | 'speed'

/** 回放控制请求 */
export interface GbPlaybackControlRequest {
  streamId: string
  action: GbPlaybackAction
  /** seek 秒数 / speed 倍数；pause/resume 省略 */
  param?: string
}

/** 回放结果（在点播结果基础上加时段元数据） */
export interface GbPlaybackResult extends GbPlayResult {
  startEpochSec?: number
  endEpochSec?: number
  durationSec?: number
}

/** 录像下载请求（与回放同构，加 speed 字段） */
export interface GbDownloadStartRequest {
  deviceId: string
  channelId: string
  /** yyyy-MM-dd HH:mm:ss */
  startTime: string
  endTime: string
  /** 收流模式，默认 TCP-passive */
  transport?: 'UDP' | 'TCP-passive' | 'TCP-active'
  /** 下载倍速：1 / 2 / 4 / 8，默认 1 */
  speed?: 1 | 2 | 4 | 8
  /** 码流 token：空/不传主码流 */
  streamNumber?: string
}

/**
 * 录像下载任务快照
 * 状态机：INVITING → RECORDING → READY / FAILED / TIMEOUT / NOT_FOUND
 */
export interface GbDownloadResult {
  streamId?: string
  status?: string
  startEpochSec?: number
  endEpochSec?: number
  durationSec?: number
  /** READY 状态有值，MinIO 1h 预签名 URL */
  downloadUrl?: string
  errorMsg?: string
}

/* ==================== 级联平台 ==================== */

/** 本平台国标身份（GET /api/gb28181/parent-platform/local-info） */
export interface GbLocalPlatformInfo {
  /** 本平台 SIP ID（20 位国标编码） */
  id?: string
  /** 本平台 SIP 域 */
  domain?: string
  /** 本平台 SIP 监听 IP */
  ip?: string
  /** 本平台 SIP 监听端口 */
  port?: number
  /** 鉴权密码（上级平台白名单配置用） */
  password?: string
  /** 设备注册有效期（秒） */
  registerExpires?: number
}

/**
 * 本平台国标配置（GET/PUT /api/gb28181/local-config）。
 * 库配置优先于 application yaml；身份项（sipId/sipDomain/sipIp/sipPort）保存后需重启生效。
 */
export interface GbLocalConfig {
  /** 本平台 SIP 国标 ID（20 位） */
  sipId?: string
  /** 本平台 SIP 域（ID 前 10 位） */
  sipDomain?: string
  /** SIP 监听 IP；空串=自动扫网卡 */
  sipIp?: string
  /** SIP 公网 IP（NAT 场景 Via/Contact 使用） */
  sipPublicIp?: string
  /** SIP 监听端口 */
  sipPort?: number
  /** 鉴权密码，恒为掩码 ****** */
  password?: string
  /** 是否已配置密码 */
  passwordConfigured?: boolean
  registerExpires?: number
  keepaliveTimeoutMultiplier?: number
  /** 手动存活探测超时（毫秒） */
  detectTimeoutMs?: number
  useSourceIpAsRemoteAddress?: boolean
  catalogPageSize?: number
  catalogAckTimeoutMs?: number
  catalogPageIntervalMs?: number
  sendRtpPortStart?: number
  sendRtpPortEnd?: number
  /** 身份项有改动待重启生效 */
  restartRequired?: boolean
  /** 当前运行中的身份（重启前的实际生效值） */
  runningSipId?: string
  runningSipDomain?: string
  runningSipIp?: string
  runningSipPort?: number
  /** db=库配置；yaml=未配置回落配置文件 */
  source?: 'db' | 'yaml'
}

/** 保存本平台国标配置请求体 */
export interface GbLocalConfigSaveRequest {
  sipId?: string
  sipDomain?: string
  /** 空串=自动扫网卡，undefined=沿用 yaml */
  sipIp?: string
  sipPublicIp?: string
  sipPort?: number
  /** 留空=不修改密码 */
  password?: string
  /** true=清空密码（开放模式） */
  resetPassword?: boolean
  registerExpires?: number
  keepaliveTimeoutMultiplier?: number
  detectTimeoutMs?: number
  useSourceIpAsRemoteAddress?: boolean
  catalogPageSize?: number
  catalogAckTimeoutMs?: number
  catalogPageIntervalMs?: number
  sendRtpPortStart?: number
  sendRtpPortEnd?: number
}

/** 上级平台配置 */
export interface GbParentPlatform {
  id: string
  /** 上级平台名称 */
  name: string
  /** 上级平台国标编码 */
  serverGbId: string
  /** 上级平台国标域 */
  serverGbDomain: string
  /** 上级平台 SIP IP */
  serverIp: string
  /** 上级平台 SIP 端口 */
  serverPort: number
  /** SIP 认证用户名 */
  username?: string
  /** SIP 认证密码 */
  password?: string
  /** 注册有效期（秒） */
  expires: number
  /** 心跳周期（秒） */
  keepTimeout: number
  /** 传输协议 UDP/TCP */
  transport: string
  /** 字符集 */
  characterSet: string
  /** 启用状态：0 禁用 1 启用 */
  enabled: number
  /** 在线状态：0 离线 1 在线 */
  status?: number
  /** Catalog 响应单包 Item 数：1/2/4/8；undefined 走全局默认 */
  catalogGroup?: number
  /** 本地设备国标编号（后端额外返回） */
  deviceGbId?: string
  createTime?: string
  updateTime?: string
}

/** 上级平台分页查询参数 */
export interface GbParentPlatformPageRequest extends PageRequest {
  name?: string
  serverGbId?: string
  enabled?: number
}

/** 上级平台推送目录分组 */
export interface GbPlatformGroup {
  id: string
  name: string
  /** 20 位国标业务码（推送 XML DeviceID 用） */
  code: string
  /** 所属上级平台主键（后端列 parent_platform_id） */
  parentPlatformId: string
  /** 父分组 id，根节点 = 0 */
  parentId: string
  children?: GbPlatformGroup[]
  createTime?: string
  updateTime?: string
}

/** 推送分组新建 / 编辑请求 */
export interface GbPlatformGroupSaveRequest {
  name: string
  code: string
  parentId: string
}

/** 推送目录：已挂载/未挂载通道分页查询参数 */
export interface GbShareChannelPageRequest extends PageRequest {
  /** 名称 / 通道国标编码 模糊匹配 */
  keyword?: string
}

/** 批量挂载请求体 */
export interface GbAddShareRequest {
  resourceIds: string[]
}

/** 已挂载通道行（后端在通道基础上额外返回推送国标码） */
export interface GbSharedChannel extends GbDeviceChannel {
  /** 推送国标码，撞码重编时与 channelId 不同 */
  exposedCode?: string
}

/** 国标接入禁用名单（黑名单） */
export interface GbPlatform {
  id: string
  /** 被禁用的国标 ID（20 位） */
  platformId: string
  /** 备注名（可空） */
  name?: string
  /** 0 拦截中（默认）1 临时放行 */
  enabled: number
  /** 关联设备状态：0 离线 1 在线 2 未注册 */
  status?: number
  /** 关联设备最近心跳时间 */
  keepaliveTime?: string
  createTime: string
  updateTime: string
}

/** 接入禁用名单分页查询参数 */
export interface GbPlatformPageRequest extends PageRequest {
  platformId?: string
  name?: string
  enabled?: number
}

/* ----------------------- 云端录像中心 ----------------------- */

/** 云端录像文件视图（status 0上传中 1成功 2失败） */
export interface GbRecordFile {
  id: string
  deviceId?: string
  deviceName?: string
  channelId?: string
  channelName?: string
  /** ZLM 流 ID（排查录制链路用） */
  streamId?: string | null
  /** 最近一次上传失败原因，仅失败时有值 */
  failReason?: string | null
  startTime?: string
  endTime?: string
  /** 时长（秒），上传中的文件可能为空 */
  duration?: number | null
  /** 文件大小（字节） */
  fileSize?: number | null
  status: number
  createTime?: string
}

/** 录像文件分页查询参数（注意后端分页字段是 size，不是 pageSize） */
export interface GbRecordFilePageRequest extends PageRequest {
  deviceId?: string
  channelId?: string
  /** yyyy-MM-dd HH:mm:ss */
  start?: string
  /** yyyy-MM-dd HH:mm:ss */
  end?: string
}

/* ----------------------- 观看记录 ----------------------- */

/** 观看记录（actorType 1平台用户 2开放API应用；status 0观看中 1正常结束 2兜底关闭） */
export interface GbViewRecord {
  id: string
  sessionId?: string
  actorType: number
  actorId?: string
  actorName?: string
  deviceId?: string
  deviceName?: string
  channelId?: string
  channelName?: string
  streamId?: string
  app?: string
  streamSchema?: string
  clientIp?: string
  mediaServerId?: string
  startTime?: string
  endTime?: string
  /** 时长（秒），观看中为空 */
  durationSec?: number | null
  totalBytes?: number | null
  status: number
  closeReason?: string
  createTime?: string
}

/* ----------------------- 云端录像计划 ----------------------- */

/** 录像计划周时段（dayOfWeek 1周一 ~ 7周日，时间 HH:mm，支持跨午夜） */
export interface GbRecordPlanPeriod {
  id?: string
  planId?: string
  dayOfWeek: number
  startTime: string
  endTime: string
}

/** 录像计划分页查询请求 */
export interface GbRecordPlanPageRequest {
  /** 当前页码（从 1 开始） */
  page?: number
  /** 每页条数 */
  pageSize?: number
  /** 所属设备国标 ID */
  deviceId?: string
  /** 通道国标 ID */
  channelId?: string
  /** 启用状态：1 启用、0 停用、不传全部 */
  enabled?: number
}

/** 云端录像计划（周计划） */
export interface GbRecordPlan {
  id?: string
  name?: string
  deviceId: string
  channelId: string
  /** 设备名称（列表冗余返回，便于展示） */
  deviceName?: string
  /** 通道名称（列表冗余返回，便于展示） */
  channelName?: string
  /** 0停 1启 */
  enabled?: number
  /** UDP / TCP-active / TCP-passive，空随设备 */
  transport?: string
  /** 码流 token，空=主码流 */
  streamNumber?: string
  /** 保留天数，0=跟随全局 */
  retentionDays?: number
  createTime?: string
  updateTime?: string
  /** 周时段，仅详情/保存时携带 */
  periods?: GbRecordPlanPeriod[]
}

/* ----------------------- 目录左树（懒加载） ----------------------- */

/** 左树查询的资源域；ALL 表示根层把本地组织根与各平台并列 */
export type GbCatalogDomain = GbDomain | 'ALL'

/** 左树节点类型：ORG 本地组织 / PLATFORM 下级平台（含接入设备端点）/ GROUP 镜像分组 */
export type GbCatalogNavNodeType = 'ORG' | 'PLATFORM' | 'GROUP'

/**
 * 左树节点（GET /api/gb28181/catalog/nav 的 children 项）。
 * 三种来源共用同一结构，前端只用 key 递归展开，用 domain/platformGbId/orgId/treePath 拼右侧查询条件。
 */
export interface GbCatalogNavNode {
  /** 节点唯一标识：org:{组织id} / plat:{端点码} / grp:{端点码}:{分组码} */
  key: string
  /** 父节点 key；根层为空 */
  parentKey?: string | null
  nodeType: GbCatalogNavNodeType
  /** 展示名称 */
  label: string
  /** 节点所属资源域：ORG=LOCAL，PLATFORM/GROUP=端点真实域 */
  domain: GbDomain
  /** 本地组织主键（nodeType=ORG） */
  orgId?: string | null
  /** 目录来源端点国标码（nodeType=PLATFORM/GROUP） */
  platformGbId?: string | null
  /** 镜像分组国标码（nodeType=GROUP） */
  groupCode?: string | null
  /** 分组物化路径（nodeType=GROUP），右侧列表按它做子树前缀过滤 */
  treePath?: string | null
  /** 端点编码类型（仅 PLATFORM） */
  kind?: 'PLATFORM' | 'DEVICE' | null
  /** 端点在线状态 0离线 1在线 2未注册（仅 PLATFORM） */
  onlineStatus?: number | null
  /** 是否还有下一层（决定展开箭头） */
  hasChildren: boolean
  /** 直接归属该组织的本地设备数，不含子组织（仅 ORG） */
  directDeviceCount?: number | null
  /** 子树通道数（PLATFORM=端点总通道数，GROUP=该分组含子分组的通道数） */
  subtreeChannelCount?: number | null
  /** 直接下级分组数（仅 PLATFORM） */
  childGroupCount?: number | null
}

/** 左树一层数据（GET /api/gb28181/catalog/nav） */
export interface GbCatalogNavLevel {
  /** 本次查询生效的资源域 */
  domain: GbCatalogDomain
  /** 本次展开的节点 key；根层为 root */
  parentKey: string
  /** 下级节点，已按后端排序规则排好 */
  children: GbCatalogNavNode[]
  /** 本层是否因 limit 被截断（前端应提示用关键字收窄） */
  hasMore: boolean
  /** 生效的单层上限 */
  limit: number
}

/** 左树懒加载请求参数 */
export interface GbCatalogNavRequest {
  domain?: GbCatalogDomain
  /** 不传=根层 */
  parentKey?: string
  /** 名称/编码关键字，只过滤当前层 */
  keyword?: string
  /** 单层上限，默认 200，后端最大 500 */
  limit?: number
}
