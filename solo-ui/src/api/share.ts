import { del, get, post } from '@/utils/request'
import type {
  OutGroup,
  OutShare,
  OutboxEvent,
  OutboxQuery,
  OutboxStatusCount,
  PublishRound,
  RoundQuery,
  ShareChannelOption,
  ShareChannelQuery,
  ShareDeriveResult,
  ShareGroupTreeResult,
  ShareItemQuery,
  ShareOrgTreeNode,
  SharePreview,
  ShareReconcileResult,
  ShareRoundResult,
  ShareTarget,
} from '@/types/api'

/* ==================== 共享编排 ==================== */

export const getShareTargetsApi = () => {
  return get<ShareTarget[]>('/api/share/compose/platforms')
}

export const getShareGroupsApi = (platformId: string) => {
  return get<OutGroup[]>('/api/share/compose/groups', { params: { platformId } })
}

/** 一键生成：按来源组织树镜像该上级的虚拟组织树（幂等，重复点不会产生同名分组） */
export const generateShareGroupsApi = (data: {
  platformId: string
  sourceOrgId: string
  mountGroupId?: string | null
}) => {
  return post<ShareGroupTreeResult>('/api/share/compose/groups/generate', data)
}

export const getShareOrgTreeApi = () => {
  return get<ShareOrgTreeNode[]>('/api/share/compose/org/tree')
}

/** 可选通道分页：已回填 shared / excluded / exposedCode */
export const pageShareChannelsApi = (data: ShareChannelQuery) => {
  return post<{ total: number; rows: ShareChannelOption[] }>('/api/share/compose/channel/page', data)
}

export const pageShareItemsApi = (data: ShareItemQuery) => {
  return post<{ total: number; rows: OutShare[] }>('/api/share/compose/items/page', data)
}

/** 提交前预览：纯计算，不落库、不分配暴露码 */
export const previewShareApi = (data: {
  platformId: string
  orgIds?: string[]
  channelIds?: string[]
  deviceIds?: string[]
}) => {
  return post<SharePreview>('/api/share/compose/preview', data)
}

export const addShareRuleApi = (data: { platformId: string; orgId: string; groupId?: string | null }) => {
  return post<ShareDeriveResult>('/api/share/compose/rules', data)
}

export const removeShareRuleApi = (platformId: string, orgId: string) => {
  return del<ShareDeriveResult>('/api/share/compose/rules', { params: { platformId, orgId } })
}

export const addShareChannelsApi = (data: {
  platformId: string
  channelIds: string[]
  groupId?: string | null
}) => {
  return post<ShareDeriveResult>('/api/share/compose/channels', data)
}

export const addShareDevicesApi = (data: {
  platformId: string
  deviceIds: string[]
  groupId?: string | null
}) => {
  return post<ShareDeriveResult>('/api/share/compose/devices', data)
}

export const excludeShareChannelApi = (data: { platformId: string; channelId: string }) => {
  return post<void>('/api/share/compose/exclude', data)
}

export const restoreShareChannelApi = (data: { platformId: string; channelId: string }) => {
  return post<void>('/api/share/compose/restore', data)
}

/* ==================== 共享总览 ==================== */

export const getOverviewTargetsApi = () => {
  return get<ShareTarget[]>('/api/share/overview/platforms')
}

export const pageOverviewItemsApi = (data: ShareItemQuery) => {
  return post<{ total: number; rows: OutShare[] }>('/api/share/overview/items/page', data)
}

export const overviewExcludeApi = (data: { platformId: string; channelId: string }) => {
  return post<void>('/api/share/overview/exclude', data)
}

export const overviewRestoreApi = (data: { platformId: string; channelId: string }) => {
  return post<void>('/api/share/overview/restore', data)
}

/** 撤销单条共享：置死 + 推 DELETE，不删行（恢复要人工重新勾选） */
export const overviewRevokeApi = (data: { platformId: string; channelId: string }) => {
  return post<void>('/api/share/overview/revoke', data)
}

/** 撤销整个上级：全部置死 + 清树 + 一条 REFRESH */
export const overviewRevokePlatformApi = (platformId: string) => {
  return post<void>(`/api/share/overview/revoke-platform/${platformId}`)
}

export const overviewRebuildApi = (platformId: string) => {
  return post<ShareDeriveResult>(`/api/share/overview/rebuild/${platformId}`)
}

export const overviewReconcileApi = (platformId: string) => {
  return post<ShareReconcileResult>(`/api/share/overview/reconcile/${platformId}`)
}

/* ==================== 推送记录 ==================== */

export const getPublishTargetsApi = () => {
  return get<ShareTarget[]>('/api/share/publish/platforms')
}

export const pageOutboxApi = (data: OutboxQuery) => {
  return post<{ total: number; rows: OutboxEvent[] }>('/api/share/publish/outbox/page', data)
}

export const getOutboxCountsApi = (platformId?: string) => {
  return get<OutboxStatusCount[]>('/api/share/publish/outbox/counts', {
    params: platformId ? { platformId } : undefined,
  })
}

export const pageRoundsApi = (data: RoundQuery) => {
  return post<{ total: number; rows: PublishRound[] }>('/api/share/publish/round/page', data)
}

export const getRoundDetailApi = (id: string) => {
  return get<PublishRound>(`/api/share/publish/round/${id}`)
}

/** 重试：只重置 FAILED/DEAD，序号不变 */
export const retryOutboxApi = (ids: string[]) => {
  return post<number>('/api/share/publish/retry', { ids })
}

/** 立即推送：同步跑完一轮，返回本轮结果 */
export const dispatchPlatformApi = (platformId: string) => {
  return post<ShareRoundResult>(`/api/share/publish/dispatch/${platformId}`)
}
