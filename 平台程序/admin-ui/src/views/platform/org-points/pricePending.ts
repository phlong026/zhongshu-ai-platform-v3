import type { FrozenPrice } from './priceDraft'

export interface PriceScope {
  tenantId: string
  actorId: string
}

export interface PendingPrice {
  schemaVersion: 1
  scope: PriceScope
  frozen: FrozenPrice
}

const PREFIX = 'zs:hq-org-point-price:v1:'
const part = (value: string) => encodeURIComponent(value)
const prefix = (scope: PriceScope) => `${PREFIX}${part(scope.tenantId)}:${part(scope.actorId)}:`
const storageKey = (scope: PriceScope, subject: string) => `${prefix(scope)}${part(subject)}`

const validScope = (scope: unknown): scope is PriceScope => {
  if (!scope || typeof scope !== 'object') return false
  const value = scope as Record<string, unknown>
  return (
    typeof value.tenantId === 'string' &&
    value.tenantId !== '' &&
    typeof value.actorId === 'string' &&
    value.actorId !== ''
  )
}

const validFrozen = (frozen: unknown): frozen is FrozenPrice => {
  if (!frozen || typeof frozen !== 'object') return false
  const value = frozen as Record<string, unknown>
  return (
    typeof value.subject === 'string' &&
    value.subject !== '' &&
    typeof value.priceMicros === 'number' &&
    Number.isSafeInteger(value.priceMicros) &&
    value.priceMicros > 0 &&
    typeof value.minCharge === 'number' &&
    Number.isSafeInteger(value.minCharge) &&
    value.minCharge > 0 &&
    typeof value.reason === 'string' &&
    value.reason.trim() !== '' &&
    typeof value.requestKey === 'string' &&
    value.requestKey !== '' &&
    value.requestKey.length <= 128
  )
}

const decode = (raw: string, expected?: PriceScope): PendingPrice => {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new Error('未确认价格发布记录损坏，请先人工核对')
  }
  if (!value || typeof value !== 'object') {
    throw new Error('未确认价格发布记录损坏，请先人工核对')
  }
  const pending = value as Partial<PendingPrice>
  if (pending.schemaVersion !== 1 || !validScope(pending.scope) || !validFrozen(pending.frozen)) {
    throw new Error('未确认价格发布记录损坏，请先人工核对')
  }
  if (
    expected &&
    (pending.scope.tenantId !== expected.tenantId || pending.scope.actorId !== expected.actorId)
  ) {
    throw new Error('未确认价格发布记录归属不一致，请先人工核对')
  }
  return pending as PendingPrice
}

export const savePricePending = (storage: Storage, scope: PriceScope, frozen: FrozenPrice) => {
  if (!validScope(scope) || !validFrozen(frozen)) throw new Error('未确认价格发布参数无效')
  const pending: PendingPrice = { schemaVersion: 1, scope, frozen }
  const raw = JSON.stringify(pending)
  const key = storageKey(scope, frozen.subject)
  storage.setItem(key, raw)
  if (storage.getItem(key) !== raw) throw new Error('未确认价格发布保存失败，未发起请求')
  return pending
}

export const findPricePending = (storage: Storage, tenantId: string, actorId: string) => {
  const scope = { tenantId, actorId }
  const expectedPrefix = prefix(scope)
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index)
    if (!key || !key.startsWith(expectedPrefix)) continue
    const raw = storage.getItem(key)
    if (raw !== null) return decode(raw, scope)
  }
  return null
}

export const clearPricePending = (storage: Storage, scope: PriceScope, subject: string) => {
  const key = storageKey(scope, subject)
  storage.removeItem(key)
  if (storage.getItem(key) !== null) {
    throw new Error('未确认价格发布清理失败，请按原请求重试')
  }
}
