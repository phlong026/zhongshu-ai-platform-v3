<template>
  <div class="organization-directory">
    <el-form class="directory-filters" :inline="true" :model="filters" @submit.prevent="search">
      <el-form-item label="搜索">
        <el-input
          v-model="filters.keyword"
          placeholder="组织、老板、手机号或账号"
          maxlength="100"
          clearable
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="组织类型">
        <el-select v-model="filters.orgType" clearable placeholder="全部" style="width: 100%">
          <el-option label="加盟门店" value="store" />
          <el-option label="总部" value="headquarters" />
        </el-select>
      </el-form-item>
      <el-form-item class="directory-deleted">
        <el-checkbox v-model="filters.includeDeleted" @change="search">包含已删除</el-checkbox>
      </el-form-item>
      <el-form-item class="directory-actions">
        <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button @click="$emit('create')">开通组织</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="directory-error" />
    <el-table
      class="directory-table"
      max-height="max(280px, calc(100dvh - 350px))"
      native-scrollbar
      v-loading="loading"
      :data="rows"
      row-key="orgId"
      empty-text="暂无符合条件的组织"
    >
      <el-table-column prop="orgName" label="组织名称" min-width="260" show-overflow-tooltip />
      <el-table-column label="类型" width="88">
        <template #default="{ row }">{{ typeName(row.orgType) }}</template>
      </el-table-column>
      <el-table-column prop="ownerName" label="老板姓名" width="90">
        <template #default="{ row }">{{ row.ownerName || '未设置' }}</template>
      </el-table-column>
      <el-table-column prop="mobile" label="手机号" width="122" />
      <el-table-column prop="loginName" label="登录账号" min-width="230" show-overflow-tooltip />
      <el-table-column label="状态" width="78">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '正常' : row.status === 'DELETED' ? '已删除' : '已关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openDetail(row.orgId)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!loading && !error" class="directory-cards">
      <el-empty v-if="!rows.length" description="暂无符合条件的组织" />
      <article v-for="row in rows" :key="row.orgId" class="directory-org-card">
        <div class="directory-card-heading">
          <strong>{{ row.orgName }}</strong>
          <el-button type="primary" link @click="openDetail(row.orgId)">详情</el-button>
        </div>
        <dl>
          <dt>类型</dt><dd>{{ typeName(row.orgType) }}</dd> <dt>老板姓名</dt
          ><dd>{{ row.ownerName || '未设置' }}</dd> <dt>手机号</dt
          ><dd>{{ row.mobile || '未设置' }}</dd> <dt>登录账号</dt
          ><dd>{{ row.loginName || '未设置' }}</dd> <dt>状态</dt
          ><dd>{{
            row.status === 'ACTIVE' ? '正常' : row.status === 'DELETED' ? '已删除' : '已关闭'
          }}</dd>
        </dl>
      </article>
    </div>
    <el-pagination
      v-model:current-page="filters.pageNo"
      v-model:page-size="filters.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      class="directory-pagination"
      @current-change="load"
      @size-change="search"
    />
    <el-drawer v-model="detailVisible" title="组织详情" size="min(1120px, 100%)" destroy-on-close>
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <OrgDetail
          v-if="detail"
          :key="detail.organization.orgId"
          :detail="detail"
          @changed="detailChanged"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import OrgDetail from './OrgDetail.vue'
import {
  getOrganizationDetail,
  getOrganizationPage,
  type Organization,
  type OrganizationDetail
} from '@/api/zs/platform-org'

defineEmits<{ create: [] }>()
const filters = reactive({
  keyword: '',
  orgType: '',
  pageNo: 1,
  pageSize: 20,
  includeDeleted: false
})
const rows = ref<Organization[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<OrganizationDetail | null>(null)
let listGeneration = 0
let detailGeneration = 0
const typeName = (type: string) => ({ store: '加盟门店', headquarters: '总部' })[type] || type
const message = (e: any, fallback: string) => e?.msg || e?.message || fallback

async function load() {
  const generation = ++listGeneration
  loading.value = true
  error.value = ''
  try {
    const result = await getOrganizationPage({ ...filters, keyword: filters.keyword.trim() })
    if (generation !== listGeneration) return
    rows.value = result.list
    total.value = result.total
  } catch (e) {
    if (generation !== listGeneration) return
    rows.value = []
    total.value = 0
    error.value = message(e, '组织列表加载失败，请重试')
  } finally {
    if (generation === listGeneration) loading.value = false
  }
}
function search() {
  filters.pageNo = 1
  void load()
}
function reset() {
  filters.keyword = ''
  filters.orgType = ''
  filters.includeDeleted = false
  search()
}
async function openDetail(orgId: number) {
  const generation = ++detailGeneration
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    const result = await getOrganizationDetail(orgId)
    if (generation === detailGeneration) detail.value = result
  } catch (e) {
    if (generation === detailGeneration) detailError.value = message(e, '组织详情加载失败，请重试')
  } finally {
    if (generation === detailGeneration) detailLoading.value = false
  }
}
function detailChanged(value: OrganizationDetail) {
  detail.value = value
  void load()
}

const route = useRoute()
onMounted(async () => {
  await load()
  const id = String(route.query.orgId || '')
  if (/^\d+$/.test(id)) void openDetail(Number(id))
})
</script>

<style scoped>
.organization-directory {
  min-width: 0;
  container-type: inline-size;
}

.directory-filters {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 190px auto auto;
  gap: 12px;
  margin-bottom: 18px;
}

.directory-filters :deep(.el-form-item) {
  min-width: 0;
  margin: 0;
}

.directory-filters :deep(.el-form-item__content) {
  min-width: 0;
  flex: 1;
}

.directory-actions :deep(.el-form-item__content) {
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.directory-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.directory-table :deep(.cell) {
  line-height: 22px;
  word-break: normal;
  white-space: nowrap;
  overflow-wrap: normal;
}

.directory-table :deep(.el-table__cell) {
  padding: 12px 0;
  vertical-align: top;
}

.directory-cards {
  display: none;
}

.directory-org-card {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.directory-org-card + .directory-org-card {
  margin-top: 12px;
}

.directory-card-heading {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.directory-card-heading strong {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.directory-org-card dl {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px;
  margin: 16px 0 0;
  font-size: 14px;
}

.directory-org-card dt {
  color: var(--el-text-color-secondary);
}

.directory-org-card dd {
  margin: 0;
  overflow-wrap: anywhere;
}

@container (width < 900px) {
  .directory-table {
    display: none;
  }

  .directory-cards {
    display: block;
  }

  .directory-filters {
    grid-template-columns: minmax(0, 1fr);
  }

  .directory-actions :deep(.el-form-item__content) {
    justify-content: flex-start;
  }

  .directory-pagination {
    justify-content: flex-start;
    flex-wrap: wrap;
    gap: 8px;
  }
}

.directory-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}

.directory-error {
  margin-bottom: 16px;
}

.copy-account {
  margin-left: 12px;
}
</style>
