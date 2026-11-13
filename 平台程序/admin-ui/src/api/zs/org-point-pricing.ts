import request from '@/config/axios'

/** 公司池科目价格（24 号 D8）：后台可调价，预扣写快照，调价只影响新任务 */
export type PriceUnit = 'CALL' | 'SECOND'

/** 科目现价：configured=false 表示后台未配置，当前使用代码默认价 */
export interface SubjectPrice {
  subject: string
  label: string
  unit: PriceUnit
  /** 微积分/单位（1 积分 = 1_000_000 微积分）；Long 序列化为字符串 */
  priceMicros: number | string
  minCharge: number | string
  /** db-vN = 后台第 N 版；vN = 代码默认版 */
  versionText: string
  configured: boolean
}

export interface SubjectPriceRule {
  id: number | string
  subject: string
  unit: PriceUnit
  priceMicros: number | string
  minCharge: number | string
  version: number
  status: 'ACTIVE' | 'RETIRED'
  effectiveAt: number | string
  retiredAt: number | string | null
  /** 发布人；停用不会覆盖，停用人单独记录 */
  operator: string | null
  retiredBy: string | null
}

const base = '/design/v1'

export const listSubjectPrices = (): Promise<SubjectPrice[]> =>
  request.get({ url: `${base}/org-point-price-subjects` })

export const listPriceRules = (subject?: string): Promise<SubjectPriceRule[]> =>
  request.get({ url: `${base}/org-point-price-rules`, params: subject ? { subject } : {} })

/** 发布新版本价：后端自动退休旧生效版，版本号递增 */
export const publishPrice = (data: {
  subject: string
  priceMicros: number
  minCharge: number
}): Promise<number | string> => request.post({ url: `${base}/org-point-price-rules`, data })

/** 停用生效价：停用后回退代码默认价，在途任务仍按预扣快照结算 */
export const retirePrice = (id: number | string): Promise<boolean> =>
  request.post({ url: `${base}/org-point-price-rules/${id}/retire` })
