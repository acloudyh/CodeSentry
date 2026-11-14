import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../http'

const trace = '0123456789abcdef0123456789abcdef'
afterEach(() => {
  vi.unstubAllGlobals()
})
function reply(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return vi
    .fn()
    .mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify(body), { status, headers })),
    )
}

describe('统一 HTTP 边界', () => {
  it('保留已有成功数据及展示转换', async () => {
    const fetcher = reply({ success: true, data: { id: 2, reason: 'DeepSeek' } })
    vi.stubGlobal('fetch', fetcher)
    expect(await request('/api/test')).toEqual({ id: 2, reason: '工具服务' })
    expect(fetcher.mock.calls[0]?.[1].credentials).toBe('same-origin')
  })
  it('支持 204，且不尝试读取空 JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    expect(await request('/api/test')).toBeUndefined()
  })
  it('向页面传递 Problem 状态、错误码和追踪编号', async () => {
    vi.stubGlobal(
      'fetch',
      reply({ detail: '请求参数校验失败', code: 'VALIDATION_FAILED', traceId: trace }, 400),
    )
    await expect(request('/api/test')).rejects.toMatchObject({
      status: 400,
      traceId: trace,
      code: 'VALIDATION_FAILED',
    })
    await expect(request('/api/test')).rejects.toThrow(trace)
  })
  it('兼容旧错误体，且不接受异常追踪文本', async () => {
    vi.stubGlobal('fetch', reply({ success: false, message: '名称重复', traceId: '<script>' }, 409))
    await expect(request('/api/test')).rejects.toMatchObject({
      status: 409,
      traceId: undefined,
      message: '名称重复',
    })
  })
  it('代理返回 HTML 时只展示安全的状态提示，并保留响应头 traceId', async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response('<html>internal details</html>', {
        status: 502,
        headers: { 'X-Trace-Id': trace },
      }),
    )
    vi.stubGlobal('fetch', fetcher)
    await expect(request('/api/test')).rejects.toMatchObject({ status: 502, traceId: trace })
    await expect(request('/api/test')).rejects.not.toThrow('internal details')
  })
  it('拒绝错误形状与数组', async () => {
    for (const body of [null, [], { success: false }, { detail: 123 }]) {
      vi.stubGlobal('fetch', reply(body))
      await expect(request('/api/test')).rejects.toBeInstanceOf(ApiError)
    }
  })
  it('FormData 不设置 Content-Type，自定义 Headers 得以保留', async () => {
    const fetcher = reply({ success: true, data: null })
    vi.stubGlobal('fetch', fetcher)
    await request('/api/test', {
      method: 'POST',
      body: new FormData(),
      headers: new Headers({ 'X-Test': 'yes' }),
    })
    const headers = fetcher.mock.calls[0]?.[1].headers as Headers
    expect(headers.has('Content-Type')).toBe(false)
    expect(headers.get('X-Test')).toBe('yes')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('写请求失败不自动重试；普通请求补充 JSON Content-Type', async () => {
    const fetcher = reply({}, 503)
    vi.stubGlobal('fetch', fetcher)
    await expect(request('/api/test', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(
      ApiError,
    )
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect((fetcher.mock.calls[0]?.[1].headers as Headers).get('Content-Type')).toBe(
      'application/json',
    )
  })
})

it('允许新接口通过显式运行时解码器读取无信封结果', async () => {
  vi.stubGlobal('fetch', reply({ reportUrl: '/api/read-only' }, 201))
  const result = await request('/api/v2/report-archives', {}, (value: unknown) => {
    if (!value || typeof value !== 'object' || !('reportUrl' in value)) throw new Error('invalid')
    return value.reportUrl
  })
  expect(result).toBe('/api/read-only')
})

it('兼容旧版省略 data 的 void 成功响应', async () => {
  vi.stubGlobal('fetch', reply({ success: true }))
  expect(await request('/api/test')).toBeUndefined()
})
