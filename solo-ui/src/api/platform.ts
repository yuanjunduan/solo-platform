import { get, post, put, del } from '@/utils/request'
import type { Platform, PlatformListItem, PlatformSaveRequest, PlatformStatus } from '@/types/api'

/** 平台列表：含入站资源数、最近同步、活跃轮次与失败冷却 */
export const getPlatformListApi = (params?: { keyword?: string; direction?: 'INBOUND' | 'OUTBOUND' }) => {
  return get<PlatformListItem[]>('/api/platform', { params })
}

export const getPlatformDetailApi = (id: string) => {
  return get<Platform>(`/api/platform/${id}`)
}

export const createPlatformApi = (data: PlatformSaveRequest) => {
  return post<Platform>('/api/platform', data)
}

/** code 不可改：后端会忽略请求里的 code */
export const updatePlatformApi = (id: string, data: PlatformSaveRequest) => {
  return put<Platform>(`/api/platform/${id}`, data)
}

export const changePlatformStatusApi = (id: string, status: PlatformStatus) => {
  return put<void>(`/api/platform/${id}/status`, undefined, { params: { status } })
}

export const deletePlatformApi = (id: string) => {
  return del<void>(`/api/platform/${id}`)
}
