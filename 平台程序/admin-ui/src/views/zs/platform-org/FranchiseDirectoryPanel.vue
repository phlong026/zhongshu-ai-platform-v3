<template>
  <section class="franchise-directory">
    <div class="directory-heading">
      <div><h2>加盟商名录</h2><p>先登记品牌与区域，之后可从名录选择并开通账号。</p></div>
      <div class="directory-actions">
        <el-button :disabled="busy" @click="openEditor()">手动登记</el-button>
        <el-button type="primary" :disabled="busy" @click="openImport">Excel 批量导入</el-button>
      </div>
    </div>
    <div class="directory-filters">
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索加盟商名称、品牌或地区"
        @keyup.enter="search"
      />
      <el-select v-model="matchStatus" clearable placeholder="全部资料状态" @change="search">
        <el-option label="已确认区县" value="RESOLVED" />
        <el-option label="部分区域待确认" value="PARTIAL" />
        <el-option label="区域待确认" value="UNRESOLVED" />
      </el-select>
      <el-button :loading="loading" @click="search">查询</el-button>
    </div>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无符合条件的名录记录">
      <el-table-column label="名称 / 品牌" min-width="210">
        <template #default="{ row }"
          ><strong>{{ row.name || row.originalRegionText }}</strong
          ><div class="muted">{{ row.brandName || row.brand }}</div
          ><div v-if="row.publicName" class="muted">地图：{{ row.publicName }}</div></template
        >
      </el-table-column>
      <el-table-column label="地区资料" min-width="240">
        <template #default="{ row }"
          ><div>{{ row.originalRegionText }}</div
          ><div class="muted">{{ regionNames(row.regionCodes) }}</div></template
        >
      </el-table-column>
      <el-table-column label="记录类型" min-width="140">
        <template #default="{ row }">{{
          row.recordType === 'FRANCHISE' ? '加盟商档案' : '品牌区域资料'
        }}</template>
      </el-table-column>
      <el-table-column label="资料状态" min-width="150">
        <template #default="{ row }"
          ><el-tag :type="row.matchStatus === 'RESOLVED' ? 'success' : 'warning'">{{
            statusLabel(row.matchStatus)
          }}</el-tag></template
        >
      </el-table-column>
      <el-table-column label="账号" width="100">
        <template #default="{ row }">{{ row.linkedOrgId ? '已关联' : '未开通' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="busy" @click="openMapEditor(row)">地图名称</el-button>
          <el-button
            link
            type="primary"
            :disabled="busy || !!row.linkedOrgId"
            @click="openEditor(row)"
            >修订</el-button
          >
          <el-button
            link
            type="primary"
            :disabled="busy || !!row.linkedOrgId || row.matchStatus !== 'RESOLVED'"
            @click="emit('open-account', row)"
            >开通账号</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <p class="directory-note"
      >区域资料按记录统计；一个记录关联多个县区时，仍是一条记录。已开通组织的信息请在“已开通组织”中维护。</p
    >
    <el-pagination
      v-model:current-page="pageNo"
      :page-size="20"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="loadList"
      class="directory-pagination"
    />

    <el-dialog
      v-model="mapEditVisible"
      title="公开地图名称"
      width="min(520px, 96vw)"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-input v-model.trim="mapPublicName" maxlength="128" :disabled="saving" placeholder="不含人员姓名；留空则显示品牌与行政区" />
      <el-alert v-if="mapEditError" :title="mapEditError" type="error" :closable="false" />
      <template #footer>
        <el-button :disabled="saving" @click="mapEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMapDisplay">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importVisible"
      title="Excel 批量导入加盟商名录"
      width="min(1000px, 96vw)"
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @closed="clearImport"
    >
      <p>支持逐行登记表，以及按品牌横向排列的原始地区表。地区资料会保留原文，不自动创建账号。</p>
      <p class="muted">同一文件重复导入可去重；首次使用逐行表时请填写稳定、唯一的“来源编号”，后续更新持续保留。</p>
      <p class="muted"
        >逐行表字段：品牌、加盟商名称、原始地区、省份、城市、区县代码；仅有地区资料时可不填加盟商名称。</p
      >
      <label class="file-label"
        >选择 .xlsx 文件（不超过 5 MB）
        <input ref="fileInput" type="file" accept=".xlsx" :disabled="busy" @change="selectFile" />
      </label>
      <div class="import-actions">
        <span>{{ importFile?.name || '尚未选择文件' }}</span>
        <el-button :disabled="!importFile || busy" :loading="prechecking" @click="precheck"
          >检查文件</el-button
        >
      </div>
      <el-alert v-if="importError" :title="importError" type="error" :closable="false" show-icon />
      <template v-if="importResult">
        <div class="import-summary" aria-live="polite">
          <strong>{{ importSubmitted ? '导入结果' : '文件检查结果' }}</strong>
          <span>读取 {{ importResult.total }} 条</span
          ><span>待核实 {{ importResult.unresolved }} 条</span>
          <span>错误 {{ importResult.failed }} 条</span>
          <template v-if="importSubmitted"
            ><span>新增 {{ importResult.created }} 条</span
            ><span>更新 {{ importResult.updated }} 条</span
            ><span>已存在 {{ importResult.skipped }} 条</span></template
          >
        </div>
        <el-alert
          v-if="importResult.failed"
          title="错误行不会入库；其他可用记录可以导入。请根据下表补充或修正错误行。"
          type="warning"
          :closable="false"
        />
        <el-table :data="importResult.rows" max-height="360" row-key="sourceKey">
          <el-table-column label="来源" width="140"
            ><template #default="{ row }"
              >{{ row.sourceSheet }}!{{ row.sourceCell || row.sourceRow }}</template
            ></el-table-column
          >
          <el-table-column prop="brand" label="品牌" width="110" />
          <el-table-column prop="originalRegionText" label="地区原文" min-width="200" />
          <el-table-column label="匹配" width="150"
            ><template #default="{ row }">{{
              statusLabel(row.matchStatus)
            }}</template></el-table-column
          >
          <el-table-column label="处理结果" min-width="220"
            ><template #default="{ row }"
              ><span :class="{ 'field-error': row.status === 'FAILED' }">{{
                importStatusLabel(row.status)
              }}<template v-if="row.message">：{{ row.message }}</template></span></template
            ></el-table-column
          >
        </el-table>
      </template>
      <template #footer>
        <el-button :disabled="busy" @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" :disabled="!canImport" @click="submitImport"
          >导入可用记录</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="editVisible"
      :title="editing ? '修订名录记录' : '手动登记加盟商'"
      width="min(680px, 96vw)"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="品牌"
          ><el-input v-model.trim="draft.brand" maxlength="40" :disabled="saving"
        /></el-form-item>
        <el-form-item label="记录类型"
          ><el-radio-group v-model="draft.recordType" :disabled="saving">
            <el-radio value="COVERAGE">品牌区域资料</el-radio
            ><el-radio value="FRANCHISE">已确认加盟商档案</el-radio>
          </el-radio-group></el-form-item
        >
        <el-form-item label="加盟商名称"
          ><el-input
            v-model.trim="draft.name"
            maxlength="120"
            :disabled="saving"
            placeholder="真实加盟商名称；区域资料可留空"
        /></el-form-item>
        <el-form-item label="地区原文"
          ><el-input
            v-model.trim="draft.originalRegionText"
            type="textarea"
            :rows="2"
            maxlength="500"
            :disabled="saving"
            placeholder="保留原始省、市、县区描述"
        /></el-form-item>
        <el-form-item label="已确认行政区域">
          <el-select
            v-model="draft.regionCodes"
            multiple
            filterable
            remote
            :remote-method="searchRegions"
            :disabled="saving || !regions.length"
            placeholder="搜索省、市或县区名称后选择"
            class="region-select"
          >
            <el-option
              v-for="region in visibleRegions"
              :key="region.code"
              :value="region.code"
              :label="regionLabel(region)"
            />
          </el-select>
          <span class="muted"
            >只选择能够确认的区域；只确定城市时请选择城市，具体区县仍待核实。</span
          >
          <button v-if="regionError" type="button" @click="loadRegions"
            >地区加载失败，点击重试</button
          >
        </el-form-item>
      </el-form>
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
      <template #footer
        ><el-button :disabled="saving" @click="editVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveRecord"
          >保存记录</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch
} from 'vue'
import { ElMessage } from 'element-plus'
import {
  getFranchiseDirectory,
  getDirectoryRegions,
  createDirectoryRecord,
  updateDirectoryRecord,
  updateDirectoryMapDisplay,
  precheckDirectoryImport,
  submitDirectoryImport,
  type DirectoryItem,
  type DirectoryInput,
  type DirectoryImportResult,
  type MapRegion
} from '@/api/zs/franchise-directory'

