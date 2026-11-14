import { createRequire } from 'node:module'
import { readFileSync, readdirSync } from 'node:fs'
const require = createRequire(new URL('../codesentry-web/package.json', import.meta.url))
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const failures = []
function walk(path) {
  return readdirSync(path, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? walk(`${path}/${entry.name}`) : [`${path}/${entry.name}`],
  )
}
const config = JSON.parse(readFileSync('codesentry-web/tsconfig.json', 'utf8'))
if (config.compilerOptions.strict !== true) failures.push('[FE-01] TypeScript strict 必须启用')
for (const path of walk('codesentry-web/src').filter((path) => /\.(ts|vue)$/.test(path))) {
  const raw = readFileSync(path, 'utf8')
  const source = path.endsWith('.vue')
    ? (() => {
        const { descriptor } = parse(raw)
        return `${descriptor.script?.content ?? ''}\n${descriptor.scriptSetup?.content ?? ''}`
      })()
    : raw
  if (/\/\/\s*@ts-(ignore|nocheck)\b/.test(source))
    failures.push(`[FE-01] ${path} 禁止跳过类型检查`)
  const file = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  function check(node) {
    if (node.kind === ts.SyntaxKind.AnyKeyword) failures.push(`[FE-01] ${path} 存在 any`)
    ts.forEachChild(node, check)
  }
  check(file)
}
const configText = readFileSync('codesentry-server/src/main/resources/application.yml', 'utf8')
if (/\$\{CODESENTRY_DB_PASSWORD:[^}]+\}/.test(configText))
  failures.push('[SEC-02] 数据库密码不能有硬编码默认值')
if (!configText.includes('fail-on-unknown-properties: true'))
  failures.push('[API-11] 未启用请求未知字段拒绝')
if (!readFileSync('codesentry-server/pom.xml', 'utf8').includes('<java.version>21</java.version>'))
  failures.push('[SEC-07] 平台 Java 基线不一致')
if (failures.length) {
  failures.forEach((failure) => console.error(failure))
  process.exit(1)
}
console.log(
  '[standards] OK strict、any、密钥默认值和请求边界检查通过；不代表鉴权、全部历史契约或人工评审已通过。',
)
