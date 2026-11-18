<template>
  <div class="training-page">
    <div class="training-heading"
      ><div><h1>资料库</h1><p>统一上传，随课引用，更新可追溯</p></div
      ><div
        ><el-button @click="$router.push('/training/courses')">课程管理</el-button
        ><TrainingCategoryManager @changed="categoriesChanged" /><el-button
          type="primary"
          @click="openUpload"
          >上传资料</el-button
        ></div
      ></div
    >
    <div class="training-toolbar"
      ><el-input
        v-model="keyword"
        placeholder="搜索资料名称"
        clearable
        @keyup.enter="search"
      /><el-select v-model="category" placeholder="全部分类" clearable @change="search"
        ><el-option v-for="c in categoryNames" :key="c" :value="c" :label="c" /></el-select
      ><el-button @click="search">查询</el-button><el-button @click="load">刷新状态</el-button></div
    >
    <div class="library-grid">
      <aside class="library-folders"
        ><h3>资料分类</h3
        ><button :class="{ active: !category }" @click="chooseCategory('')">全部资料</button
        ><button
          v-for="c in categoryNames"
          :key="c"
          :class="{ active: category === c }"
          @click="chooseCategory(c)"
          ><el-icon><FolderOpened /></el-icon>{{ c }}</button
        ></aside
      >
      <div class="library-list" v-loading="loading"
        ><el-table
          class="training-compact-table"
          native-scrollbar
          max-height="max(300px, calc(100dvh - 330px))"
          :data="rows"
          highlight-current-row
          @row-click="select"
        >
          <el-table-column label="资料名称" min-width="280"
            ><template #default="{ row }"
              ><el-tooltip :content="row.name" placement="top"
                ><strong class="file-name file-name-summary">{{ row.name }}</strong></el-tooltip
              ><span class="file-meta"
                >{{ row.extension.toUpperCase() }} · {{ size(row.size_bytes) }}</span
              ></template
            ></el-table-column
          >
          <el-table-column label="版本" width="60"
            ><template #default="{ row }">V{{ row.version_no }}</template></el-table-column
          >
          <el-table-column label="状态" min-width="95"
            ><template #default="{ row }"
              ><el-tag
                :type="
                  row.state === 'READY' ? 'success' : row.state === 'FAILED' ? 'danger' : 'warning'
                "
                >{{ stateText(row.state) }}</el-tag
              ><el-button v-if="row.state === 'FAILED'" link @click.stop="retry(row)"
                >重试</el-button
              ></template
            ></el-table-column
          >
          <el-table-column label="操作" :width="compactLibrary ? 80 : 190" fixed="right"
            ><template #default="{ row }"
              ><el-button link type="primary" @click.stop="select(row)">详情</el-button
              ><el-button
                v-if="!compactLibrary"
                link
                type="primary"
                :disabled="materialBusy"
                @click.stop="edit(row)"
                >编辑</el-button
              ><el-button
                v-if="!compactLibrary"
                link
                type="danger"
                :disabled="materialBusy"
                @click.stop="remove(row)"
                >删除</el-button
              ></template
            ></el-table-column
          >
          <template #empty
            ><el-empty description="暂无资料，先选择分类并上传文件"
          /></template> </el-table
        ><el-pagination
          v-model:current-page="page"
          :total="total"
          :page-size="50"
          layout="prev,pager,next"
          @current-change="load"
        /><p class="library-note"
          >资料按版本独立保存，课程引用到明确版本后，权限随课程生效。</p
        ></div
      >
      <el-drawer v-model="detailOpen" title="资料详情" size="min(620px,100vw)" destroy-on-close
        ><section class="library-detail" v-if="detail"
          ><h3 class="file-name">{{ detail.name }}</h3
          ><p>V{{ detail.version_no }} · {{ stateText(detail.state) }}</p
          ><p v-if="detail.error" class="material-error">{{ detail.error }}</p>
          <dl
            ><dt>源文件</dt><dd>{{ detail.file_name }}</dd
            ><dt>文件大小</dt><dd>{{ size(detail.size_bytes) }}</dd
            ><dt>所属分类</dt><dd>{{ detail.category }}</dd></dl
          >
          <div v-if="detail.state === 'READY'" class="material-actions"
            ><el-button
              v-if="
                ['pdf', 'docx', 'pptx', 'xlsx', 'jpg', 'jpeg', 'png', 'webp'].includes(
                  detail.extension
                )
              "
              @click="preview('preview')"
              >预览</el-button
            ><el-button v-if="Number(detail.duration) > 0" @click="preview('play')">播放</el-button
            ><el-button @click="preview('download')">下载原件</el-button></div
          >
          <h3>引用此资料的课程</h3
          ><p v-if="!detail.references?.length" class="muted">尚未被课程草稿或已发布课程引用</p
          ><div
            v-for="r in detail.references"
            :key="`${r.id}-${r.release_no}`"
            class="reference-row"
            >{{ r.title }} ·
            {{ r.reference_type === 'DRAFT' ? '草稿' : `第${r.release_no}版（已发布）` }}</div
          >
          <h3>版本记录</h3
          ><div v-for="v in detail.versions" :key="v.id" class="reference-row"
            ><el-button
              link
              :type="v.id === detail.id ? 'primary' : undefined"
              @click="select({ version_id: v.id })"
              >V{{ v.version_no }} · {{ stateText(v.state) }}</el-button
            ></div
          >
          <p class="library-note"
            >上传新版本后，课程继续使用当前版本，确认替换并发布课程后才会更新。</p
          ><div class="material-actions"
            ><el-button type="primary" :disabled="materialBusy" @click="newVersion"
              >上传新版本</el-button
            ><el-button :disabled="materialBusy" @click="edit({ version_id: detail.id })"
              >编辑资料</el-button
            ><el-button
              type="danger"
              plain
              :disabled="materialBusy"
              @click="remove({ version_id: detail.id })"
              >删除资料</el-button
            ></div
          >
        </section></el-drawer
      >
    </div>
    <el-dialog
      v-model="uploadOpen"
      :title="uploadMaterialId ? '上传资料新版本' : '上传资料'"
      width="min(850px,94vw)"
      :close-on-click-modal="false"
      destroy-on-close
      :before-close="closeUpload"
      ><el-select
        v-model="uploadCategory"
        style="width: 100%; margin-bottom: 18px"
        :disabled="!!uploadMaterialId"
        ><el-option v-for="c in categoryNames" :key="c" :value="c" :label="c" /></el-select
      ><TrainingUploader
        ref="uploader"
        :category="uploadCategory"
        :material-id="uploadMaterialId"
        :material-name="uploadName"
        @complete="uploaded"
    /></el-dialog>
    <el-dialog
      v-model="editOpen"
      title="编辑资料"
      width="min(520px,94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!materialBusy"
      :show-close="!materialBusy"
    >
      <el-form label-position="top" @submit.prevent="saveEdit"
        ><el-form-item label="资料名称" required
          ><el-input
            v-model="editDraft.name"
            maxlength="200"
            show-word-limit
            :disabled="materialBusy" /></el-form-item
        ><el-form-item label="所属分类" required
          ><el-select v-model="editDraft.category" :disabled="materialBusy" style="width: 100%"
            ><el-option
              v-for="c in categoryNames"
              :key="c"
              :value="c"
              :label="c" /></el-select></el-form-item
      ></el-form>
      <p class="library-note">名称和分类会同步到全部版本；如需更换文件内容，请使用“上传新版本”。</p>
      <template #footer
        ><el-button :disabled="materialBusy" @click="editOpen = false">取消</el-button
        ><el-button type="primary" :loading="materialBusy" @click="saveEdit"
          >保存修改</el-button
        ></template
      >
    </el-dialog>
    <el-dialog v-model="previewOpen" title="资料预览" width="min(1000px,94vw)" destroy-on-close
      ><video
        v-if="previewPurpose === 'play'"
        :src="previewUrl"
        controls
        style="width: 100%; max-height: 65vh"
      ></video
      ><img
        v-else-if="detail && ['jpg', 'jpeg', 'png', 'webp'].includes(detail.extension)"
        :src="previewUrl"
        alt="资料预览"
        style="display: block; max-width: 100%; max-height: 65vh; margin: auto" /><PdfPreview
        v-else
        :src="previewUrl"
    /></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ElMessageBox, ElMessage } from 'element-plus'