const emit = defineEmits<{ 'open-account': [record: DirectoryItem]; busy: [value: boolean] }>()
const rows = ref<DirectoryItem[]>([])
const total = ref(0)
const pageNo = ref(1)
const keyword = ref('')
const matchStatus = ref('')
const loading = ref(false)
const listError = ref('')
const regions = ref<MapRegion[]>([])
const regionError = ref(false)
const regionKeyword = ref('')
const editVisible = ref(false)
const editing = ref<DirectoryItem | null>(null)
const saving = ref(false)
const editError = ref('')
const mapEditVisible = ref(false)
const mapEditing = ref<DirectoryItem | null>(null)
const mapPublicName = ref('')
const mapEditError = ref('')
const draft = reactive<DirectoryInput>({
  name: '',
  brand: '',
  recordType: 'COVERAGE',
  originalRegionText: '',
  regionCodes: []
})
const importVisible = ref(false)
const importFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement>()
const importResult = ref<DirectoryImportResult | null>(null)
const importError = ref('')
const prechecking = ref(false)
const importing = ref(false)
const importSubmitted = ref(false)
const busy = computed(() => saving.value || prechecking.value || importing.value)
const canImport = computed(
  () =>
    !!importFile.value &&
    !!importResult.value &&
    importResult.value.total > importResult.value.failed &&
    !busy.value &&
    !importSubmitted.value
)
let active = true
let listGeneration = 0
let importGeneration = 0
let lifecycleGeneration = 0

