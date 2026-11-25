<template>
  <div class="announcement-page">
    <header class="announcement-heading">
      <div>
        <h1>公司公告</h1>
        <p>发布后，员工可在小程序“我的 → 公司公告”查看。</p>
      </div>
      <el-button v-if="canWrite" type="primary" :disabled="busy" @click="createDraft">
        {{ pendingCreate ? '继续确认草稿' : '新建公告' }}
      </el-button>
    </header>

    <section class="announcement-panel">
      <el-form :inline="true" :model="query" class="announcement-filters" @submit.prevent="search">
        <el-form-item label="标题">
          <el-input v-model="query.keyword" placeholder="搜索公告标题" clearable maxlength="100" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="status-select" placeholder="全部状态" clearable>
            <el-option
              v-for="(label, value) in statusLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="可见品牌">
          <el-select v-model="query.brandId" class="brand-select" placeholder="全部品牌" clearable>
            <el-option
              v-for="brand in options.brands"
              :key="brand.id"
              :label="brand.name"
              :value="brand.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
          <el-button :disabled="loading" @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon>
        <template #default
          ><el-button link type="primary" @click="load">重新加载</el-button></template
        >
      </el-alert>
      <p v-if="ready && !canWrite" class="read-only-note"
        >当前工作身份可查看公告，发布和修改由总部管理员处理。</p
      >
      <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无符合条件的公告">
        <el-table-column label="公告" min-width="250">
          <template #default="{ row }">
            <div class="announcement-title">
              <el-tag v-if="row.pinned" size="small" type="warning">置顶</el-tag>
              <el-button link type="primary" @click="openRecord(row.id)">{{ row.title }}</el-button>
            </div>
            <p v-if="row.summary" class="announcement-summary">{{ row.summary }}</p>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" min-width="180">
          <template #default="{ row }">{{ scopeLabel(row, options.brands) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{
              statusLabels[row.status]
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间（北京时间）" min-width="185">
          <template #default="{ row }">{{ publicationTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="canWrite ? 210 : 80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="busy" @click="openRecord(row.id)"
              >查看</el-button
            >
            <template v-if="canWrite">
              <el-button
                v-if="row.status !== 'PUBLISHED'"
                link
                type="primary"
                :disabled="busy"
                @click="openRecord(row.id, true)"
                >编辑</el-button
              >
              <el-button
                v-if="row.status !== 'PUBLISHED'"
                link
                type="primary"
                :disabled="busy"
                @click="changeStatus(row, 'publish')"
                >发布</el-button
              >
              <el-button
                v-else
                link
                type="danger"
                :disabled="busy"
                @click="changeStatus(row, 'withdraw')"
                >撤回</el-button
              >
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        class="announcement-pagination"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </section>

    <el-dialog
      v-model="editorOpen"
      :title="mode === 'edit' ? (selected ? '编辑公告' : '新建公告') : '公告详情'"
      width="min(820px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      destroy-on-close
      @closed="closeEditor"
    >
      <div v-loading="detailLoading">
        <el-alert
          v-if="editorError"
          :title="editorError"
          type="error"
          :closable="false"
          show-icon
        />
        <div v-if="stale && selected" class="refresh-record">
          <el-button :loading="detailLoading" @click="refreshRecord">刷新最新内容</el-button>
        </div>
        <el-form
          v-if="mode === 'edit' && canWrite"
          :model="form"
          label-position="top"
          :disabled="busy || stale || (!selected && !!pendingCreate)"
        >
          <el-form-item label="公告标题" required>
            <el-input
              v-model="form.title"
              maxlength="100"
              show-word-limit
              placeholder="例如：合家美宅年度服务升级通知"
            />
          </el-form-item>
          <el-form-item label="摘要">
            <el-input
              v-model="form.summary"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="列表中展示的简短说明"
            />
          </el-form-item>
          <el-form-item label="公告正文" required>
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="12"
              maxlength="20000"
              show-word-limit
              placeholder="填写公告正文"
            />
          </el-form-item>
          <el-form-item label="可见范围" required>
            <el-radio-group v-model="form.visibility">
              <el-radio value="ALL">全部品牌</el-radio>
              <el-radio value="BRANDS">指定品牌</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.visibility === 'BRANDS'" label="选择品牌" required>
            <el-select
              v-model="form.brandIds"
              multiple
              filterable
              placeholder="请选择可见品牌"
              class="editor-brands"
            >
              <el-option
                v-for="brand in options.brands"
                :key="brand.id"
                :label="brand.name"
                :value="brand.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="列表展示"
            ><el-checkbox v-model="form.pinned">置顶展示</el-checkbox></el-form-item
          >
        </el-form>
        <article v-else-if="selected" class="announcement-preview">
          <div class="preview-meta">
            <el-tag :type="selected.status === 'PUBLISHED' ? 'success' : 'info'">{{
              statusLabels[selected.status]
            }}</el-tag>
            <el-tag v-if="selected.pinned" type="warning">置顶</el-tag>
            <span>{{ scopeLabel(selected, options.brands) }}</span>
          </div>
          <h2>{{ selected.title }}</h2>
          <p class="publication-time"
            >发布时间：{{ publicationTime(selected.publishedAt) }}（北京时间）</p
          >
          <p v-if="selected.summary" class="preview-summary">{{ selected.summary }}</p>
          <div class="announcement-body">{{ selected.content || '正文尚未填写' }}</div>
        </article>
      </div>
      <template #footer>
        <el-button :disabled="busy" @click="editorOpen = false">关闭</el-button>
        <template v-if="canWrite && !detailLoading && !stale">
          <el-button v-if="mode === 'edit'" type="primary" :loading="busy" @click="save">{{
            !selected && pendingCreate ? '确认保存结果' : '保存草稿'
          }}</el-button>
          <template v-else-if="selected">
            <el-button v-if="selected.status !== 'PUBLISHED'" :disabled="busy" @click="editSelected"
              >编辑</el-button
            >
            <el-button
              v-if="selected.status !== 'PUBLISHED'"
              type="primary"
              :loading="busy"
              @click="changeStatus(selected, 'publish')"
              >发布公告</el-button
            >
            <el-button
              v-else
              type="danger"
              plain
              :loading="busy"
              @click="changeStatus(selected, 'withdraw')"
              >撤回公告</el-button
            >
          </template>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  announcementOptions,
  changeAnnouncementStatus,
  getAnnouncement,
  listAnnouncements,
  saveAnnouncement,
  type AnnouncementDetail,
  type AnnouncementInput,
  type AnnouncementOptions,
  type AnnouncementQuery,
  type AnnouncementRow
} from '@/api/platform/announcements'
import { getAccessToken, getTenantId, getVisitTenantId } from '@/utils/auth'
import {
  currentPostId,
  PENDING_ANNOUNCEMENT_CREATE_KEY,
  postGeneration
} from '@/utils/platformIdentity'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import { useUserStore } from '@/store/modules/user'
import {
  announcementInput,
  createRequestId,
  errorMessage,
  errorStatus,
  inputError,
  newAnnouncement,
  publicationTime,
  scopeLabel,
  statusLabels
} from './model'

