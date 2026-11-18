<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">经营看板</h1>
        <div class="zs-page-subtitle">
          财务趋势、部门实绩与当月新增指标（只读；金额按「元」展示，源数据为「分」）
        </div>
      </div>
      <div class="zs-actions">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          placeholder="选择月份"
          :clearable="false"
          @change="load"
        />
        <el-button class="zs-btn-primary" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="!canView"
      type="warning"
      :closable="false"
      title="当前工作身份无经营视图权限（franchise:performance:operate），请联系组织管理员授权。"
      show-icon
    />

    <div v-else v-loading="loading">
      <el-alert
        v-if="loadWarning"
        :title="loadWarning"
        type="warning"
        :closable="false"
        show-icon
        class="mb-12px"
      />
      <LegacyCommissionVoucher v-if="identity.currentPost?.roleCode === 'boss'" :key="String(identity.currentPost.postId)" />
      <!-- 财务概览卡（analytics.totals） -->
      <div class="zs-stat-cards dash-cards">
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">当月净流入（元）</div>
            <div class="zs-stat-value">{{ centsToYuan(currentNetCents) }}</div>
          </div>
        </div>
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">应收余额（元）</div>
            <div class="zs-stat-value">{{ centsToYuan(totals?.receivableCents) }}</div>
          </div>
        </div>
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">应付余额（元）</div>
            <div class="zs-stat-value">{{ centsToYuan(totals?.payableOutstandingCents) }}</div>
          </div>
        </div>
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">回款率</div>
            <div class="zs-stat-value">{{ percentText(totals?.collectionRate) }}</div>
          </div>
        </div>
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">资金覆盖（近 6 月净流入 / 未付应付）</div>
            <div class="zs-stat-value">{{ ratioText(totals?.coverage) }}</div>
          </div>
        </div>
        <div class="zs-stat-card">
          <div>
            <div class="zs-stat-label">近 6 月净流入（元）</div>
            <div class="zs-stat-value">{{ centsToYuan(totals?.net6MonthsCents) }}</div>
          </div>
        </div>
      </div>

      <!-- 趋势与构成 -->
      <div class="chart-grid">
        <div class="zs-table-card chart-card">
          <div class="section-head">
            <span class="section-title">近 6 月收支趋势（元）</span>
          </div>
          <Echart v-if="trendOption" :options="trendOption" :height="300" />
          <el-empty v-else description="暂无趋势数据" :image-size="60" />
        </div>
        <div class="zs-table-card chart-card">
          <div class="section-head">
            <span class="section-title">收支构成（元；含负值，负项反向显示）</span>
          </div>
          <Echart v-if="compositionOption" :options="compositionOption" :height="320" />
          <el-empty v-else description="暂无构成数据" :image-size="60" />
          <div v-if="compositionSummary" class="composition-summary">
            净支出 = 付款 {{ centsToYuan(compositionSummary.paymentCents) }} + 退款
            {{ centsToYuan(compositionSummary.refundCents) }} + 费用回流
            {{ centsToYuan(compositionSummary.expenseRefundCents) }} =
            {{ centsToYuan(compositionSummary.netPaidCents) }} 元
          </div>
        </div>
      </div>

      <!-- 新增经营指标（P1 批1 新端点） -->
      <div class="zs-table-card dash-section">
        <div class="section-head">
          <span class="section-title">当月新增指标</span>
          <el-tooltip
            content="客户/工程状态分桶为查询时刻的当前状态快照，不是月末历史状态"
            placement="top"
          >
            <span class="hint-link">状态口径</span>
          </el-tooltip>
        </div>
        <div class="metric-grid">
          <div class="metric-cell">
            <div class="metric-label">新增客户</div>
            <div class="metric-value">{{ metrics?.crm.newCustomers ?? '-' }}</div>
            <div class="metric-sub">{{ customerStatusText }}</div>
          </div>
          <div class="metric-cell">
            <div class="metric-label">新增线索</div>
            <div class="metric-value">{{ metrics?.crm.newLeads ?? '-' }}</div>
            <div class="metric-sub">含全部线索状态（按创建时间计）</div>
          </div>
          <div class="metric-cell">
            <div class="metric-label">承接工程</div>
            <div class="metric-value">{{ metrics?.engineering.created ?? '-' }}</div>
            <div class="metric-sub">{{ engineeringStatusText }}</div>
          </div>
          <div class="metric-cell">
            <div class="metric-label">
              服务委托数
              <el-tooltip
                content="仅统计经平台服务能力发起的委托（operation=CREATE_PROJECT），设计端消费者自建委托不计；含全部状态，故为发起数非成功数"
                placement="top"
              >
                <span class="hint-link">口径</span>
              </el-tooltip>
            </div>
            <div class="metric-value">{{ metrics?.design.serviceCommissions ?? '-' }}</div>
            <div class="metric-sub">服务能力发起（非设计端自建）</div>
          </div>
        </div>
        <div class="hint">状态分组为当前状态快照，非月末历史状态；数量均为所选月份“创建”口径。</div>
      </div>

      <!-- 部门实绩 + 任务完成率 -->
      <div class="zs-table-card dash-section">
        <div class="section-head">
          <span class="section-title">部门实绩与任务完成</span>
          <span class="hint">
            任务完成 {{ summary?.taskStats.completed ?? 0 }} /
            {{ summary?.taskStats.total ?? 0 }} （{{ percentText(completion) }}）
          </span>
        </div>
        <el-progress
          v-if="completion !== null"
          :percentage="Math.round(completion * 100)"
          :stroke-width="10"
          class="mb-12px"
        />
        <el-table :data="deptRows" size="small" empty-text="所选月份暂无部门实绩">
          <el-table-column prop="label" label="部门" width="120" />
          <el-table-column prop="metric" label="指标" min-width="160" />
          <el-table-column prop="value" label="数值" width="160" align="right" />
        </el-table>
        <div class="hint"
          >金额类指标按元展示；数量类指标源数据按 ×100 存储（100 = 1），已换算为原始个数。</div
        >
      </div>

      <!-- 账龄六桶 -->
      <div class="aging-grid">
        <div class="zs-table-card">
          <div class="section-head">
            <span class="section-title">应收账龄（元）</span>
          </div>
          <el-table :data="receivableAgingRows" size="small">
            <el-table-column prop="label" label="账龄桶" />
            <el-table-column prop="value" label="金额（元）" align="right" />
          </el-table>
        </div>
        <div class="zs-table-card">
          <div class="section-head">
            <span class="section-title">应付账龄（元）</span>
          </div>
          <el-table :data="payableAgingRows" size="small">
            <el-table-column prop="label" label="账龄桶" />
            <el-table-column prop="value" label="金额（元）" align="right" />
          </el-table>
        </div>
      </div>

      <!-- 快照与对账（只读） -->
      <div class="zs-table-card dash-section">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="快照记录" name="snapshots">
            <el-table :data="snapshots" size="small" empty-text="所选月份暂无归档快照">
              <el-table-column prop="version" label="版本" width="80" />
              <el-table-column prop="asOf" label="截止日期" width="130" />
              <el-table-column prop="supersedes" label="替代版本 ID" min-width="180" />
              <el-table-column prop="note" label="备注" min-width="180" />
              <el-table-column prop="id" label="快照 ID" min-width="180" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="对账记录" name="reconciliations">
            <el-table :data="reconciliations" size="small" empty-text="暂无月末对账记录">
              <el-table-column prop="period" label="月份" width="100" />
              <el-table-column label="收入（元）" width="140" align="right">
                <template #default="{ row }">{{ centsToYuan(row.incomeCents) }}</template>
              </el-table-column>
              <el-table-column label="支出（元）" width="140" align="right">
                <template #default="{ row }">{{ centsToYuan(row.paidCents) }}</template>
              </el-table-column>
              <el-table-column label="净额（元）" width="140" align="right">
                <template #default="{ row }">{{ centsToYuan(row.netCents) }}</template>
              </el-table-column>
              <el-table-column prop="receiptCount" label="收款笔数" width="100" align="right" />
              <el-table-column prop="paymentCount" label="付款笔数" width="100" align="right" />
              <el-table-column prop="updateTime" label="更新时间" min-width="180" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
        <div class="hint">
          只读展示：快照归档与月末对账写操作不在本页；财务核对明细请前往「财务核对」页。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import LegacyCommissionVoucher from './LegacyCommissionVoucher.vue'
