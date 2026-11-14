import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// Use a dedicated preview/test server: this smoke creates persistent sample data.
const base = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5173'
const output = process.env.CODESENTRY_SCREENSHOT_DIR ?? '/tmp/codesentry-business-smoke'
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({
  viewport: { width: 1440, height: 1080 },
  acceptDownloads: true,
})
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
async function api(path, body, method = 'POST') {
  const response = await page.request.fetch(`${base}/api/v1${path}`, { method, data: body })
  assert.ok(response.ok(), `${path}: ${await response.text()}`)
  return (await response.json()).data
}
try {
  await page.goto(`${base}/business-projects`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '＋ 新建项目' }).click()
  const name = `油藏平台 · 验证 ${Date.now()}`
  await page.getByLabel('项目名称', { exact: true }).fill(name)
  await page.getByLabel('项目描述').fill('混合项目：代码仓库与 JAR，统一安全扫描与整改交付。')
  await page.getByRole('button', { name: '保存项目', exact: true }).click()
  await page.getByRole('link', { name, exact: true }).click()
  await page.getByRole('heading', { name, exact: true }).waitFor()
  const projectId = Number(new URL(page.url()).pathname.split('/').pop())

  // Registration uses the real existing asset endpoint; then exercise UI association.
  const code = await api('/projects', {
    projectName: `后端服务-${Date.now()}`,
    sourceType: 'LOCAL',
    localPath:
      process.env.CODESENTRY_SAMPLE_REPO ?? fileURLToPath(new URL('../..', import.meta.url)),
    defaultBranch: 'main',
    targetJavaVersion: 11,
  })
  await page.getByRole('button', { name: '关联已有资产', exact: true }).click()
  await page.getByRole('dialog').locator('select').selectOption(String(code.id))
  await page.getByRole('button', { name: '确认关联', exact: true }).click()
  await page.getByRole('button', { name: '扫描', exact: true }).first().waitFor()

  // Upload from the project entry; verify the existing JAR form automatically associates it.
  const javaDir = `${output}/sample`
  await mkdir(javaDir, { recursive: true })
  await writeFile(
    `${javaDir}/Example.java`,
    'public class Example { public static void main(String[] args) {} }\n',
  )
  execFileSync('javac', [`${javaDir}/Example.java`])
  execFileSync('jar', ['cf', `${javaDir}/integration.jar`, '-C', javaDir, 'Example.class'])
  await page.getByRole('link', { name: '＋ 上传 JAR', exact: true }).click()
  await page
    .getByRole('dialog')
    .locator('input[type=file]')
    .setInputFiles(`${javaDir}/integration.jar`)
  await page
    .getByRole('dialog')
    .locator('input[type=text], input:not([type])')
    .first()
    .fill(`集成服务-${Date.now()}`)
  await page.getByRole('button', { name: '确认新增', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await page.getByRole('link', { name: '← 返回业务项目' }).click()
  await page.getByRole('heading', { name, exact: true }).waitFor()
  assert.equal(await page.locator('tbody tr').count(), 2)
  await page.screenshot({ path: `${output}/project-assets.png`, fullPage: true })

  await page.getByRole('button', { name: '扫描整个项目', exact: true }).click()
  const dialog = page.getByRole('dialog')
  assert.equal(await dialog.locator('.target-config').count(), 2)
  await dialog.locator('.form-grid select').nth(1).selectOption('LOCAL_OWASP')
  await page.screenshot({ path: `${output}/project-scan-dialog.png`, fullPage: true })
  const responsePromise = page.waitForResponse(
    (r) =>
      r.url().endsWith(`/business-projects/${projectId}/rounds`) && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '提交扫描', exact: true }).click()
  const response = await responsePromise
  assert.ok(response.ok(), await response.text())
  const round = (await response.json()).data
  await dialog.waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '扫描轮次', exact: true }).click()
  await page.getByRole('heading', { name: new RegExp(`#P${round.id}`) }).waitFor()
  for (let n = 0; n < 40; n++) {
    const result = await api(`/business-projects/${projectId}/rounds/${round.id}`, undefined, 'GET')
    if (result.status !== 'RUNNING') break
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '扫描轮次', exact: true }).click()
  await page.screenshot({ path: `${output}/project-round.png`, fullPage: true })

  // A selected-only submission records partial scope and the unselected asset.
  await page.getByRole('button', { name: '扫描资产', exact: true }).click()
  await page.locator('tbody input[type=checkbox]').first().check()
  await page.getByRole('button', { name: '扫描选中项（1）', exact: true }).click()
  await dialog.locator('.form-grid select').nth(1).selectOption('LOCAL_OWASP')
  const selectedPromise = page.waitForResponse(
    (r) =>
      r.url().endsWith(`/business-projects/${projectId}/rounds`) && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '提交扫描', exact: true }).click()
  const selectedRound = (await (await selectedPromise).json()).data
  assert.equal(selectedRound.fullScope, false)
  assert.equal(selectedRound.items.length, 1)
  assert.equal(selectedRound.unselectedAssets.length, 1)
  await dialog.waitFor({ state: 'hidden' })

  await page.getByRole('button', { name: '整改台账', exact: true }).click()
  await page.locator('.ledger-filters').waitFor()
  await page.getByRole('button', { name: '整改报告', exact: true }).click()
  await page.getByRole('button', { name: '导出 Word', exact: true }).waitFor()
  for (let n = 0; n < 40; n++) {
    const r = await api(
      `/business-projects/${projectId}/rounds/${selectedRound.id}`,
      undefined,
      'GET',
    )
    if (r.status !== 'RUNNING') break
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '整改报告', exact: true }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Word', exact: true }).click()
  const download = await downloadPromise
  assert.ok(download.suggestedFilename().endsWith('.docx'))
  await download.saveAs(`${output}/project-remediation.docx`)
  await page.screenshot({ path: `${output}/project-report.png`, fullPage: true })

  await page.goto(`${base}/findings?businessProjectId=${projectId}`, { waitUntil: 'networkidle' })
  assert.equal(await page.locator('#ledger-business-project').inputValue(), String(projectId))
  await page.locator('.ledger-filters').waitFor()
  await page.goto(`${base}/reports`, { waitUntil: 'networkidle' })
  await page.locator('#report-project').selectOption(String(projectId))
  await page.getByRole('button', { name: '查看项目报告', exact: true }).click()
  await page.getByRole('button', { name: '导出 PDF', exact: true }).waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: `${output}/project-mobile.png`, fullPage: true })
  assert.deepEqual(errors, [])
  console.log(
    JSON.stringify({
      success: true,
      projectId,
      rounds: [round.id, selectedRound.id],
      screenshots: output,
    }),
  )
} finally {
  await browser.close()
}
