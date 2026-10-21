// 保存草稿成功后 router.replace 补 id，AppView 按 fullPath 做 key 会整页重挂载；
// 授权勾选等组件状态会被重置，用模块级单例跨实例带回，避免保存后上传按钮仍不可用
export interface CarriedDraftState {
  id: string
  publicDisplay: boolean
  generationReference: boolean
  justSaved: boolean
}

let carried: CarriedDraftState | null = null

export const setCarriedDraftState = (value: CarriedDraftState | null) => {
  carried = value
}

/** 取出并清空（无论是否匹配目标 id 都不残留旧状态） */
export const takeCarriedDraftState = (): CarriedDraftState | null => {
  const value = carried
  carried = null
  return value
}
