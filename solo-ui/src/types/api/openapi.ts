/* ========== 开放平台第三方应用 ========== */

export interface OpenApp {
  id: string
  /** 应用标识，对接方签名时用的 appKey */
  appKey: string
  appName: string
  /** 授权范围，逗号分隔，如 gb:device:read,gb:play:write */
  scopes?: string
  /** IP 白名单，逗号分隔，支持 CIDR；空代表不限 */
  ipWhitelist?: string
  callbackUrl?: string
  /** 是否已配置 Webhook 回调签名密钥 */
  callbackSecretSet?: boolean
  /** 订阅事件类型，逗号分隔 */
  eventSubscriptions?: string
  /** QPS 上限，0 不限 */
  qpsLimit?: number
  /** 日调用量上限，0 不限 */
  dailyLimit?: number
  /** 0 停用 1 启用 */
  status: number
  expireTime?: string
  /** 旧密钥失效时间（换钥过渡期） */
  oldSecretExpireTime?: string
  remark?: string
  createTime?: string
}

export interface OpenAppPageRequest {
  page?: number
  pageSize?: number
  appName?: string
  status?: number
}

/** 创建/编辑共用表单 */
export interface OpenAppSaveRequest {
  id?: string
  appName: string
  scopes?: string
  ipWhitelist?: string
  callbackUrl?: string
  eventSubscriptions?: string
  qpsLimit?: number
  dailyLimit?: number
  expireTime?: string
  remark?: string
}

/** 明文密钥，只在创建/换钥响应里出现一次 */
export interface OpenAppCreatedSecret {
  id: string
  appKey: string
  appSecret: string
  callbackSecret?: string
}

/** 开放接口授权范围，由后端启动时扫描 @RequireScope 生成 */
export interface OpenApiScope {
  code: string
  name: string
  /** 所属接口分组 */
  tag: string
  endpoints: OpenApiScopeEndpoint[]
}

export interface OpenApiScopeEndpoint {
  method: string
  path: string
  summary: string
}

/* ========== 开放平台调用日志 ========== */

export interface OpenApiLog {
  id: string
  /** 鉴权失败（如缺少 appKey）时可能为空 */
  appKey?: string
  requestId?: string
  method: string
  path: string
  queryString?: string
  clientIp?: string
  httpStatus: number
  /** 业务码，200 成功，其余见开放 API 错误码分段 */
  bizCode?: number
  costMs: number
  errorMsg?: string
  createTime?: string
}

/* ========== 资源事件订阅（M3 事件流） ========== */

/** 一份对外订阅：topic + 两级 scope + 服务端游标 */
export interface EventSubscription {
  id: string
  appId: string
  /** 应用标识快照，应用换 Key 后仍能对账 */
  appKey: string
  appName?: string
  topic: string
  /** scope 一级：来源平台；空 = 全部平台 */
  platformId?: string | null
  platformName?: string | null
  /** scope 二级：组织子树前缀（如 /1/5/）；空 = 整租户 */
  orgPath?: string | null
  /** 服务端已发出的最大游标，即 SSE 的 Last-Event-ID */
  lastEventId: string
  /** 1 启用 0 停用 */
  status: number
  remark?: string | null
  createTime?: string
  updateTime?: string
}

export interface EventSubscriptionPageRequest {
  page?: number
  pageSize?: number
  /** appKey 模糊匹配 */
  appKey?: string
  status?: number
}

export interface EventSubscriptionSaveRequest {
  id: string
  /** 传 null 表示放宽到"全部平台" */
  platformId?: string | null
  /** 传 null 表示放宽到"整租户" */
  orgPath?: string | null
  status?: number
  remark?: string | null
}

export interface OpenApiLogPageRequest {
  page?: number
  pageSize?: number
  /** appKey 模糊匹配 */
  appKey?: string
  /** 请求路径模糊匹配 */
  path?: string
  /** true 成功（业务码200）/ false 失败 */
  success?: boolean
  startTime?: string
  endTime?: string
}