watch(busy, (value) => emit('busy', value), { flush: 'sync' })

const byCode = computed(() => new Map(regions.value.map((r) => [r.code, r])))
const visibleRegions = computed(() => {
  const query = regionKeyword.value.trim()
  const selected = new Set(draft.regionCodes)
  const matches = regions.value
    .filter((r) => (query ? regionLabel(r).includes(query) : r.level === 'province'))
    .slice(0, 80)
  return [
    ...regions.value.filter((r) => selected.has(r.code)),
    ...matches.filter((r) => !selected.has(r.code))
  ]
})
function regionLabel(region: MapRegion) {
  return [...new Set([region.provinceName, region.cityName, region.name].filter(Boolean))].join(
    ' / '
  )
}
function regionNames(codes: string[]) {
  return (
    (codes || [])
      .map((code) => byCode.value.get(code))
      .filter((r): r is MapRegion => !!r)
      .map(regionLabel)
      .join('、') || '具体区县待确认'
  )
}
function statusLabel(status: string) {
  return (
    (
      { RESOLVED: '已确认区县', PARTIAL: '部分区域待确认', UNRESOLVED: '区域待确认' } as Record<
        string,
        string
      >
    )[status] || '待检查'
  )
}
function importStatusLabel(status: string) {
  return (
    (
      {
        OK: '检查通过',
        CREATED: importSubmitted.value ? '已新增' : '将新增',
        UPDATED: importSubmitted.value ? '已更新' : '将更新',
        SKIPPED: importSubmitted.value ? '已存在，未重复导入' : '已存在，将跳过',
        FAILED: importSubmitted.value ? '导入失败' : '检查未通过'
      } as Record<string, string>
    )[status] || status
  )
}
function searchRegions(query: string) {
  regionKeyword.value = query
}