import Echart from '@/components/Echart/src/Echart.vue'
import {
  getDashboard,
  getOperatingAnalytics,
  getTaskSummary,
  getOperatingSnapshots,
  getOperatingReconciliations,
  type DashboardMetrics,
  type OperatingAnalytics,
  type OperatingSnapshot,
  type OperatingReconciliation,
  type TaskSummary
} from '@/api/platform/franchiseDashboard'
import {
  buildCompositionOption,
  buildTrendOption,
  centsToYuan,
  completionRate,
  compositionTotals,
  emptyDashboardBlocks,
  formatMetricValue,
  loadDashboardBlocks,
  mergeStatusBuckets,
  percentText,
  ratioText,
  statusSummaryText,
  CUSTOMER_STATUS_LABELS,
  DEPARTMENT_METRICS,
  DEPT_LABELS,
  ENGINEERING_STATUS_LABELS
} from './model'

defineOptions({ name: 'FranchiseDashboard' })

const CUSTOMER_STATUS_ORDER = ['REGISTERED', 'FOLLOWING']
const ENGINEERING_STATUS_ORDER = [
  'PENDING_START',
  'IN_PROGRESS',
  'READY_DELIVER',
  'DELIVERED',
  'ARCHIVED'
]
const DEPT_ORDER = ['business', 'engineering', 'finance']
const AGING_ORDER = ['notDue', 'd1_30', 'd31_60', 'd61_90', 'd90Plus', 'undated']
const AGING_LABELS: Record<string, string> = {
  notDue: '未到期',
  d1_30: '逾期 1-30 天',
  d31_60: '逾期 31-60 天',
  d61_90: '逾期 61-90 天',
  d90Plus: '逾期 90 天以上',
  undated: '未约定'
}

