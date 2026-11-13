/**
 * 异常资金写操作纯函数（/fr/finance 异常资金区）：方向/关联类型映射、权限门禁、
 * 元→分、登记载荷校验、作废原因、幂等键。
 *
 * 契约依据：FinanceController POST /abnormal-funds、POST /{id}/link、DELETE /{id}；
 * 表 829 列长度与枚举（fund_direction / method / status / linked_type）。
 */

export const ABNORMAL_DIRECTIONS = ['客户收款', '客户退款', '支出退回'] as const

export const DIRECTION_LINK_TYPE: Record<string, string> = {
  客户收款: 'receipt',
  客户退款: 'refund',
  支出退回: 'payment'
}

export const LINK_TYPE_LABEL: Record<string, string> = {
  receipt: '收款单',
  refund: '客户退款',
  payment: '付款单'
}

export const ABNORMAL_METHODS = [
  { value: 'bank', label: '银行' },
  { value: 'cash', label: '现金' },
  { value: 'other', label: '其他' }
]

/** V852：register=finance/finance-lead；refund-void=finance-lead/boss/owner。 */
const REGISTER_ROLES = ['finance', 'finance-lead']
const VOID_ROLES = ['finance-lead', 'boss', 'owner']

export function canRegisterAbnormal(roleCode: string | null | undefined): boolean {
  return !!roleCode && REGISTER_ROLES.includes(roleCode)
}

export function canVoidAbnormal(roleCode: string | null | undefined): boolean {
  return !!roleCode && VOID_ROLES.includes(roleCode)
}

export function linkTypeFor(direction: string | null | undefined): string {
  return DIRECTION_LINK_TYPE[(direction || '').trim()] || ''
}

/** 关联仅「已核待关联」；作废见 {@link isVoidableAbnormal}（已关联也可作废=误关联撤销）。 */
export function isPendingAbnormal(row: { status?: string | null } | null | undefined): boolean {
  return (row?.status || '').trim() === '已核待关联'
}

/**
 * 可作废：待关联或已关联都允许（既有后端语义——关联只是标注，误关联可作废撤销并释放流水号；
 * FinRefundThreeDirectionIntegrationTest 明确依赖该行为）。已作废不再显示。
 */
export function isVoidableAbnormal(row: { status?: string | null } | null | undefined): boolean {
  const status = (row?.status || '').trim()
  return status === '已核待关联' || status === '已关联'
}

/**
 * 元→分：只接受非负十进制、最多两位小数；>0；上限 1e12 分（829 CHECK）。
 * 返回 null 表示非法（调用方给用户提示）。
 */
export function parseYuanToCents(value: string | null | undefined): number | null {
  const raw = (value ?? '').trim()
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) return null
  const [yuan, decimals = ''] = raw.split('.')
  const cents = Number(yuan) * 100 + Number(decimals.padEnd(2, '0'))
  if (!Number.isSafeInteger(cents) || cents <= 0 || cents > 1_000_000_000_000) return null
  return cents
}

export interface AbnormalRegisterForm {
  fundDirection: string
  amountYuan: string
  counterparty?: string
  relatedProjectId?: string
  stageOrCategory?: string
  businessDate?: string
  method?: string
  transactionNo?: string
  reason?: string
}

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

export interface BuildResult {
  ok: boolean
  errors: string[]
  payload?: AbnormalRegisterPayload
}

/** 登记载荷：与后端 AbnormalFundCommand 同字段；未填的可选字段不下发。 */
export function buildRegisterCommand(form: AbnormalRegisterForm): BuildResult {
  const errors: string[] = []
  const direction = (form.fundDirection || '').trim()
  if (!ABNORMAL_DIRECTIONS.includes(direction as (typeof ABNORMAL_DIRECTIONS)[number])) {
    errors.push('请选择资金方向（客户收款/客户退款/支出退回）')
  }
  const amountCents = parseYuanToCents(form.amountYuan)
  if (amountCents === null) {
    errors.push('金额须为大于 0 的金额，最多两位小数')
  }
  const counterparty = (form.counterparty || '').trim()
  if (counterparty.length > 64) errors.push('对方最长 64 字')
  const stage = (form.stageOrCategory || '').trim()
  if (stage.length > 32) errors.push('期次/类别最长 32 字')
  const txnNo = (form.transactionNo || '').trim()
  if (txnNo.length > 64) errors.push('交易号最长 64 字')
  const reason = (form.reason || '').trim()
  if (reason.length > 255) errors.push('原因最长 255 字')
  const method = (form.method || '').trim()
  if (method && !ABNORMAL_METHODS.some((m) => m.value === method)) {
    errors.push('收款方式不合法')
  }
  // Snowflake/19 位 id 必须保持字符串：Number() 会丢精度（1987654321098765432 → ...65300）。
  let relatedProjectId: string | undefined
  const projectRaw = (form.relatedProjectId || '').trim()
  if (projectRaw) {
    if (!/^[1-9]\d*$/.test(projectRaw)) {
      errors.push('关联项目编号须为正整数')
    } else {
      relatedProjectId = projectRaw
    }
  }
  const businessDate = (form.businessDate || '').trim()
  if (businessDate && !/^\d{4}-\d{2}-\d{2}$/.test(businessDate)) {
    errors.push('业务日期须为 YYYY-MM-DD')
  }
  if (errors.length) return { ok: false, errors }
  const payload: AbnormalRegisterPayload = {
    fundDirection: direction,
    amountCents: amountCents as number
  }
  if (counterparty) payload.counterparty = counterparty
  if (relatedProjectId) payload.relatedProjectId = relatedProjectId
  if (stage) payload.stageOrCategory = stage
  if (businessDate) payload.businessDate = businessDate
  if (method) payload.method = method
  if (txnNo) payload.transactionNo = txnNo
  if (reason) payload.reason = reason
  return { ok: true, errors: [], payload }
}

export function linkTargetError(value: string | number | null | undefined): string {
  const raw = `${value ?? ''}`.trim()
  // 纯字符串判定：不以 0 开头、1..19 位（Long 上限 9223372036854775807）；不做任何数值转换。
  if (!/^[1-9]\d{0,18}$/.test(raw)) return '关联单号须为正整数'
  return ''
}

export function voidReasonError(reason: string | null | undefined): string {
  const raw = (reason || '').trim()
  if (!raw) return '请填写作废原因'
  if (raw.length > 255) return '作废原因最长 255 字'
  return ''
}

/** 每次打开登记弹窗生成一个键；重试同一次提交复用（提交失败重试时由页面保持）。 */
export function newAbnormalRegisterKey(): string {
  const cryptoObj = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (cryptoObj?.randomUUID) {
    return `abnormal-register:${cryptoObj.randomUUID().replace(/-/g, '').slice(0, 32)}`
  }
  return `abnormal-register:${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}
