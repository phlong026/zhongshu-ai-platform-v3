<template>
  <div class="zs-page task-operations-page">
    <div class="zs-page-header"
      ><div
        ><h1 class="zs-page-title">任务与积分核对</h1
        ><p class="zs-page-subtitle">核对生成结果、预扣、结算与退回</p></div
      ><el-button :loading="loading" @click="load">刷新</el-button></div
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="zs-table-card">
      <el-form label-position="top" class="filters" @submit.prevent="search">
        <el-form-item label="组织"
          ><el-select
            v-model="filters.orgId"
            filterable
            remote
            clearable
            :remote-method="findOrganizations"
            placeholder="搜索组织名称"
            :loading="orgLoading"
            ><el-option
              v-for="org in organizations"
              :key="org.orgId"
              :label="org.orgName"
              :value="org.orgId" /></el-select
        ></el-form-item>
        <el-form-item label="能力"
          ><el-select v-model="filters.subject" clearable placeholder="全部能力"
            ><el-option
              v-for="subject in subjects"
              :key="subject.subject"
              :label="subject.label"
              :value="subject.subject" /></el-select
        ></el-form-item>
        <el-form-item label="核对状态"
          ><el-select v-model="filters.caseStatus" clearable placeholder="全部状态"
            ><el-option label="待核对" value="OPEN" /><el-option
              label="待外部确认"
              value="WAITING_EXTERNAL" /><el-option label="已核对" value="RESOLVED" /></el-select
        ></el-form-item>
        <el-form-item label="任务或操作人"
          ><el-input
            v-model="filters.keyword"
            maxlength="100"
            clearable
            placeholder="任务编号、业务号或姓名"
            @keyup.enter="search"
        /></el-form-item>
        <div class="filter-actions"
          ><el-checkbox v-model="filters.onlyAnomalies">仅显示异常或待跟进</el-checkbox
          ><el-button type="primary" @click="search">查询</el-button
          ><el-button @click="reset">重置</el-button></div
        >
      </el-form>
      <el-table native-scrollbar :data="rows" v-loading="loading" empty-text="暂无符合条件的记录">
        <el-table-column prop="org_name" label="组织" min-width="190" />
        <el-table-column label="任务 / 能力" min-width="210"
          ><template #default="{ row }"
            ><div>{{ row.subject_label }}</div
            ><span class="secondary">{{ row.task_id || row.operation_id }}</span></template
          ></el-table-column
        >
        <el-table-column prop="actor_name" label="操作人" min-width="110" />
        <el-table-column label="生成状态" min-width="130"
          ><template #default="{ row }">{{ state(row.task_status) }}</template></el-table-column
        >
        <el-table-column label="账务 / 核对" min-width="170"
          ><template #default="{ row }"
            ><div>{{ billing(row.billing_state) }}</div
            ><span class="secondary">{{ caseLabel(row.case_status) }}</span></template
          ></el-table-column
        >
        <el-table-column label="核对提示" min-width="160"
          ><template #default="{ row }"
            ><el-tag :type="row.anomaly === 'NONE' ? 'info' : 'warning'">{{
              anomaly(row.anomaly)
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="预扣" min-width="100" align="right"
          ><template #default="{ row }">{{ integer(row.requested) }}</template></el-table-column
        >
        <el-table-column label="已扣" min-width="100" align="right"
          ><template #default="{ row }">{{ integer(row.charged) }}</template></el-table-column
        >
        <el-table-column label="已退" min-width="100" align="right"
          ><template #default="{ row }">{{ integer(row.refunded) }}</template></el-table-column
        >
        <el-table-column label="预扣时间" min-width="175"
          ><template #default="{ row }">{{ date(row.create_time) }}</template></el-table-column
        >
        <el-table-column label="操作" width="90" fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="openTask(row.task_key)"
              >详情</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-pagination
        class="pagination"
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="prev,pager,next,total"
        @current-change="load"
      />
    </div>
    <el-drawer v-model="drawer" title="任务与积分详情" size="min(1040px,96vw)" destroy-on-close>
      <template #header
        ><div class="task-detail-heading"
          ><h2>任务与积分详情</h2
          ><el-button :loading="detailLoading" @click="openTask(taskKey)">刷新详情</el-button></div
        ></template
      >
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <template v-if="detail">
          <el-descriptions :column="1" border class="task-summary">
            <el-descriptions-item label="组织"
              ><el-button class="organization-link" link type="primary" @click="openOrganization">{{
                detail.task.org_name
              }}</el-button></el-descriptions-item
            >
            <el-descriptions-item label="能力">{{
              detail.task.subject_label
            }}</el-descriptions-item>
            <el-descriptions-item label="任务编号">{{
              detail.task.task_id || '未关联到生成任务'
            }}</el-descriptions-item>
            <el-descriptions-item label="业务号">{{
              detail.task.biz_key || '未记录'
            }}</el-descriptions-item>
            <el-descriptions-item label="操作人">{{ detail.task.actor_name }}</el-descriptions-item>
            <el-descriptions-item label="生成状态">{{
              state(detail.task.task_status)
            }}</el-descriptions-item>
            <el-descriptions-item label="账务状态">{{
              billing(detail.task.billing_state)
            }}</el-descriptions-item>
            <el-descriptions-item label="核对状态">{{
              caseLabel(detail.task.case_status)
            }}</el-descriptions-item>
            <el-descriptions-item label="冻结用量"
              >{{ detail.task.reserved_units || '未记录' }} {{ frozenUnit }}</el-descriptions-item
            >
            <el-descriptions-item label="积分"
              >预扣 {{ integer(detail.task.requested) }} · 已扣 {{ integer(detail.task.charged) }} ·
              已退 {{ integer(detail.task.refunded) }}</el-descriptions-item
            >
            <el-descriptions-item label="核对提示">{{
              anomaly(detail.task.anomaly)
            }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.task.task_error" label="失败原因">{{
              detail.task.task_error
            }}</el-descriptions-item>
          </el-descriptions>
          <div class="review-actions">
            <el-button v-if="detail.task.can_review" @click="prepare('WAITING_EXTERNAL')"
              >记录待外部确认</el-button
            >
            <el-button v-if="detail.task.can_sync" type="primary" @click="prepare('SYNC_TERMINAL')"
              >按已确认结果同步账务</el-button
            >
            <template v-if="detail.task.can_verify"
              ><el-button type="primary" @click="prepare('DELIVERED')">核验已交付并结算</el-button
              ><el-button type="danger" plain @click="prepare('NOT_DELIVERED')"
                >核验未交付并退回</el-button
              ></template
            >
            <el-button v-if="canAcknowledge" @click="prepare('REVIEWED')">确认核对完成</el-button>
          </div>
          <p
            v-if="
              !detail.task.can_sync &&
              !detail.task.can_verify &&
              detail.task.billing_state === 'PENDING'
            "
            class="secondary"
            >尚无可确认的终态。记录跟进说明后，等待供应商结果，保留当前预扣。</p
          >
          <h3>积分流水</h3>
          <el-table native-scrollbar :data="detail.ledger" empty-text="暂无关联积分流水"
            ><el-table-column label="类型" min-width="90"
              ><template #default="{ row }">{{ ledgerType(row.type) }}</template></el-table-column
            ><el-table-column prop="delta" label="可用积分变动" min-width="140" /><el-table-column
              label="实际消耗"
              min-width="110"
              ><template #default="{ row }">{{
                row.type === 'SETTLE'
                  ? integer(parse(row.price_snapshot).actualCredits ?? detail.task.charged)
                  : '—'
              }}</template></el-table-column
            ><el-table-column label="时间" min-width="175"
              ><template #default="{ row }">{{ date(row.create_time) }}</template></el-table-column
            ><el-table-column label="冻结价格" min-width="230"
              ><template #default="{ row }"
                ><span v-if="row.type === 'RESERVE'">{{
                  snapshotDescription(row.price_snapshot)
                }}</span
                ><span v-else>沿用预扣价格</span></template
              ></el-table-column
            ></el-table
          >
          <h3>核对记录</h3>
          <el-empty v-if="!detail.history.length" description="暂无核对记录" :image-size="48" />
          <div v-for="item in detail.history" :key="item.id" class="history-item"
            ><div>{{ date(item.created_at) }} · {{ item.actor_name || '平台管理员' }}</div
            ><p>{{ decision(historyData(item).decision) }} · {{ item.reason }}</p
            ><p v-if="historyData(item).evidence" class="secondary">{{
              historyData(item).evidence
            }}</p></div
          >
        </template>
      </div>
    </el-drawer>
    <el-dialog
      v-model="reviewing"
      :title="decision(review.decision)"
      width="min(640px,94vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!mutation.saving.value"
    >
      <el-alert :title="effect" type="warning" :closable="false" />
      <p
        >{{ detail?.task.org_name }} · {{ detail?.task.subject_label }} · 预扣
        {{ integer(detail?.task.requested) }} 积分</p
      >
      <el-form label-position="top" :disabled="!!mutation.pending.value || mutation.saving.value">
        <el-form-item label="核对说明" required
          ><el-input v-model="review.reason" type="textarea" :rows="3" maxlength="500"
        /></el-form-item>
        <el-form-item label="核验依据" required
          ><el-input
            v-model="review.evidence"
            type="textarea"
            :rows="3"
            maxlength="1000"
            placeholder="填写供应商工单、结果查询情况或核验记录"
        /></el-form-item>
      </el-form>
      <el-alert
        v-if="mutation.pending.value"
        title="处理结果待确认，请按原请求重试，或先查看最新任务状态。"
        type="warning"
        :closable="false"
      />
      <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" />
      <template #footer
        ><el-button
          v-if="mutation.pending.value"
          :disabled="mutation.saving.value"
          @click="reloadTask"
          >查看最新状态</el-button
        ><el-button :disabled="mutation.saving.value" @click="reviewing = false">{{
          mutation.pending.value ? '稍后确认' : '取消'
        }}</el-button
        ><el-button type="primary" :loading="mutation.saving.value" @click="saveReview">{{
          mutation.pending.value ? '重试确认' : '确认处理'
        }}</el-button></template
      >
    </el-dialog>
    <el-drawer
      v-model="organizationDrawer"
      title="组织详情"
      size="min(1120px,98vw)"
      destroy-on-close
      ><OrgDetail
        v-if="organizationDetail"
        :detail="organizationDetail"
        @changed="organizationDetail = $event"
    /></el-drawer>
  </div>
