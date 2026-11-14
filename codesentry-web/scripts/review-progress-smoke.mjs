import { chromium } from 'playwright'
import assert from 'node:assert/strict'
const base = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5174'
const project = Number(process.env.CODESENTRY_LEDGER_PROJECT_ID)
assert.ok(project > 0, 'Set CODESENTRY_LEDGER_PROJECT_ID')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } })
const errors = [],
  mutations = []
page.on('pageerror', (e) => errors.push(e.message))
page.on('request', (r) => {
  if (r.url().includes('/api/') && r.method() !== 'GET' && r.method() !== 'OPTIONS')
    mutations.push(r.method())
})
try {
  await page.goto(`${base}/findings?businessProjectId=${project}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '查看审核进度', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '批量工具审核进度' })
  await dialog.locator('.tool-job').first().waitFor()
  const first = (await dialog.locator('.tool-job strong').allTextContents()).map(
    (x) => x.match(/任务 #(\d+)/)?.[1],
  )
  assert.ok(first.length > 0)
  assert.equal(await dialog.locator('progress').count(), first.length)
  const started = dialog.locator('.datetime-text').first()
  assert.equal(await started.evaluate((el) => getComputedStyle(el).whiteSpace), 'nowrap')
  await page.keyboard.press('Escape')
  await page.goto(`${base}/business-projects`, { waitUntil: 'networkidle' })
  await page.goto(`${base}/findings?businessProjectId=${project}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '查看审核进度', exact: true }).click()
  await dialog.locator('.tool-job').first().waitFor()
  const restored = (await dialog.locator('.tool-job strong').allTextContents()).map(
    (x) => x.match(/任务 #(\d+)/)?.[1],
  )
  assert.deepEqual(restored, first, 'Returning must recover persisted task identities')
  assert.deepEqual(errors, [])
  assert.deepEqual(mutations, [], 'Progress recovery must never restart a review')
  if (process.env.CODESENTRY_SCREENSHOT_PATH) {
    await page.waitForTimeout(600)
    await page.screenshot({ path: process.env.CODESENTRY_SCREENSHOT_PATH })
  }
  console.log(
    JSON.stringify({
      passed: true,
      recoveredTasks: first.length,
      backgroundTasksNotRestarted: true,
    }),
  )
} finally {
  await browser.close()
}
