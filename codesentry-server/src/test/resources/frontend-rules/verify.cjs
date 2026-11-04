// Runs the installed Semgrep against positive and negative fixtures without Registry access.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const root = path.resolve(__dirname, '../../../../..');
const rule = path.join(root, 'codesentry-server/src/main/resources/rules/codesentry-frontend.yml');
const targets = fs.readdirSync(__dirname).filter(f => /\.(js|ts|jsx|tsx|vue)$/.test(f));
const expected = new Set();
let safeCases = 0;
for (const file of targets) {
  const lines = fs.readFileSync(path.join(__dirname, file), 'utf8').split('\n');
  lines.forEach((line, index) => {
    const annotation = line.match(/\b(ruleid|ok):\s*([\w.-]+)/);
    if (!annotation) return;
    if (annotation[1] === 'ruleid') expected.add(`${file}:${index + 2}:${annotation[2]}`);
    else safeCases++;
  });
}
const output = execFileSync(process.env.SEMGREP_EXECUTABLE || 'semgrep', [
  'scan', '--config', rule, '--json', '--no-git-ignore', '--no-rewrite-rule-ids',
  '--metrics', 'off', '--strict', ...targets.map(f => path.join(__dirname, f))
], { cwd: root, env: { ...process.env, SEMGREP_ENABLE_VERSION_CHECK: '0' }, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
const report = JSON.parse(output);
if (report.errors.length) throw new Error(JSON.stringify(report.errors));
const actual = new Set(report.results.map(r => `${path.basename(r.path)}:${r.start.line}:${r.check_id}`));
const missing = [...expected].filter(x => !actual.has(x));
const extra = [...actual].filter(x => !expected.has(x));
if (missing.length || extra.length) throw new Error(JSON.stringify({ missing, extra }, null, 2));
console.log(`前端规则验证通过：${expected.size} 个风险样例、${safeCases} 个安全样例，覆盖 ${targets.length} 种文件。`);
