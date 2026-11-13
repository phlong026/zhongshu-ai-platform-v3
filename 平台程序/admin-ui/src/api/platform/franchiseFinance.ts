import request from '@/config/axios'
import { service } from '@/config/axios/service'

/**
 * 加盟商财务核对 API（/admin-api/franchise/finance）
 * 端点契约以批2/批3 后端为准：五表只读分页 + 月度对账 + CSV 导出。
 * 金额字段一律为「分」（cents），页面按 §5.2 以「分」展示，仅导出转元（§8.1）。
 */

export interface FinancePageQuery {
  pageNo: number
  pageSize: number
}

export interface FinancePage<T> {
  total: number
  list: T[]
}

/** 实收收款单（FinReceiptDO） */
export interface ReceiptRow {
  id: string
  companyId: string
  projectId: string | null
  customerId: string | null
  sourceType: string
  sourceRef: string | null
  name: string | null
  amountCents: number
  status: string
  receivedDate: string
  registeredAt: string
  registrarUserId: string | null
  method: string | null
  transactionNo: string | null
  voucherFileId: string | null
  revisionVersion: number
  voidReason: string | null
  voidByUserId: string | null
}

/** 付款单（FinPaymentDO） */
export interface PaymentRow {
  id: string
  payableId: string | null
  projectId: string | null
  amountCents: number
  pendingLinkCents: number | null
  linkStatus: string | null
  status: string
  payDate: string
  operatorUserId: string | null
  transactionNo: string | null
  voucherFileId: string | null
  voidByUserId: string | null
  voidReason: string | null
  voidedAt: string | null
}

/** 退款单（FinRefundDO，含更正链字段——仅退款具备） */
export interface RefundRow {
  id: string
  receiptId: string | null
  refundDirection: string
  paymentId: string | null
  amountCents: number
  status: string
  reason: string | null
  operatorUserId: string | null
  replacedById: string | null
  replacesId: string | null
  date: string
  transactionNo: string | null
  voucherFileId: string | null
  voidByUserId: string | null
  voidReason: string | null
  voidedAt: string | null
}

/** 应付单（FinPayableDO——无到期日/业务日期字段） */
export interface PayableRow {
  id: string
  projectId: string | null
  stage: string | null
  totalCents: number
  recipient: string | null
  status: string
  agreementRef: string | null
}

/** 异常资金（FinAbnormalFundDO） */
export interface AbnormalFundRow {
  id: string
  fundDirection: string | null
  amountCents: number
  counterparty: string | null
  relatedProjectId: string | null
  relatedCommissionId: string | null
  stageOrCategory: string | null
  businessDate: string | null
  method: string | null
  transactionNo: string | null
  reason: string | null
  voucherFileId: string | null
  status: string
  linkedType: string | null
  linkedId: string | null
  operatorUserId: string | null
}

/** 月度对账（MonthReconciliation 8 分量） */
export interface MonthReconciliation {
  month: string
  grossReceiptsCents: number
  clientRefundsCents: number
  netReceivedCents: number
  abnormalPendingCents: number
  abnormalPendingCount: number
  abnormalDatePendingCents: number
  abnormalDatePendingCount: number
}

export interface ReceiptQuery extends FinancePageQuery {
  status?: string
  receivedDateFrom?: string
  receivedDateTo?: string
  projectId?: string
  method?: string
  sourceType?: string
  keyword?: string
}

export interface PaymentQuery extends FinancePageQuery {
  status?: string
  payDateFrom?: string
  payDateTo?: string
  projectId?: string
  keyword?: string
}

export interface RefundQuery extends FinancePageQuery {
  status?: string
  dateFrom?: string
  dateTo?: string
  receiptId?: string
  refundDirection?: string
  keyword?: string
}

export interface PayableQuery extends FinancePageQuery {
  status?: string
  projectId?: string
  keyword?: string
}

export interface AbnormalFundQuery extends FinancePageQuery {
  status?: string
  datePendingOnly?: boolean
  businessDateFrom?: string
  businessDateTo?: string
  keyword?: string
}

export const getReceiptPage = (params: ReceiptQuery): Promise<FinancePage<ReceiptRow>> =>
  request.get({ url: '/franchise/finance/receipts/page', params })
export const getPaymentPage = (params: PaymentQuery): Promise<FinancePage<PaymentRow>> =>
  request.get({ url: '/franchise/finance/payments/page', params })
export const getRefundPage = (params: RefundQuery): Promise<FinancePage<RefundRow>> =>
  request.get({ url: '/franchise/finance/refunds/page', params })
export const getPayablePage = (params: PayableQuery): Promise<FinancePage<PayableRow>> =>
  request.get({ url: '/franchise/finance/payables/page', params })