import { useMediaQuery } from '@vueuse/core'
import { FolderOpened } from '@element-plus/icons-vue'
import PdfPreview from './PdfPreview.vue'
import {
  listMaterials,
  getMaterial,
  listMaterialCategories,
  materialTicket,
  retryMaterial,
  editTrainingMaterial,
  deleteTrainingMaterial,
  stateText,
  type TrainingMaterial,
  type TrainingCategory
} from '@/api/platform/training'
import TrainingUploader from './TrainingUploader.vue'
import TrainingCategoryManager from './TrainingCategoryManager.vue'
const compactLibrary = useMediaQuery('(max-width: 600px)')
const keyword = ref(''),
  category = ref(''),
  page = ref(1),
  total = ref(0),
  loading = ref(false)
const rows = ref<TrainingMaterial[]>([]),
  detail = ref<Record<string, any>>()
const detailOpen = ref(false)
const categoryRows = ref<TrainingCategory[]>([])
const categoryNames = computed(() => categoryRows.value.map((c) => c.name))
const uploadOpen = ref(false),
  uploadCategory = ref('AI短视频获客'),
  uploadMaterialId = ref(''),
  uploadName = ref('')
const uploader = ref<InstanceType<typeof TrainingUploader>>()
const editOpen = ref(false),
  materialBusy = ref(false)
