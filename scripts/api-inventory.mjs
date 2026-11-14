import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? walk(`${directory}/${entry.name}`) : [`${directory}/${entry.name}`],
  )
}
function operations(source, file) {
  const classStart = source.indexOf('public class ')
  const matcher =
    /@(?:\w+\.)*(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\b/g
  let base = '',
    match
  const result = []
  while ((match = matcher.exec(source))) {
    let at = matcher.lastIndex
    while (/\s/.test(source[at] ?? '') && at < source.length) at++
    let args = ''
    if (source[at] === '(') {
      let depth = 1,
        quote = false,
        escaped = false
      const start = ++at
      while (depth && at < source.length) {
        const char = source[at++]
        if (escaped) {
          escaped = false
          continue
        }
        if (char === '\\' && quote) {
          escaped = true
          continue
        }
        if (char === '"') quote = !quote
        if (!quote) {
          if (char === '(') depth++
          if (char === ')') depth--
        }
      }
      args = source.slice(start, at - 1)
      matcher.lastIndex = at
    }
    const path = args.match(/"(\/[^"\n]*)"/)?.[1] ?? ''
    if (match.index < classStart) {
      if (match[1] === 'RequestMapping') base = path
      continue
    }
    const fullPath = base + path
    if (!fullPath.startsWith('/api/')) continue
    const method = match[1].replace('Mapping', '').toUpperCase()
    if (method === 'REQUEST') throw new Error(`${file}: API 需要显式声明 HTTP 方法`)
    result.push({ method, path: fullPath, controller: file })
  }
  return result
}
const files = walk('codesentry-server/src/main/java').filter((file) =>
  file.endsWith('Controller.java'),
)
const entries = files
  .flatMap((file) => operations(readFileSync(file, 'utf8'), file))
  .sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`))
const keys = entries.map((entry) => `${entry.method} ${entry.path}`)
if (new Set(keys).size !== keys.length) throw new Error('发现重复接口映射')
const json =
  JSON.stringify(
    {
      schemaVersion: 1,
      description: '全量实现清单；不是全部接口已经满足 OpenAPI/分页/DTO 规范的声明。',
      operations: entries,
    },
    null,
    2,
  ) + '\n'
const target = 'docs/standards/api-inventory.json'
if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== json)
    throw new Error('API 清单有漂移，请运行 npm run generate:inventory')
  const ref = process.env.CONTRACT_BASE_REF ?? 'origin/main'
  const before = new Set(
    files.flatMap((file) => {
      try {
        return operations(
          execFileSync('git', ['show', `${ref}:${file}`], {
            encoding: 'utf8',
            stdio: ['ignore', 'pipe', 'ignore'],
          }),
          file,
        ).map((entry) => `${entry.method} ${entry.path}`)
      } catch {
        return []
      }
    }),
  )
  const spec = JSON.parse(readFileSync('openapi/openapi.json', 'utf8'))
  for (const entry of entries.filter((entry) => !before.has(`${entry.method} ${entry.path}`))) {
    if (!spec.paths[entry.path.replace(/^\/api/, '')]?.[entry.method.toLowerCase()])
      throw new Error(`[API-02] 新接口没有契约: ${entry.method} ${entry.path}`)
  }
  console.log(
    `[inventory] OK ${entries.length} 个接口已盘点，新接口均已纳入契约；历史完整类型/分页迁移仍需评审。`,
  )
} else {
  writeFileSync(target, json)
  const rows = entries
    .map(
      (entry) => `| ${entry.method} | \`${entry.path}\` | ${entry.controller.split('/').pop()} |`,
    )
    .join('\n')
  writeFileSync(
    'docs/standards/api-inventory.md',
    `# CodeSentry 接口实现清单\n\n共 ${entries.length} 个 HTTP 操作，由 Controller 静态映射生成。旧版 v1 完整 schema、分页与领域 DTO 迁移状态见整改记录；本清单不把历史差距标为已通过。\n\n| 方法 | 路径 | Controller |\n| --- | --- | --- |\n${rows}\n`,
  )
  console.log(`[inventory] generated ${entries.length} operations`)
}
