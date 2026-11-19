/**
 * 链路追踪：前端为每个请求生成 traceId，后端 TraceIdFilter 写入 MDC 并输出到日志。
 * header 名与后端 com.solo.config.TraceIdFilter.HEADER_TRACE_ID 保持一致。
 */

/** traceId 请求/响应头名 */
export const TRACE_ID_HEADER = 'X-Trace-Id'

/**
 * 生成 32 位十六进制 traceId。
 * 优先 crypto.randomUUID（安全上下文可用），非 HTTPS/老浏览器回退时间戳+随机数。
 */
export function newTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return `${Date.now().toString(16)}${Math.random().toString(16).slice(2, 10)}`.padEnd(24, '0')
}