async function loadList() {
  const generation = ++listGeneration
  loading.value = true
  listError.value = ''
  try {
    const result = await getFranchiseDirectory({
      keyword: keyword.value.trim(),
      matchStatus: matchStatus.value || undefined,
      pageNo: pageNo.value,
      pageSize: 20
    })
    if (!active || generation !== listGeneration) return
    rows.value = result.list
    total.value = result.total
  } catch (error: any) {
    if (!active || generation !== listGeneration) return
    rows.value = []
    total.value = 0
    listError.value = error?.msg || error?.message || '名录加载失败，请重试'
  } finally {
    if (active && generation === listGeneration) loading.value = false
  }
}
function search() {
  pageNo.value = 1
  void loadList()
}

async function loadRegions() {
  const generation = lifecycleGeneration
  regionError.value = false
  try {
    const result = await getDirectoryRegions()
    if (active && generation === lifecycleGeneration) regions.value = result
  } catch {
    if (active && generation === lifecycleGeneration) regionError.value = true
  }
}

function openEditor(item?: DirectoryItem) {
  if (busy.value || item?.linkedOrgId) return
  editing.value = item || null
  Object.assign(draft, {
    name: item?.name || '',
    brand: item?.brandName || item?.brand || '',
    recordType: item?.recordType || 'COVERAGE',
    originalRegionText: item?.originalRegionText || '',
    regionCodes: [...(item?.regionCodes || [])],
    provinceCode: item?.provinceCode || undefined,
    cityCode: item?.cityCode || undefined,
    expectedVersion: item?.version
  })
  regionKeyword.value = ''
  editError.value = ''
  editVisible.value = true
}

function openMapEditor(item: DirectoryItem) {
  if (busy.value) return
  mapEditing.value = item
  mapPublicName.value = item.publicName || ''
  mapEditError.value = ''
  mapEditVisible.value = true
}

async function saveMapDisplay() {
  if (busy.value || !mapEditing.value) return
  const generation = lifecycleGeneration
  const item = mapEditing.value
  saving.value = true
  mapEditError.value = ''
  try {
    await updateDirectoryMapDisplay(item.id, {
      publicName: mapPublicName.value.trim(),
      expectedVersion: item.version
    })
    if (!active || generation !== lifecycleGeneration) return
    mapEditVisible.value = false
    ElMessage.success('地图名称已保存')
    await loadList()
  } catch (error: any) {
    if (active && generation === lifecycleGeneration) {
      mapEditError.value = error?.msg || error?.message || '保存失败，请重新打开记录后核对'
    }
  } finally {
    if (active && generation === lifecycleGeneration) saving.value = false
  }
}

async function saveRecord() {
  if (busy.value) return
  editError.value = ''
  if (
    !draft.brand.trim() ||
    !draft.originalRegionText.trim() ||
    (draft.recordType === 'FRANCHISE' && !draft.name.trim())
  ) {
    editError.value = '请填写品牌、地区原文；加盟商档案还需填写真实名称。'
    return
  }
  if (regionError.value) {
    editError.value = '请先重新加载行政区域后再保存。'
    return
  }
  saving.value = true
  const generation = lifecycleGeneration
  try {
    // Region scope is derived again on the server from the selected codes.
    const payload: DirectoryInput = {
      name: draft.name.trim(),
      brand: draft.brand.trim(),
      recordType: draft.recordType,
      originalRegionText: draft.originalRegionText.trim(),
      regionCodes: [...draft.regionCodes],
      expectedVersion: editing.value?.version
    }
    if (editing.value) await updateDirectoryRecord(editing.value.id, payload)
    else await createDirectoryRecord(payload)
    if (!active || generation !== lifecycleGeneration) return
    editVisible.value = false
    ElMessage.success('名录已保存')
    await loadList()
  } catch (error: any) {
    if (active && generation === lifecycleGeneration)
      editError.value = error?.msg || error?.message || '保存失败，请刷新记录后重试'
  } finally {
    if (generation === lifecycleGeneration) saving.value = false
  }
}

function openImport() {
  clearImport()
  importVisible.value = true
}
function clearImport() {
  ++importGeneration
  importFile.value = null
  importResult.value = null
  importSubmitted.value = false
  importError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}
