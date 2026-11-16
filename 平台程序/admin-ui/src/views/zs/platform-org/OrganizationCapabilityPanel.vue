<template>
  <section v-loading="loading" class="capability-panel">
    <div class="heading"
      ><div
        ><h3>组织能力开通</h3
        ><p>开通后，成员仍需具备对应岗位权限。关闭能力不影响在途任务结算和历史记录。</p></div
      ><el-button @click="loadPolicy">刷新</el-button></div
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="policy">
      <el-alert
        v-if="status === 'DISABLED'"
        title="组织当前已关闭，启用组织后才可使用已开通能力。"
        type="info"
        :closable="false"
      />
      <el-alert
        v-if="mutation.pending.value"
        title="上次设置结果待确认，已保留原内容。可重试，或重新加载最新设置。"
        type="warning"
        :closable="false"
      />
      <el-checkbox-group
        v-model="selected"
        class="capability-list"
        :disabled="status === 'DELETED' || !!mutation.pending.value || mutation.saving.value"
      >
        <div v-for="item in policy.items" :key="item.subject" class="capability-row">
          <el-checkbox
            :value="item.subject"
            :disabled="!item.priced && !selected.includes(item.subject)"
            >{{ item.label }}</el-checkbox
          >
          <el-tag v-if="!item.priced" type="warning" size="small">请先定价</el-tag>
        </div>
      </el-checkbox-group>
      <el-form label-position="top" v-if="status !== 'DELETED'">
        <el-form-item label="设置原因" required
          ><el-input
            v-model="reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="填写开通或关闭原因"
            :disabled="!!mutation.pending.value || mutation.saving.value"
        /></el-form-item>
        <div class="footer">
          <el-button v-if="mutation.pending.value" :disabled="mutation.saving.value" @click="reload"
            >重新加载最新设置</el-button
          >
          <el-button type="primary" :loading="mutation.saving.value" @click="save">{{
            mutation.pending.value ? '重试确认' : '保存能力设置'
          }}</el-button>
          <el-button link type="primary" @click="router.push('/platform-pricing')"
            >定价管理</el-button
          >
        </div>
      </el-form>
    </template>
  </section>
</template>
<script setup lang="ts">
import { ElMessage } from 'element-plus'
import {
  getOrganizationCapabilities,
  updateOrganizationCapabilities,
  type CapabilityPolicy,
  type CapabilityUpdate
} from '@/api/platform/organizationOperations'
import { operationsError, useOperationsMutation } from '@/views/platform/operations/mutation'
const props = defineProps<{ orgId: number; status: string }>()
const emit = defineEmits<{ saved: [] }>(),
  router = useRouter()
const loading = ref(false),
  policy = ref<CapabilityPolicy>(),
  error = ref(''),
  selected = ref<string[]>([]),
  reason = ref(''),
  version = ref(0)
const mutation = useOperationsMutation<CapabilityUpdate>(() => `capabilities:${props.orgId}`)
async function loadPolicy() {
  loading.value = true
  error.value = ''
  try {
    const result = await getOrganizationCapabilities(props.orgId)
    policy.value = result
    mutation.restore()
    const pending = mutation.pending.value
    selected.value = pending
      ? [...pending.enabledSubjects]
      : result.items.filter((i) => i.enabled).map((i) => i.subject)
    version.value = pending?.expectedVersion ?? result.version
    reason.value = pending?.reason || ''
    return result
  } catch (e) {
    error.value = operationsError(e)
    return undefined
  } finally {
    loading.value = false
  }
}
async function reload() {
  if (mutation.saving.value) return
  loading.value = true
  try {
    const fresh = await getOrganizationCapabilities(props.orgId)
    mutation.clear()
    policy.value = fresh
    selected.value = fresh.items.filter((i) => i.enabled).map((i) => i.subject)
    version.value = fresh.version
    reason.value = ''
    error.value = ''
  } catch (e) {
    error.value = operationsError(e)
  } finally {
    loading.value = false
  }
}
async function save() {
  if (!policy.value || props.status === 'DELETED') return
  try {
    if (!mutation.pending.value && !reason.value.trim()) throw new Error('请填写设置原因')
    const result = await mutation.run(
      {
        expectedVersion: version.value,
        enabledSubjects: [...selected.value].sort(),
        reason: reason.value.trim(),
        requestKey: crypto.randomUUID()
      },
      (data) => updateOrganizationCapabilities(props.orgId, data)
    )
    policy.value = result
    version.value = result.version
    selected.value = result.items.filter((i) => i.enabled).map((i) => i.subject)
    reason.value = ''
    error.value = ''
    ElMessage.success('组织能力已更新')
    emit('saved')
  } catch (e) {
    error.value = operationsError(e)
  }
}
onMounted(loadPolicy)
</script>
<style scoped>
.capability-panel {
  padding: 12px 0;
}
.heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}
h3 {
  margin: 0 0 8px;
}
p {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
.capability-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 24px;
  margin: 12px 0 24px;
}
.capability-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-lighter);
  min-height: 52px;
  min-width: 0;
}
.capability-row :deep(.el-checkbox) {
  height: auto;
  margin: 12px 0;
  min-width: 0;
}
.capability-row :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.footer {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.footer :deep(.el-button) {
  margin-left: 0;
}
:deep(.el-alert) {
  margin-bottom: 12px;
}
@media (max-width: 650px) {
  .capability-list {
    grid-template-columns: 1fr;
  }
}
</style>
