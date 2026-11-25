import request from '@/config/axios'

export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'
export type AnnouncementVisibility = 'ALL' | 'BRANDS'

export interface AnnouncementInput {
  title: string
  summary: string
  content: string
  visibility: AnnouncementVisibility
  brandIds: string[]
  pinned: boolean
}

export interface AnnouncementRow extends Omit<AnnouncementInput, 'content'> {
  id: string
  status: AnnouncementStatus
  revision: number
  publishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface AnnouncementDetail extends AnnouncementRow {
  content: string
}

export interface AnnouncementOptions {
  canWrite: boolean
  brands: { id: string; name: string }[]
}

export interface AnnouncementQuery {
  pageNo: number
  pageSize: number
  keyword: string
  status: AnnouncementStatus | ''
  brandId: string
}

const base = '/platform/announcements/admin'
// The page handles errors inline, including a stale revision. Keep business codes intact.
const errors = { silentError: true, preserveBusinessError: true }

export const announcementOptions = (): Promise<AnnouncementOptions> =>
  request.get({ url: `${base}/options`, ...errors })

export const listAnnouncements = (
  params: AnnouncementQuery
): Promise<{ list: AnnouncementRow[]; total: number }> =>
  request.get({ url: base, params, ...errors })

export const getAnnouncement = (id: string): Promise<AnnouncementDetail> =>
  request.get({ url: `${base}/${encodeURIComponent(id)}`, ...errors })

export const saveAnnouncement = (
  input: AnnouncementInput,
  existing?: { id: string; revision: number },
  createRequestId?: string
): Promise<AnnouncementDetail> =>
  existing
    ? request.put({
        url: `${base}/${encodeURIComponent(existing.id)}`,
        data: { ...input, expectedRevision: existing.revision },
        ...errors
      })
    : request.post({ url: base, data: { ...input, createRequestId }, ...errors })

export const changeAnnouncementStatus = (
  id: string,
  action: 'publish' | 'withdraw',
  expectedRevision: number
): Promise<AnnouncementDetail> =>
  request.post({
    url: `${base}/${encodeURIComponent(id)}/${action}`,
    data: { expectedRevision },
    ...errors
  })