</template>
<script setup lang="ts">
import { ElMessage } from 'element-plus'
import {
  getOperationsTasks,
  getOperationsTask,
  reviewOperationsTask,
  type TaskDetail,
  type TaskRow,
  type TaskReview
} from '@/api/platform/organizationOperations'
import {
  getOrganizationPage,
  getOrganizationDetail,
  type Organization,
  type OrganizationDetail
} from '@/api/zs/platform-org'
import { getSubjectPrices } from '@/api/platform/orgPoints'
import OrgDetail from '@/views/zs/platform-org/OrgDetail.vue'
import { formatInteger, formatMicros } from '@/views/platform/org-points/int64'
import { operationsError, useOperationsMutation } from './mutation'
const route = useRoute(),
  filters = reactive({
    orgId: undefined as number | undefined,
    subject: '',
    keyword: '',
    caseStatus: '',
    onlyAnomalies: true
  })
const page = ref(1),
  total = ref(0),
  rows = ref<TaskRow[]>([]),
  loading = ref(false),
  error = ref(''),
  organizations = ref<Organization[]>([]),
  orgLoading = ref(false),
  subjects = ref<{ subject: string; label: string }[]>([])
const drawer = ref(false),
  detail = ref<TaskDetail>(),
  taskKey = ref(''),
  detailLoading = ref(false),
  detailError = ref(''),
  reviewing = ref(false),
  reviewError = ref('')
