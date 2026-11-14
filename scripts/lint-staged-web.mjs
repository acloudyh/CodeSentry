import { execFileSync } from 'node:child_process'
import { relative, resolve } from 'node:path'
const cwd = resolve('codesentry-web')
const files = process.argv.slice(2).map((file) => relative(cwd, file))
execFileSync(process.execPath, ['node_modules/eslint/bin/eslint.js', '--fix', ...files], {
  cwd,
  stdio: 'inherit',
})
execFileSync(process.execPath, ['node_modules/prettier/bin/prettier.cjs', '--write', ...files], {
  cwd,
  stdio: 'inherit',
})
