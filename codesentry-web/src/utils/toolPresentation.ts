/** Presentation only: preserve API identifiers, statuses and original source evidence. */
export function toolText(text: string): string {
  return text
    .replace(/OpenAI|DeepSeek(?:[- ]?v[0-9]+[- ]?(?:pro|chat)?)?/gi, '工具服务')
    .replace(/\bAI\b/gi, '工具')
    .replace(/双轮|两轮/g, '工具')
    .replace(/工具\s+工具/g, '工具')
}

const presentationFields = new Set([
  'reason',
  'errorMessage',
  'triageComment',
  'comment',
  'reviewer',
])
export function presentToolData<T>(data: T): T {
  if (Array.isArray(data)) return data.map(presentToolData) as T
  if (data !== null && typeof data === 'object') {
    return Object.fromEntries(
      Object.entries(data).map(([key, value]) => [
        key,
        presentationFields.has(key) && typeof value === 'string'
          ? toolText(value)
          : presentToolData(value),
      ]),
    ) as T
  }
  return data
}
