import { ref, shallowRef } from 'vue'
import { getTenantId } from '@/utils/auth'
import { useUserStore } from '@/store/modules/user'

/** 请求先持久化再发送；网络丢失确认时重试相同请求，不更换人员、版本或请求号。 */
export function useOperationsMutation<T extends { requestKey: string }>(name: () => string) {
  const pending = shallowRef<T | null>(null)
  const saving = ref(false)
  const scope = () => {
    const actor = String(useUserStore().getUser.id || '')
    const tenant = String(getTenantId() ?? '')
    if (!actor || !tenant) throw new Error('请重新登录后操作')
    return `zs-ops:v1:${tenant}:${actor}:${name()}`
  }
  let storageKey = ''
  function restore() {
    storageKey = scope()
    const value = localStorage.getItem(storageKey)
    if (!value) {
      pending.value = null
      return
    }
    const parsed = JSON.parse(value)
    if (!parsed || typeof parsed.requestKey !== 'string' || parsed.requestKey.length > 100)
      throw new Error('待确认请求无法读取，请重新加载最新状态')
    pending.value = parsed
  }
  function clear() {
    if (storageKey && storageKey !== scope()) throw new Error('工作账号已切换，请重新打开页面')
    localStorage.removeItem(storageKey || scope())
    pending.value = null
  }
  async function run<R>(draft: T, send: (payload: T) => Promise<R>) {
    if (saving.value) throw new Error('请求正在处理中')
    const current = scope()
    if (storageKey && storageKey !== current) throw new Error('工作账号已切换，请重新打开页面')
    storageKey = current
    const payload = JSON.parse(JSON.stringify(pending.value || draft)) as T
    localStorage.setItem(current, JSON.stringify(payload))
    pending.value = payload
    saving.value = true
    try {
      const result = await send(payload)
      clear()
      return result
    } finally {
      saving.value = false
    }
  }
  return { pending, saving, restore, clear, run }
}
export const operationsError = (error: any) => error?.msg || error?.message || '操作失败，请重试'
