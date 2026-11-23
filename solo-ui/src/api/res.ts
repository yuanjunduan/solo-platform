import { get, post, put, del } from '@/utils/request'
import service from '@/utils/request'
import type {
  ChannelListItem,
  ChannelLiveStartRequest,
  ChannelPlaybackStartRequest,
  ChannelPresetSaveRequest,
  ChannelPtzDragZoomRequest,
  ChannelPtzMoveRequest,
  ChannelRecordQueryRequest,
  ChannelSaveRequest,
  ChannelSourceGroup,
  DeviceSaveRequest,
  GbPlaybackControlRequest,
  GbPlaybackResult,
  GbPlayResult,
  GbRecordItem,
  ObservabilitySummary,
  OrgSaveRequest,
  PlayRecordQueryRequest,
  ResChannel,
  ResChannelPreset,
  ResDevice,
  ResOrg,
  ResOrgTreeNode,
  ResPlayRecord,
  ResourcePageRequest,
  SourceType,
} from '@/types/api'

/* ==================== 组织 ==================== */

/** 组织树：节点自带"直接归属"的设备/通道计数与 writable（INBOUND = false） */
export const getResOrgTreeApi = (sourceType?: SourceType) => {
  return get<ResOrgTreeNode[]>('/api/res/org/tree', {
    params: sourceType ? { sourceType } : undefined,
  })
}

export const createResOrgApi = (data: OrgSaveRequest) => {
  return post<ResOrg>('/api/res/org', data)
}

/** 换父时会重写整棵子树的 path / level，前端刷新整棵树即可 */
export const updateResOrgApi = (id: string, data: OrgSaveRequest) => {
  return put<void>(`/api/res/org/${id}`, data)
}

export const deleteResOrgApi = (id: string) => {
  return del<void>(`/api/res/org/${id}`)
}

/* ==================== 设备 ==================== */

export const getResDevicePageApi = (data: ResourcePageRequest) => {
  return post<{ total: number; rows: ResDevice[] }>('/api/res/device/page', data)
}

export const getResDeviceDetailApi = (id: string) => {
  return get<ResDevice>(`/api/res/device/${id}`)
}

export const createResDeviceApi = (data: DeviceSaveRequest) => {
  return post<ResDevice>('/api/res/device', data)
}

export const updateResDeviceApi = (id: string, data: DeviceSaveRequest) => {
  return put<void>(`/api/res/device/${id}`, data)
}

export const deleteResDeviceApi = (id: string) => {
  return del<void>(`/api/res/device/${id}`)
}

/* ==================== 通道 ==================== */

export const getResChannelPageApi = (data: ResourcePageRequest) => {
  return post<{ total: number; rows: ChannelListItem[] }>('/api/res/channel/page', data)
}

export const getResChannelDetailApi = (id: string) => {
  return get<ResChannel>(`/api/res/channel/${id}`)
}

export const createResChannelApi = (data: ChannelSaveRequest) => {
  return post<ResChannel>('/api/res/channel', data)
}

export const updateResChannelApi = (id: string, data: ChannelSaveRequest) => {
  return put<void>(`/api/res/channel/${id}`, data)
}

export const deleteResChannelApi = (id: string) => {
  return del<void>(`/api/res/channel/${id}`)
}

/* ==================== 同源组（3.10.3） ==================== */

/** 同源组明细：组主 + 成员；未关联时只有它自己 */
export const getChannelSourcesApi = (id: string) => {
  return get<ChannelSourceGroup>(`/api/res/channel/${id}/sources`)
}

/** 同源绑定：只新建组（至少 2 条、且都不是任何组的成员/组主） */
export const bindChannelSourcesApi = (ids: string[]) => {
  return post<ChannelSourceGroup>('/api/res/channel/sources/bind', { ids })
}

/** 解除同源关联：只解除这一行；解除组主时后端自动提升新组主 */
export const unbindChannelSourceApi = (id: string) => {
  return post<void>('/api/res/channel/sources/unbind', { id })
}

/** 导出通道（CSV，最多 5 万行；条件与列表一致） */
export const exportResChannelApi = (params: ResourcePageRequest) => {
  return service.get('/api/res/channel/export', { params, responseType: 'blob' })
}

