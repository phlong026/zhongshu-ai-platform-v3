<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">{{ section?.title || '历史平台钱包' }}</h1>
        <div class="zs-page-subtitle"
          >历史钱包运营记录，不代表当前公司积分池余额；金额以分计，设计调用成本以微元计</div
        >
      </div>
    </div>
    <div class="zs-table-card">
      <el-form inline @submit.prevent="search">
        <el-form-item v-if="organizationFilter" label="组织编号">
          <el-input v-model="orgId" clearable placeholder="输入组织编号筛选" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="search">查询</el-button>
          <el-button v-if="resource === 'plans' && canPlanWrite" @click="openPlan()"
            >新增套餐</el-button
          >
          <el-button v-if="resource === 'design-prices' && canPriceWrite" @click="openPrice()"
            >新增价目</el-button
          >
        </el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <el-table native-scrollbar v-else v-loading="loading" :data="rows" :row-key="rowKey" empty-text="暂无记录">
        <el-table-column
          v-for="column in section?.columns"
          :key="column[0]"
          :prop="column[0]"
          :label="column[1]"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ displayValue(column[0], row[column[0]]) }}</template>
        </el-table-column>
        <el-table-column
          v-if="resource === 'plans' || resource === 'design-prices' || resource === 'anomalies'"
          label="操作"
          fixed="right"
          width="150"
        >
          <template #default="{ row }">
            <el-button
              v-if="resource === 'plans' && canPlanWrite"
              type="primary"
              link
              @click="openPlan(row)"
              >编辑套餐</el-button
            >
            <el-button
              v-if="resource === 'design-prices' && canPriceWrite"
              type="primary"
              link
              @click="openPrice(row)"
              >发布新版本</el-button
            >
            <el-button
              v-if="resource === 'anomalies'"
              type="primary"
              link
              @click="openReviews(row.id)"
              >核对记录</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNo"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        :disabled="loading"
        @current-change="load"
        @size-change="search"
      />
    </div>
    <el-dialog
      v-model="planVisible"
      :title="editingPlanId ? '编辑充值套餐' : '新增充值套餐'"
      width="560px"
      :close-on-click-modal="false"
      :show-close="!saving"
    >
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
      <el-form label-width="120px" :disabled="saving" @submit.prevent="savePlan">
        <el-form-item label="当前版本">{{ planForm.expectedVersion || '新建' }}</el-form-item>
        <el-form-item label="套餐名称"
          ><el-input v-model="planForm.name" maxlength="128"
        /></el-form-item>
        <el-form-item label="价格（分）"
          ><el-input-number v-model="planForm.priceFen" :min="1" :max="maxInteger" :precision="0"
        /></el-form-item>
        <el-form-item label="基础点数"
          ><el-input-number v-model="planForm.basePoints" :min="1" :max="maxInteger" :precision="0"
        /></el-form-item>
        <el-form-item label="赠送点数"
          ><el-input-number
            v-model="planForm.bonusPoints"
            :min="0"
            :max="maxInteger"
            :precision="0"
        /></el-form-item>
        <el-form-item label="微信商品编号"
          ><el-input v-model="planForm.wxProductId" maxlength="128" placeholder="启用套餐时必填"
        /></el-form-item>
        <el-form-item label="状态"
          ><el-select v-model="planForm.status"
            ><el-option label="停用" value="DISABLED" /><el-option
              label="启用"
              value="ACTIVE" /></el-select
        ></el-form-item>
        <el-form-item label="排序"
          ><el-input-number v-model="planForm.sort" :min="0" :max="2147483647" :precision="0"
        /></el-form-item>
        <el-form-item label="变更原因"
          ><el-input v-model="planForm.reason" type="textarea" maxlength="512" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="planVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="savePlan">保存</el-button></template
      >
    </el-dialog>
    <el-dialog
      v-model="priceVisible"
      :title="editingPriceId ? '发布设计价格新版本' : '新增设计价目'"
      width="580px"
      :close-on-click-modal="false"
      :show-close="!saving"
    >
      <el-alert
        title="变更仅影响后续新报价，已生成的报价保持原版本。"
        type="info"
        :closable="false"
      />
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
      <el-form label-width="140px" :disabled="saving" @submit.prevent="savePrice">
        <el-form-item label="当前版本">{{ priceForm.expectedVersion || '新建' }}</el-form-item>
        <el-form-item label="设计能力"
          ><el-select
            v-model="priceForm.operation"
            :disabled="!!editingPriceId"
            @change="changePriceOperation"
            ><el-option
              v-for="operation in priceOperations"
              :key="operation.value"
              :value="operation.value"
              :label="operation.label" /></el-select
        ></el-form-item>
        <el-form-item v-if="generationPrice" label="分辨率"
          ><el-select v-model="priceForm.resolution" :disabled="!!editingPriceId"
            ><el-option label="2K" value="2K" /><el-option label="4K" value="4K" /></el-select
        ></el-form-item>
        <template v-if="generationPrice">
          <el-form-item label="提示词点数"
            ><el-input-number
              v-model="priceForm.promptPoints"
              :min="0"
              :max="maxInteger"
              :precision="0"
          /></el-form-item>
          <el-form-item label="每图点数"
            ><el-input-number
              v-model="priceForm.imagePoints"
              :min="0"
              :max="maxInteger"
              :precision="0"
          /></el-form-item>
          <el-form-item label="最多图片数"
            ><el-input-number v-model="priceForm.maxImageUnits" :min="1" :max="8" :precision="0"
          /></el-form-item>
        </template>
        <el-form-item v-else label="每次调用点数"
          ><el-input-number
            v-model="priceForm.usagePoints"
            :min="0"
            :max="maxInteger"
            :precision="0"
        /></el-form-item>
        <el-form-item label="启用此版本"><el-switch v-model="priceForm.active" /></el-form-item>
        <el-form-item label="变更原因"
          ><el-input v-model="priceForm.reason" type="textarea" maxlength="512" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="priceVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="savePrice"
          >发布版本</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="reviewVisible"
      title="异常核对记录"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-alert
        title="核对说明只作记录，支付与积分状态以已验证事实为准。"
        type="info"
        :closable="false"
      />
      <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" />
      <el-table native-scrollbar :data="reviews" v-loading="reviewLoading" empty-text="暂无核对说明">
        <el-table-column prop="actorId" label="操作人编号" width="190" /><el-table-column
          prop="note"
          label="核对说明"
        /><el-table-column prop="createTime" label="记录时间" width="200" />
      </el-table>
      <el-pagination
        v-model:current-page="reviewPage"
        :page-size="20"
        :total="reviewTotal"
        layout="total, prev, pager, next"
        @current-change="loadReviews"
      />
      <template v-if="canReview">
        <el-input
          v-model="reviewNote"
          type="textarea"
          maxlength="512"
          placeholder="填写本次核对说明"
        />
        <el-button type="primary" :loading="reviewSaving" @click="saveReview"
          >追加核对说明</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { checkPermi } from '@/utils/permission'