const editDraft = ref({ materialId: '', expectedRevision: 0, name: '', category: '' })
const previewOpen = ref(false),
  previewPurpose = ref(''),
  previewUrl = ref('')
let timer: ReturnType<typeof setInterval> | undefined,
  sequence = 0,
  selection = 0
function size(value: number) {
  return value < 1024
    ? `${value} B`
    : value < 1048576
      ? `${(value / 1024).toFixed(1)} KB`
      : `${(value / 1048576).toFixed(1)} MB`
}
async function load() {
  const gen = ++sequence
  loading.value = true
  try {
    await loadCategories()
    if (gen !== sequence) return
    const result = await listMaterials(keyword.value, category.value, page.value)
    if (gen !== sequence) return
    rows.value = result.list
    total.value = result.total
    if (detail.value) {
      const id = detail.value.id
      const next = await getMaterial(id)
      if (detail.value?.id === id) detail.value = next
    }
  } finally {
    if (gen === sequence) loading.value = false
  }
}
async function loadCategories() {
  categoryRows.value = await listMaterialCategories()
  if (category.value && !categoryNames.value.includes(category.value)) category.value = ''
  if (!categoryNames.value.includes(uploadCategory.value))
    uploadCategory.value = categoryNames.value.includes('未分类')
      ? '未分类'
      : categoryNames.value[0] || ''
}
async function categoriesChanged() {
  category.value = ''
  page.value = 1
  await load()
}
function search() {
  page.value = 1
  load()
}
function openUpload() {
  uploadMaterialId.value = ''
  uploadName.value = ''
  uploadOpen.value = true
}
function chooseCategory(value: string) {
  category.value = value
  search()
}
async function select(row: { version_id: string }) {
  const gen = ++selection
  const next = await getMaterial(row.version_id)
  if (gen === selection) {
    detail.value = next
    detailOpen.value = true
  }
}
async function retry(row: TrainingMaterial) {
  await retryMaterial(row.version_id)
  ElMessage.success('已重新排队处理')
  await load()
}
function newVersion() {
  if (!detail.value) return
  uploadMaterialId.value = detail.value.material_id
  uploadName.value = detail.value.name
  uploadCategory.value = detail.value.category
  uploadOpen.value = true
}
async function edit(row: { version_id: string }) {
  const gen = ++selection,
    value = await getMaterial(row.version_id)
  if (gen !== selection) return
  detail.value = value
  editDraft.value = {
    materialId: value.material_id,
    expectedRevision: Number(value.material_revision),
    name: value.name,
    category: value.category
  }
  editOpen.value = true
}
async function saveEdit() {
  if (materialBusy.value) return
  const input = { ...editDraft.value, name: editDraft.value.name.trim() }
  if (!input.name || !categoryNames.value.includes(input.category)) {
    ElMessage.warning('请填写资料名称并选择分类')
    return
  }
  materialBusy.value = true
  try {
    await editTrainingMaterial(input.materialId, {
      expectedRevision: input.expectedRevision,
      name: input.name,
      category: input.category
    })
    editOpen.value = false
    keyword.value = ''
    category.value = input.category
    page.value = 1
    ElMessage.success('资料信息已更新')
    await load()
  } finally {
    materialBusy.value = false
  }
}
async function remove(row: { version_id: string }) {
  if (materialBusy.value) return
  const value = await getMaterial(row.version_id)
  try {
    await ElMessageBox.confirm(
      `确认删除资料“${value.name}”及其全部版本？删除后将从资料库移除；课程仍在引用或正在处理的资料不能删除。`,
      '删除资料',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  materialBusy.value = true
  try {
    await deleteTrainingMaterial(value.material_id, Number(value.material_revision))
    if (detail.value?.material_id === value.material_id) {
      selection++
      detail.value = undefined
      detailOpen.value = false
      previewOpen.value = false
    }
    ElMessage.success('资料已删除')
    await load()
  } finally {
    materialBusy.value = false
  }
}
async function uploaded(id: string) {
  category.value = uploadCategory.value
  keyword.value = ''
  page.value = 1
  await load()
  await select({ version_id: id })
  if (!uploader.value?.pending()) {
    uploadOpen.value = false
    ElMessage.success('资料已上传并处理完成')
  }
}
async function preview(purpose: string) {
  if (!detail.value) return
  previewPurpose.value = purpose
  previewUrl.value = await materialTicket(detail.value.id, purpose)
  if (purpose === 'download') {
    const a = document.createElement('a')
    a.href = previewUrl.value
    a.download = detail.value.file_name
    a.rel = 'noreferrer'
    a.click()
  } else previewOpen.value = true
}
async function closeUpload(done: () => void) {
  if (uploader.value?.hasActiveTransfer())
    await ElMessageBox.confirm(
      '仍有文件正在上传，确认关闭？重新选择原文件可继续上传；已经在后台处理的文件不受影响。',
      '关闭上传'
    )
  done()
}
onMounted(async () => {
  await load()
  timer = setInterval(() => {
    if (
      !loading.value &&
      rows.value.some((r) => ['UPLOADING', 'UPLOADED', 'PROCESSING'].includes(r.state))
    )
      load()
  }, 5000)
})
onBeforeUnmount(() => {
  sequence++
  selection++
  clearInterval(timer)
})
</script>
<style scoped src="./training.css">
@media (width <= 600px) {
  .training-toolbar > .el-select {
    width: 100%;
    max-width: 100%;
    flex-basis: 100%;
  }
}
</style>
<style scoped>
.library-grid {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  gap: 22px;
}

.library-folders {
  padding-right: 14px;
  border-right: 1px solid #eee8df;
}

.library-folders button {
  display: flex;
  width: 100%;
  padding: 13px 8px;
  color: #6f665a;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 6px;
  align-items: center;
  gap: 10px;
}

.library-folders .active {
  color: #89512a;
  background: #f8eddd;
}

.library-list {
  min-width: 0;
}

.file-name {
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.file-meta {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  color: #776e64;
}

.library-detail {
  min-width: 0;
  padding: 0;
}

.library-detail h2 {
  margin-bottom: 22px;
}

.library-detail h3 {
  margin-top: 24px;
}

.library-detail h3:first-child {
  margin-top: 0;
}

.library-detail p {
  font-size: 13px;
  line-height: 1.8;
  color: #776e64;
}

.library-detail dl {
  display: grid;
  grid-template-columns: 85px minmax(0, 1fr);
  gap: 12px 10px;
  font-size: 13px;
}

.library-detail dt {
  color: #776e64;
}

.library-detail dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.material-error {
  color: #b3402a !important;
}

.reference-row {
  padding: 11px 0;
  font-size: 13px;
  border-bottom: 1px solid #eee8df;
  overflow-wrap: anywhere;
}

.material-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.material-actions .el-button {
  margin: 0;
}

.library-note {
  margin-top: 22px;
  font-size: 12px;
  line-height: 1.8;
  color: #776e64;
}

.file-name-summary {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.training-toolbar .el-select {
  max-width: 180px;
}

@media (width <= 1250px) {
  .library-grid {
    grid-template-columns: 140px minmax(0, 1fr);
  }

  .library-detail {
    padding: 0;
  }
}

@media (width <= 760px) {
  .library-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .library-folders {
    display: none;
  }

  .library-detail {
    grid-column: 1;
  }

  .library-list {
    overflow: auto;
  }
}

@media (width <= 600px) {
  .training-toolbar > .el-select {
    width: 100%;
    max-width: 100%;
    flex-basis: 100%;
  }
}
</style>