/* ==================== 实时点播（M6 统一入口） ==================== */

/** 开始实时点播：本地通道走设备 INVITE，入站通道交给来源平台；同一路流多人复用 */
export const startChannelLiveApi = (id: string, data?: ChannelLiveStartRequest) => {
  return post<GbPlayResult>(`/api/res/channel/${id}/play/start`, data)
}

/** 停止实时点播：以 streamId 为准（同源选路后可能与请求行不同） */
export const stopChannelLiveApi = (streamId: string) => {
  return post<void>('/api/res/channel/play/stop', { streamId })
}

/* ==================== 录像回放（M6 统一入口，入站只转发不落存储） ==================== */

export const queryChannelPlaybackApi = (id: string, data: ChannelRecordQueryRequest) => {
  return post<GbRecordItem[]>(`/api/res/channel/${id}/playback/query`, data)
}

export const startChannelPlaybackApi = (id: string, data: ChannelPlaybackStartRequest) => {
  return post<GbPlaybackResult>(`/api/res/channel/${id}/playback/start`, data)
}

export const stopChannelPlaybackApi = (streamId: string) => {
  return post<void>('/api/res/channel/playback/stop', { streamId })
}

/** 回放控制：pause / resume / seek / speed */
export const controlChannelPlaybackApi = (data: GbPlaybackControlRequest) => {
  return post<void>('/api/res/channel/playback/control', data)
}

/* ==================== 云台与预置位（M6 统一入口，入站由来源平台转发） ==================== */

export const channelPtzMoveApi = (id: string, data: ChannelPtzMoveRequest) => {
  return post<void>(`/api/res/channel/${id}/ptz/move`, data)
}

export const channelPtzFocusApi = (id: string, direction: 'near' | 'far' | 'stop', speed = 5) => {
  return post<void>(`/api/res/channel/${id}/ptz/focus`, undefined, { params: { direction, speed } })
}

export const channelPtzIrisApi = (id: string, direction: 'open' | 'close' | 'stop', speed = 5) => {
  return post<void>(`/api/res/channel/${id}/ptz/iris`, undefined, { params: { direction, speed } })
}

export const channelPtzWiperApi = (id: string, action: 'on' | 'off') => {
  return post<void>(`/api/res/channel/${id}/ptz/wiper`, undefined, { params: { action } })
}

export const channelPtzDragZoomApi = (id: string, data: ChannelPtzDragZoomRequest) => {
  return post<void>(`/api/res/channel/${id}/ptz/drag-zoom`, data)
}

export const channelPtzHomePositionApi = (
  id: string,
  params: { enabled: boolean; resetTime?: number; presetIndex?: number },
) => {
  return post<void>(`/api/res/channel/${id}/ptz/home-position`, undefined, { params })
}

export const getChannelPresetsApi = (id: string) => {
  return get<ResChannelPreset[]>(`/api/res/channel/${id}/presets`)
}

export const saveChannelPresetApi = (id: string, data: ChannelPresetSaveRequest) => {
  return post<ResChannelPreset>(`/api/res/channel/${id}/presets`, data)
}

export const callChannelPresetApi = (id: string, presetId: string) => {
  return post<void>(`/api/res/channel/${id}/presets/${presetId}/call`)
}

export const deleteChannelPresetApi = (id: string, presetId: string) => {
  return del<void>(`/api/res/channel/${id}/presets/${presetId}`)
}

/* ==================== 播放记录（M6，3.11.6） ==================== */

/** 分页查询：按人 / 通道 / 时间 / 动作 / 结果 */
export const getResPlayRecordPageApi = (params: PlayRecordQueryRequest) => {
  return get<{ total: number; rows: ResPlayRecord[] }>('/api/res/play-record/page', { params })
}

/** 导出播放记录（CSV，最多 5 万行） */
export const exportResPlayRecordApi = (params: PlayRecordQueryRequest) => {
  return service.get('/api/res/play-record/export', { params, responseType: 'blob' })
}

/* ==================== 运行自检（M6，可观测） ==================== */

export const getObservabilitySummaryApi = () => {
  return get<ObservabilitySummary>('/api/res/observability/summary')
}
