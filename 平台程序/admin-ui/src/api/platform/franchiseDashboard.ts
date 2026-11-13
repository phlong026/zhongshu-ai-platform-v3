import request from '@/config/axios'

/**
 * 加盟商经营看板 API（/admin-api/franchise/performance）
 *
 * 端点分工（不重复聚合）：
 * - GET /dashboard：P1 批1 新增，客资/工程/设计三域“当月创建量”；
 * - GET /operating/analytics：既有，财务趋势/构成/余额/账龄；
 * - GET /tasks/summary：既有，部门实绩 + 任务统计；
 * - GET /operating/snapshots、/operating/reconciliations：既有，只读快照与对账记录。
 *
 * 金额字段一律为「分」；页面按「元」展示（仅前端换算）。
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

export interface OperatingTotals {
  receivableCents: number
  payableOutstandingCents: number
  collectionRate: number | null
  net6MonthsCents: number
  coverage: number | null
}

export interface OperatingAnalytics {
  trend: TrendPoint[]
  incomeComposition: CompositionRow[]
  paidComposition: CompositionRow[]
  totals: OperatingTotals
  receivableAging: Record<string, number>
  payableAging: Record<string, number>
  sourceSignature?: string
}

export interface TaskSummary {
  period: string
  departments: Record<string, Record<string, number>>
  taskStats: { total: number; completed: number }
}

export interface DashboardCrm {
  newCustomers: number
  newCustomersByStatus: Record<string, number>
  newLeads: number
}

export interface DashboardEngineering {
  created: number
  byStatus: Record<string, number>
}

export interface DashboardDesign {
  /**
   * 服务委托数（发起）：仅统计经平台服务能力创建、operation='CREATE_PROJECT' 的命令；
   * 消费者在设计端自建的项目不计入；含全部状态（NEW/SENT/UNKNOWN/SUCCEEDED/REJECTED），
   * 故为发起数非成功数。
   */
  serviceCommissions: number
}

export interface DashboardMetrics {
  period: string
  crm: DashboardCrm
  engineering: DashboardEngineering
  design: DashboardDesign
}

export interface OperatingSnapshot {
  id: string
  version: number
  supersedes: string | null
  report?: string
  asOf: string
  note: string | null
}

export interface OperatingSnapshots {
  period: string
  snapshots: OperatingSnapshot[]
}

export interface OperatingReconciliation {
  period: string
  incomeCents?: number
  paidCents?: number
  netCents?: number
  receiptCount?: number
  paymentCount?: number
  updateTime?: string
}

/** 新增看板指标（客资/工程/设计当月创建量）。 */
export const getDashboard = (period: string): Promise<DashboardMetrics> =>
  request.get({ url: '/franchise/performance/dashboard', params: { period } })

/** 财务维度实时经营分析（趋势/构成/余额/账龄）。 */
export const getOperatingAnalytics = (period: string): Promise<OperatingAnalytics> =>
  request.get({ url: '/franchise/performance/operating/analytics', params: { period } })

/** 部门实绩 + 任务统计（权限为 franchise:performance:query；operate 岗位当前均同时持有）。 */
export const getTaskSummary = (period: string): Promise<TaskSummary> =>
  request.get({ url: '/franchise/performance/tasks/summary', params: { period } })

/** 经营快照列表（只读；归档动作不在 Web P1 范围）。 */
export const getOperatingSnapshots = (period: string): Promise<OperatingSnapshots> =>
  request.get({ url: '/franchise/performance/operating/snapshots', params: { period } })

/** 月末对账记录列表（只读）。 */
export const getOperatingReconciliations = (): Promise<OperatingReconciliation[]> =>
  request.get({ url: '/franchise/performance/operating/reconciliations' })