const organizationDrawer = ref(false),
  organizationDetail = ref<OrganizationDetail>()
const mutation = useOperationsMutation<TaskReview>(() => `task:${taskKey.value}`)
const review = reactive<TaskReview>({
  expectedVersion: 0,
  decision: 'WAITING_EXTERNAL',
  reason: '',
  evidence: '',
  requestKey: ''
})
let generation = 0,
  orgSequence = 0,
  detailSequence = 0
const dictionary = (values: Record<string, string>, key: any) =>
  values[String(key)] || String(key || '未记录')
const state = (v: any) =>
  dictionary(
    {
      SUBMISSION_UNCERTAIN: '提交结果待核验',
      SUBMITTING: '提交中',
      RUNNING: '生成中',
      QUEUED: '等待生成',
      SUCCEEDED: '成功',
      READY: '已就绪',
      FAILED: '失败',
      CANCELLED: '已取消',
      UNKNOWN: '结果未知',
      UNAVAILABLE: '未关联生成结果'
    },
    v
  )
const billing = (v: any) =>
  dictionary(
    {
      PENDING: '预扣待处理',
      SETTLED: '已结算',
      RELEASED: '已退回',
      MISSING: '缺少预扣记录',
      INCONSISTENT: '账本不一致'
    },
    v
  )
