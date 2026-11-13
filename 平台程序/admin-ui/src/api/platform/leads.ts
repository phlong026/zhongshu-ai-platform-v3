import request from '@/config/axios'
import type { PlatformId } from '@/utils/platformIdentity'

/** 待关联客资（GET /franchise/leads/pending，与小程序同源）。 */
export interface PendingLead {
  id: PlatformId
  name: string
  phone: string
  need: string
  source: string
  status: string
  conversion?: string | null
  customerId?: PlatformId | null
  registrarUserId?: PlatformId | null
  note?: string | null
  revision: number
  createTime?: string
}

export interface LeadImportRowPayload {
  line: number
  name: string
  phone: string
  need: string
  source: string
}

export interface LeadImportResultRow extends LeadImportRowPayload {
  status: string
  code?: number | null
  message?: string | null
  leadId?: PlatformId | null
  customerId?: PlatformId | null
  note?: string | null
  action?: string | null
}

export interface LeadImportResult {
  orgId: PlatformId
  total: number
  created: number
  skipped: number
  failed: number
  rows: LeadImportResultRow[]
}

export const getPendingLeads = (): Promise<PendingLead[]> =>
  request.get({ url: '/franchise/leads/pending' })

export const linkLead = (id: PlatformId, revision: number, idempotencyKey: string) =>
  request.post({
    url: `/franchise/leads/${id}/link`,
    data: { revision },
    headers: { 'Idempotency-Key': idempotencyKey }
  })

export const invalidateLead = (
  id: PlatformId,
  note: string,
  revision: number,
  idempotencyKey: string
) =>
  request.post({
    url: `/franchise/leads/${id}/invalid`,
    data: { note, revision },
    headers: { 'Idempotency-Key': idempotencyKey }
  })

export const precheckLeadImport = (data: {
  orgId: PlatformId
  batchId: string
  rows: LeadImportRowPayload[]
}): Promise<LeadImportResult> =>
  request.post<LeadImportResult>({ url: '/franchise/lead-import/precheck', data })

export const submitLeadImport = (data: {
  orgId: PlatformId
  batchId: string
  rows: LeadImportRowPayload[]
}): Promise<LeadImportResult> =>
  request.post<LeadImportResult>({ url: '/franchise/lead-import', data })
