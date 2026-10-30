const SHANGHAI_TIME_ZONE = 'Asia/Shanghai'
const EXPLICIT_ZONE = /(Z|[+-]\d{2}:?\d{2})$/i

function parseBackendTime(value: string): Date {
  const normalized = EXPLICIT_ZONE.test(value) ? value : `${value}+08:00`
  return new Date(normalized)
}

export function formatChinaTime(value?: string): string {
  if (!value) return '-'
  const date = parseBackendTime(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { hour12: false, timeZone: SHANGHAI_TIME_ZONE })
}

export function formatChinaDate(value: string | Date = new Date()): string {
  const date = typeof value === 'string' ? parseBackendTime(value) : value
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleDateString('zh-CN', { timeZone: SHANGHAI_TIME_ZONE })
}
