import type { EChartsOption } from 'echarts'
import type {
  DashboardMetrics,
  OperatingAnalytics,
  OperatingReconciliation,
  OperatingSnapshot,
  OperatingSnapshots,
  TaskSummary
} from '@/api/platform/franchiseDashboard'

/**
 * 经营看板纯函数口径（/fr/dashboard）。
 *
 * 注意：
 * - 金额源数据一律为「分」，看板按「元」展示（仅前端换算，不改数据）。
 * - 部门实绩指标：金额类 value=分；数量类按 ×100 存储（100=1，PerfMetrics P1-9 定稿）。
 * - 状态桶是查询时刻的当前状态快照，非月末历史状态（页面 tooltip 同步登记）。
 */

export interface TrendPoint {
  period: string
  incomeCents: number
  paidCents: number
  netCents: number
}

export interface CompositionRow {
  kind: string
  cents: number
}

export type StatusBuckets = Record<string, number>

export type MetricKind = 'money' | 'count'

export interface MetricMeta {
  label: string
  kind: MetricKind
}

export const CUSTOMER_STATUS_LABELS: Record<string, string> = {
  REGISTERED: '已登记',
  FOLLOWING: '跟进中'
}

export const ENGINEERING_STATUS_LABELS: Record<string, string> = {
  PENDING_START: '待开工',
  IN_PROGRESS: '施工中',
  READY_DELIVER: '待交付',
  DELIVERED: '已交付',
  ARCHIVED: '已归档'
}

export const DEPT_LABELS: Record<string, string> = {
  business: '业务组',
  engineering: '工程组',
  finance: '财务组'
}

/** 三组 summary 的指标元数据（口径来源 PerfMetrics.deptOfMetric / departmentMetrics）。 */
export const DEPARTMENT_METRICS: Record<string, MetricMeta> = {
  'auto:collectionsAmount': { label: '回款金额', kind: 'money' },
  'auto:contract': { label: '签约金额', kind: 'money' },
  'metric:commissionAmount': { label: '委托金额', kind: 'money' },
  'auto:publications': { label: '发布数', kind: 'count' },
  'auto:effectiveLeads': { label: '有效线索', kind: 'count' },
  'metric:activityTotal': { label: '活动合计', kind: 'count' },
  'metric:effectiveCustomers': { label: '有效客户', kind: 'count' },
  'auto:approvedDeliverables': { label: '交付通过', kind: 'count' },
  'auto:completedNodes': { label: '完成节点', kind: 'count' },
  'auto:onTimeNodes': { label: '按期节点', kind: 'count' },
  'metric:firstPassNodes': { label: '一次通过节点', kind: 'count' },
  'auto:qualifiedDeliveries': { label: '合格到货', kind: 'count' },
  'auto:financeRegistrations': { label: '财务登记', kind: 'count' }
}

/** 已知状态零填，未知状态兜底追加（后端同口径，前端再做一次幂等合并）。 */
export function mergeStatusBuckets(base: string[], actual?: StatusBuckets | null): StatusBuckets {
  const out: StatusBuckets = {}
  for (const key of base) {
    out[key] = 0
  }
  for (const [key, value] of Object.entries(actual || {})) {
    out[key] = Number(value) || 0
  }
  return out
}

