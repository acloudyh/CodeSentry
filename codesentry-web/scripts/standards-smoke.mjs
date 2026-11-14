import { chromium } from 'playwright'
import AxeBuilder from '@axe-core/playwright'
import { mkdir, writeFile } from 'node:fs/promises'

const base = process.env.CODESENTRY_WEB_URL ?? 'https://127.0.0.1:5175'
const browser = await chromium.launch({
  channel: process.env.CODESENTRY_BROWSER_CHANNEL ?? 'chrome',
})
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  ignoreHTTPSErrors: true,
})
const page = await context.newPage()
const errors = [],
  results = []
page.on('pageerror', (error) => errors.push(error.message))
// 所有业务请求均使用合成数据，页面验收不会触发扫描、模型调用或生产写入。
await page.route(/\/api\/v[0-9]+\//, async (route) => {
  const path = new URL(route.request().url()).pathname
  let data = []
  if (path.endsWith('/dashboard/summary'))
    data = {
      businessProjects: 0,
      assets: 0,
      codeAssets: 0,
      jarAssets: 0,
      unassignedAssets: 0,
      scans: 0,
      runningScans: 0,
      newFindings: 0,
    }
  if (path.endsWith('/reports/page')) data = { items: [], total: 0, page: 1, pageSize: 20 }
  if (path.endsWith('/ai-reviews/config'))
    data = { enabled: false, baseUrl: '', model: '', apiKeyConfigured: false }
  if (path.endsWith('/rule-profiles/library'))
    data = { status: 'READY', ruleCount: 0, enabled: true }
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  })
})
await mkdir('test-results', { recursive: true })
try {
  for (const route of [
    '/',
    '/business-projects',
    '/projects',
    '/scans',
    '/findings',
    '/reports',
    '/rules',
    '/tools',
    '/settings',
  ]) {
    await page.goto(base + route, { waitUntil: 'networkidle' })
    await page.locator('h1').first().waitFor({ state: 'visible' })
    const axe = await new AxeBuilder({ page }).analyze()
    const violations = axe.violations.filter((v) => ['critical', 'serious'].includes(v.impact))
    results.push({
      route,
      violations: violations.map((v) => ({
        id: v.id,
        impact: v.impact,
        targets: v.nodes.map((n) => n.target),
      })),
    })
    if (route === '/projects') {
      await page.getByRole('button', { name: /登记仓库/ }).click()
      await page.getByRole('dialog').waitFor()
      const modal = await new AxeBuilder({ page }).analyze()
      results.push({
        route: '/projects#register',
        violations: modal.violations
          .filter((v) => ['critical', 'serious'].includes(v.impact))
          .map((v) => ({ id: v.id, impact: v.impact, targets: v.nodes.map((n) => n.target) })),
      })
      await page.keyboard.press('Escape')
    }
  }
  await writeFile('test-results/standards-smoke.json', JSON.stringify({ results, errors }, null, 2))
  const failed = results.filter((result) => result.violations.length)
  if (failed.length || errors.length) throw new Error(JSON.stringify({ failed, errors }))
  console.log('PC 页面、登记弹窗与 axe critical/serious 检查通过')
} finally {
  await browser.close()
}
