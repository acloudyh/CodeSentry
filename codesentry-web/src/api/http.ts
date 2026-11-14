import type { components } from '@/types/generated/protocol'
type Problem = components['schemas']['Problem']
import { presentToolData, toolText } from '@/utils/toolPresentation'

/** 保留状态与追踪编号，让页面错误可关联服务端日志，避免直接展示代理返回的 HTML。 */
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public traceId?: string,
    public code?: Problem['code'],
  ) {
    super(traceId ? `${message}（追踪编号：${traceId}）` : message)
    this.name = 'ApiError'
  }
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/** 统一处理 v1 信封与 Problem 错误；不自动重试写操作，避免重复扫描、审核或上传。 */
export async function request<T>(
  path: string,
  options?: RequestInit,
  decode?: (value: unknown) => T,
): Promise<T> {
  const headers = new Headers(options?.headers)
  if (!(options?.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(path, { ...options, credentials: 'same-origin', headers })
  if (response.status === 204) return undefined as T
  let payload: unknown
  try {
    payload = JSON.parse(await response.text()) as unknown
  } catch {
    payload = undefined
  }
  if (response.ok && decode) return decode(payload)
  const body = object(payload) ? payload : undefined
  const headerTrace = response.headers.get('X-Trace-Id')
  const rawTrace = body?.traceId ?? headerTrace
  const traceId =
    typeof rawTrace === 'string' && /^[a-f0-9]{32}$/.test(rawTrace) ? rawTrace : undefined
  if (!response.ok || body?.success !== true) {
    const fallback =
      response.status >= 500
        ? `CodeSentry 服务暂不可用（HTTP ${response.status}）`
        : `请求处理失败（HTTP ${response.status}）`
    const message = body?.detail ?? body?.message
    throw new ApiError(
      toolText(typeof message === 'string' ? message : fallback),
      response.status,
      traceId,
      typeof body?.code === 'string' ? body.code : undefined,
    )
  }
  return presentToolData(body.data as T)
}
