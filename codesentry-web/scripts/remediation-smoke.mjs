import { chromium } from 'playwright'
import assert from 'node:assert/strict'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' })
const errors = []
page.on('pageerror', e => errors.push(e.message))
const source = { id: 7, projectId: 1, scanMode: 'FULL', workspaceMode: 'BRANCH_SNAPSHOT', ruleProfile: 'LOCAL_OWASP', status: 'COMPLETED', scanRef: 'main', createdAt: '2026-11-03T09:00:00' }
const finding = { id: 42, instanceId: 84, scanRunId: 7, fingerprint: 'abc', scanner: 'Semgrep', ruleId: 'sql-injection', title: '数据库查询参数未校验', filePath: 'src/Query.java', startLine: 20, severity: 'HIGH' }
let marked = false, verification, started
await page.route('**/api/v1/**', async route => {
  const url = new URL(route.request().url())
  let result
  if (url.pathname.endsWith('/acceptance')) {
    result = { source, verification, history: verification ? [verification] : [], counts: { [verification ? 'NOT_DETECTED' : marked ? 'PENDING_VERIFICATION' : 'TODO']: 1 }, items: [{ finding, status: verification ? 'NOT_DETECTED' : marked ? 'PENDING_VERIFICATION' : 'TODO', comment: marked ? '使用参数化查询，提交 abc123' : '' }] }
  } else if (url.pathname.endsWith('/triage')) {
    const body = route.request().postDataJSON()
    assert.equal(body.triageStatus, 'FIXED_PENDING'); assert.equal(body.comment, '使用参数化查询，提交 abc123')
    marked = true; result = { ...finding, ...body }
  } else if (url.pathname.endsWith('/projects')) result = [{ id: 1, projectName: '验收演示', sourceType: 'LOCAL', defaultBranch: 'main' }]
  else if (url.pathname.endsWith('/branches')) result = [{ name: 'main', commit: 'abc1234' }, { name: 'fix/sql', commit: 'def5678' }]
  else if (url.pathname.endsWith('/rule-profiles')) result = [{ id: 'LOCAL_OWASP', name: '本地安全规则', offlineReady: true }]
  else if (url.pathname.endsWith('/scan-runs') && route.request().method() === 'POST') {
    started = route.request().postDataJSON()
    verification = { ...source, id: 8, sourceReportRunId: 7, scanCommit: 'def5678' }; result = verification
  } else result = []
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: result }) })
})
try {
  const base = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5173'
  await page.goto(`${base}/reports/7/acceptance`)
  await page.getByRole('button', { name: '记录整改', exact: true }).click()
  await page.getByLabel('整改说明 / 提交编号').fill('使用参数化查询，提交 abc123')
  await page.getByRole('button', { name: '保存整改记录', exact: true }).click()
  await page.getByRole('cell', { name: '已整改待验证', exact: true }).waitFor()
  await page.getByRole('link', { name: '发起验收复扫' }).click()
  await page.getByText('正在为报告 CSR-000007 创建验收复扫。', { exact: false }).waitFor()
  assert.equal(await page.locator('.form-grid select').first().isDisabled(), true)
  assert.equal(await page.locator('#rule-profile').isDisabled(), true)
  await page.getByRole('button', { name: '启动扫描', exact: true }).click()
  await page.waitForURL('**/reports/7/acceptance')
  assert.equal(started.sourceReportRunId, 7); assert.equal(started.projectId, 1)
  assert.equal(started.scanMode, 'FULL'); assert.equal(started.ruleProfile, 'LOCAL_OWASP')
  await page.goto(`${base}/reports/7/acceptance`)
  await page.getByRole('cell', { name: '复扫未再检出', exact: true }).waitFor()
  await page.getByRole('link', { name: '#8', exact: true }).waitFor()
  await page.addStyleTag({ content: '*, *::before, *::after { animation: none !important; transition: none !important; opacity: 1 !important }' })
  await page.screenshot({ path: '/tmp/codesentry-remediation-acceptance.png', fullPage: true })
  assert.deepEqual(errors, [])
  console.log('通过：整改记录、报告关联、锁定项目/规则集/全量模式、复扫结果与批次链接；无页面异常。')
} catch (error) { console.error((await page.locator('body').innerText()).slice(0, 2500)); console.error(errors); throw error } finally { await browser.close() }
