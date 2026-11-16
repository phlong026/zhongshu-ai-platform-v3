import request from '@/config/axios'
import type { Int64 } from './orgPoints'

export interface PriceHistory {
  id: string
  subject: string
  label: string
  unit: string
  priceMicros: Int64
  minCharge: Int64
  version: number
  status: string
  effectiveAt: string
  operatorName: string
  reason: string | null
}

export const getPriceHistory = (subject?: string): Promise<PriceHistory[]> =>
  request.get({ url: '/platform/org-points/price-history', params: { subject } })
