<template>
  <el-dialog
    v-model="visible"
    title="设置能力价格"
    width="600px"
    :before-close="beforeClose"
    :close-on-click-modal="false"
  >
    <template v-if="row">
      <p class="price-name"
        >{{ row.label }} <el-tag type="info">每{{ unitLabel(row.unit) }}</el-tag></p
      >
      <el-alert
        title="发布后用于新任务，进行中的任务按原价格结算。"
        type="info"
        :closable="false"
      />
      <el-form
        label-position="top"
        :disabled="saving || draft.attempted"
        class="price-form"
        @submit.prevent="save"
      >
        <el-form-item label="单价（积分）" required>
          <el-input
            v-model="draft.points"
            inputmode="decimal"
            placeholder="大于 0，最多 6 位小数"
          />
        </el-form-item>
        <el-form-item label="每笔最低收费（积分）" required>
          <el-input
            v-model="draft.minCharge"
            inputmode="numeric"
            placeholder="至少 1 积分，填写整数"
          />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-input
            v-model="draft.reason"
            type="textarea"
            :rows="2"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert
        v-if="draft.attempted"
        title="上次发布结果尚未确认，请按原请求重试。"
        type="warning"
        :closable="false"
      />
    </template>
    <template #footer>
      <el-button :disabled="saving || draft.attempted" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canWrite" @click="save">{{
        draft.attempted ? '按原请求重试' : '发布价格'
      }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { getTenantId } from '@/utils/auth'
import { checkPermi } from '@/utils/permission'
import { publishSubjectPrice } from '@/api/platform/orgPoints'
import { createPriceDraft, beginPriceAttempt, restorePriceDraft } from '../org-points/priceDraft'
import {
  savePricePending,
  clearPricePending,
  findPricePending,
  type PriceScope
} from '../org-points/pricePending'
import { runPersistedRequest } from '../org-points/persistedRequest'
import { formatMicros, integerText } from '../org-points/int64'
import { isActivePricingSubject, unitLabel, type PricingItem } from './catalog'

const emit = defineEmits<{ saved: [] }>()
const user = useUserStore()
const visible = ref(false)
const saving = ref(false)
const error = ref('')
const row = ref<PricingItem>()
const scope = ref<PriceScope>()
const key = () => `pricing:${crypto.randomUUID()}`
const draft = ref(createPriceDraft(key()))
const canWrite = computed(() => checkPermi(['platform:org-point:price:update']))
const actor = (): PriceScope => {
  const result = { tenantId: String(getTenantId() ?? ''), actorId: String(user.getUser.id ?? '') }
  if (!result.tenantId || !result.actorId || result.actorId === '0')
    throw new Error('请重新登录后设置价格')
  return result
}
function open(item: PricingItem) {
  if (draft.value.attempted) {
    visible.value = true
    return
  }
  try {
    if (!canWrite.value || !item.available || !isActivePricingSubject(item.subject)) return
    const current = actor()
    const pending = findPricePending(localStorage, current.tenantId, current.actorId, isActivePricingSubject)
    if (pending && pending.frozen.subject !== item.subject)
      throw new Error('其他能力有待确认的调价，请先打开对应能力重试')
    row.value = item
    scope.value = current
    draft.value = createPriceDraft(key())
    if (pending) restorePriceDraft(draft.value, pending.frozen)
    else {
      draft.value.points =
        BigInt(integerText(item.priceMicros) || '0') > 0n ? formatMicros(item.priceMicros) : ''
      draft.value.minCharge = integerText(item.minCharge)
    }
    error.value = ''
    visible.value = true
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '无法打开价格设置')
  }
}
function beforeClose(done: () => void) {
  if (saving.value) return
  if (draft.value.attempted) {
    error.value = '请先按原请求重试，确认发布结果'
    return
  }
  done()
}
async function save() {
  if (saving.value || !row.value || !canWrite.value) return
  let started = false
  const attempted = draft.value.attempted
  try {
    const current = actor()
    if (
      !scope.value ||
      scope.value.tenantId !== current.tenantId ||
      scope.value.actorId !== current.actorId
    )
      throw new Error('账号已切换，请重新打开定价页面')
    const payload = beginPriceAttempt(draft.value, row.value.subject)
    if (payload.priceMicros > 1_000_000_000 || payload.minCharge > 1_000_000_000)
      throw new Error('单价最多 1000 积分，最低收费最多 1000000000 积分')
    saving.value = true
    error.value = ''
    await runPersistedRequest(
      () => savePricePending(localStorage, current, payload),
      () => {
        started = true
        return publishSubjectPrice(payload)
      },
      () => clearPricePending(localStorage, current, payload.subject)
    )
    draft.value = createPriceDraft(key())
    visible.value = false
    ElMessage.success('价格已发布')
    emit('saved')
  } catch (failure) {
    if (!started && !attempted) {
      draft.value.attempted = false
      delete draft.value.frozen
    }
    error.value = failure instanceof Error ? failure.message : '发布失败，请重试'
  } finally {
    saving.value = false
  }
}
defineExpose({ open })
</script>

<style scoped>
.price-form {
  margin-top: 20px;
}

.price-name {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  font-size: 17px;
  font-weight: 600;
}
</style>
