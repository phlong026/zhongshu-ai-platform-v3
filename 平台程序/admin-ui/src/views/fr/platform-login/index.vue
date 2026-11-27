<template>
  <div class="platform-login">
    <div class="login-card">
      <img src="@/assets/imgs/zs-logo.png" alt="众墅之家" class="brand" />
      <h1>员工工作台登录</h1>
      <p>使用管理员配置的手机号登录，系统只展示已授权的有效任职。</p>

      <el-alert v-if="notice" :title="notice" type="warning" show-icon class="mb-18px" />
      <el-alert
        v-if="authenticated && identity.error"
        :title="identity.error"
        type="error"
        show-icon
        class="mb-18px"
      />
      <el-form
        v-if="!authenticated"
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <el-form-item label="手机号" prop="mobile">
          <el-input v-model="form.mobile" maxlength="11" placeholder="请输入员工手机号" />
        </el-form-item>
        <el-form-item label="短信验证码" prop="captcha">
          <el-input v-model="form.captcha" maxlength="6" placeholder="6位验证码">
            <template #append>
              <el-button :disabled="countdown > 0" @click="sendCode">
                {{ countdown > 0 ? `${countdown}秒` : '获取验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-button type="primary" class="w-full" :loading="submitting" @click="login"
          >登录</el-button
        >
      </el-form>

      <div v-else-if="posts.length">
        <h2>选择工作身份</h2>
        <button
          v-for="post in posts"
          :key="post.postId"
          type="button"
          class="post-card"
          @click="selectPost(post.postId)"
        >
          <span>{{ post.orgName || `组织 ${post.orgId}` }}</span>
          <strong>{{ roleLabel(post.roleCode) }}</strong>
        </button>
      </div>
      <el-button
        v-else
        type="primary"
        class="w-full"
        :loading="identity.loading"
        @click="retryPosts"
      >
        重新加载工作身份
      </el-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, onBeforeUnmount } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { sendStaffSms, staffSmsLogin } from '@/api/platform/staff'
import { getAccessToken, setToken } from '@/utils/auth'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import { usePermissionStore } from '@/store/modules/permission'
import { useUserStore } from '@/store/modules/user'
import { deleteUserCache } from '@/hooks/web/useCache'
import {
  portalMode,
  resolveStaffLandingPath,
  setPortalMode,
  type PlatformId
} from '@/utils/platformIdentity'
import { roleLabel } from '@/utils/platformRoles'

defineOptions({ name: 'PlatformStaffLogin' })

const router = useRouter()
const identity = usePlatformIdentityStore()
const permissionStore = usePermissionStore()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const form = reactive({ mobile: '', captcha: '' })
const submitting = ref(false)
const countdown = ref(0)
const notice = ref('')
const posts = computed(() => identity.posts)
const isStaffSession = () => {
  const token = getAccessToken()
  return !!token && portalMode(token) === 'staff'
}
const authenticated = ref(isStaffSession())
let timer: ReturnType<typeof setInterval> | undefined

// 过期会话可能在浏览器里残留岗位列表；未登录时必须回到短信表单。
if (!getAccessToken()) identity.clear()
else if (portalMode(getAccessToken()) === 'staff' && !identity.posts.length && !identity.error) {
  notice.value = '暂无有效任职，请联系组织管理员配置工作身份。'
}

const rules: FormRules = {
  mobile: [{ required: true, pattern: /^1\d{10}$/, message: '请输入正确手机号', trigger: 'blur' }],
  captcha: [{ required: true, pattern: /^\d{6}$/, message: '请输入6位验证码', trigger: 'blur' }]
}
const sendCode = async () => {
  if (!/^1\d{10}$/.test(form.mobile)) return ElMessage.warning('请先填写正确手机号')
  await sendStaffSms(form.mobile)
  ElMessage.success('验证码已发送')
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) clearInterval(timer)
  }, 1000)
}

const retryPosts = async () => {
  notice.value = ''
  try {
    const rows = await identity.loadPosts()
    authenticated.value = isStaffSession()
    if (!authenticated.value) return
    if (!rows.length) notice.value = '暂无有效任职，请联系组织管理员配置工作身份。'
    else if (rows.length === 1) await selectPost(rows[0].postId)
  } catch {
    authenticated.value = isStaffSession()
    if (!authenticated.value) notice.value = '登录状态已失效，请重新获取验证码。'
  }
}

const login = async () => {
  if (!(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  notice.value = ''
  try {
    const token = await staffSmsLogin(form.mobile, form.captcha)
    deleteUserCache()
    userStore.resetState()
    permissionStore.$reset()
    identity.clear()
    setToken(token as any)
    setPortalMode('staff', token.accessToken)
    authenticated.value = true
    await retryPosts()
  } finally {
    submitting.value = false
  }
}

const selectPost = async (postId: PlatformId) => {
  const context = await identity.selectPost(postId)
  if (!context) return
  await permissionStore.generateRoutes()
  await router.replace(
    resolveStaffLandingPath(context.post, identity.can, router.currentRoute.value.query.redirect)
  )
}

onBeforeUnmount(() => timer && clearInterval(timer))
</script>

<style scoped lang="scss">
.platform-login {
  display: grid;
  min-height: 100vh;
  padding: 24px;
  background: #f6f3ee;
  place-items: center;
}

.login-card {
  width: min(420px, 100%);
  padding: 36px;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 18px 60px rgb(76 48 27 / 10%);
}

.brand {
  width: 150px;
}

h1 {
  margin: 20px 0 8px;
  color: #4d301e;
}

h2 {
  margin: 18px 0 12px;
}

p {
  margin: 0 0 24px;
  line-height: 1.6;
  color: #7a6e65;
}

.post-card {
  display: flex;
  width: 100%;
  padding: 16px;
  margin-top: 10px;
  color: #4d301e;
  cursor: pointer;
  background: #fffaf5;
  border: 1px solid #eadbcd;
  border-radius: 12px;
  justify-content: space-between;
}

.post-card:hover {
  border-color: #a35e2b;
}
</style>