/**
 * 异常资金分页。
 * L3（批2 遗留，硬性）：后端 datePendingOnly=true 时追加 businessDate IS NULL，
 * 若同时传区间将恒为空集（FinAbnormalFundMapper 实证 isNull + ge/le 并存）。
 * 因此在 API 封装层做防御性互斥：待归月模式下强制剔除区间参数。
 */
export const getAbnormalFundPage = (
  params: AbnormalFundQuery
): Promise<FinancePage<AbnormalFundRow>> => {
  const query: AbnormalFundQuery = { ...params }
  if (query.datePendingOnly) {
    delete query.businessDateFrom
    delete query.businessDateTo
  }
  return request.get({ url: '/franchise/finance/abnormal-funds/page', params: query })
}

export const getMonthReconciliation = (month: string): Promise<MonthReconciliation> =>
  request.get({ url: '/franchise/finance/reconciliation', params: { month } })

export interface FinanceExportQuery {
  table: 'receipts' | 'payments' | 'refunds' | 'payables' | 'reconciliation'
  month?: string
  status?: string
  projectId?: string
  keyword?: string
  receivedDateFrom?: string
  receivedDateTo?: string
  method?: string
  sourceType?: string
  payDateFrom?: string
  payDateTo?: string
  dateFrom?: string
  dateTo?: string
  receiptId?: string
  refundDirection?: string
}

/**
 * 导出当前筛选全部结果（CSV）。
 * 成功：返回 Blob（text/csv + attachment）；失败：HTTP 200 + CommonResult JSON，
 * 由 service 拦截器 blob 分支（data.type === application/json）解析后按 code 判错 reject。
 *
 * 注意：service 拦截器在 blob 分支直接返回 Blob（非 axios response），因此不能用
 * request.post（其内部取 res.data 会得到 undefined），直接使用 service。
 * 文件名由前端按 table 约定（后端 Content-Disposition 仍正确，仅前端未取用——如实登记边界）。
 */
export const exportFinanceCsv = (params: FinanceExportQuery): Promise<Blob> =>
  service({
    method: 'POST',
    url: '/franchise/finance/exports',
    params,
    responseType: 'blob'
  }) as unknown as Promise<Blob>

// ===== 异常资金写操作（P1 尾批①）=====

export interface AbnormalRegisterPayload {
  fundDirection: string
  amountCents: number
  counterparty?: string
  relatedProjectId?: string
  stageOrCategory?: string
  businessDate?: string
  method?: string
  transactionNo?: string
  reason?: string
}

/** 登记异常资金（幂等键由页面按弹窗会话生成）。 */
export const registerAbnormalFund = (
  data: AbnormalRegisterPayload,
  idempotencyKey: string
): Promise<{ fundId: string }> =>
  request.post({
    url: '/franchise/finance/abnormal-funds',
    data,
    headers: { 'Idempotency-Key': idempotencyKey }
  })

/** 转正关联：linkedType 由资金方向唯一决定（receipt/refund/payment）。 */
export const linkAbnormalFund = (
  id: string | number,
  data: { linkedType: string; linkedId: string }
): Promise<boolean> => request.post({ url: `/franchise/finance/abnormal-funds/${id}/link`, data })

/** 作废（留痕；幂等）。 */
export const voidAbnormalFund = (id: string | number, reason: string): Promise<boolean> =>
  request.delete({ url: `/franchise/finance/abnormal-funds/${id}`, data: { reason } })

// ===== 银行对账（P1 尾批②）=====

export interface BankFlowRow {
  id: string
  companyId: string
  bankRef: string
  amountCents: number
  bankDate: string
  status: string
  source: string
  createTime?: string
}

export const getBankFlows = (status?: string): Promise<BankFlowRow[]> =>
  // 页面统一展示单条提示，关掉全局 toast，避免"成功提示 + 红色刷新错误"同时出现。
  request.get({
    url: '/franchise/finance/bank-flows',
    params: status ? { status } : {},
    silentError: true
  })

/** 确认到账候选：既有应收列表按「待登记」过滤（复用旧端点，生产流水入口未建前不做新契约）。 */
export const getPendingBankReceipts = (): Promise<ReceiptRow[]> =>
  request.get({
    url: '/franchise/finance/receipts',
    params: { status: '待登记' },
    silentError: true
  })

export const confirmBankFlow = (
  bankFlowId: string,
  receiptId: string,
  idempotencyKey: string
): Promise<{ confirmId: string }> =>
  request.post({
    url: '/franchise/finance/bank-confirms',
    data: { bankFlowId, receiptId },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
