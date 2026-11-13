/**
 * 客资表格纯函数（/fr/customers 客资列表 tab）：筛选归一化、状态标签、转让幂等键。
 */

export const CUSTOMER_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'REGISTERED', label: '已登记' },
  { value: 'FOLLOWING', label: '跟进中' }
]

const STATUS_LABEL: Record<string, string> = {
  REGISTERED: '已登记',
  FOLLOWING: '跟进中'
}

export function customerStatusLabel(status: string | null | undefined): string {
  const raw = (status || '').trim()
  if (!raw) return '-'
  return Object.prototype.hasOwnProperty.call(STATUS_LABEL, raw) ? STATUS_LABEL[raw] : raw
}

export function customerStatusTag(
  status: string | null | undefined
): 'success' | 'warning' | 'info' {
  if (status === 'REGISTERED') return 'success'
  if (status === 'FOLLOWING') return 'warning'
  return 'info'
}

export interface CustomerQueryInput {
  pageNo?: number
  pageSize?: number
  keyword?: string | null
  status?: string | null
  ownerUserId?: string | number | null
}

export interface CustomerQuery {
  pageNo: number
  pageSize: number
  keyword?: string
  status?: string
  ownerUserId?: string | number
}

/** 服务端分页边界 1..100；空筛选不下发（避免 undefined/空串混入查询）。 */
export function normalizeCustomerQuery(input: CustomerQueryInput): CustomerQuery {
  const pageNo =
    Number.isFinite(input.pageNo) && (input.pageNo as number) >= 1
      ? Math.trunc(input.pageNo as number)
      : 1
  const rawSize = Number.isFinite(input.pageSize) ? Math.trunc(input.pageSize as number) : 20
  const pageSize = Math.min(Math.max(rawSize, 1), 100)
  const query: CustomerQuery = { pageNo, pageSize }
  const keyword = (input.keyword || '').trim()
  if (keyword) query.keyword = keyword
  const status = (input.status || '').trim()
  if (status) query.status = status
  if (
    input.ownerUserId !== null &&
    input.ownerUserId !== undefined &&
    `${input.ownerUserId}` !== ''
  ) {
    query.ownerUserId = input.ownerUserId
  }
  return query
}

/** 转让幂等键：客户 + 版本（重试同一版本复用，版本变化自然新键）。 */
export function transferIdempotencyKey(customerId: string | number, revision: number): string {
  return `customer-transfer:${customerId}:${revision}`
}
