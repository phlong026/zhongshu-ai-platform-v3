/**
 * 钱包对账视图纯函数（/fr/wallet）：点数格式化、增减符号、类型/状态标签。
 * 点数为整数，不是金额分；展示加千分位但不带货币单位。
 */

export function formatPoints(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '-'
  const n = Number(value)
  const sign = n < 0 ? '-' : ''
  const digits = Math.abs(Math.trunc(n)).toString()
  return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** 流水增减：正数带 +，负数带 -，0 原样。 */
export function signedPoints(delta: number | null | undefined): string {
  if (delta === null || delta === undefined || !Number.isFinite(Number(delta))) return '-'
  const n = Number(delta)
  if (n === 0) return '0'
  return (n > 0 ? '+' : '') + formatPoints(n)
}

/**
 * 流水类型标签。只映射 PointAccountService / WalletChargeService / RechargePaymentService /
 * RefundPaymentService / ManualPointAdjustmentService 实际写入 pf_point_ledger.type 的取值；
 * 其它一律原样展示（不做前缀猜测）。注意 RESERVE/RELEASE 只改余额不写流水，
 * MANUAL_ADJUSTMENT 是 bizType 不是 type。
 */
const LEDGER_TYPE: Record<string, string> = {
  RECHARGE_BASE_CREDIT: '充值本金',
  RECHARGE_BONUS_CREDIT: '充值赠送',
  CHARGE: '消费扣减',
  REFUND_BASE_REVERSAL: '退款冲正（本金）',
  REFUND_BONUS_REVERSAL: '退款冲正（赠送）',
  MANUAL_CREDIT: '人工增加',
  MANUAL_DEBIT: '人工扣减'
}

export function ledgerTypeLabel(type: string | null | undefined): string {
  const raw = (type || '').trim()
  if (!raw) return '-'
  // 必须用 hasOwnProperty：直接下标会把 toString/constructor/__proto__ 等原型链成员当成类型标签。
  return Object.prototype.hasOwnProperty.call(LEDGER_TYPE, raw) ? LEDGER_TYPE[raw] : raw
}

// WalletQueryService.account 只返回 ACTIVE / FROZEN（由 spending_frozen 派生），
// 不是 pf_wallet.status 的 DISABLED/CLOSED。
const WALLET_STATUS: Record<string, string> = {
  ACTIVE: '正常',
  FROZEN: '已冻结'
}

export function walletStatusLabel(status: string | null | undefined): string {
  const raw = (status || '').trim()
  if (!raw) return '-'
  return Object.prototype.hasOwnProperty.call(WALLET_STATUS, raw) ? WALLET_STATUS[raw] : raw
}

export function walletStatusTag(status: string | null | undefined): 'success' | 'warning' | 'info' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'FROZEN') return 'warning'
  return 'info'
}

/** 分页边界：页码从 1 开始，每页 1..100（与服务端 @Min/@Max 一致）。 */
export function normalizeLedgerPage(
  pageNo: number,
  pageSize: number
): { pageNo: number; pageSize: number } {
  const page = Number.isFinite(pageNo) && pageNo >= 1 ? Math.trunc(pageNo) : 1
  const size = Number.isFinite(pageSize) && pageSize >= 1 ? Math.min(Math.trunc(pageSize), 100) : 20
  return { pageNo: page, pageSize: size }
}