/** 分 → 元（两位小数、千分位）。 */
export function centsToYuan(cents: number | null | undefined): string {
  if (cents === null || cents === undefined || Number.isNaN(cents)) return '-'
  return (cents / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}

function trimNumber(value: number): string {
  if (!Number.isFinite(value)) return '-'
  return String(Math.round(value * 100) / 100)
}

/** 部门指标展示值：money=分→元；count=×100 存储→原始个数。 */
export function formatMetricValue(code: string, value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  const meta = DEPARTMENT_METRICS[code]
  if (meta?.kind === 'money') return centsToYuan(value)
  return trimNumber(value / 100)
}

/** 0~1 比率 → 百分比文本（null 显示「-」）。 */
export function percentText(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return `${(value * 100).toFixed(digits)}%`
}

/** 倍数文本（资金覆盖）；null 显示「-」。 */
export function ratioText(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return `${value.toFixed(digits)}×`
}

/** 任务完成率：total<=0 时为 null（页面显示「-」）；结果夹在 0~1。 */
export function completionRate(
  completed: number | null | undefined,
  total: number | null | undefined
): number | null {
  if (!total || total <= 0) return null
  return Math.min(1, Math.max(0, (completed || 0) / total))
}

/** 状态分桶文本（tooltip 用）。 */
export function statusSummaryText(
  buckets: StatusBuckets | null | undefined,
  labels: Record<string, string>
): string {
  if (!buckets) return '-'
  return Object.entries(buckets)
    .map(([key, value]) => `${labels[key] || key} ${value}`)
    .join(' / ')
}

/**
 * 月份作用域区块的 settled 结果形状（与 load() 的 Promise.allSettled 元组对应）。
 * reconciliations 不是月份作用域，不在此列。
 */
export interface DashboardBlockResults {
  dashboard: PromiseSettledResult<DashboardMetrics>
  analytics: PromiseSettledResult<OperatingAnalytics>
  summary: PromiseSettledResult<TaskSummary>
  snapshots: PromiseSettledResult<OperatingSnapshots>
}

export interface DashboardBlocks {
  metrics: DashboardMetrics | null
  analytics: OperatingAnalytics | null
  summary: TaskSummary | null
  snapshots: OperatingSnapshot[]
  failedBlocks: number
}

/** 空区块：请求发出时先落到该状态，保证失败区块不会残留上一月份值。 */
export function emptyDashboardBlocks(): DashboardBlocks {
  return { metrics: null, analytics: null, summary: null, snapshots: [], failedBlocks: 0 }
}

/**
 * 合并当月请求结果：失败区块一律归 null/空数组（绝不回退到上一月份的值）。
 * load() 在请求发出时先清空四块，再写入本函数结果，双保险防止跨月混用。
 */
export function resolveDashboardBlocks(results: DashboardBlockResults): DashboardBlocks {
  return {
    metrics: results.dashboard.status === 'fulfilled' ? results.dashboard.value : null,
    analytics: results.analytics.status === 'fulfilled' ? results.analytics.value : null,
    summary: results.summary.status === 'fulfilled' ? results.summary.value : null,
    snapshots:
      results.snapshots.status === 'fulfilled' ? results.snapshots.value?.snapshots || [] : [],
    failedBlocks: [results.dashboard, results.analytics, results.summary, results.snapshots].filter(
      (result) => result.status === 'rejected'
    ).length
  }
}

/** 页面注入的取数函数（可测试：测试可替换为受控 Promise）。 */
export interface DashboardFetchers {
  dashboard: (period: string) => Promise<DashboardMetrics>
  analytics: (period: string) => Promise<OperatingAnalytics>
  summary: (period: string) => Promise<TaskSummary>
  snapshots: (period: string) => Promise<OperatingSnapshots>
  reconciliations: () => Promise<OperatingReconciliation[]>
}

export interface DashboardLoadResult {
  /** true=本请求已被更新的请求取代；页面必须丢弃，不得写回。 */
  stale: boolean
  month: string
  blocks: DashboardBlocks
  /** null=对账请求失败，页面应保留既有值（非月份作用域）。 */
  reconciliations: OperatingReconciliation[] | null
  failedBlocks: number
}

/**
 * 数据写回回调（P2 结构性修复）：在 isCurrent() 为 true 后同步立即调用，
 * 中间不得有任何 await/微任务边界（否则 watch/nextTick 可能插入新请求）。
 */
export type DashboardApply = (result: DashboardLoadResult) => void

/**
 * 加载编排：发出全部请求 → 等待 settled → isCurrent 通过后**同步**调用 apply 再返回。
 *
 * <p>代次校验与写回处于同一同步回合：isCurrent() 返回 true 与 apply(result) 之间
 * 没有任何 await/微任务，其他请求无法插入，从结构上消除“判定通过但写回前被新请求取代”的窗口。
 */
export async function loadDashboardBlocks(
  fetchers: DashboardFetchers,
  period: string,
  isCurrent: () => boolean,
  apply: DashboardApply
): Promise<DashboardLoadResult> {
  const results = await Promise.allSettled([
    fetchers.dashboard(period),
    fetchers.analytics(period),
    fetchers.summary(period),
    fetchers.snapshots(period),
    fetchers.reconciliations()
  ])
  const [dashboardResult, analyticsResult, summaryResult, snapshotResult, reconciliationResult] =
    results
  if (!isCurrent()) {
    return {
      stale: true,
      month: period,
      blocks: emptyDashboardBlocks(),
      reconciliations: null,
      failedBlocks: 0
    }
  }
  const blocks = resolveDashboardBlocks({
    dashboard: dashboardResult,
    analytics: analyticsResult,
    summary: summaryResult,
    snapshots: snapshotResult
  })
  const result: DashboardLoadResult = {
    stale: false,
    month: period,
    blocks,
    reconciliations:
      reconciliationResult.status === 'fulfilled' ? reconciliationResult.value || [] : null,
    failedBlocks: blocks.failedBlocks + (reconciliationResult.status === 'rejected' ? 1 : 0)
  }
  // 关键：isCurrent() 为 true 后同步写回，不得在此前插入 await/微任务。
  apply(result)
  return result
}

/**
 * 收支构成合计（源数据为分；expense-refund 在服务端已为负值，此处不做 abs）。
 * 净支出 = 付款 + 退款 + 费用回流（负值），供页面逐项核对。
 */
export interface CompositionTotals {
  incomeCents: number
  paymentCents: number
  refundCents: number
  expenseRefundCents: number
  netPaidCents: number
}

export function compositionTotals(
  income: CompositionRow[],
  paid: CompositionRow[]
): CompositionTotals {
  const sum = (rows: CompositionRow[]) =>
    rows.reduce((total, row) => total + (Number(row.cents) || 0), 0)
  const sumKind = (rows: CompositionRow[], kind: string) =>
    rows
      .filter((row) => row.kind === kind)
      .reduce((total, row) => total + (Number(row.cents) || 0), 0)
  return {
    incomeCents: sum(income),
    paymentCents: sumKind(paid, 'payment'),
    refundCents: sumKind(paid, 'refund'),
    expenseRefundCents: sumKind(paid, 'expense-refund'),
    netPaidCents: sum(paid)
  }
}

/** 近 6 月趋势：系列按元展示（源数据为分）。 */
export function buildTrendOption(trend: TrendPoint[]): EChartsOption {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['实收', '支出', '净流入'], top: 0 },
    grid: { left: 8, right: 20, top: 36, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: trend.map((point) => point.period) },
    yAxis: { type: 'value', name: '元', splitLine: { lineStyle: { color: '#f0ece6' } } },
    series: [
      {
        name: '实收',
        type: 'bar',
        data: trend.map((point) => point.incomeCents / 100),
        barMaxWidth: 18,
        itemStyle: { color: '#9b5c2b' }
      },
      {
        name: '支出',
        type: 'bar',
        data: trend.map((point) => point.paidCents / 100),
        barMaxWidth: 18,
        itemStyle: { color: '#d9b18c' }
      },
      {
        name: '净流入',
        type: 'line',
        data: trend.map((point) => point.netCents / 100),
        smooth: true,
        itemStyle: { color: '#3f7d5a' }
      }
    ]
  }
}

