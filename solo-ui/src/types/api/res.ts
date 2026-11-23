/**
 * 统一资源模型（res_org / res_group / res_device / res_channel）的前端类型。
 *
 * id 统一用 string：后端用 ToStringSerializer 输出，避免 JS 大整数精度丢失。
 * 来源（sourceType）是行的属性：NATIVE = 本地自建，INBOUND = 下级平台同步而来；
 * INBOUND 行在前端一律不给编辑/删除入口（后端还有四层只读守卫）。
 */

/** 资源状态：ACTIVE 在线 / STALE 疑似消失 / OFFLINE 离线 / REMOVED 已确认消失（软删） */
export type ResourceStatus = 'ACTIVE' | 'STALE' | 'OFFLINE' | 'REMOVED'

/** 来源：本地自建 / 入站同步 */
export type SourceType = 'NATIVE' | 'INBOUND'

export interface ResOrg {
  id: string
  tenantId?: number
  code: string
  parentCode?: string
  parentId?: string | null
  path?: string
  level?: number
  name: string
  localName?: string
  sourceType?: SourceType
  sourcePlatformId?: string
  originChain?: string
  hopCount?: number
  status?: ResourceStatus
  removedAt?: string
  orgMissing?: number
  unclassified?: number
  sortNo?: number
  createTime?: string
  updateTime?: string
}

/** 组织树节点（组织管理页左树） */
export interface ResOrgTreeNode {
  id: string
  parentId?: string | null
  code: string
  name: string
  localName?: string
  path?: string
  level: number
  sortNo?: number
  sourceType?: SourceType
  status?: ResourceStatus
  /** INBOUND 节点为 false：前端据此隐藏编辑入口 */
  writable?: boolean
  /** 直接归属本组织的设备数（不含子组织） */
  deviceCount?: number
  /** 直接归属本组织的通道数（不含子组织） */
  channelCount?: number
  children?: ResOrgTreeNode[]
}

export interface ResDevice {
  id: string
  tenantId?: number
  code: string
  name: string
  localName?: string
  orgId?: string
  orgPath?: string
  deviceIp?: string
  vendor?: string
  model?: string
  channelCount?: number
  fingerprint?: string
  sourceType?: SourceType
  sourcePlatformId?: string
  originPlatformId?: string
  originCode?: string
  originChain?: string
  hopCount?: number
  status?: ResourceStatus
  removedAt?: string
  orgMissing?: number
  createTime?: string
  updateTime?: string
}

export interface ResChannel {
  id: string
  tenantId?: number
  code: string
  name: string
  localName?: string
  orgId?: string
  orgPath?: string
  deviceId?: string
  groupId?: string
  streamType?: string
  onlineStatus?: string
  capability?: string
  mediaHint?: string
  sourceType?: SourceType
  sourcePlatformId?: string
  originPlatformId?: string
  originCode?: string
  codeChain?: string
  originChain?: string
  hopCount?: number
  canonicalId?: string
  status?: ResourceStatus
  removedAt?: string
  orgMissing?: number
  createTime?: string
  updateTime?: string
}

/** 通道列表行：通道本体 + 展示用名字（由后端按页批量补齐） */
export interface ChannelListItem {
  channel: ResChannel
  deviceName?: string
  orgName?: string
  /** 同源组内的行数（含自己；未关联时为 1）——用于"同源 N"徽标 */
  sourceCount?: number
  /** 同源组主 id：列表按它聚合展示，未关联时等于自身 id */
  sourceGroupLeaderId?: string
}

/** 同源组成员（3.10.3） */
export interface ChannelSourceGroupMember {
  id: string
  code: string
  name: string
  sourceType?: SourceType
  /** 直接来源平台 id，0 = 本地 */
  sourcePlatformId?: string
  /** 直接来源平台名称，本地为空 */
  sourcePlatformName?: string
  onlineStatus?: string
  hopCount?: number
  status?: ResourceStatus
  /** 是否组主 */
  leader: boolean
}

/** 同源组视图：组主 + 全部成员 */
export interface ChannelSourceGroup {
  leaderId: string
  members: ChannelSourceGroupMember[]
}

/** 设备 / 通道分页入参（orgId 走 org_path 前缀匹配，不是先查子树再 IN） */
export interface ResourcePageRequest {
  page?: number
  pageSize?: number
  keyword?: string
  orgId?: string
  includeSubtree?: boolean
  deviceId?: string
  sourceType?: SourceType
  status?: ResourceStatus
}

export interface OrgSaveRequest {
  code: string
  name: string
  parentId?: string
  localName?: string
  sortNo?: number
}

export interface DeviceSaveRequest {
  code: string
  name: string
  orgId: string
  deviceIp?: string
  vendor?: string
  model?: string
  localName?: string
}

export interface ChannelSaveRequest {
  code: string
  name: string
  orgId: string
  deviceId: string
  streamType?: string
  capability?: string
  mediaHint?: string
  localName?: string
}

/* ==================== M6 视频业务：实时点播 / 回放 / 云台 / 播放记录 / 运行自检 ==================== */

