<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">总部运营工作台</h1>
        <div class="zs-page-subtitle">组织开通、员工支持与平台钱包运营</div>
      </div>
    </div>
    <div class="zs-table-card">
      <el-empty v-if="!entries.length" description="当前账号暂无运营菜单权限，请联系管理员授权。" />
      <el-table v-else :data="entries">
        <el-table-column prop="title" label="工作入口" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" link @click="router.push(row.path)">进入</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { usePermissionStore } from '@/store/modules/permission'

defineOptions({ name: 'PlatformOperationsHome' })
const router = useRouter()
const permissions = usePermissionStore()
const entries = computed(() => {
  const result: { path: string; title: string }[] = []
  const visit = (routes: AppRouteRecordRaw[], parent = '') => {
    for (const route of routes) {
      if (route.meta?.hidden) continue
      const path = route.path.startsWith('/') ? route.path : `${parent}/${route.path}`
      if (route.children?.length) visit(route.children, path)
      else if (route.meta?.title && path !== '/index' && path !== '//index') {
        result.push({ path, title: String(route.meta.title) })
      }
    }
  }
  visit(permissions.getRouters)
  return result
})
</script>