defineOptions({ name: 'PlatformAnnouncements' })

const identity = usePlatformIdentityStore()
const user = useUserStore()
const options = ref<AnnouncementOptions>({ canWrite: false, brands: [] })
const ready = ref(false)
const rows = ref<AnnouncementRow[]>([])
const total = ref(0)
const loading = ref(false)
const listError = ref('')
const query = reactive<AnnouncementQuery>({
  pageNo: 1,
  pageSize: 20,
  keyword: '',
  status: '',
  brandId: ''
})
const editorOpen = ref(false)
const detailLoading = ref(false)
const selected = ref<AnnouncementDetail | null>(null)
const mode = ref<'view' | 'edit'>('view')
const form = reactive<AnnouncementInput>(newAnnouncement())
const editorError = ref('')
type PendingCreate = { id: string; input: AnnouncementInput; scope: string }
const pendingCreate = ref<PendingCreate | null>(null)
const stale = ref(false)
const busy = ref(false)
const canWrite = computed(() => ready.value && options.value.canWrite)
let active = true
let loadedScope = ''
let listSequence = 0
let detailSequence = 0
const scope = () =>
  JSON.stringify([
    Boolean(getAccessToken()),
    getTenantId(),
    getVisitTenantId(),
    user.getUser.id,
    currentPostId(),
    postGeneration()
  ])
