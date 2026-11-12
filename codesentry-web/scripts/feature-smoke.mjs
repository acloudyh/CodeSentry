import { chromium } from 'playwright'

const baseUrl = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, acceptDownloads: true })

await page.goto(`${baseUrl}/scans/new`, { waitUntil: 'networkidle' })
await page.getByLabel('扫描规则集').waitFor()
const ruleOptions = await page.getByLabel('扫描规则集').locator('option').count()
if (ruleOptions < 3) throw new Error(`扫描规则集数量异常: ${ruleOptions}`)

await page.goto(`${baseUrl}/rules`, { waitUntil: 'networkidle' })
await page.getByRole('heading', { name: '扫描规则中心' }).waitFor()
if (await page.locator('.rule-card').count() < 3) throw new Error('规则中心未渲染完整规则集')

await page.goto(`${baseUrl}/scans`, { waitUntil: 'networkidle' })
const findingsLink = page.getByRole('link', { name: '查看问题台账' }).first()
if (await findingsLink.count()) {
  const href = await findingsLink.getAttribute('href')
  if (!href?.includes('projectId=') || !href.includes('runId=')) throw new Error(`问题台账跳转未携带项目和批次: ${href}`)
  await findingsLink.click()
  await page.waitForURL(/\/findings\?.*projectId=.*runId=/)
  const target = new URL(page.url())
  const selectedProjectId = await page.locator('.finding-filter-grid .field').nth(0).locator('select').inputValue()
  const selectedRunId = await page.locator('.finding-filter-grid .field').nth(1).locator('select').inputValue()
  if (selectedProjectId !== target.searchParams.get('projectId') || selectedRunId !== target.searchParams.get('runId')) {
    throw new Error(`问题台账未选中目标项目或批次: ${selectedProjectId}/${selectedRunId}`)
  }
} else {
  await page.goto(`${baseUrl}/findings`, { waitUntil: 'networkidle' })
}
const reviewButton = page.locator('.findings-table').getByRole('button', { name: /人工复核|更新复核/ }).first()
if (await reviewButton.count()) {
  const batchToolbar = page.locator('.batch-review-toolbar')
  const aiConfig = (await (await page.request.get(`${baseUrl}/api/v1/ai-reviews/config`)).json()).data
  const aiBatchButton = batchToolbar.getByRole('button', { name: '批量工具审核' })
  if (aiConfig.enabled) await aiBatchButton.waitFor()
  else if (await aiBatchButton.count()) throw new Error('未启用工具审核时不应显示批量工具审核按钮')
  await batchToolbar.getByRole('button', { name: '批量人工复核' }).waitFor()
  const titleClamp = await page.locator('.finding-title-clamp').first().evaluate((element) => getComputedStyle(element).webkitLineClamp)
  if (titleClamp !== '2') throw new Error(`问题摘要未限制为两行: ${titleClamp}`)
  const snippetTrigger = page.locator('.findings-table').getByRole('button', { name: /查看原始内容|查看文件说明/ }).first()
  if (await snippetTrigger.count()) {
    await snippetTrigger.click()
    await page.getByRole('dialog', { name: '问题原始内容查看器' }).waitFor()
    await page.locator('.code-viewer, .redacted-view, .source-empty').first().waitFor()
    await page.getByRole('button', { name: '关闭原始内容' }).click()
  }
  await reviewButton.click()
  await page.getByRole('dialog', { name: '人工复核' }).waitFor()
  await page.getByLabel('复核结论').waitFor()
  await page.getByLabel('复核人').waitFor()
}

await page.goto(`${baseUrl}/reports`, { waitUntil: 'networkidle' })
for (const format of ['HTML', 'DOCX', 'PDF']) {
  const exportButton = page.locator('.report-actions').first().getByRole('button', { name: format, exact: true })
  if (await exportButton.count()) {
    const downloadPromise = page.waitForEvent('download')
    await exportButton.click()
    const download = await downloadPromise
    if (!download.suggestedFilename().toLowerCase().endsWith(`.${format.toLowerCase()}`)) {
      throw new Error(`整改报告下载文件格式不是 ${format}`)
    }
    await page.waitForTimeout(1300)
  }
}

await browser.close()
console.log('Feature smoke passed: rules, scan-to-findings link, grouped batch actions, original finding content, manual triage, HTML/DOCX/PDF downloads')
