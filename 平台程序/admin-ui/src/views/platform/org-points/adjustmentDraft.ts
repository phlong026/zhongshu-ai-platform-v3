export interface AdjustmentDraft {
  delta: number
  reason: string
  requestKey: string
  attempted: boolean
  frozen?: {
    delta: number
    reason: string
    requestKey: string
  }
}

export const createDraft = (requestKey: string): AdjustmentDraft => ({
  delta: 0,
  reason: '',
  requestKey,
  attempted: false
})

export const beginAttempt = (draft: AdjustmentDraft) => {
  if (draft.frozen) return draft.frozen
  if (!Number.isSafeInteger(draft.delta) || draft.delta === 0) {
    throw new Error('积分变动必须是非零安全整数')
  }
  const reason = draft.reason.trim()
  if (!reason) throw new Error('请填写申请原因')
  draft.attempted = true
  draft.frozen = { delta: draft.delta, reason, requestKey: draft.requestKey }
  return draft.frozen
}

export const resetDraft = (draft: AdjustmentDraft, requestKey: string) => {
  Object.assign(draft, createDraft(requestKey))
  delete draft.frozen
}

export const restoreDraft = (
  draft: AdjustmentDraft,
  frozen: { delta: number; reason: string; requestKey: string }
) => {
  Object.assign(draft, {
    delta: frozen.delta,
    reason: frozen.reason,
    requestKey: frozen.requestKey,
    attempted: true,
    frozen: { ...frozen }
  })
}
