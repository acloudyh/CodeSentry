import { chromium } from 'playwright'

const routes = [
  '/',
  '/projects',
  '/scans/new',
  '/scans',
  '/scans/4',
  '/findings',
  '/rules',
  '/reports',
  '/settings',
]
const viewports = [
  { width: 1763, height: 995 },
  { width: 1536, height: 864 },
  { width: 1440, height: 900 },
  { width: 1366, height: 768 },
  { width: 1280, height: 820 },
  { width: 1024, height: 768 },
]

const baseUrl = process.env.CODESENTRY_WEB_URL ?? 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const failures = []

for (const viewport of viewports) {
  const page = await browser.newPage({ viewport })
  for (const route of routes) {
    await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(250)
    const result = await page.evaluate(() => {
      const width = document.documentElement.clientWidth
      const scrollWidth = document.documentElement.scrollWidth
      const offenders = [...document.body.querySelectorAll('*')]
        .filter((element) => !element.closest('.table-scroll, .log-box'))
        .filter((element) => {
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden'
        })
        .map((element) => {
          const rect = element.getBoundingClientRect()
          return {
            tag: element.tagName.toLowerCase(),
            className: String(element.getAttribute('class') ?? ''),
            text: String(element.textContent ?? '')
              .trim()
              .slice(0, 80),
            right: Math.ceil(rect.right),
            left: Math.floor(rect.left),
          }
        })
        .filter((item) => item.right > width + 1 || item.left < -1)
        .slice(0, 8)
      const clippedContainers = [
        ...document.body.querySelectorAll(
          '.app-frame, .app-stage, .content-stage, .page-stack, .section-heading, .panel-pad, form',
        ),
      ]
        .map((element) => {
          const style = getComputedStyle(element)
          return {
            tag: element.tagName.toLowerCase(),
            className: String(element.getAttribute('class') ?? ''),
            overflowX: style.overflowX,
            clientWidth: element.clientWidth,
            scrollWidth: element.scrollWidth,
          }
        })
        .filter(
          (item) =>
            ['hidden', 'clip'].includes(item.overflowX) && item.scrollWidth > item.clientWidth + 1,
        )
        .slice(0, 8)
      const clippedControls = [
        ...document.body.querySelectorAll('button, a, input, select, textarea'),
      ]
        .filter((element) => !element.closest('.table-scroll, .log-box'))
        .filter((element) => {
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden'
        })
        .map((element) => {
          const rect = element.getBoundingClientRect()
          let ancestor = element.parentElement
          while (ancestor) {
            const style = getComputedStyle(ancestor)
            if (['hidden', 'clip'].includes(style.overflowX)) {
              const parentRect = ancestor.getBoundingClientRect()
              if (rect.left < parentRect.left - 1 || rect.right > parentRect.right + 1) {
                return {
                  tag: element.tagName.toLowerCase(),
                  text: String(element.textContent ?? '')
                    .trim()
                    .slice(0, 60),
                  ancestor: String(ancestor.getAttribute('class') ?? ''),
                }
              }
            }
            ancestor = ancestor.parentElement
          }
          return null
        })
        .filter(Boolean)
        .slice(0, 8)
      return { width, scrollWidth, offenders, clippedContainers, clippedControls }
    })
    if (
      result.scrollWidth > result.width + 1 ||
      result.offenders.length ||
      result.clippedContainers.length ||
      result.clippedControls.length
    ) {
      failures.push({ route, viewport, ...result })
    }
  }
  await page.close()
}

await browser.close()

if (failures.length) {
  console.error(JSON.stringify(failures, null, 2))
  process.exit(1)
}

console.log(`UI smoke passed: ${routes.length} routes x ${viewports.length} viewports`)
