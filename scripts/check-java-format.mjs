import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const pom = fileURLToPath(new URL('../codesentry-server/pom.xml', import.meta.url))
const result = spawnSync('mvn', ['-q', '-f', pom, 'spotless:check'], { stdio: 'inherit' })
if (result.error) console.error('Java 检查需要 JDK 21 和 Maven；请先配置 JAVA_HOME。')
process.exit(result.status ?? 1)
