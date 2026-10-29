import type { ApiResponse } from '@/types'
import { presentToolData, toolText } from '@/utils/toolPresentation'

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message)
  }
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })

  if (response.status === 204) return undefined as T

  const rawBody = await response.text()
  let payload: ApiResponse<T> | undefined
  if (rawBody) {
    try {
      payload = JSON.parse(rawBody) as ApiResponse<T>
    } catch {
      payload = undefined
    }
  }

  if (!response.ok || !payload?.success) {
    const fallback = response.status >= 500
      ? `CodeSentry 服务暂不可用（HTTP ${response.status}）`
      : `请求处理失败（HTTP ${response.status}）`
    throw new ApiError(toolText(payload?.message ?? fallback), response.status)
  }
  return presentToolData(payload.data)
}
