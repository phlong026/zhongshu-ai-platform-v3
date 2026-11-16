// 兼容后台 JSON 中的毫秒时间戳（number/string）和 ISO 时间。
export function adminDateTime(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  const raw = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value
  if (typeof raw !== 'number' && typeof raw !== 'string') return String(value)
  const date = new Date(raw)
  return Number.isNaN(date.getTime())
    ? String(value)
    : date.toLocaleString('zh-CN', { hour12: false })
}