/**
 * 收支构成：横向条形（源数据为分，按元展示）。
 * 条形图天然支持正负值：费用回流等负项反向显示、标签与 tooltip 保留负号；
 * 标签按符号逐项放置（负值 left、正值/零 right），避免负值标签压在零线上。
 * 不再使用 ECharts pie（pie 无法表达负值，会静默丢弃负项）。
 */
export function buildCompositionOption(
  income: CompositionRow[],
  paid: CompositionRow[]
): EChartsOption {
  const rows = [
    ...income.map((row) => ({ name: `收入 · ${row.kind}`, value: row.cents / 100 })),
    ...paid.map((row) => ({ name: `支出 · ${row.kind}`, value: row.cents / 100 }))
  ]
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 56, top: 8, bottom: 8, containLabel: true },
    xAxis: { type: 'value', name: '元', splitLine: { lineStyle: { color: '#f0ece6' } } },
    yAxis: { type: 'category', data: rows.map((row) => row.name), axisTick: { show: false } },
    series: [
      {
        name: '金额',
        type: 'bar',
        data: rows.map((row) => ({
          value: row.value,
          label: { position: row.value < 0 ? 'left' : 'right' }
        })),
        barMaxWidth: 20,
        itemStyle: { color: '#9b5c2b' },
        label: { show: true, position: 'right' }
      }
    ]
  }
}
