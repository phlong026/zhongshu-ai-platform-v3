export interface AdjustmentScope {
  tenantId: string
  actorId: string
  orgId: string
}

export interface FrozenAdjustment {
  delta: number
  reason: string
  requestKey: string
}

export interface PendingAdjustment {
  schemaVersion: 1
  scope: AdjustmentScope
  frozen: FrozenAdjustment
}

const PREFIX = 'zs:hq-org-point-adjustment:v1:'
const part = (value: string) => encodeURIComponent(value)
const prefix = (tenantId: string, actorId: string) => `${PREFIX}${part(tenantId)}:${part(actorId)}:`
const storageKey = (scope: AdjustmentScope) =>
  `${prefix(scope.tenantId, scope.actorId)}${part(scope.orgId)}`

const validScope = (scope: unknown): scope is AdjustmentScope => {
  if (!scope || typeof scope !== 'object') return false
  const value = scope as Record<string, unknown>
  return ['tenantId', 'actorId', 'orgId'].every(
    (field) => typeof value[field] === 'string' && value[field] !== ''
  )
}

const validFrozen = (frozen: unknown): frozen is FrozenAdjustment => {
  if (!frozen || typeof frozen !== 'object') return false
  const value = frozen as Record<string, unknown>
  return (
    typeof value.delta === 'number' &&
    Number.isSafeInteger(value.delta) &&
    value.delta !== 0 &&
    typeof value.reason === 'string' &&
    value.reason.trim() !== '' &&
    typeof value.requestKey === 'string' &&
    value.requestKey !== ''
  )
}

const decode = (raw: string, expected?: AdjustmentScope): PendingAdjustment => {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw new Error('未确认调点记录损坏，请先人工核对')
  }
  if (!value || typeof value !== 'object') throw new Error('未确认调点记录损坏，请先人工核对')
  const pending = value as Partial<PendingAdjustment>
  if (pending.schemaVersion !== 1 || !validScope(pending.scope) || !validFrozen(pending.frozen)) {
    throw new Error('未确认调点记录损坏，请先人工核对')
  }
  if (
    expected &&
    (pending.scope.tenantId !== expected.tenantId ||
      pending.scope.actorId !== expected.actorId ||
      pending.scope.orgId !== expected.orgId)
  ) {
    throw new Error('未确认调点记录归属不一致，请先人工核对')
  }
  return pending as PendingAdjustment
}

export const savePending = (storage: Storage, scope: AdjustmentScope, frozen: FrozenAdjustment) => {
  if (!validScope(scope) || !validFrozen(frozen)) throw new Error('未确认调点参数无效')
  const pending: PendingAdjustment = { schemaVersion: 1, scope, frozen }
  const raw = JSON.stringify(pending)
  const key = storageKey(scope)
  storage.setItem(key, raw)
  if (storage.getItem(key) !== raw) throw new Error('未确认调点保存失败，未发起请求')
  return pending
}

export const loadPending = (storage: Storage, scope: AdjustmentScope) => {
  const raw = storage.getItem(storageKey(scope))
  return raw === null ? null : decode(raw, scope)
}

export const findPending = (storage: Storage, tenantId: string, actorId: string) => {
  const expectedPrefix = prefix(tenantId, actorId)
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index)
    if (!key || !key.startsWith(expectedPrefix)) continue
    const raw = storage.getItem(key)
    if (raw !== null) return decode(raw)
  }
  return null
}

export const clearPending = (storage: Storage, scope: AdjustmentScope) => {
  storage.removeItem(storageKey(scope))
  if (storage.getItem(storageKey(scope)) !== null)
    throw new Error('未确认调点清理失败，请按原申请重试')
}