import { adminDateTime } from '@/utils/adminDisplay'
import {
  getWallets,
  getRechargePlans,
  getRechargeOrders,
  getLedger,
  getAnomalies,
  getDesignPrices,
  getDesignCosts,
  getAudit,
  createRechargePlan,
  updateRechargePlan,
  createDesignPrice,
  updateDesignPrice,
  getAnomalyReviews,
  reviewAnomaly,
  type PlanRow,
  type PlanChange,
  type DesignPriceRow,
  type DesignPriceChange,
  type AnomalyReview,
  type OperationsQuery,
  type OperationsPage
} from '@/api/platform/operations'

defineOptions({ name: 'PlatformWalletOperations' })
const route = useRoute()
const sections: Record<
  string,
  {
    title: string
    permission: string
    query: (params: OperationsQuery) => Promise<OperationsPage<unknown>>
    columns: string[][]
  }
> = {
  wallets: {
    title: '历史组织钱包',
    permission: 'platform:wallet:query',
    query: getWallets,
    columns: [
      ['id', '钱包编号'],
      ['orgId', '组织编号'],
      ['status', '状态'],
      ['spendingFrozen', '支出冻结'],
      ['availablePoints', '可用点数'],
      ['reservedPoints', '预留点数']
    ]
  },
  plans: {
    title: '历史充值套餐',
    permission: 'platform:wallet:plan:query',
    query: getRechargePlans,
    columns: [
      ['id', '套餐编号'],
      ['name', '套餐名称'],
      ['priceFen', '价格（分）'],
      ['basePoints', '基础点数'],
      ['bonusPoints', '赠送点数'],
      ['version', '版本'],
      ['status', '状态']
    ]
  },
  orders: {
    title: '历史充值订单',
    permission: 'platform:wallet:order:query',
    query: getRechargeOrders,
    columns: [
      ['orderNo', '订单号'],
      ['orgId', '组织编号'],
      ['priceFen', '金额（分）'],
      ['basePoints', '基础点数'],
      ['bonusPoints', '赠送点数'],
      ['status', '支付状态'],
      ['creditState', '积分入账状态'],
      ['createTime', '创建时间']
    ]
  },
  ledger: {
    title: '历史钱包流水',
    permission: 'platform:wallet:ledger:query',
    query: getLedger,
    columns: [
      ['id', '流水编号'],
      ['orgId', '组织编号'],
      ['type', '类型'],
      ['delta', '点数变动'],
      ['availableAfter', '变动后可用'],
      ['reservedAfter', '变动后预留'],
      ['bizType', '业务类型'],
      ['bizId', '业务编号'],
      ['reason', '原因'],
      ['createTime', '记录时间']
    ]
  },
  anomalies: {
    title: '支付异常核对',
    permission: 'platform:wallet:anomaly:query',
    query: getAnomalies,
    columns: [
      ['id', '异常编号'],
      ['orgId', '组织编号'],
      ['orderId', '订单编号'],
      ['anomalyType', '异常类型'],
      ['detail', '异常详情'],
      ['createTime', '记录时间']
    ]
  }
}
sections['design-prices'] = {
  title: '历史设计能力定价',
  permission: 'platform:design:price:query',
  query: getDesignPrices,
  columns: [
    ['id', '价目编号'],
    ['operation', '设计能力'],
    ['resolution', '分辨率'],
    ['version', '版本'],
    ['promptPoints', '提示词点数'],
    ['imagePoints', '每图点数'],
    ['usagePoints', '每次调用点数'],
    ['maxImageUnits', '最多图片数'],
    ['active', '当前启用']
  ]
}
sections['design-costs'] = {
  title: '设计调用成本',
  permission: 'platform:design:cost:query',
  query: getDesignCosts,
  columns: [
    ['id', '命令编号'],
    ['orgId', '组织编号'],
    ['operation', '设计能力'],
    ['status', '命令状态'],
    ['priceVersion', '报价版本'],
    ['chargedPoints', '已收点数'],
    ['refundedPoints', '已退点数'],
    ['costState', '成本事实'],
    ['chargedCostMicros', '累计成本（微元）'],
    ['settlementVersion', '结算版本'],
    ['audited', '调用已审计'],
    ['createTime', '创建时间']
  ]
}
sections.audit = {
  title: '配置审计',
  permission: 'platform:ops:audit:query',
  query: getAudit,
  columns: [
    ['id', '记录编号'],
    ['resourceType', '配置类型'],
    ['resourceId', '配置编号'],
    ['resourceVersion', '配置版本'],
    ['action', '操作'],
    ['actorId', '操作人编号'],
    ['reason', '变更原因'],
    ['beforeJson', '变更前'],
    ['afterJson', '变更后'],
    ['createTime', '记录时间']
  ]
}
const displayValue = (field: string, value: unknown) => {
  if (value === null || value === undefined)
    return field.endsWith('CostMicros') ? '未取得成本事实' : '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (field.endsWith('Time') || field.endsWith('At')) return adminDateTime(value)
  const labels: Record<string, string> = {
    UNAVAILABLE: '未取得成本事实',
    RECORDED: '已有成本事实',
    ACTIVE: '已启用',
    DISABLED: '已停用',
    CREATED: '已创建',
    PENDING: '处理中',
    UNKNOWN: '待核实',
    SUCCEEDED: '已成功',
    FAILED: '已失败',
    CLOSED: '已关闭',
    NOT_READY: '尚未入账',
    CREDITED: '已入账',
    BLOCKED: '入账受阻',
    NEW: '新建',
    SENT: '已发送',
    REJECTED: '已拒绝'
  }
  return ['status', 'creditState', 'costState'].includes(field)
    ? labels[String(value)] || String(value)
    : String(value)
}
const rowKey = (row: { id: string; version?: number }) => `${row.id}:${row.version || ''}`
const resource = computed(() => route.path.split('/').pop() || '')
const organizationFilter = computed(
  () => !['plans', 'design-prices', 'audit'].includes(resource.value)
)
const section = computed(() => sections[resource.value])
const rows = ref<unknown[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const orgId = ref('')
const loading = ref(false)
const error = ref('')
let generation = 0

const load = async () => {
  const current = ++generation
  rows.value = []
  total.value = 0
  error.value = ''
  if (!section.value || !checkPermi([section.value.permission])) {
    error.value = '当前账号没有此运营页面的查询权限。'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await section.value.query({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      ...(organizationFilter.value && orgId.value.trim() ? { orgId: orgId.value.trim() } : {})
    })
    if (current !== generation) return
    rows.value = result.list
    total.value = result.total
  } catch (failure) {
    if (current !== generation) return
    error.value = `查询失败：${failure instanceof Error ? failure.message : '服务不可用或权限不足，请稍后重试。'}`
  } finally {
    if (current === generation) loading.value = false
  }
}
const search = () => {
  pageNo.value = 1
  return load()
}
const maxInteger = Number.MAX_SAFE_INTEGER
const canPlanWrite = computed(() => checkPermi(['platform:wallet:plan:update']))
const canPriceWrite = computed(() => checkPermi(['platform:design:price:update']))
const canReview = computed(() => checkPermi(['platform:wallet:anomaly:review']))
const planVisible = ref(false)
const priceVisible = ref(false)
const editingPlanId = ref('')
const editingPriceId = ref('')
const saving = ref(false)
const editError = ref('')
const emptyPlan = (): PlanChange & { wxProductId: string } => ({
  expectedVersion: 0,
  name: '',
  priceFen: 0,
  basePoints: 0,
  bonusPoints: 0,
  status: 'DISABLED',
  sort: 0,
  wxProductId: '',
  reason: ''
})
const planForm = ref(emptyPlan())
const priceOperations = [
  { value: 'CREATE_FLAT_JOB', label: '平面设计' },
  { value: 'CREATE_ELEVATION_JOB', label: '立面设计' },
  { value: 'CREATE_REVISION_REQUEST', label: '设计修改' },
  { value: 'NOTE_POLISH', label: '需求润色' },
  { value: 'CREATE_BUDGET_ESTIMATE', label: '快速预算' }
]
const emptyPrice = (): DesignPriceChange => ({
  expectedVersion: 0,
  operation: 'CREATE_FLAT_JOB',
  resolution: '2K',
  promptPoints: 0,
  imagePoints: 0,
  usagePoints: 0,
  maxImageUnits: 1,
  active: false,
  reason: ''
})
const priceForm = ref(emptyPrice())
const generationPrice = computed(() =>
  ['CREATE_FLAT_JOB', 'CREATE_ELEVATION_JOB', 'CREATE_REVISION_REQUEST'].includes(
    priceForm.value.operation
  )
)
const changePriceOperation = () => {
  priceForm.value.resolution = generationPrice.value ? '2K' : ''
  priceForm.value.promptPoints = 0
  priceForm.value.imagePoints = 0
  priceForm.value.usagePoints = 0
  priceForm.value.maxImageUnits = generationPrice.value ? 1 : 0
}
const openPlan = (row?: PlanRow) => {
  if (!canPlanWrite.value || saving.value) return
  editingPlanId.value = row?.id || ''
  planForm.value = row
    ? {
        expectedVersion: row.version,
        name: row.name,
        priceFen: row.priceFen,
        basePoints: row.basePoints,
        bonusPoints: row.bonusPoints,
        status: row.status,
        sort: row.sort,
        wxProductId: row.wxProductId || '',
        reason: ''
      }
    : emptyPlan()
  editError.value = ''
  planVisible.value = true
}
const openPrice = (row?: DesignPriceRow) => {
  if (!canPriceWrite.value || saving.value) return
  editingPriceId.value = row?.id || ''
  priceForm.value = row
    ? {
        expectedVersion: row.version,
        operation: row.operation,
        resolution: row.resolution,
        promptPoints: row.promptPoints,
        imagePoints: row.imagePoints,
        usagePoints: row.usagePoints,
        maxImageUnits: row.maxImageUnits,
        active: row.active,
        reason: ''
      }
    : emptyPrice()
  editError.value = ''
  priceVisible.value = true
}
const failureMessage = (failure: unknown) =>
  failure instanceof Error ? failure.message : '操作失败，请刷新确认记录后重试。'
const savePlan = async () => {
  if (!canPlanWrite.value) {
    editError.value = '没有套餐维护权限。'
    return
  }
  if (saving.value) return
  const form = planForm.value
  if (
    !form.name.trim() ||
    !form.reason.trim() ||
    ![form.priceFen, form.basePoints, form.bonusPoints, form.sort, form.expectedVersion].every(
      Number.isSafeInteger
    )
  ) {
    editError.value = '请填写名称、变更原因及有效整数。'
    return
  }
  saving.value = true
  editError.value = ''
  const current = generation
  try {
    if (editingPlanId.value) await updateRechargePlan(editingPlanId.value, { ...form })
    else await createRechargePlan({ ...form })
    if (current !== generation) return
    planVisible.value = false
    await load()
  } catch (failure) {
    if (current === generation) editError.value = failureMessage(failure)
  } finally {
    saving.value = false
  }
}
const savePrice = async () => {
  if (!canPriceWrite.value) {
    editError.value = '没有设计定价维护权限。'
    return
  }
  if (saving.value) return
  const form = priceForm.value
  if (
    !form.reason.trim() ||
    ![
      form.expectedVersion,
      form.promptPoints,
      form.imagePoints,
      form.usagePoints,
      form.maxImageUnits
    ].every(Number.isSafeInteger)
  ) {
    editError.value = '请填写变更原因及有效整数。'
    return
  }
  saving.value = true
  editError.value = ''
  const current = generation
  try {
    if (editingPriceId.value) await updateDesignPrice(editingPriceId.value, { ...form })
    else await createDesignPrice({ ...form })
    if (current !== generation) return
    priceVisible.value = false
    await load()
  } catch (failure) {
    if (current === generation) editError.value = failureMessage(failure)
  } finally {
    saving.value = false
  }
}
const reviewVisible = ref(false)
const reviewId = ref('')
const reviewNote = ref('')
const reviews = ref<AnomalyReview[]>([])
const reviewTotal = ref(0)
const reviewPage = ref(1)
const reviewLoading = ref(false)
const reviewSaving = ref(false)
const reviewError = ref('')
let reviewGeneration = 0
const loadReviews = async () => {
  const current = ++reviewGeneration
  reviews.value = []
  reviewTotal.value = 0
  reviewError.value = ''
  reviewLoading.value = true
  try {
    const result = await getAnomalyReviews(reviewId.value, {
      pageNo: reviewPage.value,
      pageSize: 20
    })
    if (current !== reviewGeneration) return
    reviews.value = result.list
    reviewTotal.value = result.total
  } catch (failure) {
    if (current === reviewGeneration) reviewError.value = failureMessage(failure)
  } finally {
    if (current === reviewGeneration) reviewLoading.value = false
  }
}
const openReviews = (id: string) => {
  reviewId.value = id
  reviewPage.value = 1
  reviewNote.value = ''
  reviewVisible.value = true
  void loadReviews()
}
const saveReview = async () => {
  if (!canReview.value) {
    reviewError.value = '没有异常核对权限。'
    return
  }
  if (!reviewNote.value.trim()) {
    reviewError.value = '请填写核对说明。'
    return
  }
  if (reviewSaving.value) return
  const id = reviewId.value
  const current = reviewGeneration
  reviewSaving.value = true
  try {
    await reviewAnomaly(id, reviewNote.value.trim())
    if (current !== reviewGeneration || id !== reviewId.value || !reviewVisible.value) return
    reviewNote.value = ''
    reviewPage.value = 1
    await loadReviews()
  } catch (failure) {
    if (current === reviewGeneration && id === reviewId.value)
      reviewError.value = failureMessage(failure)
  } finally {
    reviewSaving.value = false
  }
}
watch(
  () => route.path,
  () => {
    orgId.value = ''
    planVisible.value = false
    priceVisible.value = false
    reviewVisible.value = false
    reviewGeneration++
    void search()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  generation++
  reviewGeneration++
  reviewVisible.value = false
})
</script>
