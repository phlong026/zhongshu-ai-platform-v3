<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">工作身份</h1>
        <div class="zs-page-subtitle">切换后会清空上一身份的页面缓存和未提交表单。</div>
      </div>
    </div>
    <el-alert
      v-if="error || identity.error"
      :title="error || identity.error"
      type="error"
      show-icon
      class="mb-16px"
    >
      <template #default>
        <el-button link type="primary" :loading="identity.loading" @click="retry"
          >重新加载工作身份</el-button
        >
      </template>
    </el-alert>
    <div class="identity-grid">
      <button
        v-for="post in identity.posts"
        :key="post.postId"
        type="button"
        class="identity-card"
        :class="{ active: String(post.postId) === String(identity.currentPost?.postId) }"
        :disabled="identity.loading"
        @click="switchPost(post.postId)"
      >
        <span>{{ post.orgName || `组织 ${post.orgId}` }}</span>
        <strong>{{ roleLabel(post.roleCode) }}</strong>
        <small>{{ post.orgType === 'headquarters' ? '总部' : '门店' }}</small>
      </button>
    </div>
    <el-empty
      v-if="!identity.posts.length && !identity.error"
      description="暂无有效任职，请联系组织管理员配置工作身份"
    />
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import { usePermissionStore } from '@/store/modules/permission'
import type { PlatformId } from '@/utils/platformIdentity'
import { roleLabel } from '@/utils/platformRoles'

defineOptions({ name: 'FranchiseIdentity' })
const identity = usePlatformIdentityStore()
const permissions = usePermissionStore()
const router = useRouter()
const error = ref('')
/**
 * 选岗后按当前身份权限选择默认落地页（批4 硬性交付，替代原硬编码 org:query）。
 * 优先级：财务查询（老板/财务 Web 刚需第一项，R5 §二/§四 P0）> 组织查询 > 留在身份页。
 */
const defaultLandingPath = (): string => {
  if (identity.can('franchise:fin:query')) return '/fr/finance'
  if (identity.can('platform:org:query')) return '/fr/org/staff'
  return '/fr/identity'
}
const switchPost = async (postId: PlatformId) => {
  error.value = ''
  try {
    await identity.selectPost(postId)
    await permissions.generateRoutes()
    await router.replace(defaultLandingPath())
  } catch {
    error.value = '工作身份切换失败，该任职可能已被撤销，请刷新后重试。'
  }
}
const retry = async () => {
  error.value = ''
  try {
    await identity.restoreContext()
    await permissions.generateRoutes()
  } catch {
    error.value = '工作身份加载失败，请检查网络后重试。'
  }
}
onMounted(async () => {
  if (!identity.posts.length && !identity.error) await retry()
})
</script>

<style scoped lang="scss">
.identity-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.identity-card {
  display: flex;
  padding: 22px;
  color: #6f6259;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #eadfd5;
  border-radius: 14px;
  flex-direction: column;
  gap: 8px;
}

.identity-card strong {
  font-size: 20px;
  color: #4d301e;
}

.identity-card.active {
  border-color: #9b5c2b;
  box-shadow: 0 8px 26px rgb(141 79 36 / 12%);
}

.identity-card:disabled {
  cursor: wait;
  opacity: 0.65;
}
</style>