const current = (captured: string) => active && captured === scope()

// 页面刷新会重新确认同一岗位并更新 generation；存储身份不记录 token，只认同账号、租户与岗位。
const recoveryScope = () => {
  const actor = user.getUser.id
  const post = currentPostId()
  return getAccessToken() && actor && post
    ? JSON.stringify([getTenantId(), getVisitTenantId(), String(actor), post])
    : ''
}
const forgetCreate = () => {
  pendingCreate.value = null
  try {
    sessionStorage.removeItem(PENDING_ANNOUNCEMENT_CREATE_KEY)
  } catch {
    // Storage may be unavailable; retain in-page safeguards.
  }
}
const persistCreate = (command: PendingCreate) => {
  const binding = recoveryScope()
  if (!binding) return
  try {
    sessionStorage.setItem(
      PENDING_ANNOUNCEMENT_CREATE_KEY,
      JSON.stringify({ id: command.id, input: command.input, binding })
    )
  } catch {
    // The current page can still retry its frozen request.
  }
}
const restoreCreate = (captured: string) => {
  if (pendingCreate.value) return
  const binding = recoveryScope()
  if (!binding) return
  try {
    const raw = sessionStorage.getItem(PENDING_ANNOUNCEMENT_CREATE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    const input = saved?.input
    if (
      saved?.binding !== binding ||
      !/^[0-9a-f-]{36}$/i.test(saved?.id) ||
      !input ||
      typeof input.title !== 'string' ||
      typeof input.summary !== 'string' ||
      typeof input.content !== 'string' ||
      typeof input.pinned !== 'boolean' ||
      !['ALL', 'BRANDS'].includes(input.visibility) ||
      !Array.isArray(input.brandIds) ||
      input.brandIds.some((value: unknown) => typeof value !== 'string') ||
      inputError(input)
    ) {
      forgetCreate()
      return
    }
    pendingCreate.value = { id: saved.id, input: announcementInput(input), scope: captured }
  } catch {
    forgetCreate()
  }
}

const clearPrivateState = (discardRecovery = true) => {
  loadedScope = ''
  listSequence++
  detailSequence++
  rows.value = []
  total.value = 0
  options.value = { canWrite: false, brands: [] }
  ready.value = false
  selected.value = null
  if (discardRecovery) forgetCreate()
  else pendingCreate.value = null
  Object.assign(form, newAnnouncement())
  editorOpen.value = false
  busy.value = false
  loading.value = false
  detailLoading.value = false
}

const load = async () => {
  const captured = scope()
  const sequence = ++listSequence
  loading.value = true
  listError.value = ''
  const params = { ...query, keyword: query.keyword.trim() }
  try {
    const [choices, page] = await Promise.all([announcementOptions(), listAnnouncements(params)])
    if (!current(captured) || sequence !== listSequence) return
    loadedScope = captured
    options.value = choices
    rows.value = page.list
    total.value = page.total
    ready.value = true
    if (choices.canWrite) restoreCreate(captured)
    else forgetCreate()
  } catch (error) {
    if (!current(captured) || sequence !== listSequence) return
    rows.value = []
    total.value = 0
    if ([401, 403].includes(errorStatus(error))) clearPrivateState()
    listError.value = errorMessage(error, '公告加载失败，请重试。')
  } finally {
    if (current(captured) && sequence === listSequence) loading.value = false
  }
}

const search = () => {
  query.pageNo = 1
  return load()
}
const resetFilters = () => {
  Object.assign(query, { pageNo: 1, keyword: '', status: '', brandId: '' })
  return load()
}

const createDraft = () => {
  if (!canWrite.value || busy.value || !current(loadedScope)) return
  detailSequence++
  selected.value = null
  if (pendingCreate.value?.scope !== scope()) pendingCreate.value = null
  Object.assign(form, announcementInput(pendingCreate.value?.input || newAnnouncement()))
  editorError.value = pendingCreate.value ? '上次保存结果待确认，点击「确认保存结果」继续。' : ''
  stale.value = false
  mode.value = 'edit'
  detailLoading.value = false
  editorOpen.value = true
}

const closeEditor = () => {
  detailSequence++
  selected.value = null
  Object.assign(form, newAnnouncement())
  editorError.value = ''
  stale.value = false
}

const openRecord = async (id: string, editable = false) => {
  if (busy.value) return
  const captured = scope()
  const sequence = ++detailSequence
  selected.value = null
  editorError.value = ''
  stale.value = false
  mode.value = 'view'
  editorOpen.value = true
  detailLoading.value = true
  try {
    const record = await getAnnouncement(id)
    if (!current(captured) || sequence !== detailSequence || !editorOpen.value) return
    selected.value = record
    Object.assign(form, announcementInput(record))
    mode.value = editable && canWrite.value && record.status !== 'PUBLISHED' ? 'edit' : 'view'
  } catch (error) {
    if (!current(captured) || sequence !== detailSequence) return
    selected.value = null
    Object.assign(form, newAnnouncement())
    editorError.value = errorMessage(error, '公告详情加载失败，请关闭后重试。')
    if ([401, 403].includes(errorStatus(error))) {
      clearPrivateState()
      listError.value = errorMessage(error, '当前工作身份已失效，请重新登录。')
    }
  } finally {
    if (current(captured) && sequence === detailSequence) detailLoading.value = false
  }
}

const editSelected = () => {
  if (!canWrite.value || !selected.value || selected.value.status === 'PUBLISHED' || busy.value)
    return
  Object.assign(form, announcementInput(selected.value))
  mode.value = 'edit'
  editorError.value = ''
}

const refreshRecord = async () => {
  if (!selected.value || busy.value) return
  const id = selected.value.id
  const editable = mode.value === 'edit'
  const captured = scope()
  if (editable) {
    try {
      await ElMessageBox.confirm('刷新会替换当前未保存的内容，是否继续？', '刷新公告', {
        type: 'warning'
      })
    } catch {
      return
    }
  }
  if (current(captured)) await openRecord(id, editable)
}

const mutationFailed = (error: unknown) => {
  const message = errorMessage(error, '操作未完成，请刷新确认最新状态后重试。')
  if ([401, 403].includes(errorStatus(error))) {
    clearPrivateState()
    listError.value = message
    return
  }
  if (editorOpen.value) {
    editorError.value = message
    stale.value = errorStatus(error) === 409
  } else listError.value = message
}

const save = async () => {
  if (
    !canWrite.value ||
    busy.value ||
    stale.value ||
    mode.value !== 'edit' ||
    !current(loadedScope)
  )
    return
  const captured = scope()
  const record = selected.value
  if (pendingCreate.value?.scope !== captured) pendingCreate.value = null
  const pending = record ? null : pendingCreate.value
  const input = pending ? announcementInput(pending.input) : announcementInput(form)
  const issue = inputError(input)
  if (issue) {
    editorError.value = issue
    return
  }
  let command = pending
  if (!record && !command) {
    try {
      command = { id: createRequestId(), input, scope: captured }
      pendingCreate.value = command
      persistCreate(command)
    } catch {
      editorError.value = '当前浏览器无法安全保存草稿，请刷新页面或换一个浏览器后重试。'
      return
    }
  }
  busy.value = true
  editorError.value = ''
  try {
    const saved = await saveAnnouncement(
      input,
      record ? { id: record.id, revision: record.revision } : undefined,
      command?.id
    )
    if (!current(captured)) return
    if (command) forgetCreate()
    selected.value = saved
    Object.assign(form, announcementInput(saved))
    mode.value = 'view'
    ElMessage.success(
      saved.status === 'DRAFT' ? '草稿已保存，可预览后发布' : '已找到原公告，显示当前状态'
    )
    await load()
  } catch (error) {
    if (!current(captured)) return
    const code = errorStatus(error)
    if (command && code === 400) forgetCreate()
    if (command && ![400, 401, 403].includes(code)) {
      editorError.value =
        code === 409
          ? '本次保存与原记录不一致，请先核对公告列表。'
          : '保存结果待确认，请点击「确认保存结果」，不会重复新增。'
      if (code === 409) stale.value = true
    } else mutationFailed(error)
  } finally {
    if (current(captured)) busy.value = false
  }
}

const changeStatus = async (row: AnnouncementRow, action: 'publish' | 'withdraw') => {
  if (!canWrite.value || busy.value || stale.value) return
  if ((action === 'publish') === (row.status === 'PUBLISHED')) return
  const captured = scope()
  const expectedRevision = row.revision
  busy.value = true
  try {
    await ElMessageBox.confirm(
      action === 'publish'
        ? `发布“${row.title}”后，${scopeLabel(row, options.value.brands)}的员工可在公司公告中查看。`
        : `撤回“${row.title}”后，员工刷新公告列表将不再看到这条公告。`,
      action === 'publish' ? '发布公告' : '撤回公告',
      {
        type: 'warning',
        confirmButtonText: action === 'publish' ? '确认发布' : '确认撤回',
        cancelButtonText: '取消'
      }
    )
    if (!current(captured)) return
    const updated = await changeAnnouncementStatus(row.id, action, expectedRevision)
    if (!current(captured)) return
    if (selected.value?.id === row.id) {
      selected.value = updated
      mode.value = 'view'
      editorError.value = ''
    }
    ElMessage.success(action === 'publish' ? '公告已发布' : '公告已撤回')
    await load()
  } catch (error) {
    if (current(captured) && error !== 'cancel' && error !== 'close') mutationFailed(error)
  } finally {
    if (current(captured)) busy.value = false
  }
}

watch(
  () => identity.context,
  () => {
    clearPrivateState(false)
    if (active) void load()
  }
)
onMounted(load)
onBeforeUnmount(() => {
  active = false
  clearPrivateState(false)
})
</script>

<style scoped>
.announcement-page {
  max-width: 1440px;
  margin: 0 auto;
}

.announcement-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.announcement-heading h1 {
  margin: 0;
  font-size: 24px;
}

.announcement-heading p,
.read-only-note {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.announcement-panel {
  padding: 24px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.announcement-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0 12px;
}

.announcement-filters :deep(.el-form-item) {
  margin-right: 0;
}

.status-select {
  width: 140px;
}

.brand-select {
  width: 180px;
}

.announcement-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.announcement-title :deep(.el-button) {
  height: auto;
  line-height: 1.6;
  text-align: left;
  white-space: normal;
}

.announcement-summary {
  margin: 4px 0;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}

.read-only-note {
  margin-bottom: 12px;
}

.announcement-pagination {
  justify-content: flex-end;
  margin-top: 20px;
}

.editor-brands {
  width: 100%;
}

.preview-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--el-text-color-secondary);
}

.announcement-preview h2 {
  margin: 20px 0 8px;
  font-size: 22px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.publication-time {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.preview-summary {
  padding: 12px 16px;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-left: 3px solid var(--el-color-primary-light-5);
  overflow-wrap: anywhere;
}

.announcement-body {
  margin-top: 24px;
  font-size: 15px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.refresh-record {
  margin: 12px 0;
}

@media (width <= 640px) {
  .announcement-panel {
    padding: 16px;
  }

  .announcement-heading {
    align-items: flex-start;
  }
}
</style>
