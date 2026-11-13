import { chromium } from 'playwright'
import assert from 'node:assert/strict'

// 只读验证真实大台账；指定已有项目，避免向生产库写入测试资产或扫描记录。
const base = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5173'
const projectId = Number(process.env.CODESENTRY_LEDGER_PROJECT_ID)
assert.ok(projectId > 0, 'Set CODESENTRY_LEDGER_PROJECT_ID to an existing project')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } })
const errors = [], requests = []
const isLedger = url => url.includes(`/business-projects/${projectId}/findings?`)
page.on('pageerror', error => errors.push(error.message))
page.on('request', request => { if (isLedger(request.url())) requests.push(new URL(request.url())) })
const loaded = () => page.waitForFunction(() => !document.body.textContent.includes('正在加载项目问题'))
async function change(action, expected) {
  const before = requests.length
  const response = page.waitForResponse(response => isLedger(response.url()))
  await action(); assert.ok((await response).ok()); await loaded(); await page.waitForTimeout(100)
  assert.equal(requests.length - before, 1, 'One ledger request per interaction')
  for (const [key, value] of Object.entries(expected)) assert.equal(requests.at(-1).searchParams.get(key), value)
}
try {
  await page.goto(`${base}/findings?businessProjectId=${projectId}`, { waitUntil: 'networkidle' })
  await loaded(); assert.equal(requests.length, 1, 'Initialization must not load the ledger twice')
  const filters = page.locator('.ledger-filters select')
  if (process.env.CODESENTRY_LEDGER_ASSET_ID) await change(() => filters.nth(1).selectOption(process.env.CODESENTRY_LEDGER_ASSET_ID), { assetId: process.env.CODESENTRY_LEDGER_ASSET_ID, page: '1' })
  await change(() => page.getByRole('button', { name: '下一页', exact: true }).click(), { page: '2' })
  await change(() => filters.nth(2).selectOption('HIGH'), { page: '1', severity: 'HIGH' })
  await change(() => page.getByRole('button', { name: '下一页', exact: true }).click(), { page: '2' })
  await change(() => page.getByPlaceholder('问题、规则、位置或资产').fill('')
    .then(() => page.getByRole('button', { name: '查询', exact: true }).click()), { page: '1' })

  // 模拟快速筛选时先发慢响应、后发快响应，确认过期请求取消且不会覆盖最新筛选结果。
  let aborted = 0
  page.on('requestfailed', request => { if (isLedger(request.url())) aborted++ })
  await page.route(`**/business-projects/${projectId}/findings?**`, async route => {
    const severity = new URL(route.request().url()).searchParams.get('severity')
    await new Promise(resolve => setTimeout(resolve, severity === 'LOW' ? 500 : 20))
    try { await route.fulfill({ json: { success: true, data: { items: [], total: 0, page: 1, pageSize: 20, summary: { total: severity === 'LOW' ? 999 : 321, newCount: 0, fixedCount: 0 } } } }) }
    catch { /* 被 AbortController 取消的旧请求无需回写。 */ }
  })
  await filters.nth(2).selectOption('LOW'); await page.waitForTimeout(80)
  await filters.nth(2).selectOption('MEDIUM'); await loaded(); await page.waitForTimeout(600)
  assert.ok(aborted >= 1, 'A superseded request must be cancelled')
  assert.ok((await page.locator('body').innerText()).includes('321'))
  assert.ok(!(await page.locator('body').innerText()).includes('999'))
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: true, initialRequests: 1, singleRequestPerInteraction: true, staleRequestsCancelled: aborted }))
} finally { await browser.close() }
