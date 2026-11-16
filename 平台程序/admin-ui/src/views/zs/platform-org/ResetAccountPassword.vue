<template>
  <section v-if="allowed" class="account-management">
    <h3>账号管理</h3>
    <el-button :loading="loading" @click="open">重置登录密码</el-button>
    <el-dialog v-model="visible" title="重置加盟商登录密码" width="min(520px, 94vw)"
      append-to-body :close-on-click-modal="false" :show-close="!saving"
      :close-on-press-escape="!saving" @closed="clear">
      <p>{{ orgName }}</p>
      <p>登录账号：{{ account?.loginName }}</p>
      <el-alert v-if="account && !account.resetAllowed" :title="account.blockedReason" type="warning" :closable="false" />
      <template v-else>
        <el-alert :title="impact" type="warning" :closable="false" />
        <el-form label-position="top" :disabled="saving || attempted" class="reset-form" autocomplete="off">
          <el-form-item label="新密码"><el-input v-model="password" type="password" show-password autocomplete="new-password" maxlength="32" placeholder="8至32位，包含字母和数字" /></el-form-item>
          <el-form-item label="确认新密码"><el-input v-model="confirmation" type="password" show-password autocomplete="new-password" maxlength="32" /></el-form-item>
          <el-form-item label="输入完整组织名称确认"><el-input v-model="confirmedName" /></el-form-item>
          <el-form-item label="重置原因"><el-input v-model="reason" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        </el-form>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="visible = false">关闭</el-button>
        <el-button v-if="account?.resetAllowed" type="primary" :loading="saving" :disabled="!valid" @click="save">{{ attempted ? '重试原请求' : '确认重置' }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { getOrganizationAccount, resetOrganizationPassword, type OrganizationAccount } from '@/api/zs/platform-org'
const props = defineProps<{ orgId: number; orgName: string }>()
const allowed = computed(() => {
  const p = useUserStore().getPermissions
  return p.has('*:*:*') || (p.has('platform:org:onboard') && p.has('system:user:update-password'))
})
const visible = ref(false), loading = ref(false), saving = ref(false), attempted = ref(false)
const account = ref<OrganizationAccount>()
const password = ref(''), confirmation = ref(''), confirmedName = ref(''), reason = ref(''), error = ref(''), requestKey = ref('')
const valid = computed(() => /^[\x21-\x7e]{8,32}$/.test(password.value) && /[A-Za-z]/.test(password.value) && /[0-9]/.test(password.value) && password.value === confirmation.value && confirmedName.value === props.orgName && !!reason.value.trim())
const impact = computed(() => account.value && account.value.relatedOrganizations.length > 1
  ? `同一用户关联${account.value.relatedOrganizations.length}个组织，所有关联登录账号将共用新密码。现有登录会退出，微信绑定保留。`
  : '重置后现有登录会退出，微信绑定保留。请将新密码交给账号本人。')
const message = (e: any) => e?.msg || e?.message || '请求未确认，可重试原请求'
function clear() { password.value = ''; confirmation.value = ''; reason.value = ''; confirmedName.value = ''; error.value = ''; attempted.value = false; requestKey.value = '' }
async function open() {
  loading.value = true
  try { account.value = await getOrganizationAccount(props.orgId); clear(); requestKey.value = crypto.randomUUID(); visible.value = true }
  catch (e) { ElMessage.error(message(e)) }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || !valid.value) return
  saving.value = true; attempted.value = true; error.value = ''
  try {
    await resetOrganizationPassword(props.orgId, { password: password.value, confirmedName: confirmedName.value, reason: reason.value.trim(), requestKey: requestKey.value })
    visible.value = false; clear(); ElMessage.success('密码已重置，原登录已退出')
  } catch (e) { error.value = message(e) }
  finally { saving.value = false }
}
</script>
<style scoped>
.account-management { margin-top: 24px; }
.account-management h3 { font-size: 16px; }
.reset-form { margin-top: 20px; }
</style>
