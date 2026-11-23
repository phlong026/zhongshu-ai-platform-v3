import request from '@/config/axios'

export interface PaymentModeSnapshot {
  mode: 'MOCK' | 'REAL'
  version: number
  updatedAt: string
  updatedBy: string
  reason: string
}

export const getPaymentMode = (): Promise<PaymentModeSnapshot> =>
  request.get({ url: '/platform/payment-mode' })

export const changePaymentMode = (data: {
  mode: 'MOCK' | 'REAL'
  expectedVersion: number
  reason: string
}): Promise<PaymentModeSnapshot> =>
  request.put({ url: '/platform/payment-mode', data })