const anomaly = (v: any) =>
  dictionary(
    {
      NONE: '无异常',
      UNCERTAIN: '结果待核验',
      FAILED_PENDING: '失败未退回',
      SUCCESS_PENDING: '成功未结算',
      LONG_PENDING: '预扣超过15分钟',
      MISSING_BILLING: '缺少预扣记录',
      INCONSISTENT: '账本异常',
      STATE_MISMATCH: '结果与账务不一致'
    },
    v
  )
const caseLabel = (v: any) =>
  dictionary({ OPEN: '待核对', WAITING_EXTERNAL: '待外部确认', RESOLVED: '已核对' }, v)
const decision = (v: any) =>
  dictionary(
    {
      WAITING_EXTERNAL: '记录待外部确认',
      REVIEWED: '确认核对完成',
      DELIVERED: '核验已交付并结算',
      NOT_DELIVERED: '核验未交付并退回',
      SYNC_TERMINAL: '按已确认结果同步账务'
    },
    v
  )
const ledgerType = (v: any) => dictionary({ RESERVE: '预扣', SETTLE: '结算', RELEASE: '退回' }, v)
const integer = (v: any) => (v == null ? '—' : formatInteger(String(v))),
  date = (v: any) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—')
const parse = (v: any) => {
  if (v && typeof v === 'object') return v
  try {
    return JSON.parse(v || '{}')
  } catch {
    return {}
  }
}
const unit = (v: any) =>
  dictionary(
    {
      SECOND: '秒',
      CALL: '次',
      second: '秒',
      sec: '秒',
      count: '次',
      call: '次',
      times: '次',
      image: '张',
      minute: '分钟'
    },
    v
  )
