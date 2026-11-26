import request from '@/config/axios'
import type { PlatformId } from '@/utils/platformIdentity'

/** 八步流程摘要（服务端内联在客资行上）；status 为 ACTIVE / PAUSED / LOST / COMPLETED。 */
export interface CustomerWorkflowSummary {
  status?: string | null
  completedCount?: number | null
  totalSteps?: number | null
  currentStepName?: string | null
  progressPercent?: string | number | null
  statusReason?: string | null
}

/** 客资表格行（GET /franchise/customers，含负责人姓名映射）。 */
export interface CustomerRow {
  id: PlatformId
  name: string
  phone: string
  wechat?: string | null
  source: string
  province?: string | null
  city?: string | null
  summary?: string | null
  status: string
  ownerUserId: PlatformId
  ownerName?: string
  nextVisitDate?: string | null
  lastFollowAt?: string | null
  workflow?: CustomerWorkflowSummary | null
  revision: number
  createTime?: string
}

export interface CustomerPageQuery {
  pageNo: number
  pageSize: number
  keyword?: string
  status?: string
  ownerUserId?: PlatformId
}

export interface CustomerPage {
  list: CustomerRow[]
  total: number
}

export interface TransferCandidate {
  userId: PlatformId
  name: string
  roleCode: string
  teamId?: PlatformId | null
  teamName?: string
}

export const getCustomers = (params: CustomerPageQuery): Promise<CustomerPage> =>
  request.get({ url: '/franchise/customers', params })

export interface OwnerOption {
  userId: PlatformId
  name?: string
}

export const getOwnerOptions = (): Promise<OwnerOption[]> =>
  request.get({ url: '/franchise/customers/owner-options' })

export const getTransferCandidates = (customerId: PlatformId): Promise<TransferCandidate[]> =>
  request.get({ url: '/franchise/customers/transfer-candidates', params: { customerId } })

export const transferCustomer = (
  id: PlatformId,
  data: { targetUserId: PlatformId; reason: string; revision: number },
  idempotencyKey: string
) =>
  request.post({
    url: `/franchise/customers/${id}/transfer`,
    data,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
