/**
 * 平台接入（platform 表）：一个外部平台一条记录，方向由 acceptInbound / pushOutbound 两个能力位表达。
 *
 * 列表行额外带"它给我多少资源、最近同步结果、当前活跃轮次、失败冷却"，
 * 这些字段都不在 platform 表里，由后端按平台各查一次。
 */

export type PlatformStatus = 'ONLINE' | 'OFFLINE' | 'UNREACHABLE' | 'DISABLED'

/** MANUAL 手动 / SCHEDULED 定时 / NOTIFY 通知 / BOTH 两者 */
export type SyncPolicy = 'MANUAL' | 'SCHEDULED' | 'NOTIFY' | 'BOTH'

export interface Platform {
  id: string
  tenantId?: number
  /** 平台编码（建议用国标码，建立后不可改） */
  code: string
  name: string
  vendor?: string
  /** 旧版单协议位，M2a 起仅作缺省兜底 */
  protocol?: string
  syncProtocol?: string
  publishProtocol?: string
  mediaProtocol?: string
  host?: string
  port?: number
  localDomain?: string
  /** 1 = 它是我的下级，可向本平台推目录 */
  acceptInbound?: number
  /** 1 = 它是我的上级，本平台可向它推目录 */
  pushOutbound?: number
  status?: PlatformStatus
  lastOnlineAt?: string
  syncPolicy?: SyncPolicy
  syncCron?: string
  /** G2 批量删除保护阈值，0 = 关闭 */
  deleteGuardRatio?: number | string
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface PlatformListItem {
  platform: Platform
  inboundOrg: number
  inboundDevice: number
  inboundChannel: number
  lastSyncStatus?: string
  lastSyncAt?: string
  lastDeleteSkipped?: number
  /** 当前活跃轮次；无则为 null */
  activeTaskId?: string | null
  activeProgress?: number
  /** 失败冷却剩余毫秒，0 = 可立即触发 */
  cooldownMs: number
}

export interface PlatformSaveRequest {
  code: string
  name: string
  vendor?: string
  syncProtocol?: string
  publishProtocol?: string
  mediaProtocol?: string
  host?: string
  port?: number
  localDomain?: string
  acceptInbound?: number
  pushOutbound?: number
  syncPolicy?: SyncPolicy
  syncCron?: string
  deleteGuardRatio?: number
  remark?: string
}