const frozenUnit = computed(() => {
  const reserve = detail.value?.ledger.find((i) => i.type === 'RESERVE')
  const value = parse(reserve?.price_snapshot).unit
  return value ? unit(value) : ''
})
const snapshotDescription = (v: any) => {
  const p = parse(v)
  return p.priceMicrosPerUnit != null
    ? `${formatMicros(String(p.priceMicrosPerUnit))} 积分 / ${unit(p.unit)} · 版本 ${p.priceVersion ?? '未记录'}`
    : '未记录价格快照'
}
const historyData = (v: TaskRow) => parse(v.after_data)
const canAcknowledge = computed(
  () =>
    detail.value?.task.can_review &&
    detail.value?.task.case_status !== 'RESOLVED' &&
    detail.value?.task.anomaly === 'NONE' &&
    ['SETTLED', 'RELEASED'].includes(detail.value.task.billing_state)
)
const effect = computed(
  () =>
    ({
      DELIVERED: `按任务创建时冻结的 ${detail.value?.task.reserved_units || '—'} ${frozenUnit.value} 结算，不重新生成。`,
      NOT_DELIVERED: '确认未交付后退回该任务预扣积分；操作会保留核验记录。',
      SYNC_TERMINAL: '仅使用任务已确认的成功、失败或取消结果同步账务，不调用供应商重新生成。',
      WAITING_EXTERNAL: '保留当前账务状态和预扣，记录外部核验进展。',
      REVIEWED: '仅标记已核对，不改变积分。'
    })[review.decision] || ''
)
async function load() {
  const sequence = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await getOperationsTasks({
      ...filters,
      orgId: filters.orgId || undefined,
      subject: filters.subject || undefined,
      caseStatus: filters.caseStatus || undefined,
      pageNo: page.value,
      pageSize: 20
    })
    if (sequence === generation) {
      rows.value = result.list
      total.value = result.total
    }
  } catch (e) {
    if (sequence === generation) {
      rows.value = []
      total.value = 0
      error.value = operationsError(e)
    }
  } finally {
    if (sequence === generation) loading.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
function reset() {
  Object.assign(filters, {
    orgId: undefined,
    subject: '',
    keyword: '',
    caseStatus: '',
    onlyAnomalies: true
  })
  search()
}
async function findOrganizations(keyword = '') {
  const sequence = ++orgSequence
  orgLoading.value = true
  try {
    const result = await getOrganizationPage({
      keyword,
      orgType: '',
      pageNo: 1,
      pageSize: 50,
      includeDeleted: true
    })
    if (sequence === orgSequence) organizations.value = result.list
  } catch (e) {
    error.value = operationsError(e)
  } finally {
    if (sequence === orgSequence) orgLoading.value = false
  }
}
async function openTask(key: string) {
  taskKey.value = key
  drawer.value = true
  detail.value = undefined
  const sequence = ++detailSequence
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await getOperationsTask(key)
    if (sequence === detailSequence) detail.value = result
  } catch (e) {
    detailError.value = operationsError(e)
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}
function prepare(choice: string) {
  try {
    mutation.restore()
    Object.assign(
      review,
      mutation.pending.value || {
        expectedVersion: Number(detail.value?.task.case_version || 0),
        decision: choice,
        reason: '',
        evidence: '',
        requestKey: ''
      }
    )
    reviewError.value = ''
    reviewing.value = true
  } catch (e) {
    detailError.value = operationsError(e)
  }
}
async function reloadTask() {
  try {
    const fresh = await getOperationsTask(taskKey.value)
    mutation.clear()
    detail.value = fresh
    reviewing.value = false
    void load()
  } catch (e) {
    reviewError.value = operationsError(e)
  }
}
async function saveReview() {
  try {
    if (!mutation.pending.value && (!review.reason.trim() || !review.evidence.trim()))
      throw new Error('请填写核对说明和核验依据')
    const result = await mutation.run({ ...review, requestKey: crypto.randomUUID() }, (data) =>
      reviewOperationsTask(taskKey.value, data)
    )
    detail.value = result
    reviewing.value = false
    ElMessage.success('核对结果已保存')
    void load()
  } catch (e) {
    reviewError.value = operationsError(e)
  }
}
async function openOrganization() {
  try {
    organizationDetail.value = await getOrganizationDetail(Number(detail.value?.task.org_id))
    organizationDrawer.value = true
  } catch (e) {
    detailError.value = operationsError(e)
  }
}
watch(
  () => route.query.orgId,
  () => {
    const id = String(route.query.orgId || '')
    filters.orgId = /^[1-9]\d{0,14}$/.test(id) ? Number(id) : undefined
    organizationDrawer.value = false
    drawer.value = false
    search()
  }
)
onMounted(async () => {
  const id = String(route.query.orgId || '')
  if (/^[1-9]\d{0,14}$/.test(id)) filters.orgId = Number(id)
  await Promise.allSettled([
    load(),
    findOrganizations(),
    getSubjectPrices().then((result) => (subjects.value = result))
  ])
  if (filters.orgId && !organizations.value.some((o) => o.orgId === filters.orgId)) {
    try {
      const org = await getOrganizationDetail(filters.orgId)
      organizations.value.unshift(org.organization)
    } catch (e) {
      error.value = operationsError(e)
    }
  }
})
</script>
<style scoped>
.task-summary :deep(.el-descriptions__table) {
  table-layout: fixed;
  width: 100%;
}
.task-summary :deep(.el-descriptions__label) {
  width: 108px;
  min-width: 108px;
  word-break: keep-all;
  overflow-wrap: normal;
}
.task-summary :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
  word-break: break-word;
}
.organization-link {
  max-width: 100%;
  height: auto;
  white-space: normal;
  text-align: left;
  line-height: 1.5;
}
.organization-link :deep(span) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.task-detail-heading {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}
.task-detail-heading h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  line-height: 1.5;
}
.filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 0 16px;
}
.filters :deep(.el-select) {
  width: 100%;
}
.filter-actions {
  grid-column: 1/-1;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 0 0 20px;
}
.filter-actions :deep(.el-button) {
  margin-left: 0;
}
.secondary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.pagination {
  margin-top: 20px;
  justify-content: flex-end;
  max-width: 100%;
  overflow-x: auto;
}
.review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 20px 0;
}
.review-actions :deep(.el-button) {
  margin-left: 0;
  height: auto;
  min-height: 32px;
  white-space: normal;
}
.history-item {
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.history-item p {
  margin: 8px 0 0;
}
:deep(.el-alert) {
  margin-bottom: 16px;
}
:deep(.el-table .cell) {
  overflow-wrap: anywhere;
}
:deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
@media (max-width: 1100px) {
  .filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .filters {
    grid-template-columns: 1fr;
  }
  .pagination {
    justify-content: flex-start;
  }
}
</style>
