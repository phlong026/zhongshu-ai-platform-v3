<template>
  <el-button v-if="isSuperAdmin" type="primary" @click="open">添加积分</el-button>
  <el-dialog
    v-model="visible"
    title="添加组织积分"
    width="min(520px, 94vw)"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <el-form label-position="top" :disabled="saving || !!pending || blocked">
      <el-form-item label="组织">{{ orgName }}</el-form-item>
      <el-form-item label="增加积分"
        ><el-input-number
          v-model="credits"
          :min="1"
          :max="Number.MAX_SAFE_INTEGER"
          :precision="0"
          :step="100"
      /></el-form-item>
      <el-form-item label="原因"
        ><el-input v-model="reason" type="textarea" :rows="3" maxlength="512"
      /></el-form-item>
    </el-form>
    <el-alert
      v-if="pending"
      title="上次结果尚未确认，重试会核对原请求，不会重复加分。"
      type="warning"
      :closable="false"
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{
        pending ? '稍后核对' : '取消'
      }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="blocked" @click="submit">{{
        pending ? '重试原请求' : '确认添加'
      }}</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="receiptVisible" title="积分已添加" width="min(460px, 94vw)" append-to-body>
    <el-descriptions v-if="receipt" :column="1" border>
      <el-descriptions-item label="组织">{{ orgName }}</el-descriptions-item>
      <el-descriptions-item label="增加积分">{{
        formatInteger(receipt.credits)
      }}</el-descriptions-item>
      <el-descriptions-item label="原余额">{{
        formatInteger(receipt.beforeBalance)
      }}</el-descriptions-item>
      <el-descriptions-item label="现余额">{{
        formatInteger(receipt.afterBalance)
      }}</el-descriptions-item>
      <el-descriptions-item label="流水编号">{{ receipt.id }}</el-descriptions-item>
    </el-descriptions>
    <template #footer
      ><el-button type="primary" @click="receiptVisible = false">完成</el-button></template
    >
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getTenantId } from '@/utils/auth'
import { addCompanyCredits, type DirectCreditReceipt } from '@/api/platform/orgPoints'
import { formatInteger } from './int64'
import { runPersistedRequest } from './persistedRequest'

const props = defineProps<{ orgId: string; orgName: string }>()
const emit = defineEmits<{ credited: [] }>()
const user = useUserStore()
const isSuperAdmin = computed(() => user.getRoles.includes('super_admin'))
interface Pending {
  orgId: string
  orgName?: string
  credits: number
  reason: string
  requestKey: string
  actor: string
  tenant: string
}
const visible = ref(false),
  saving = ref(false),
  receiptVisible = ref(false),
  error = ref('')
const credits = ref(100),
  reason = ref(''),
  pending = ref<Pending | null>(null),
  receipt = ref<DirectCreditReceipt | null>(null)
const blocked = ref(false)
const actor = () => String(user.getUser.id)
const tenant = () => String(getTenantId() || '')
const key = () => `zs-org-direct-credit-v1:${tenant()}:${actor()}`
function open() {
  error.value = ''
  visible.value = true
  blocked.value = false
  pending.value = null
  try {
    const raw = localStorage.getItem(key())
    const saved = raw ? (JSON.parse(raw) as Pending) : null
    if (
      saved &&
      (saved.orgId !== props.orgId || saved.actor !== actor() || saved.tenant !== tenant())
    ) {
      throw new Error(
        `组织「${saved.orgName || saved.orgId}」有待确认加分，请先进入该组织核对原请求`
      )
    }
    if (
      saved &&
      (!Number.isSafeInteger(saved.credits) ||
        saved.credits <= 0 ||
        !saved.reason ||
        !saved.requestKey)
    )
      throw new Error('待确认请求异常，请联系管理员核对')
    pending.value = saved
    credits.value = saved?.credits || 100
    reason.value = saved?.reason || ''
  } catch (e: any) {
    blocked.value = true
    error.value = e?.message || '待确认请求读取失败'
    visible.value = true
  }
}
async function submit() {
  if (saving.value || blocked.value) return
  error.value = ''
  try {
    if (!isSuperAdmin.value) throw new Error('仅平台超级管理员可添加积分')
    if (!Number.isSafeInteger(credits.value) || credits.value <= 0 || !reason.value.trim())
      throw new Error('请填写正整数积分和原因')
    const frozen = pending.value || {
      orgId: props.orgId,
      orgName: props.orgName,
      credits: credits.value,
      reason: reason.value.trim(),
      requestKey: crypto.randomUUID(),
      actor: actor(),
      tenant: tenant()
    }
    if (frozen.actor !== actor() || frozen.tenant !== tenant() || frozen.orgId !== props.orgId)
      throw new Error('登录身份或目标组织已变化，请重新打开')
    saving.value = true
    const result = await runPersistedRequest(
      () => {
        localStorage.setItem(key(), JSON.stringify(frozen))
        pending.value = frozen
      },
      () =>
        addCompanyCredits(frozen.orgId, {
          credits: frozen.credits,
          reason: frozen.reason,
          requestKey: frozen.requestKey
        }),
      () => localStorage.removeItem(`zs-org-direct-credit-v1:${frozen.tenant}:${frozen.actor}`)
    )
    pending.value = null
    if (frozen.actor !== actor() || frozen.tenant !== tenant()) return
    receipt.value = result
    receiptVisible.value = true
    visible.value = false
    emit('credited')
  } catch (e: any) {
    error.value = e?.msg || e?.message || String(e) || '添加失败，请重试原请求'
  } finally {
    saving.value = false
  }
}
</script>
