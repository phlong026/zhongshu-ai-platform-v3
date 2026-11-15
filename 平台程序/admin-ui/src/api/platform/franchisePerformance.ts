import type { AxiosRequestConfig } from 'axios'
import { service } from '@/config/axios/service'

/**
 * 绩效报表导出 API（P1 尾批③）。
 * 端点：GET /franchise/performance/commission/export?month=YYYY-MM
 * 响应：text/csv; charset=UTF-8 + attachment + no-store + UTF-8 BOM。
 *
 * 与 franchiseFinance.exportFinanceCsv 同型：service 拦截器在 blob 分支直接返回 Blob，
 * 失败（HTTP 200 + CommonResult JSON）由拦截器解析后按 code 判错 reject；因此不能用
 * request.get（其内部取 res.data 会得到 undefined）。
 */
export const exportPerfCommissionCsv = (month: string): Promise<Blob> => {
  // 页面只展示单条失败提示；关掉拦截器全局 notification，避免「全局错误 + 页面提示」双弹
  const config: AxiosRequestConfig & { silentError?: boolean } = {
    method: 'GET',
    url: '/franchise/performance/commission/export',
    params: { month },
    responseType: 'blob',
    silentError: true
  }
  return service(config) as unknown as Promise<Blob>
}
