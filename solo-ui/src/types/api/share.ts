import type { ResChannel, ResOrgTreeNode, ResourcePageRequest } from './res'

/**
 * 上级资源共享（M4 出站）的前端类型。
 *
 * 三组状态不要混用：
 * - `ShareSummaryStatus` 是共享项/平台汇总（取最差）；
 * - `OutboxStatus` 是单条报文的投递状态；
 * - `RoundStatus` 是一轮推送的台账状态。
 */

export type ShareSummaryStatus = 'PENDING' | 'SYNCED' | 'FAILED' | 'DEAD' | 'REVOKING'
export type ShareRefType = 'ORG' | 'DEVICE' | 'CHANNEL'
export type ShareScopeMode = 'EXPLICIT' | 'SUBTREE'
export type OutboxStatus = 'PENDING' | 'SENT' | 'FAILED' | 'DEAD' | 'PAUSED'
export type PublishEventType = 'ADD' | 'UPDATE' | 'DELETE' | 'REFRESH'
export type RoundStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
export type RoundTrigger = 'MANUAL' | 'SCHEDULED' | 'RECONCILE' | 'RESOURCE'

/** 目标上级平台 + 汇总卡片 */
export interface ShareTarget {
  id: string
  name: string
  serverGbId?: string
  enabled?: number
  status?: number
  channelCount: number
  ruleCount: number
  excludedCount: number
  /** null = 还没有共享项（与"共享正常"是两件事） */
  summaryStatus?: ShareSummaryStatus | null
  pendingCount: number
  lastRoundStatus?: RoundStatus | null
  lastRoundSent?: number | null
  lastRoundFailed?: number | null
  lastPushedAt?: string | null
}

/** 上级虚拟组织节点（215 业务分组 / 216 虚拟组织） */
export interface OutGroup {
  id: string
  tenantId?: number
  platformId: string
  code: string
  parentId?: string | null
  parentCode?: string | null
  path: string
  level?: number
  name: string
  groupType?: '215' | '216'
  sourceOrgId?: string | null
  sourceOrgPath?: string | null
  sortNo?: number
  remark?: string
  createTime?: string
  updateTime?: string
}

/** 共享关系行（规则行 + 资源行同表） */
export interface OutShare {
  id: string
  tenantId?: number
  platformId: string
  groupId?: string | null
  refType: ShareRefType
  refId?: string | null
  refSourceType?: string
  refSourcePlatformId?: string | null
  refCode?: string
  orgPath?: string
  scopeMode?: ShareScopeMode
  /** 1 = 由规则派生 */
  derived?: number
  /** 0 = 已剔除 */
  enabled?: number
  /** 0 = 资源已消失/已撤销 */
  refAlive?: number
  status?: ShareSummaryStatus
  lastError?: string | null
  lastPushedAt?: string | null
  operator?: string | null
  createTime?: string
  updateTime?: string
  /** 联表带出 */
  refName?: string | null
  platformName?: string | null
  groupName?: string | null
}

/** 编排页可选通道（含对该上级的共享状态） */
export interface ShareChannelOption {
  channel: ResChannel
  deviceName?: string | null
  orgName?: string | null
  shared: boolean
  excluded: boolean
  exposedCode?: string | null
}

export interface ShareChannelQuery extends ResourcePageRequest {
  platformId?: string
}

export interface ShareItemQuery {
  page?: number
  pageSize?: number
  platformId?: string
  refType?: ShareRefType
  scopeMode?: ShareScopeMode
  status?: ShareSummaryStatus
  enabled?: number
  refAlive?: number
  keyword?: string
}

export interface OutboxQuery {
  page?: number
  pageSize?: number
  platformId?: string
  status?: OutboxStatus
  eventType?: PublishEventType
  keyword?: string
}

export interface RoundQuery {
  page?: number
  pageSize?: number
  platformId?: string
  status?: RoundStatus
}

/** 出站事件（一条报文一行） */
export interface OutboxEvent {
  id: string
  tenantId?: number
  platformId: string
  seqNo: string
  eventType: PublishEventType
  refType?: ShareRefType
  refId?: string | null
  refCode?: string | null
  exposedCode?: string | null
  payload?: string | null
  version?: number | null
  status: OutboxStatus
  retryCount?: number
  nextRetryAt?: string | null
  lastError?: string | null
  roundId?: string | null
  createTime?: string
  updateTime?: string
  platformName?: string | null
}

/** 推送轮次台账（一轮一行，不可重写） */
export interface PublishRound {
  id: string
  tenantId?: number
  platformId: string
  platformName?: string | null
  triggerType?: RoundTrigger
  status?: RoundStatus
  planned?: number
  sent?: number
  failed?: number
  /** 1 = 超阈值已合并为 REFRESH */
  degraded?: number
  threshold?: number | null
  errorCode?: string | null
  errorMsg?: string | null
  summary?: string | null
  beginTime?: string
  endTime?: string | null
  createTime?: string
  updateTime?: string
}

export interface OutboxStatusCount {
  status: OutboxStatus
  total: number
}

/** 编排预览：本次提交会发生什么 */
export interface SharePreview {
  total: number
  shared: number
  added: number
  restored: number
  excluded: number
  /** 需要新分配暴露码的入站通道数 */
  renumber: number
  conflicts: SharePreviewConflict[]
}

export interface SharePreviewConflict {
  refId: string
  code?: string
  name?: string
  reason: string
}

/** 派生重算结果 */
export interface ShareDeriveResult {
  added: number
  updated: number
  restored: number
  removed: number
  skipped: number
}

/** 一键生成虚拟组织树结果 */
export interface ShareGroupTreeResult {
  created: number
  updated: number
  moved: number
  total: number
}

/** 对账结果（全部为 0 表示没有偏差） */
export interface ShareReconcileResult {
  derivedAdded: number
  derivedRestored: number
  derivedRemoved: number
  readded: number
  redeleted: number
  refreshed: boolean
}

/** 一轮推送的即时结果（手工"立即推送"返回） */
export interface ShareRoundResult {
  roundId?: string | null
  status?: RoundStatus | null
  planned: number
  sent: number
  failed: number
  degraded: boolean
  summary: string
}

/** 编排页组织树（与组织管理页同一份结构） */
export type ShareOrgTreeNode = ResOrgTreeNode
