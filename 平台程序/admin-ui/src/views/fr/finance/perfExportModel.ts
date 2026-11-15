/**
 * 绩效报表导出纯函数（/fr/finance 页头按钮）。
 *
 * 权限真源=后端 `@post.has('franchise:performance:commission')`（V857：boss/owner/finance/finance-lead）；
 * 前端按当前岗位 roleCode 显隐（WEB_CAPABILITIES 不含该码，不能走 identity.can）。
 * 注意：§八通用条款的 `franchise:fin:query` 仅适用于财务账表导出，不适用于本绩效报表。
 * 月份口径=提成应付生成月（后端 release_at 自然月），前端只负责把当前所选月份原样下发。
 */

/** V857 role_menu：franchise:performance:commission 授予的四岗。 */
export const PERF_EXPORT_ROLES = ['boss', 'owner', 'finance', 'finance-lead'] as const

export function canExportPerfCommission(roleCode?: string | null): boolean {
  return !!roleCode && (PERF_EXPORT_ROLES as readonly string[]).includes(roleCode)
}

/** 与后端 PerfCommissionExportService.validateMonth 同型：严格 YYYY-MM（01-12）。 */
export function isValidMonth(value?: string | null): boolean {
  return !!value && /^\d{4}-(0[1-9]|1[0-2])$/.test(value.trim())
}

/** 下载文件名（与后端 Content-Disposition 同型；前端仅作本地命名）。 */
export function perfExportFilename(month: string): string {
  const value = (month || '').trim()
  return `performance-commission-${value || 'unknown'}.csv`
}

/**
 * 失败提示归一化：字符串（拦截器业务错误）/ Error.message / 兜底文案。
 *
 * 既有 blob 拦截器对 HTTP 200 + CommonResult（400/403 等）统一 reject 字面量 `'error'`，
 * 服务端 msg 不向前端透出；该字面量没有可读性，按兜底文案处理（月份不合法在前端已前置拦截）。
 */
export function perfExportErrorMessage(error: unknown): string {
  if (typeof error === 'string' && error.trim() && error.trim() !== 'error') {
    return error
  }
  const message = (error as { message?: unknown } | null | undefined)?.message
  if (typeof message === 'string' && message.trim() && message.trim() !== 'error') {
    return message
  }
  return '绩效报表导出失败，请确认月份与权限后重试'
}
