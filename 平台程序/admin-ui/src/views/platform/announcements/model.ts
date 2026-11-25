import type {
  AnnouncementInput,
  AnnouncementOptions,
  AnnouncementRow
} from '@/api/platform/announcements'

export const statusLabels = { DRAFT: '草稿', PUBLISHED: '已发布', WITHDRAWN: '已撤回' }

export const newAnnouncement = (): AnnouncementInput => ({
  title: '',
  summary: '',
  content: '',
  visibility: 'ALL',
  brandIds: [],
  pinned: false
})

export const announcementInput = (value: AnnouncementInput): AnnouncementInput => ({
  title: value.title.trim(),
  summary: value.summary.trim(),
  content: value.content,
  visibility: value.visibility,
  brandIds: value.visibility === 'ALL' ? [] : [...value.brandIds],
  pinned: value.pinned
})

// Web Crypto getRandomValues is available on supported intranet HTTP browsers too.
export const createRequestId = (): string => {
  const secure = globalThis.crypto
  if (typeof secure?.randomUUID === 'function') return secure.randomUUID()
  if (typeof secure?.getRandomValues !== 'function') throw new Error('无法安全生成请求编号')
  const bytes = secure.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20)
  ].join('-')
}

export const inputError = (value: AnnouncementInput): string => {
  if (!value.title.trim() || value.title.trim().length > 100) return '请填写 100 字以内的公告标题'
  if (value.summary.trim().length > 200) return '公告摘要最多 200 字'
  if (!value.content.trim()) return '请填写公告正文'
  if (value.content.length > 20000) return '公告正文最多 20000 字'
  if (value.visibility === 'BRANDS' && !value.brandIds.length) return '请选择至少一个可见品牌'
  return ''
}

export const scopeLabel = (
  row: Pick<AnnouncementRow, 'visibility' | 'brandIds'>,
  brands: AnnouncementOptions['brands']
) =>
  row.visibility === 'ALL'
    ? '全部品牌'
    : row.brandIds
        .map((id) => brands.find((brand) => brand.id === id)?.name || '已停用品牌')
        .join('、')

export const publicationTime = (value?: string | null): string => {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).format(date)
}

export const errorStatus = (error: unknown): number => {
  const value = error as { code?: unknown; response?: { status?: number } } | undefined
  return Number(value?.code || value?.response?.status) || 0
}

export const errorMessage = (error: unknown, fallback: string): string => {
  if (errorStatus(error) === 409) return '公告状态或版本已更新，请刷新最新内容后再操作。'
  if (errorStatus(error) === 403) return '当前工作身份无权执行此操作，请切换工作身份或联系管理员。'
  if (errorStatus(error) === 404) return '公告不存在或已无权访问，请刷新列表。'
  return error instanceof Error && error.message !== 'error' ? error.message : fallback
}
