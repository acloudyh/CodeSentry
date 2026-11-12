import type { ApplicationType, ScanProject, FindingView } from '@/types'

const statuses: Record<string, string> = {
  PENDING: '等待执行', PREPARING: '准备中', RUNNING: '执行中', COMPLETED: '已完成',
  PARTIAL: '部分完成', FAILED: '执行失败', CANCELLED: '已停止', SKIPPED: '已跳过',
  NEW: '新增', EXISTING: '存量', FIXED: '已修复', REOPENED: '重新出现',
  CRITICAL: '严重', HIGH: '高危', MEDIUM: '中危', LOW: '低危', INFO: '提示',
  CONFIRMED: '确认风险', FALSE_POSITIVE: '误报', ACCEPTED_RISK: '接受风险', FIXED_PENDING: '已整改待验证',
}

export const statusLabel = (value?: string) => statuses[value ?? ''] ?? value ?? '未知'
export const ruleProfileLabel = (value?: string) => ({
  LOCAL_OWASP: '本地 OWASP / CWE 安全基线', COMMUNITY_GENERAL: 'Semgrep 社区通用规则',
  COMPREHENSIVE: '综合深度审计',
} as Record<string, string>)[value ?? ''] ?? value ?? '未指定'
export const categoryLabel = (value?: string) => ({
  security: '安全风险', correctness: '代码正确性', maintainability: '可维护性',
  secret: '敏感凭据', secrets: '敏感凭据', vulnerability: '依赖漏洞',
  misconfiguration: '配置风险', code_standard: '代码规范',
} as Record<string, string>)[String(value).toLowerCase()] ?? value ?? '未分类'

export const standardLabel = (value: string) => ({
  'OWASP Top 10': 'OWASP 十大安全风险', OWASP: 'OWASP 安全规范', CWE: 'CWE 常见弱点分类',
  CERT: 'CERT 安全编码规范', 'CERT Java': 'CERT Java 安全编码规范',
  'Semgrep Registry': 'Semgrep 社区规则库',
  'Alibaba Java Coding Guidelines': '阿里巴巴 Java 开发规范',
} as Record<string, string>)[value] ?? value

export const scannerLabel = (value: string) => ({
  Semgrep: 'Semgrep（代码规则）', Gitleaks: 'Gitleaks（凭据泄露）',
  Trivy: 'Trivy（配置与密钥）', 'Trivy Secret': 'Trivy（密钥检测）',
  'Alibaba-P3C': '阿里巴巴 P3C（Java 代码规范）',
} as Record<string, string>)[value] ?? value

export function findingTitle(item: FindingView) {
  const rule = item.ruleId.toLowerCase()
  if (rule.includes('generic-api-key')) return '疑似通用 API 密钥泄露'
  if (rule.includes('cookie-missing-httponly')) return 'Cookie 缺少 HttpOnly 属性'
  if (rule.includes('cookie-missing-secure') || rule.includes('cookie-issecure-false')) return 'Cookie 缺少 Secure 安全属性'
  if (rule.includes('tainted-file-path')) return '用户输入可能控制文件路径'
  if (rule.includes('use-of-md5') || rule.includes('weak-message-digest')) return '使用不安全的消息摘要算法'
  if (rule.includes('insecure-hostname-verifier')) return '不安全的 TLS 主机名校验器'
  if (rule.includes('disallow-old-tls')) return 'TLS 配置允许过时协议版本'
  if (rule.includes('detect-non-literal-regexp')) return '动态正则表达式可能导致拒绝服务'
  if (rule.includes('sql-concatenation')) return 'SQL 字符串拼接可能导致注入'
  if (rule.includes('command-execution')) return '系统命令执行需要人工确认输入边界'
  return item.title
}

export const applicationTypeLabel = (type?: ApplicationType) => ({ FRONTEND: '前端', BACKEND: '后端', FULL_STACK: '前后端混合' })[type ?? 'BACKEND']
export function runtimeVersionLabel(project: Pick<ScanProject, 'applicationType' | 'targetJavaVersion' | 'targetNodeVersion'>) {
  const java = project.targetJavaVersion ? `Java ${project.targetJavaVersion}` : 'Java 版本未确认'
  const node = project.targetNodeVersion ? `Node.js ${project.targetNodeVersion}` : 'Node.js 版本未确认'
  return project.applicationType === 'FRONTEND' ? node : project.applicationType === 'FULL_STACK' ? `${java} / ${node}` : java
}

export const repositorySyncLabel = (asset: { lastSyncStatus?: string }) => ({ PENDING: '等待拉取', RUNNING: '拉取中', COMPLETED: '已同步', FAILED: '拉取失败' }[asset.lastSyncStatus ?? ''] ?? '尚未拉取')
export const repositorySyncBusy = (asset: { lastSyncStatus?: string }) => ['PENDING', 'RUNNING'].includes(asset.lastSyncStatus ?? '')
export const repositoryReady = (asset: { sourceType: string; managedPath?: string; lastSyncStatus?: string }) => asset.sourceType !== 'GIT' || (!!asset.managedPath && !repositorySyncBusy(asset))

export const projectRoundLabel = (round: { id: number; assetHistory?: boolean }) => round.assetHistory ? `资产历史扫描 #${-round.id}` : `项目扫描 #P${round.id}`
export const projectRoundScope = (round: { assetHistory?: boolean; fullScope: boolean }) => round.assetHistory ? '单次资产历史扫描' : round.fullScope ? '全部资产扫描' : '部分资产扫描'
