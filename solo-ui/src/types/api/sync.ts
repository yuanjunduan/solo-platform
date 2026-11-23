/**
 * 同步中心：一轮全量目录同步 = 一行 sync_task，变更留痕在 sync_record。
 *
 * 三段会话：start（建轮次 RUNNING）→ synching（分片 ACK，写暂存）→ finish（单事务合并）。
 * PENDING / RUNNING / SYNCHING 视为"活跃"，同一平台同时只允许一个活跃轮次。
 */

export type SyncTaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SYNCHING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'

export type SyncTriggerType = 'MANUAL' | 'SCHEDULED' | 'NOTIFY'

export interface SyncTask {
  id: string
  tenantId?: number
  taskSn?: string
  platformId?: string
  triggerType?: SyncTriggerType
  status?: SyncTaskStatus
  /** 0-100 */
  progress?: number
  /** 对端告知的总条数（SumNum） */
  expectedTotal?: number
  recvTotal?: number
  orgRecv?: number
  devRecv?: number
  chRecv?: number
  addedCount?: number
  updatedCount?: number
  staleCount?: number
  failedCount?: number
  /** G2 跳过删除：1 = 本轮因批量删除保护没有执行删除 */
  deleteSkipped?: number
  /** 本轮当时的 G2 阈值快照 */
  guardRatio?: number | string
  /** 4 步回填落到 [未分类] 的行数 */
  orgMissingCount?: number
  catalogFingerprint?: string
  errorCode?: string
  errorMsg?: string
  beginTime?: string
  endTime?: string
  createTime?: string
  updateTime?: string
}

export interface SyncRecord {
  id: string
  taskId?: string
  platformId?: string
  /** ORG / GROUP / DEVICE / CHANNEL */
  itemType?: string
  code?: string
  /** ADDED / UPDATED / STALE / FAILED */
  changeType?: string
  beforeSummary?: string
  afterSummary?: string
  createTime?: string
}

export interface SyncTaskQueryRequest {
  page?: number
  pageSize?: number
  platformId?: string
  status?: SyncTaskStatus
  triggerType?: SyncTriggerType
}

export interface SyncTaskDetail {
  task: SyncTask
  platformName?: string
  platformCode?: string
  /** 上一轮目录指纹，便于对比"这一轮目录真的变了吗" */
  lastFingerprint?: string
  /** 是否仍是活跃轮次（活跃时详情页展示取消按钮） */
  active: boolean
  records: SyncRecord[]
}
