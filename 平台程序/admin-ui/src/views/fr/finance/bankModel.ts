/**
 * 银行对账纯函数（/fr/finance 银行对账 tab）：现状=既有流水展示 + 确认到账。
 *
 * 契约依据：GET /franchise/finance/bank-flows（status 可选）、POST /bank-confirms
 * （bankFlowId/receiptId + Idempotency-Key；权限 franchise:fin:register=finance/finance-lead）。
 * 流水状态只有 待匹配/已认领；应收状态 待登记/已登记/已撤销；确认要求同组织、同金额、应收待登记。
 */

export const BANK_FLOW_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: '待匹配', label: '待匹配' },
  { value: '已认领', label: '已认领' }
]

const SOURCE_LABEL: Record<string, string> = {
  dev: '联调注入',
  e2e: 'E2E 注入',
  import: '导入'
}

export function bankSourceLabel(source: string | null | undefined): string {
  const raw = (source || '').trim()
  if (!raw) return '-'
  return Object.prototype.hasOwnProperty.call(SOURCE_LABEL, raw) ? SOURCE_LABEL[raw] : raw
}

/** 确认到账复用 register 权限（V852：finance/finance-lead）。 */
const CONFIRM_ROLES = ['finance', 'finance-lead']

export function canConfirmBank(roleCode: string | null | undefined): boolean {
  return !!roleCode && CONFIRM_ROLES.includes(roleCode)
}

/** 只有「待匹配」流水可确认；模板与测试共用同一判定。 */
export function isConfirmableBankFlow(
  flow: { status?: string | null } | null | undefined
): boolean {
  return (flow?.status || '').trim() === '待匹配'
}

export interface BankReceiptCandidate {
  id: string
  amountCents: number | string
  status?: string | null
}

/** 候选应收：仅待登记且金额与流水完全一致（服务端仍会二次校验金额与状态）。 */
export function matchingReceipts<T extends BankReceiptCandidate>(
  receipts: T[] | null | undefined,
  amountCents: number
): T[] {
  if (!receipts || !receipts.length) return []
  const target = `${amountCents}`
  return receipts.filter(
    (receipt) =>
      (receipt.status || '').trim() === '待登记' && `${receipt.amountCents}`.trim() === target
  )
}

/**
 * 确认前的前端校验（服务端真源：BANK_FLOW_NOT_FOUND / BANK_FLOW_TAKEN /
 * BANK_RECEIPT_NOT_PENDING / BANK_CONFIRM_AMOUNT_MISMATCH）。返回空串表示通过。
 */
export function confirmError(
  flow: { status?: string | null; amountCents?: number | string } | null | undefined,
  receipt: BankReceiptCandidate | null | undefined,
  receiptId: string | null | undefined
): string {
  if (!flow) return '流水不存在'
  if ((flow.status || '').trim() !== '待匹配') return '该流水已被认领，请刷新'
  if (!receiptId || !`${receiptId}`.trim()) return '请选择待登记的应收单'
  if (!receipt) return '请选择待登记的应收单'
  if ((receipt.status || '').trim() !== '待登记') return '所选应收不处于待登记状态，请刷新'
  if (`${receipt.amountCents}`.trim() !== `${flow.amountCents}`.trim()) {
    return '流水金额与应收金额不一致'
  }
  return ''
}

export function newBankConfirmKey(): string {
  const cryptoObj = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (cryptoObj?.randomUUID) {
    return `bank-confirm:${cryptoObj.randomUUID().replace(/-/g, '').slice(0, 32)}`
  }
  return `bank-confirm:${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}