function selectFile(event: Event) {
  if (busy.value) return
  ++importGeneration
  importResult.value = null
  importSubmitted.value = false
  importError.value = ''
  const file = (event.target as HTMLInputElement).files?.[0] || null
  if (file && (!/\.xlsx$/i.test(file.name) || file.size > 5 * 1024 * 1024 || file.size === 0)) {
    importFile.value = null
    importError.value = '请选择不超过 5 MB 的有效 .xlsx 文件。'
    return
  }
  importFile.value = file
}
async function precheck() {
  if (!importFile.value || busy.value) return
  const generation = ++importGeneration
  prechecking.value = true
  importResult.value = null
  importSubmitted.value = false
  importError.value = ''
  try {
    const result = await precheckDirectoryImport(importFile.value)
    if (active && generation === importGeneration) importResult.value = result
  } catch (error: any) {
    if (active && generation === importGeneration)
      importError.value = error?.msg || error?.message || '文件检查失败，请重试'
  } finally {
    if (active && generation === importGeneration) prechecking.value = false
  }
}
async function submitImport() {
  if (!canImport.value || !importFile.value) return
  const generation = ++importGeneration
  importing.value = true
  importError.value = ''
  try {
    const result = await submitDirectoryImport(importFile.value)
    if (!active || generation !== importGeneration) return
    importResult.value = result
    importSubmitted.value = true
    ElMessage.success(`导入处理完成：新增 ${result.created} 条，失败 ${result.failed} 条`)
    await loadList()
  } catch (error: any) {
    if (active && generation === importGeneration) {
      importError.value =
        error?.msg ||
        error?.message ||
        '导入请求未完成，请重新检查当前文件后重试；同一文件已入库的记录会被识别。'
      importResult.value = null
    }
  } finally {
    if (active && generation === importGeneration) importing.value = false
  }
}
onMounted(() => {
  void loadList()
  void loadRegions()
})
onActivated(() => {
  if (active) return
  active = true
  void loadList()
  void loadRegions()
})
function dispose() {
  active = false
  ++lifecycleGeneration
  ++listGeneration
  ++importGeneration
  clearImport()
  rows.value = []
  total.value = 0
  regions.value = []
  editVisible.value = false
  importVisible.value = false
  editing.value = null
  mapEditVisible.value = false
  mapEditing.value = null
  mapPublicName.value = ''
  mapEditError.value = ''
  Object.assign(draft, { name: '', brand: '', originalRegionText: '', regionCodes: [] })
  saving.value = false
  importing.value = false
  prechecking.value = false
  emit('busy', false)
}
onDeactivated(dispose)
onBeforeUnmount(dispose)
</script>

<style scoped lang="scss">
.franchise-directory {
  padding-top: 18px;
  min-width: 0;
}
.directory-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 20px;
}
.directory-heading h2 {
  margin: 0;
  font-size: 20px;
  color: #4b2a16;
}
.directory-heading p,
.muted,
.directory-note {
  color: #7b6a5b;
  font-size: 13px;
  line-height: 1.6;
}
.directory-heading p {
  margin: 6px 0 0;
}
.directory-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.directory-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.directory-filters {
  display: grid;
  grid-template-columns: minmax(200px, 420px) 180px 80px;
  gap: 12px;
  margin-bottom: 18px;
}
.directory-pagination {
  justify-content: flex-end;
  margin-top: 18px;
}
.region-select {
  width: 100%;
}
.file-label {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  background: #faf8f4;
  border-radius: 10px;
}
.file-label input {
  max-width: 100%;
}
.import-actions,
.import-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin: 16px 0;
  overflow-wrap: anywhere;
}
.field-error {
  color: var(--el-color-danger);
}
@media (width <= 720px) {
  .directory-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }
  .directory-filters {
    grid-template-columns: minmax(0, 1fr) 80px;
  }
  .directory-filters > :first-child {
    grid-column: 1 / -1;
  }
  .directory-pagination {
    justify-content: flex-start;
  }
}
</style>
