import assert from 'node:assert/strict'
const base = process.env.CODESENTRY_WEB_URL ?? 'https://127.0.0.1:5175'
// 真实代理联调：无浏览器路由 mock，校验 TLS 终止后的 Host / Origin 端口仍一致。
const same = await fetch(`${base}/api/v1/dashboard/summary`, { headers: { Origin: base } })
assert.equal(same.status, 200)
assert.equal((await same.json()).success, true)
const rejected = await fetch(`${base}/api/v1/business-projects`, {
  method: 'POST',
  headers: { Origin: 'https://outside.invalid', 'Content-Type': 'application/json' },
  body: JSON.stringify({ name: 'must-not-create' }),
})
assert.equal(rejected.status, 403)
assert.equal((await rejected.json()).code, 'CROSS_ORIGIN_FORBIDDEN')
console.log('真实 HTTPS 代理同源允许、跨站写入拒绝检查通过')
