import { afterEach, expect, it, vi } from 'vitest'
import { api } from '../codesentry'
import { safeFileName } from '@/utils/files'

afterEach(() => {
  vi.unstubAllGlobals()
})
function successfulArchive() {
  const fetcher = vi
    .fn()
    .mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ reportUrl: '/api/read-only' }), { status: 201 }),
      ),
    )
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

it('显式归档支持资产、整项目和项目下历史资产，来源互斥', async () => {
  const fetcher = successfulArchive()
  await api.archiveReport(5)
  await api.archiveProjectReport(2, 3)
  await api.archiveProjectReport(2, -5)
  expect(fetcher.mock.calls.map((call) => JSON.parse(call[1].body as string))).toEqual([
    { scanRunId: 5 },
    { businessProjectId: 2, roundId: 3 },
    { scanRunId: 5 },
  ])
  expect((fetcher.mock.calls[0]?.[1].headers as Headers).get('Idempotency-Key')).toBe(
    'archive-scan-5',
  )
})

it('归档拒绝缺少只读位置或错误类型的响应', async () => {
  for (const body of [null, {}, { reportUrl: 123 }]) {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(new Response(JSON.stringify(body), { status: 201 })),
        ),
    )
    await expect(api.archiveReport(5)).rejects.toThrow('归档响应格式异常')
  }
})

it('刷新分支先显式同步，再读取分支；普通查询只读', async () => {
  const fetcher = vi
    .fn()
    .mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify({ success: true, data: [] }))),
    )
  vi.stubGlobal('fetch', fetcher)
  await api.branches(2, true)
  await api.branches(2)
  expect(fetcher.mock.calls.map((call) => [call[0], call[1].method ?? 'GET'])).toEqual([
    ['/api/v1/projects/2/sync', 'POST'],
    ['/api/v1/projects/2/branches', 'GET'],
    ['/api/v1/projects/2/branches', 'GET'],
  ])
})

it('下载名称保留中文，清除路径与控制字符', () => {
  expect(safeFileName(' ccus/项目\u0000\u007f ')).toBe('ccus_项目__')
  expect(safeFileName('')).toBe('')
})
