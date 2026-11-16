<template>
  <section class="profile-panel" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="profile">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="联系人">{{
          profile.contactName || '未设置'
        }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{
          profile.contactMobile || '未设置'
        }}</el-descriptions-item>
        <el-descriptions-item label="地址">{{ profile.address || '未设置' }}</el-descriptions-item>
        <el-descriptions-item label="老板微信">{{
          profile.wechatBound ? '已有微信绑定' : '暂无微信绑定'
        }}</el-descriptions-item>
      </el-descriptions>
      <div class="actions" v-if="profile.organization.status !== 'DELETED'">
        <el-button type="primary" plain @click="openEdit">维护组织资料</el-button>
        <el-button
          v-if="profile.organization.accountId"
          :disabled="profile.organization.status !== 'ACTIVE'"
          @click="openTransfer"
          >老板交接</el-button
        >
      </div>
    </template>
    <el-dialog
      v-model="editing"
      title="维护组织资料"
      width="min(680px,94vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!edit.saving.value"
    >
      <el-alert
        title="修改姓名用于纠正资料；更换老板请使用“老板交接”。登录账号保持不变。"
        type="info"
        :closable="false"
      />
      <el-alert
        v-if="edit.pending.value"
        title="上次请求待确认，已保留原内容。可重试，或重新加载最新资料后编辑。"
        type="warning"
        :closable="false"
      />
      <el-form
        label-position="top"
        class="edit-form"
        :disabled="!!edit.pending.value || edit.saving.value"
      >
        <el-form-item label="组织名称" required
          ><el-input v-model="form.orgName" maxlength="64"
        /></el-form-item>
        <template v-if="profile?.organization.accountId">
          <el-form-item label="品牌" required
            ><el-input v-model="form.brand" maxlength="40"
          /></el-form-item>
          <div class="region-fields">
            <el-form-item label="省" required
              ><el-input v-model="form.province" maxlength="40"
            /></el-form-item>
            <el-form-item label="市" required
              ><el-input v-model="form.city" maxlength="40"
            /></el-form-item>
            <el-form-item label="区 / 县" required
              ><el-input v-model="form.area" maxlength="40"
            /></el-form-item>
          </div>
          <el-form-item label="老板姓名" required
            ><el-input v-model="form.ownerName" maxlength="40"
          /></el-form-item>
          <el-form-item label="老板账号手机号" required>
            <el-input
              v-model="form.ownerMobile"
              maxlength="11"
              :disabled="!!profile?.mobileBlockedReason"
            />
            <span class="hint">{{
              profile?.mobileBlockedReason || '更正手机号后，该账号已有登录会失效，需重新登录。'
            }}</span>
          </el-form-item>
        </template>
        <el-form-item label="联系人"
          ><el-input v-model="form.contactName" maxlength="40"
        /></el-form-item>
        <el-form-item label="联系电话"
          ><el-input v-model="form.contactMobile" maxlength="20"
        /></el-form-item>
        <el-form-item label="地址"
          ><el-input v-model="form.address" type="textarea" :rows="2" maxlength="300"
        /></el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500"
        /></el-form-item>
        <el-form-item label="修改原因" required
          ><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500"
        /></el-form-item>
      </el-form>
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
      <template #footer>
        <el-button v-if="edit.pending.value" :disabled="edit.saving.value" @click="reloadEdit"
          >重新加载最新资料</el-button
        >
        <el-button :disabled="edit.saving.value" @click="editing = false">{{
          edit.pending.value ? '稍后确认' : '取消'
        }}</el-button>
        <el-button type="primary" :loading="edit.saving.value" @click="saveEdit">{{
          edit.pending.value ? '重试确认' : '保存资料'
        }}</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="transferring"
      title="老板交接"
      width="min(600px,94vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!transfer.saving.value"
    >
      <el-alert
        title="原老板的本组织老板岗位将撤销，新老板使用自己的现有密码。登录账号保持不变，个人微信绑定和密码不会转交。"
        type="warning"
        :closable="false"
      />
      <el-form
        label-position="top"
        class="edit-form"
        :disabled="!!transfer.pending.value || transfer.saving.value"
      >
        <el-form-item label="新老板（本组织已有成员）" required>
          <el-select
            v-model="transferForm.newOwnerUserId"
            filterable
            remote
            :remote-method="searchMembers"
            :loading="membersLoading"
            placeholder="输入姓名或手机号查找"
            style="width: 100%"
          >
            <el-option
              v-for="member in members"
              :key="member.id"
              :value="member.id"
              :label="`${member.name} · ${member.mobile || '无手机号'}`"
              :disabled="member.id === profile?.ownerUserId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="确认组织名称" required
          ><el-input
            v-model="transferForm.confirmedName"
            :placeholder="profile?.organization.orgName"
            maxlength="64"
        /></el-form-item>
        <el-form-item label="交接原因" required
          ><el-input v-model="transferForm.reason" type="textarea" :rows="3" maxlength="500"
        /></el-form-item>
      </el-form>
      <el-alert
        v-if="transfer.pending.value"
        title="交接结果待确认，请重试原请求，或先重新加载最新状态。"
        type="warning"
        :closable="false"
      />
      <el-alert v-if="transferError" :title="transferError" type="error" :closable="false" />
      <template #footer>
        <el-button
          v-if="transfer.pending.value"
          :disabled="transfer.saving.value"
          @click="reloadTransfer"
          >重新加载最新状态</el-button
        >
        <el-button :disabled="transfer.saving.value" @click="transferring = false">{{
          transfer.pending.value ? '稍后确认' : '取消'
        }}</el-button>
        <el-button type="primary" :loading="transfer.saving.value" @click="saveTransfer">{{
          transfer.pending.value ? '重试确认' : '确认交接'
        }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { ElMessage } from 'element-plus'
import {
  getOrganizationProfile,
  updateOrganizationProfile,
  transferOrganizationOwner,
  getOwnerCandidates,
  type OrganizationProfile,
  type ProfileUpdate,
  type OwnerTransfer
} from '@/api/platform/organizationOperations'
import { operationsError, useOperationsMutation } from '@/views/platform/operations/mutation'
const props = defineProps<{ orgId: number }>()
const emit = defineEmits<{ updated: [OrganizationProfile] }>()
const profile = ref<OrganizationProfile>(),
  loading = ref(false),
  error = ref(''),
  editing = ref(false),
  transferring = ref(false),
  editError = ref(''),
  transferError = ref('')
const edit = useOperationsMutation<ProfileUpdate>(() => `profile:${props.orgId}`)
const transfer = useOperationsMutation<OwnerTransfer>(() => `owner:${props.orgId}`)
const form = reactive<ProfileUpdate>({
  expectedVersion: 0,
  orgName: '',
  brand: '',
  province: '',
  city: '',
  area: '',
  ownerName: '',
  ownerMobile: '',
  contactName: '',
  contactMobile: '',
  address: '',
  remark: '',
  reason: '',
  requestKey: ''
})
const transferForm = reactive<OwnerTransfer>({
  expectedVersion: 0,
  newOwnerUserId: '',
  confirmedName: '',
  reason: '',
  requestKey: ''
})
const members = ref<{ id: string; name: string; mobile: string }[]>([]),
  membersLoading = ref(false)
let memberSearch = 0
async function load() {
  loading.value = true
  error.value = ''
  const id = props.orgId
  try {
    const result = await getOrganizationProfile(id)
    if (id === props.orgId) profile.value = result
    return result
  } catch (e) {
    error.value = operationsError(e)
    throw e
  } finally {
    loading.value = false
  }
}
function populate() {
  const p = profile.value
  if (!p) return
  const o = p.organization
  Object.assign(form, {
    expectedVersion: p.version,
    orgName: o.orgName,
    brand: o.brand || '',
    province: o.province || '',
    city: o.city || '',
    area: o.area || '',
    ownerName: o.ownerName || '',
    ownerMobile: o.mobile || '',
    contactName: p.contactName || '',
    contactMobile: p.contactMobile || '',
    address: p.address || '',
    remark: o.remark || '',
    reason: '',
    requestKey: ''
  })
}
function openEdit() {
  try {
    edit.restore()
    populate()
    if (edit.pending.value) Object.assign(form, edit.pending.value)
    editError.value = ''
    editing.value = true
  } catch (e) {
    error.value = operationsError(e)
  }
}
async function reloadEdit() {
  try {
    await load()
    edit.clear()
    populate()
    editError.value = ''
  } catch (e) {
    editError.value = operationsError(e)
  }
}
async function saveEdit() {
  try {
    if (!edit.pending.value) {
      if (!form.orgName.trim() || !form.reason.trim()) throw new Error('请填写组织名称和修改原因')
      if (
        profile.value?.organization.accountId &&
        [form.brand, form.province, form.city, form.area, form.ownerName].some((v) => !v.trim())
      )
        throw new Error('请完整填写品牌、地区和老板姓名')
      if (
        profile.value?.organization.accountId &&
        form.ownerMobile !== (profile.value.organization.mobile || '') &&
        !/^1\d{10}$/.test(form.ownerMobile)
      )
        throw new Error('请填写11位老板手机号')
      if (!/^[+0-9() -]{0,20}$/.test(form.contactMobile)) throw new Error('请检查联系电话格式')
    }
    const result = await edit.run({ ...form, requestKey: crypto.randomUUID() }, (data) =>
      updateOrganizationProfile(props.orgId, data)
    )
    profile.value = result
    editing.value = false
    emit('updated', result)
    ElMessage.success('组织资料已更新')
  } catch (e) {
    editError.value = operationsError(e)
  }
}
async function searchMembers(keyword = '') {
  const sequence = ++memberSearch
  membersLoading.value = true
  try {
    const found = await getOwnerCandidates(props.orgId, keyword)
    if (sequence === memberSearch) members.value = found
  } catch (e) {
    transferError.value = operationsError(e)
  } finally {
    if (sequence === memberSearch) membersLoading.value = false
  }
}
async function openTransfer() {
  try {
    transfer.restore()
    Object.assign(
      transferForm,
      transfer.pending.value || {
        expectedVersion: profile.value?.version || 0,
        newOwnerUserId: '',
        confirmedName: '',
        reason: '',
        requestKey: ''
      }
    )
    transferError.value = ''
    transferring.value = true
    await searchMembers()
  } catch (e) {
    error.value = operationsError(e)
  }
}
async function reloadTransfer() {
  try {
    await load()
    transfer.clear()
    await openTransfer()
  } catch (e) {
    transferError.value = operationsError(e)
  }
}
async function saveTransfer() {
  try {
    if (!transfer.pending.value) {
      if (!transferForm.newOwnerUserId || !transferForm.reason.trim())
        throw new Error('请选择新老板并填写交接原因')
      if (transferForm.confirmedName !== profile.value?.organization.orgName)
        throw new Error('确认组织名称不一致')
    }
    const result = await transfer.run(
      { ...transferForm, requestKey: crypto.randomUUID() },
      (data) => transferOrganizationOwner(props.orgId, data)
    )
    profile.value = result
    transferring.value = false
    emit('updated', result)
    ElMessage.success('老板交接已完成')
  } catch (e) {
    transferError.value = operationsError(e)
  }
}
watch(
  () => props.orgId,
  () => {
    profile.value = undefined
    load().catch(() => {})
  },
  { immediate: true }
)
</script>
<style scoped>
.profile-panel {
  margin: 20px 0;
}
.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.actions :deep(.el-button) {
  margin-left: 0;
}
.edit-form {
  margin-top: 20px;
}
.region-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
:deep(.el-alert) {
  margin-bottom: 12px;
}
:deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
@media (max-width: 560px) {
  .region-fields {
    grid-template-columns: 1fr;
  }
}
</style>
