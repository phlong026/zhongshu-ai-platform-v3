export interface FrozenPrice {
  subject: string
  priceMicros: number
  minCharge: number
  reason: string
  requestKey: string
}

export interface PriceDraft {
  points: string
  minCharge: string
  reason: string
  requestKey: string
  attempted: boolean
  frozen?: FrozenPrice
}

const microsText = (value: number) => {
  const whole = Math.floor(value / 1_000_000)
  const fraction = String(value % 1_000_000)
    .padStart(6, '0')
    .replace(/0+$/, '')
  return fraction ? `${whole}.${fraction}` : String(whole)
}

export const createPriceDraft = (requestKey: string): PriceDraft => ({
  points: '',
  minCharge: '1',
  reason: '',
  requestKey,
  attempted: false
})

export const beginPriceAttempt = (draft: PriceDraft, subject: string): FrozenPrice => {
  if (draft.frozen) return draft.frozen
  const priceMatch = draft.points.trim().match(/^(\d+)(?:\.(\d{1,6}))?$/)
  const priceMicrosBig = priceMatch
    ? BigInt(priceMatch[1]) * 1_000_000n + BigInt((priceMatch[2] || '').padEnd(6, '0'))
    : 0n
  const minChargeBig = /^\d+$/.test(draft.minCharge.trim()) ? BigInt(draft.minCharge.trim()) : 0n
  const safeMaximum = BigInt(Number.MAX_SAFE_INTEGER)
  const reason = draft.reason.trim()
  if (
    !subject ||
    priceMicrosBig <= 0n ||
    priceMicrosBig > safeMaximum ||
    minChargeBig < 1n ||
    minChargeBig > safeMaximum ||
    !reason
  ) {
    throw new Error('请填写有效价格、最低收费和变更原因')
  }
  if (!draft.requestKey || draft.requestKey.length > 128) throw new Error('价格发布幂等键无效')
  draft.attempted = true
  draft.frozen = {
    subject,
    priceMicros: Number(priceMicrosBig),
    minCharge: Number(minChargeBig),
    reason,
    requestKey: draft.requestKey
  }
  return draft.frozen
}

export const resetPriceDraft = (draft: PriceDraft, requestKey: string) => {
  Object.assign(draft, createPriceDraft(requestKey))
  delete draft.frozen
}

export const restorePriceDraft = (draft: PriceDraft, frozen: FrozenPrice) => {
  Object.assign(draft, {
    points: microsText(frozen.priceMicros),
    minCharge: String(frozen.minCharge),
    reason: frozen.reason,
    requestKey: frozen.requestKey,
    attempted: true,
    frozen: { ...frozen }
  })
}
