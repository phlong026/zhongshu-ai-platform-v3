<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">支付中心</h1>
        <div class="zs-page-subtitle">统一管理全局支付模式。模拟交易与真实资金严格隔离。</div>
      </div>
      <el-button :loading="loading" @click="load">刷新状态</el-button>
    </div>

    <el-alert
      title="当前为模拟支付模式：新建支付订单将由服务端模拟适配器自动完成，不会发生真实扣款。"
      type="warning"
      show-icon
      :closable="false"
      class="mb-16px"
    />

    <div class="zs-table-card payment-mode-card">
      <div class="mode-heading">
        <div>
          <div class="mode-label">全局运行模式</div>
          <div class="mode-value">{{ snapshot?.mode === 'MOCK' ? '模拟支付' : '真实支付' }}</div>
        </div>
        <el-tag :type="snapshot?.mode === 'MOCK' ? 'warning' : 'success'" size="large">
          {{ snapshot?.mode || '读取中' }}
        </el-tag>
      </div>
      <el-descriptions v-if="snapshot" :column="2" border>
        <el-descriptions-item label="配置版本">{{ snapshot.version }}</el-descriptions-item>
        <el-descriptions-item label="最后修改人">{{ snapshot.updatedBy }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ snapshot.updatedAt }}</el-descriptions-item>
        <el-descriptions-item label="变更原因">{{ snapshot.reason }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        class="mt-16px"
        title="真实支付通道尚未完成统一接入与双渠道就绪验证，当前版本禁止切换到真实支付。"
        type="info"
        :closable="false"
      />
      <div class="mode-actions">
        <el-button type="primary" :disabled="snapshot?.mode === 'MOCK'" @click="openMockConfirm">
          切换为模拟支付
        </el-button>
        <el-button type="danger" disabled>切换为真实支付（尚未开放）</el-button>
      </div>
    </div>

    <el-dialog v-model="confirmVisible" title="确认切换支付模式" width="520px" :close-on-click-modal="false">
      <el-alert
        title="切换只影响之后创建的新订单。已创建订单继续使用创建时固化的支付模式和渠道。"
        type="warning"
        :closable="false"
      />
      <el-form label-position="top" class="mt-16px">
        <el-form-item label="变更原因" required>
          <el-input v-model="reason" type="textarea" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!reason.trim()" @click="changeMode">
          确认切换
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { changePaymentMode, getPaymentMode, type PaymentModeSnapshot } from '@/api/platform/payment'

defineOptions({ name: 'PlatformPaymentMode' })
const loading = ref(false)
const saving = ref(false)
const snapshot = ref<PaymentModeSnapshot>()
const confirmVisible = ref(false)
const reason = ref('')

async function load() {
  loading.value = true
  try {
    snapshot.value = await getPaymentMode()
  } finally {
    loading.value = false
  }
}
function openMockConfirm() {
  reason.value = ''
  confirmVisible.value = true
}
async function changeMode() {
  if (!snapshot.value || !reason.value.trim()) return
  saving.value = true
  try {
    snapshot.value = await changePaymentMode({
      mode: 'MOCK',
      expectedVersion: snapshot.value.version,
      reason: reason.value.trim()
    })
    confirmVisible.value = false
    ElMessage.success('已切换为模拟支付')
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.payment-mode-card { padding: 24px; }
.mode-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.mode-label { color: var(--el-text-color-secondary); font-size: 13px; }
.mode-value { margin-top: 8px; font-size: 28px; font-weight: 650; }
.mode-actions { display: flex; gap: 12px; margin-top: 20px; }
</style>