/** 实时点播请求（transport / streamNumber 留空走服务端默认） */
export interface ChannelLiveStartRequest {
  transport?: 'UDP' | 'TCP-passive' | 'TCP-active'
  /** 码流 token：空 = 主码流 */
  streamNumber?: string
}

/** 回放开始请求（时间格式 yyyy-MM-dd HH:mm:ss） */
export interface ChannelPlaybackStartRequest {
  startTime: string
  endTime: string
  transport?: 'UDP' | 'TCP-passive' | 'TCP-active'
  streamNumber?: string
}

/** 录像查询请求（时间格式 yyyy-MM-dd HH:mm:ss） */
export interface ChannelRecordQueryRequest {
  startTime: string
  endTime: string
}

/** 通道预置位（挂在 res_channel.id 上，本地与入站同一套） */
export interface ResChannelPreset {
  id: string
  channelRowId: string
  /** 设备侧编号 1-255 */
  presetIndex: number
  name: string
  createTime?: string
  updateTime?: string
}

/** 设置预置位：同编号已存在则覆盖该槽位 */
export interface ChannelPresetSaveRequest {
  presetIndex: number
  name?: string
}

/** 云台方向控制 */
export interface ChannelPtzMoveRequest {
  /** left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop */
  command: string
  /** 移动速度 0-255 */
  speed?: number
  /** 缩放速度 0-15 */
  zoomSpeed?: number
}

/** 拉框缩放：坐标均为视频画面像素值 */
export interface ChannelPtzDragZoomRequest {
  action: 'in' | 'out'
  length: number
  width: number
  midPointX: number
  midPointY: number
  lengthX: number
  lengthY: number
}

/** 播放 / 回放请求记录（一次点播一行，失败也留痕） */
export interface ResPlayRecord {
  id: string
  channelRowId?: string
  channelCode?: string
  channelName?: string
  sourceType?: SourceType
  sourcePlatformId?: string
  action?: 'PLAY' | 'PLAYBACK'
  streamId?: string
  actorId?: string
  actorName?: string
  clientIp?: string
  result?: 'SUCCESS' | 'FAILED'
  failReason?: string
  beginTime?: string
  endTime?: string
  durationSec?: number
}

/** 播放记录查询条件（时间为 yyyy-MM-dd HH:mm:ss） */
export interface PlayRecordQueryRequest {
  page?: number
  size?: number
  channelRowId?: string
  channelCode?: string
  actorId?: string
  action?: 'PLAY' | 'PLAYBACK'
  result?: 'SUCCESS' | 'FAILED'
  begin?: string
  end?: string
}

/** 运行自检：取流会话统计 */
export interface ObservabilitySessionStats {
  total: number
  inviting: number
  ready: number
  play: number
  playback: number
}

/** 运行自检：单个取流会话 */
export interface ObservabilitySessionItem {
  streamId: string
  serverId?: string
  deviceId?: string
  channelId?: string
  sessionType?: string
  status?: string
  startTime?: string
  readyTime?: string
  lastReaderTime?: string
  lastRtpTime?: string
  readers: number
  externalConsumer: boolean
}

/** 运行自检：节点上的一路流 */
export interface ObservabilityStreamItem {
  app: string
  stream: string
  readers: number
  bytesSpeed: number
  aliveSeconds: number
  videoCodec?: string
}

/** 运行自检：媒体节点 */
export interface ObservabilityNodeItem {
  serverId: string
  name?: string
  hostIp?: string
  status?: number
  reachable: boolean
  streamCount: number
  totalReaders: number
  streams: ObservabilityStreamItem[]
}

/** 运行自检：带宽头部流 */
export interface ObservabilityBandwidthStream {
  serverId?: string
  app: string
  stream: string
  bytesPerSec: number
  mbps: number
  readers: number
  externalConsumer: boolean
}

/** 运行自检：带宽汇总（6.8 风险监控） */
export interface ObservabilityBandwidthStats {
  totalBytesPerSec: number
  totalMbps: number
  streamCount: number
  totalReaders: number
  topStreams: ObservabilityBandwidthStream[]
}

/** 运行自检：今日播放记录统计 */
export interface ObservabilityRecordStats {
  total: number
  success: number
  failed: number
  open: number
}

/** 运行自检：关键阈值快照 */
export interface ObservabilityConfigSnapshot {
  idleReclaimSec: number
  inviteTimeoutSec: number
  playbackTimeoutSec: number
  cascadeRtpTimeoutSec: number
  hopCooldownSec: number
  recordRetentionDays: number
}

/** 运行自检汇总 */
export interface ObservabilitySummary {
  generatedAt?: string
  sessions: ObservabilitySessionStats
  activeSessions: ObservabilitySessionItem[]
  nodes: ObservabilityNodeItem[]
  bandwidth: ObservabilityBandwidthStats
  cascadeSendCount: number
  broadcastCount: number
  recordsToday: ObservabilityRecordStats
  config: ObservabilityConfigSnapshot
}