const identity = usePlatformIdentityStore()
const canView = computed(() => identity.can('franchise:performance:operate'))

const month = ref(currentMonth())
const loading = ref(false)
const loadWarning = ref('')
const metrics = ref<DashboardMetrics | null>(null)
const analytics = ref<OperatingAnalytics | null>(null)
const summary = ref<TaskSummary | null>(null)
const snapshots = ref<OperatingSnapshot[]>([])
const reconciliations = ref<OperatingReconciliation[]>([])
const activeTab = ref('snapshots')

/** 月份查询请求代次：快速切月时旧响应不得覆盖新结果。 */
let loadGeneration = 0

async function load() {
  if (!canView.value) return
  const generation = ++loadGeneration
  const period = month.value
  loading.value = true
  loadWarning.value = ''
  // P1-1：请求发出即清空月份作用域区块——失败区块保持空态，绝不与上一月份数据混用。
  // reconciliations 非月份作用域，失败时保留既有值。
  const cleared = emptyDashboardBlocks()
  metrics.value = cleared.metrics
  analytics.value = cleared.analytics
  summary.value = cleared.summary
  snapshots.value = cleared.snapshots
  try {
    // P2：代次校验与写回在 loadDashboardBlocks 内同一同步回合完成（apply 回调）；
    // 页面不再在 await 之后写 refs，从结构上消除可被微任务插入的窗口。
    await loadDashboardBlocks(
      {
        dashboard: getDashboard,
        analytics: getOperatingAnalytics,
        summary: getTaskSummary,
        snapshots: getOperatingSnapshots,
        reconciliations: getOperatingReconciliations
      },
      period,
      () => generation === loadGeneration,
      (result) => {
        metrics.value = result.blocks.metrics
        analytics.value = result.blocks.analytics
        summary.value = result.blocks.summary
        snapshots.value = result.blocks.snapshots
        if (result.reconciliations !== null) reconciliations.value = result.reconciliations
        if (result.failedBlocks > 0) {
          loadWarning.value = `部分区块加载失败（${result.failedBlocks} 项），对应区块已清空，请刷新重试`
          ElMessage.warning(loadWarning.value)
        }
        loading.value = false
      }
    )
  } finally {
    // 兜底：stale 或异常时收尾（正常写回路径已在 apply 内同步收尾）。
    if (generation === loadGeneration) loading.value = false
  }
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

const totals = computed(() => analytics.value?.totals ?? null)
const currentNetCents = computed(() => {
  const trend = analytics.value?.trend
  return trend && trend.length ? trend[trend.length - 1].netCents : null
})
const trendOption = computed(() =>
  analytics.value ? buildTrendOption(analytics.value.trend) : null
)
const compositionOption = computed(() =>
  analytics.value
    ? buildCompositionOption(analytics.value.incomeComposition, analytics.value.paidComposition)
    : null
)
const compositionSummary = computed(() =>
  analytics.value
    ? compositionTotals(analytics.value.incomeComposition, analytics.value.paidComposition)
    : null
)
const customerStatus = computed(() =>
  mergeStatusBuckets(CUSTOMER_STATUS_ORDER, metrics.value?.crm.newCustomersByStatus)
)
const engineeringStatus = computed(() =>
  mergeStatusBuckets(ENGINEERING_STATUS_ORDER, metrics.value?.engineering.byStatus)
)
const customerStatusText = computed(() =>
  statusSummaryText(customerStatus.value, CUSTOMER_STATUS_LABELS)
)
const engineeringStatusText = computed(() =>
  statusSummaryText(engineeringStatus.value, ENGINEERING_STATUS_LABELS)
)
const completion = computed(() =>
  completionRate(summary.value?.taskStats.completed, summary.value?.taskStats.total)
)

interface DeptRow {
  label: string
  metric: string
  value: string
}

const deptRows = computed<DeptRow[]>(() => {
  const departments = summary.value?.departments || {}
  const rows: DeptRow[] = []
  for (const dept of DEPT_ORDER) {
    const bucket = departments[dept]
    if (!bucket) continue
    for (const [code, meta] of Object.entries(DEPARTMENT_METRICS)) {
      if (bucket[code] === undefined) continue
      rows.push({
        label: DEPT_LABELS[dept] || dept,
        metric: meta.label,
        value: formatMetricValue(code, bucket[code])
      })
    }
    for (const [code, value] of Object.entries(bucket)) {
      if (DEPARTMENT_METRICS[code]) continue
      rows.push({
        label: DEPT_LABELS[dept] || dept,
        metric: code,
        value: formatMetricValue(code, value)
      })
    }
  }
  return rows
})

function agingRows(buckets: Record<string, number> | null | undefined) {
  const merged = mergeStatusBuckets(AGING_ORDER, buckets)
  return AGING_ORDER.map((key) => ({
    label: AGING_LABELS[key] || key,
    value: centsToYuan(merged[key])
  }))
}

const receivableAgingRows = computed(() => agingRows(analytics.value?.receivableAging))
const payableAgingRows = computed(() => agingRows(analytics.value?.payableAging))

onMounted(load)
</script>

<style scoped lang="scss">
.dash-cards {
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
}

.chart-grid,
.aging-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  min-height: 360px;
}

.dash-section {
  margin-bottom: 20px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #4d301e;
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #9b9b9b;
}

.composition-summary {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #6f6259;
}

.hint-link {
  font-size: 12px;
  color: #9b5c2b;
  cursor: help;
  border-bottom: 1px dashed #c9a883;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.metric-cell {
  padding: 16px 18px;
  background: #fbf7f2;
  border: 1px solid #eadfd5;
  border-radius: 12px;
}

.metric-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #6f6259;
}

.metric-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
  color: #282728;
}

.metric-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #8a7d72;
}

.mb-12px {
  margin-bottom: 12px;
}

@media (width <= 768px) {
  .chart-grid,
  .aging-grid {
    grid-template-columns: 1fr;
  }
}
</style>
