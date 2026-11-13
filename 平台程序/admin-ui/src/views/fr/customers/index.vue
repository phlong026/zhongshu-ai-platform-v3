<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">客资</h1>
        <div class="zs-page-subtitle">
          待关联客资的关联/作废在 Web 处理；批量导入仅老板/负责人可发起。
        </div>
      </div>
      <el-button v-if="canImport" type="primary" @click="openImport">批量导入</el-button>
    </div>

    <el-alert
      v-if="!canHandle"
      title="当前工作身份无客资处理权限（需 franchise:lead:handle）"
      type="warning"
      show-icon
      class="mb-16px"
    />

    <template v-else>
      <el-tabs v-model="activeTab" class="customers-tabs">
        <el-tab-pane label="待关联" name="pending">
          <div class="zs-table-card">
            <div class="section-head">
              <span class="section-title">待关联客资</span>
              <el-button size="small" :loading="pendingLoading" @click="loadPending"
                >刷新</el-button
              >
            </div>
            <el-alert
              v-if="actionNotice"
              :title="actionNotice"
              :type="actionError ? 'error' : 'success'"
              show-icon
              class="mb-12px"
            />
            <el-table v-loading="pendingLoading" :data="pending" empty-text="暂无待关联客资">
              <el-table-column prop="name" label="姓名" width="110" />
              <el-table-column prop="phone" label="手机" width="130" />
              <el-table-column prop="need" label="需求" min-width="180" show-overflow-tooltip />
              <el-table-column prop="source" label="来源" width="130" />
              <el-table-column prop="note" label="最近说明" min-width="160" show-overflow-tooltip />
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openLink(row)">关联</el-button>
                  <el-button link type="danger" @click="openInvalid(row)">作废</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="客资列表" name="customers">
          <div class="zs-table-card">
            <div class="filters">
              <el-input
                v-model="customerFilters.keyword"
                placeholder="姓名/手机号"
                clearable
                class="filter-input"
                @keyup.enter="searchCustomers"
              />
              <el-select v-model="customerFilters.status" class="filter-select">
                <el-option
                  v-for="option in CUSTOMER_STATUS_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-select
                v-model="customerFilters.ownerUserId"
                class="filter-select"
                clearable
                placeholder="负责人"
              >
                <el-option
                  v-for="owner in ownerOptions"
                  :key="owner.userId"
                  :label="owner.name || `用户 ${owner.userId}`"
                  :value="owner.userId"
                />
              </el-select>
              <el-button type="primary" :loading="customerLoading" @click="searchCustomers"
                >查询</el-button
              >
              <el-button :loading="customerLoading" @click="reloadCustomers">刷新</el-button>
            </div>
            <el-alert
              v-if="customerNotice"
              :title="customerNotice"
              :type="customerError ? 'error' : 'success'"
              show-icon
              class="mb-12px"
            />
            <el-table v-loading="customerLoading" :data="customers.list" empty-text="暂无客资">
              <el-table-column prop="name" label="姓名" width="110" />
              <el-table-column prop="phone" label="手机" width="130" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="customerStatusTag(row.status)">
                    {{ customerStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="负责人" width="120">
                <template #default="{ row }">{{ row.ownerName || row.ownerUserId }}</template>
              </el-table-column>
              <el-table-column prop="source" label="来源" width="120" show-overflow-tooltip />
              <el-table-column label="下次回访" width="120">
                <template #default="{ row }">{{ row.nextVisitDate || '-' }}</template>
              </el-table-column>
              <el-table-column label="最近跟进" width="170">
                <template #default="{ row }">{{ formatTime(row.lastFollowAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="canTransfer" link type="primary" @click="openTransfer(row)"
                    >转让</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-if="customers.total > customerPageSize"
              class="customers-pager"
              background
              layout="total, prev, pager, next"
              :total="customers.total"
              :page-size="customerPageSize"
              :current-page="customerPageNo"
              :disabled="customerLoading"
              @current-change="onCustomerPageChange"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="linkDialog" title="关联客资" width="420px">
      <p>
        将 <strong>{{ actionRow?.name }}</strong> （{{
          actionRow?.phone
        }}）关联到同手机号的原有客户？
      </p>
      <div class="hint">关联后该客资转为已转化，原客户资料不变。</div>
      <template #footer>
        <el-button @click="linkDialog = false">取消</el-button>
        <el-button type="primary" :loading="actionBusy" @click="confirmLink">确认关联</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="invalidDialog" title="作废客资" width="480px">
      <p>
        作废 <strong>{{ actionRow?.name }}</strong> （{{ actionRow?.phone }}）的待关联客资。
      </p>
      <el-input
        v-model="invalidNote"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="请填写作废原因（必填）"
      />
      <template #footer>
        <el-button @click="invalidDialog = false">取消</el-button>
        <el-button type="danger" :loading="actionBusy" @click="confirmInvalid">确认作废</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferDialog" title="转让客资" width="480px">
      <p>
        将 <strong>{{ transferRow?.name }}</strong
        >（{{ transferRow?.phone }}）转交给：
      </p>
      <el-select v-model="transferTargetUserId" class="transfer-select" placeholder="选择接手人">
        <el-option
          v-for="candidate in transferCandidates"
          :key="candidate.userId"
          :label="`${candidate.name}（${candidate.roleCode}${candidate.teamName ? ' · ' + candidate.teamName : ''}）`"
          :value="candidate.userId"
        />
      </el-select>
      <el-input
        v-model="transferReason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="请填写转让原因（必填）"
        class="transfer-reason"
      />
      <div class="hint">接手人仅限本组织在岗业务人员；负责人只能转给本人管理团队的人。</div>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="transferBusy" @click="confirmTransfer"
          >确认转让</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="客资批量导入" width="880px" @closed="resetImport">
      <div class="import-toolbar">
        <el-button @click="downloadImportTemplate">下载模板（CSV）</el-button>
        <el-button :disabled="importBusy" @click="importFileInput?.click()"
          >选择 CSV 文件</el-button
        >
        <input
          ref="importFileInput"
          type="file"
          accept=".csv,text/csv"
          class="import-file"
          @change="onImportFile"
        />
        <span v-if="importFileName" class="import-file-name">{{ importFileName }}</span>
      </div>
      <div class="hint">
        模板列：姓名 / 手机 / 需求 / 来源；单批 1-{{ LEAD_IMPORT_MAX_ROWS }} 行（文件上限
        {{
          CSV_MAX_FILE_BYTES / 1024 / 1024
        }}MB）。同手机号已有客户不会覆盖，落“待关联”等负责人处理。
      </div>
      <el-alert
        v-if="importNotice"
        :title="importNotice"
        type="warning"
        show-icon
        class="import-error"
      />
      <el-alert
        v-for="error in importParseErrors"
        :key="error"
        :title="error"
        type="error"
        show-icon
        class="import-error"
      />
      <el-table v-if="importRows.length" :data="importRows" max-height="220" size="small">
        <el-table-column prop="line" label="行号" width="70" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="phone" label="手机" width="130" />
        <el-table-column prop="need" label="需求" min-width="200" />
        <el-table-column prop="source" label="来源" min-width="120" />
        <el-table-column prop="error" label="解析提示" min-width="180" />
      </el-table>

      <template v-if="importPrecheckResult">
        <div class="import-summary">
          预检结果：共 {{ importPrecheckResult.total }} 行，将创建
          {{ importPrecheckResult.created }}，跳过 {{ importPrecheckResult.skipped }}，失败
          {{ importPrecheckResult.failed }}（提交时服务端逐行重新校验）
        </div>
        <el-table :data="importPrecheckResult.rows" max-height="300" size="small">
          <el-table-column prop="line" label="行号" width="70" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="phone" label="手机" width="125" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusMeta[row.status]?.type || 'info'">
                {{ statusMeta[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="240">
            <template #default="{ row }">{{ row.message || row.note || '' }}</template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="importSubmitResult">
        <el-alert
          :title="`提交完成：新建 ${importSubmitResult.created}，跳过 ${importSubmitResult.skipped}，失败 ${importSubmitResult.failed}`"
          :type="importSubmitResult.failed ? 'warning' : 'success'"
          show-icon
          class="import-error"
        />
        <el-table :data="importSubmitResult.rows" max-height="300" size="small">
          <el-table-column prop="line" label="行号" width="70" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="phone" label="手机" width="125" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusMeta[row.status]?.type || 'info'">
                {{ statusMeta[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="240">
            <template #default="{ row }">{{ row.message || row.note || '' }}</template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button :disabled="!canPrecheckImport" :loading="importBusy" @click="runImportPrecheck"
          >预检</el-button
        >
        <el-button
          type="primary"
          :disabled="!canSubmitImport"
          :loading="importBusy"
          @click="submitImport"
          >确认提交</el-button
        >
        <el-button v-if="canRetryFailed" :disabled="importBusy" @click="retryFailedRows"
          >仅重提失败行</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import * as api from '@/api/platform/leads'
import * as customerApi from '@/api/platform/customers'
import type { CustomerRow, OwnerOption, TransferCandidate } from '@/api/platform/customers'
import {
  CUSTOMER_STATUS_OPTIONS,
  customerStatusLabel,
  customerStatusTag,
  normalizeCustomerQuery,
  transferIdempotencyKey
} from './model'
import type { LeadImportResult, PendingLead } from '@/api/platform/leads'
import {
  CSV_MAX_FILE_BYTES,
  LEAD_IMPORT_MAX_ROWS,
  buildLeadImportTemplateCsv,
  createImportGenerationGuard,
  failedLeadRowsForRetry,
  newImportBatchId,
  parseLeadImportCsv,
  runGuardedImportRequest,
  withinFileSizeLimit,
  withinLeadImportLimit,
  type LeadImportRow
} from '@/views/fr/org-staff/leadImportModel'

defineOptions({ name: 'FranchiseCustomers' })

const identity = usePlatformIdentityStore()
const canHandle = computed(() => identity.can('franchise:lead:handle'))
const canImport = computed(() => {
  const role = identity.currentPost?.roleCode
  return canHandle.value && (role === 'boss' || role === 'owner')
})
const orgId = () => identity.currentPost?.orgId as any

const activeTab = ref('pending')

// ===== 客资列表（批4）=====
const canQueryCustomers = computed(() => identity.can('franchise:customer:query'))
const canTransfer = computed(() => identity.can('franchise:customer:transfer'))
const customerFilters = ref<{ keyword: string; status: string; ownerUserId: string }>({
  keyword: '',
  status: '',
  ownerUserId: ''
})
const customers = ref<{ list: CustomerRow[]; total: number }>({ list: [], total: 0 })
const customerPageNo = ref(1)
const customerPageSize = 20
const customerLoading = ref(false)
const customerNotice = ref('')
const customerError = ref(false)
let customerGeneration = 0
const ownerOptions = ref<OwnerOption[]>([])
const ownerOptionsLoaded = ref(false)
const transferCandidates = ref<TransferCandidate[]>([])
const transferDialog = ref(false)
const transferBusy = ref(false)
const transferRow = ref<CustomerRow | null>(null)
const transferTargetUserId = ref('')
const transferReason = ref('')

const loadCustomers = async (): Promise<boolean> => {
  if (!canQueryCustomers.value) return false
  const generation = ++customerGeneration
  customerLoading.value = true
  customerNotice.value = ''
  try {
    const query = normalizeCustomerQuery({
      pageNo: customerPageNo.value,
      pageSize: customerPageSize,
      keyword: customerFilters.value.keyword,
      status: customerFilters.value.status,
      ownerUserId: customerFilters.value.ownerUserId
    })
    const result = await customerApi.getCustomers(query)
    if (generation !== customerGeneration) return false
    customers.value = { list: result?.list || [], total: result?.total || 0 }
    return true
  } catch {
    if (generation !== customerGeneration) return false
    customerNotice.value = '客资列表加载失败，请检查网络或当前工作身份后重试'
    customerError.value = true
    return false
  } finally {
    if (generation === customerGeneration) customerLoading.value = false
  }
}

const searchCustomers = () => {
  customerPageNo.value = 1
  void loadCustomers()
}
const reloadCustomers = () => {
  void loadCustomers()
}
const onCustomerPageChange = (page: number) => {
  customerPageNo.value = page
  void loadCustomers()
}

const loadOwnerOptions = async (force = false) => {
  if (!canQueryCustomers.value || (ownerOptionsLoaded.value && !force)) return
  try {
    ownerOptions.value = await customerApi.getOwnerOptions()
    ownerOptionsLoaded.value = true
  } catch {
    ownerOptions.value = []
  }
}

const loadTransferCandidates = async () => {
  if (!canTransfer.value || transferCandidates.value.length) return
  try {
    transferCandidates.value = await customerApi.getTransferCandidates()
  } catch {
    transferCandidates.value = []
  }
}

const openTransfer = async (row: CustomerRow) => {
  transferRow.value = row
  transferTargetUserId.value = ''
  transferReason.value = ''
  transferDialog.value = true
  await loadTransferCandidates()
}

const confirmTransfer = async () => {
  if (!transferRow.value) return
  if (!transferTargetUserId.value) {
    ElMessage.warning('请选择接手人')
    return
  }
  if (!transferReason.value.trim()) {
    ElMessage.warning('请填写转让原因')
    return
  }
  transferBusy.value = true
  try {
    await customerApi.transferCustomer(
      transferRow.value.id,
      {
        targetUserId: transferTargetUserId.value,
        reason: transferReason.value.trim(),
        revision: transferRow.value.revision
      },
      transferIdempotencyKey(transferRow.value.id, transferRow.value.revision)
    )
    transferDialog.value = false
    // 先刷新列表再落提示：loadCustomers 会清空提示，顺序反了会把"转让成功"吞掉。
    const refreshed = await loadCustomers()
    // 新负责人可能此前没有客资：强制重载名册，否则筛选下拉缺少刚转入的人。
    await loadOwnerOptions(true)
    if (refreshed) {
      customerNotice.value = '转让成功'
      customerError.value = false
    } else {
      // 转让已成功（服务端已提交），刷新失败不得把成功事实说成失败。
      customerNotice.value = '转让已成功，列表刷新失败，请手动刷新'
      customerError.value = false
    }
  } catch {
    transferDialog.value = false
    const refreshed = await loadCustomers()
    if (refreshed) {
      customerNotice.value = '转让失败：该客资可能已被他人修改，列表已刷新，请重试'
      customerError.value = true
    }
  } finally {
    transferBusy.value = false
  }
}

// ===== 待关联 =====
const pending = ref<PendingLead[]>([])
const pendingLoading = ref(false)
const actionBusy = ref(false)
const actionNotice = ref('')
const actionError = ref(false)
const actionRow = ref<PendingLead | null>(null)
const linkDialog = ref(false)
const invalidDialog = ref(false)
const invalidNote = ref('')

const loadPending = async (): Promise<boolean> => {
  pendingLoading.value = true
  actionNotice.value = ''
  try {
    pending.value = await api.getPendingLeads()
    return true
  } catch {
    actionNotice.value = '待关联客资加载失败，请检查网络或当前工作身份后重试'
    actionError.value = true
    return false
  } finally {
    pendingLoading.value = false
  }
}

const openLink = (row: PendingLead) => {
  actionRow.value = row
  linkDialog.value = true
}
const openInvalid = (row: PendingLead) => {
  actionRow.value = row
  invalidNote.value = ''
  invalidDialog.value = true
}

/** revision 冲突（他人已处理）统一提示并刷新，避免重复操作。 */
const handleActionFailure = () => {
  actionNotice.value = '该客资已被他人处理或网络异常，列表已刷新，请重试'
  actionError.value = true
  loadPending()
}

const confirmLink = async () => {
  if (!actionRow.value) return
  actionBusy.value = true
  try {
    await api.linkLead(
      actionRow.value.id,
      actionRow.value.revision,
      `lead-link:${actionRow.value.id}:${actionRow.value.revision}`
    )
    linkDialog.value = false
    actionNotice.value = '关联成功'
    actionError.value = false
    await loadPending()
  } catch {
    linkDialog.value = false
    handleActionFailure()
  } finally {
    actionBusy.value = false
  }
}

const confirmInvalid = async () => {
  if (!actionRow.value) return
  if (!invalidNote.value.trim()) {
    ElMessage.warning('请填写作废原因')
    return
  }
  actionBusy.value = true
  try {
    await api.invalidateLead(
      actionRow.value.id,
      invalidNote.value.trim(),
      actionRow.value.revision,
      `lead-invalid:${actionRow.value.id}:${actionRow.value.revision}`
    )
    invalidDialog.value = false
    actionNotice.value = '作废成功'
    actionError.value = false
    await loadPending()
  } catch {
    invalidDialog.value = false
    handleActionFailure()
  } finally {
    actionBusy.value = false
  }
}

// ===== 批量导入 =====
const importDialog = ref(false)
const importFileInput = ref<HTMLInputElement>()
const importBatchId = ref('')
const importBusy = ref(false)
const importFileName = ref('')
const importRows = ref<LeadImportRow[]>([])
const importParseErrors = ref<string[]>([])
const importNotice = ref('')
const importPrecheckResult = ref<LeadImportResult | null>(null)
const importSubmitResult = ref<LeadImportResult | null>(null)
const importGuard = createImportGenerationGuard()

const statusMeta: Record<
  string,
  { label: string; type: 'success' | 'info' | 'warning' | 'danger' }
> = {
  OK: { label: '可创建', type: 'success' },
  CREATED: { label: '已创建', type: 'success' },
  SKIPPED: { label: '跳过', type: 'info' },
  FAILED: { label: '失败', type: 'danger' }
}

const canPrecheckImport = computed(
  () =>
    !importBusy.value &&
    importRows.value.length > 0 &&
    importParseErrors.value.length === 0 &&
    !importRows.value.some((row) => row.error)
)
const canSubmitImport = computed(
  () => !importBusy.value && !!importPrecheckResult.value && canPrecheckImport.value
)
const canRetryFailed = computed(
  () => !importBusy.value && !!importSubmitResult.value && importSubmitResult.value.failed > 0
)

const resetImport = () => {
  importGuard.next()
  importBusy.value = false
  importBatchId.value = ''
  importFileName.value = ''
  importRows.value = []
  importParseErrors.value = []
  importPrecheckResult.value = null
  importSubmitResult.value = null
  importNotice.value = ''
  if (importFileInput.value) importFileInput.value.value = ''
}
const openImport = () => {
  resetImport()
  importDialog.value = true
}
const downloadImportTemplate = () => {
  const blob = new Blob([buildLeadImportTemplateCsv()], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '客资导入模板.csv'
  link.click()
  URL.revokeObjectURL(url)
}

const onImportFile = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const generation = importGuard.next()
  importFileName.value = file.name
  // 一次导入一个稳定批次号：重试失败行时必须沿用，服务端据此判定 replay。
  importBatchId.value = newImportBatchId()
  importBusy.value = true
  importNotice.value = ''
  // 换文件即清空旧批次：读取/超限失败保持全空，提交按钮因 precheck=null 而禁用。
  importRows.value = []
  importParseErrors.value = []
  importPrecheckResult.value = null
  importSubmitResult.value = null
  if (!withinFileSizeLimit(file.size)) {
    ElMessage.error(`文件超过大小上限（${CSV_MAX_FILE_BYTES / 1024 / 1024}MB）`)
    importBusy.value = false
    input.value = ''
    return
  }
  try {
    const parsed = parseLeadImportCsv(await file.text())
    if (!importGuard.isCurrent(generation)) return
    importRows.value = parsed.rows
    importParseErrors.value = parsed.errors
    if (parsed.errors.length) ElMessage.error('CSV 解析失败，请检查表头与列数')
  } catch {
    if (importGuard.isCurrent(generation)) ElMessage.error('文件读取失败，请重试')
  } finally {
    input.value = ''
    if (importGuard.isCurrent(generation)) importBusy.value = false
  }
}

const importPayloadRows = () =>
  importRows.value.map(({ line, name, phone, need, source }) => ({
    line,
    name,
    phone,
    need,
    source
  }))

const runImportPrecheck = async () => {
  if (!importRows.value.length) {
    ElMessage.warning('请先选择包含数据的 CSV 文件')
    return
  }
  if (importParseErrors.value.length || importRows.value.some((row) => row.error)) {
    ElMessage.error('存在解析错误或重复行，请修正后重新上传')
    return
  }
  if (!withinLeadImportLimit(importRows.value)) {
    ElMessage.error(`单批须为 1-${LEAD_IMPORT_MAX_ROWS} 行，当前 ${importRows.value.length} 行`)
    return
  }
  const generation = importGuard.next()
  importBusy.value = true
  try {
    await runGuardedImportRequest(
      () =>
        api.precheckLeadImport({
          orgId: orgId(),
          batchId: importBatchId.value,
          rows: importPayloadRows()
        }),
      generation,
      importGuard.isCurrent,
      (result) => {
        importPrecheckResult.value = result
        importSubmitResult.value = null
        importBusy.value = false
      }
    )
  } catch {
    if (importGuard.isCurrent(generation)) {
      ElMessage.error('预检失败，请检查网络或当前工作身份后重试')
    }
  } finally {
    if (importGuard.isCurrent(generation)) importBusy.value = false
  }
}

const submitImport = async () => {
  if (!importPrecheckResult.value) {
    ElMessage.warning('请先完成预检')
    return
  }
  const generation = importGuard.next()
  importBusy.value = true
  try {
    await runGuardedImportRequest(
      () =>
        api.submitLeadImport({
          orgId: orgId(),
          batchId: importBatchId.value,
          rows: importPayloadRows()
        }),
      generation,
      importGuard.isCurrent,
      (result) => {
        importSubmitResult.value = result
        importNotice.value = result.failed
          ? `提交完成，${result.failed} 行失败：数据错误请修正 CSV 后重新上传（新批次）；临时错误可直接重试失败行`
          : '提交完成'
        importBusy.value = false
        // 导入可能新增负责人（owner=操作者）：刷新负责人名册。
        void loadOwnerOptions(true)
        // 导入可能产生新的待关联客资：刷新列表失败不得把提交成功误报为失败；
        // 回调必须校验代次，避免重开/换文件后旧批次回调污染新提示。
        void loadPending().then((ok) => {
          if (!importGuard.isCurrent(generation)) return
          if (!ok) importNotice.value = '导入已提交，待关联列表刷新失败，请手动刷新'
        })
      }
    )
  } catch {
    if (importGuard.isCurrent(generation)) ElMessage.error('提交失败，请稍后重试')
  } finally {
    if (importGuard.isCurrent(generation)) importBusy.value = false
  }
}

const retryFailedRows = () => {
  if (!importSubmitResult.value) return
  const rows = failedLeadRowsForRetry(importSubmitResult.value.rows)
  if (!rows.length) return
  importRows.value = rows
  importParseErrors.value = []
  // batchId 保持不变：失败行重提走同一幂等键。
  importPrecheckResult.value = null
  importSubmitResult.value = null
  importNotice.value = `已载入 ${rows.length} 行失败记录，请重新预检后提交`
}

const formatTime = (value?: string) => {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`
}

onMounted(() => {
  if (canHandle.value) loadPending()
  if (canQueryCustomers.value) {
    void loadCustomers()
    void loadOwnerOptions()
  }
})

// ElMessageBox 在确认链中保留引用，避免 tree-shaking 误删（与 org-staff 一致）。
void ElMessageBox
</script>

<style scoped lang="scss">
.customers-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #4d301e;
}

.hint {
  margin: 8px 0 12px;
  font-size: 12px;
  color: #8a7a6d;
}

.import-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.import-file {
  display: none;
}

.import-file-name {
  font-size: 12px;
  color: #8a7a6d;
}

.import-error {
  margin-top: 8px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}

.filter-input {
  width: 220px;
}

.filter-select {
  width: 180px;
}

.customers-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.transfer-select,
.transfer-reason {
  width: 100%;
  margin-top: 8px;
}

.import-summary {
  margin: 12px 0 8px;
  font-size: 13px;
  color: #6f6259;
}
</style>
