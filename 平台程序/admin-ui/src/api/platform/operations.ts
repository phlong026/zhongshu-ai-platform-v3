import request from '@/config/axios'

export interface OperationsQuery {
  pageNo: number
  pageSize: number
  orgId?: string
}
export interface OperationsPage<T> {
  list: T[]
  total: number
}
export interface WalletRow {
  id: string
  orgId: string
  status: string
  spendingFrozen: boolean
  availablePoints: number
  reservedPoints: number
}
export interface PlanRow {
  id: string
  name: string
  priceFen: number
  basePoints: number
  bonusPoints: number
  version: number
  status: string
  sort: number
  wxProductId: string | null
}
export interface OrderRow {
  id: string
  orgId: string
  walletId: string
  orderNo: string
  status: string
  creditState: string
  priceFen: number
  basePoints: number
  bonusPoints: number
  createTime: string
}
export interface LedgerRow {
  id: string
  orgId: string
  walletId: string
  actorId: string
  type: string
  delta: number
  availableAfter: number
  reservedAfter: number
  bizType: string
  bizId: string
  reason: string
  createTime: string
}
export interface AnomalyRow {
  id: string
  orgId: string
  walletId: string
  orderId: string
  anomalyType: string
  detail: string
  createTime: string
}

export const getWallets = (params: OperationsQuery): Promise<OperationsPage<WalletRow>> =>
  request.get({ url: '/platform/ops/wallets', params })
export const getRechargePlans = (params: OperationsQuery): Promise<OperationsPage<PlanRow>> =>
  request.get({ url: '/platform/ops/recharge-plans', params })
export const getRechargeOrders = (params: OperationsQuery): Promise<OperationsPage<OrderRow>> =>
  request.get({ url: '/platform/ops/recharge-orders', params })
export const getLedger = (params: OperationsQuery): Promise<OperationsPage<LedgerRow>> =>
  request.get({ url: '/platform/ops/ledger', params })
export const getAnomalies = (params: OperationsQuery): Promise<OperationsPage<AnomalyRow>> =>
  request.get({ url: '/platform/ops/anomalies', params })

export interface PlanChange extends Omit<PlanRow, 'id' | 'version'> {
  expectedVersion: number
  reason: string
}
export interface DesignPriceRow {
  id: string
  version: number
  operation: string
  resolution: string
  unit: string
  promptPoints: number
  imagePoints: number
  usagePoints: number
  maxImageUnits: number
  active: boolean
}
export interface DesignPriceChange extends Omit<DesignPriceRow, 'id' | 'version' | 'unit'> {
  expectedVersion: number
  reason: string
}
export interface DesignCostRow {
  id: string
  orgId: string
  operation: string
  status: string
  priceVersion: number | null
  reservedPoints: number | null
  chargedPoints: number | null
  refundedPoints: number | null
  settlementVersion: number
  costState: 'UNAVAILABLE' | 'RECORDED'
  chargedCostMicros: number | null
  reservedCostMicros: number | null
  refundedCostMicros: number | null
  audited: boolean
  createTime: string
}
export interface AuditRow {
  id: string
  resourceType: string
  resourceId: string
  resourceVersion: number
  action: string
  actorId: string
  reason: string
  beforeJson: string
  afterJson: string
  createTime: string
}
export interface AnomalyReview {
  id: string
  anomalyId: string
  actorId: string
  note: string
  createTime: string
}
export const createRechargePlan = (data: PlanChange): Promise<PlanRow> =>
  request.post({ url: '/platform/ops/recharge-plans', data })
export const updateRechargePlan = (id: string, data: PlanChange): Promise<PlanRow> =>
  request.put({ url: `/platform/ops/recharge-plans/${encodeURIComponent(id)}`, data })
export const getDesignPrices = (params: OperationsQuery): Promise<OperationsPage<DesignPriceRow>> =>
  request.get({ url: '/platform/ops/design-prices', params })
export const getDesignCosts = (params: OperationsQuery): Promise<OperationsPage<DesignCostRow>> =>
  request.get({ url: '/platform/ops/design-costs', params })
export const createDesignPrice = (data: DesignPriceChange): Promise<DesignPriceRow> =>
  request.post({ url: '/platform/ops/design-prices', data })
export const updateDesignPrice = (id: string, data: DesignPriceChange): Promise<DesignPriceRow> =>
  request.put({ url: `/platform/ops/design-prices/${encodeURIComponent(id)}`, data })
export const getAudit = (params: OperationsQuery): Promise<OperationsPage<AuditRow>> =>
  request.get({ url: '/platform/ops/audit', params })
export const reviewAnomaly = (id: string, note: string): Promise<string> =>
  request.post({ url: `/platform/ops/anomalies/${encodeURIComponent(id)}/reviews`, data: { note } })
export const getAnomalyReviews = (
  id: string,
  params: OperationsQuery
): Promise<OperationsPage<AnomalyReview>> =>
  request.get({ url: `/platform/ops/anomalies/${encodeURIComponent(id)}/reviews`, params })

export interface WalletSummary {
  walletCount: number
  totalAvailablePoints: number
  topWallets: WalletRow[]
}
export const getWalletSummary = (): Promise<WalletSummary> =>
  request.get({ url: '/platform/ops/wallet-summary' })
export const getActiveDesignPrices = (
  params: OperationsQuery
): Promise<OperationsPage<DesignPriceRow>> =>
  request.get({ url: '/platform/ops/design-prices/active', params })
