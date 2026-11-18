import request from '@/config/axios'

export type Int64 = string | number

export interface CompanyPool {
  orgId: string
  name: string
  status: string | null
  simulatedReceiptFen?: string
  simulatedPoints?: string
  balance: Int64 | null
  inFlight: Int64
}

export interface DirectCreditReceipt {
  id: string
  beforeBalance: string
  afterBalance: string
  credits: string
}
export const addCompanyCredits = (
  orgId: string,
  data: { credits: number; reason: string; requestKey: string }
): Promise<DirectCreditReceipt> =>
  request.post({ url: `/platform/org-points/${encodeURIComponent(orgId)}/credits`, data })

export interface PoolSummary {
  orgId: string
  balance: Int64
  inFlight: Int64
  status: string
  month: string
  monthUsedTotal: Int64
}

export interface PoolLedgerEntry {
  id: string
  operationId: string | null
  type: string
  subject: string
  actorUserId: string | null
  availableDelta: Int64
  billingMonth: string | null
  refType: string | null
  refId: string | null
  note: string | null
  createTime: string
}

export interface Adjustment {
  id: string
  orgId: string
  delta: Int64
  reason: string
  status: 'SUBMITTED' | 'EXECUTED' | 'REJECTED'
  makerId: string
  checkerId: string | null
  checkerComment: string | null
  resultBalance: Int64 | null
}

export interface SubjectPrice {
  subject: string
  label: string
  unit: string
  priceMicros: Int64
  minCharge: Int64
  versionText: string
  configured: boolean
}

export const getCompanyPools = (): Promise<CompanyPool[]> =>
  request.get({ url: '/platform/org-points/companies' })

export const getCompanyPoolSummary = (orgId: string): Promise<PoolSummary> =>
  request.get({ url: `/platform/org-points/${encodeURIComponent(orgId)}/summary` })

export const getCompanyPoolLedger = (
  orgId: string,
  params: { page: number; size: number }
): Promise<PoolLedgerEntry[]> =>
  request.get({ url: `/platform/org-points/${encodeURIComponent(orgId)}/ledger`, params })

export const getCompanyAdjustments = (orgId: string): Promise<Adjustment[]> =>
  request.get({ url: `/platform/org-points/${encodeURIComponent(orgId)}/adjustments` })

export const submitCompanyAdjustment = (
  orgId: string,
  data: { delta: number; reason: string; requestKey: string }
): Promise<string> =>
  request.post({ url: `/platform/org-points/${encodeURIComponent(orgId)}/adjustments`, data })

export const reviewCompanyAdjustment = (
  id: string,
  data: { approve: boolean; comment: string }
): Promise<number | null> =>
  request.put({
    url: `/platform/org-points/adjustments/${encodeURIComponent(id)}/review`,
    data
  })

export const getSubjectPrices = (): Promise<SubjectPrice[]> =>
  request.get({ url: '/platform/org-points/prices' })

export const publishSubjectPrice = (data: {
  subject: string
  priceMicros: number
  minCharge: number
  reason: string
  requestKey: string
}): Promise<string> => request.post({ url: '/platform/org-points/prices', data })
