import { get, post } from '@/utils/request'
import type { SyncRecord, SyncTask, SyncTaskDetail, SyncTaskQueryRequest } from '@/types/api'

/* ==================== 轮次台账 ==================== */

export const getSyncTaskPageApi = (data: SyncTaskQueryRequest) => {
  return post<{ total: number; rows: SyncTask[] }>('/api/sync/task/page', data)
}

/** 详情含闸门快照（deleteSkipped / guardRatio / orgMissingCount）与最近 200 条变更留痕 */
export const getSyncTaskDetailApi = (id: string) => {
  return get<SyncTaskDetail>(`/api/sync/task/${id}`)
}

export const getSyncRecordPageApi = (id: string, page = 1, pageSize = 20) => {
  return get<{ total: number; rows: SyncRecord[] }>(`/api/sync/task/${id}/records`, {
    params: { page, pageSize },
  })
}

/* ==================== 触发 / 取消 ==================== */

/**
 * 发起一轮全量同步：立即返回轮次 id，目录在分片到达后陆续写入暂存，
 * finish 阶段才合并进资源表 —— 所以前端要轮询进度，不能等这个接口返回结果。
 */
export const triggerSyncApi = (platformId: string, triggerType: 'MANUAL' | 'SCHEDULED' | 'NOTIFY' = 'MANUAL') => {
  return post<number>('/api/sync/trigger/' + platformId, undefined, { params: { triggerType } })
}

/** 取消：丢弃暂存，资源表不动 */
export const cancelSyncApi = (taskId: string) => {
  return post<void>('/api/sync/cancel/' + taskId)
}
