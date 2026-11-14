import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
const titles =
  process.argv[2] === '--range'
    ? execFileSync(
        'git',
        ['log', '--no-merges', '--format=%s', `${process.argv[3] ?? 'origin/main'}..HEAD`],
        { encoding: 'utf8' },
      )
        .split('\n')
        .filter(Boolean)
    : [readFileSync(process.argv[2], 'utf8').split('\n')[0]]
for (const title of titles) {
  if (
    !/^(feat|fix|chore|docs|refactor|test|perf|ci|build|revert)(\([a-z0-9-]+\))?!?: .+/u.test(title)
  ) {
    console.error('提交格式：fix(api): 修复统一错误响应（使用英文类型前缀与中文描述）')
    process.exit(1)
  }
}
