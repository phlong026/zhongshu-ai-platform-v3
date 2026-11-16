<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">总部运营工作台</h1>
      </div>
      <el-button
        v-if="checkPermi(['platform:org:onboard'])"
        type="primary"
        class="zs-btn-primary"
        @click="router.push('/platform-org/onboard')"
      >
        <el-icon class="btn-icon"><Plus /></el-icon>
        开通新组织
      </el-button>
    </div>

    <el-alert
      v-if="hasErrors"
      title="部分运营数据加载失败，成功的数据仍可查看"
      type="error"
      :closable="false"
    />
    <el-button v-if="hasErrors" :loading="loading" @click="load">重新加载</el-button>

    <section
      v-if="checkPermi(['platform:org:onboard'])"
      class="zs-table-card organization-card"
      v-loading="organizationsLoading"
    >
      <div class="organization-card-heading">
        <h2
          >已开通组织 <span> {{ organizationTotal }} </span></h2
        >
        <el-button link type="primary" @click="router.push('/platform-org/onboard')"
          >查看全部</el-button
        >
      </div>
      <el-alert
        v-if="organizationsError"
        :title="organizationsError"
        type="error"
        :closable="false"
      />
      <el-table native-scrollbar :data="organizations" empty-text="暂无已开通组织">
        <el-table-column prop="orgName" label="组织名称" min-width="240" show-overflow-tooltip />
        <el-table-column prop="ownerName" label="老板姓名" width="110" />
        <el-table-column prop="mobile" label="手机号" width="140" />
        <el-table-column prop="loginName" label="登录账号" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              @click="router.push({ path: '/platform-org/onboard', query: { orgId: row.orgId } })"
              >详情</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </section>
    <el-collapse class="legacy-statistics">
      <el-collapse-item title="历史运营数据" name="legacy">
        <div class="zs-stat-cards">
          <div
            v-for="card in statCards"
            :key="card.label"
            class="zs-stat-card"
            :class="{ 'zs-stat-card--alert': card.alert }"
            @click="card.to && router.push(card.to)"
          >
            <div class="zs-stat-icon"
              ><el-icon><component :is="card.icon" /></el-icon
            ></div>
            <div>
              <div class="zs-stat-label">{{ card.label }}</div>
              <div class="zs-stat-value" v-loading="sourceStates[card.source] === 'pending'">{{
                card.value
              }}</div>
            </div>
          </div>
        </div>

        <!-- 图表区（2×2） -->
        <el-row :gutter="16">
          <el-col :xs="24" :md="12">
            <div class="chart-card">
              <div class="chart-card__title">历史钱包可用点数（前10，不含公司积分池）</div>
              <el-empty
                v-if="sourceMessage('wallets')"
                :description="sourceMessage('wallets')"
                :image-size="60"
              />
              <Echart v-else-if="walletChartOptions" :options="walletChartOptions" :height="300" />
              <el-empty v-else-if="!loading" description="暂无钱包数据" :image-size="60" />
            </div>
          </el-col>
          <el-col :xs="24" :md="12">
            <div class="chart-card">
              <div class="chart-card__title">最近100条流水（按日汇总）</div>
              <el-empty
                v-if="sourceMessage('ledger')"
                :description="sourceMessage('ledger')"
                :image-size="60"
              />
              <Echart v-else-if="ledgerChartOptions" :options="ledgerChartOptions" :height="300" />
              <el-empty v-else-if="!loading" description="暂无流水数据" :image-size="60" />
            </div>
          </el-col>
          <el-col :xs="24" :md="12">
            <div class="chart-card">
              <div class="chart-card__title">最近100条流水类型分布</div>
              <el-empty
                v-if="sourceMessage('ledger')"
                :description="sourceMessage('ledger')"
                :image-size="60"
              />
              <Echart
                v-else-if="bizTypeChartOptions"
                :options="bizTypeChartOptions"
                :height="300"
              />
              <el-empty v-else-if="!loading" description="暂无流水数据" :image-size="60" />
            </div>
          </el-col>
          <el-col :xs="24" :md="12">
            <div class="chart-card">
              <div class="chart-card__title">生效设计定价点数（前10）</div>
              <el-empty
                v-if="sourceMessage('prices')"
                :description="sourceMessage('prices')"
                :image-size="60"
              />
              <Echart v-else-if="priceChartOptions" :options="priceChartOptions" :height="300" />
              <el-empty v-else-if="!loading" description="暂无定价数据" :image-size="60" />
            </div>
          </el-col>
        </el-row>
      </el-collapse-item>
    </el-collapse>

    <!-- 快捷入口 -->
    <div class="zs-table-card quick-row">
      <span class="quick-row__title">快捷入口</span>
      <el-tag
        v-for="entry in quickEntries"
        :key="entry.path"
        class="quick-tag"
        @click="router.push(entry.path)"
      >
        {{ entry.title }}
      </el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { checkPermi } from '@/utils/permission'
import { getOrganizationPage, type Organization } from '@/api/zs/platform-org'
import {
  Coin,
  Wallet,
  Document,
  Warning,
  PriceTag,
  DataLine,
  DocumentChecked,
  Plus
} from '@element-plus/icons-vue'
import Echart from '@/components/Echart/src/Echart.vue'
import type { EChartsOption } from 'echarts'
import {
  getWalletSummary,
  getRechargePlans,
  getRechargeOrders,
  getAnomalies,
  getActiveDesignPrices,
  getLedger,
  getDesignCosts,
  getAudit,
  type WalletRow,
  type LedgerRow,
  type DesignPriceRow
} from '@/api/platform/operations'

defineOptions({ name: 'PlatformOperationsHome' })
const router = useRouter()
const loading = ref(true)
const organizations = ref<Organization[]>([]),
  organizationTotal = ref(0),
  organizationsLoading = ref(false),
  organizationsError = ref('')
onMounted(async () => {
  if (!checkPermi(['platform:org:onboard'])) return
  organizationsLoading.value = true
  try {
    const result = await getOrganizationPage({ keyword: '', orgType: '', pageNo: 1, pageSize: 5 })
    organizations.value = result.list
    organizationTotal.value = result.total
  } catch {
    organizationsError.value = '组织加载失败，请进入组织管理重试'
  } finally {
    organizationsLoading.value = false
  }
})

const wallets = ref<WalletRow[]>([])
const ledger = ref<LedgerRow[]>([])
const prices = ref<DesignPriceRow[]>([])
const walletCount = ref(0)
const totalAvailablePoints = ref(0)
const orderTotal = ref(0)
const planTotal = ref(0)
const anomalyTotal = ref(0)
const priceTotal = ref(0)
const costTotal = ref(0)
const auditTotal = ref(0)
const permissions = {
  wallets: 'platform:wallet:query',
  ledger: 'platform:wallet:ledger:query',
  prices: 'platform:design:price:query',
  orders: 'platform:wallet:order:query',
  plans: 'platform:wallet:plan:query',
  anomalies: 'platform:wallet:anomaly:query',
  costs: 'platform:design:cost:query',
  audit: 'platform:ops:audit:query'
} as const
type Source = keyof typeof permissions
type Status = 'pending' | 'ready' | 'error' | 'denied'
const sourceStates = ref(
  Object.fromEntries(Object.keys(permissions).map((key) => [key, 'pending'])) as Record<
    Source,
    Status
  >
)
const hasErrors = computed(() => Object.values(sourceStates.value).includes('error'))
const sourceMessage = (source: Source) =>
  ({ pending: '加载中', ready: '', error: '加载失败，请重试', denied: '无权限' })[
    sourceStates.value[source]
  ]
const display = (source: Source, value: string) =>
  sourceStates.value[source] === 'ready'
    ? value
    : sourceStates.value[source] === 'error'
      ? '加载失败'
      : sourceStates.value[source] === 'denied'
        ? '无权限'
        : '—'
const statCards = computed(() =>
  [
    {
      label: '历史钱包总可用点数',
      source: 'wallets' as Source,
      value: totalAvailablePoints.value.toLocaleString(),
      icon: Coin,
      to: '/platform-ops/wallets'
    },
    {
      label: '历史组织钱包数',
      source: 'wallets' as Source,
      value: String(walletCount.value),
      icon: Wallet,
      to: '/platform-ops/wallets'
    },
    {
      label: '充值订单总数',
      source: 'orders' as Source,
      value: String(orderTotal.value),
      icon: Document,
      to: '/platform-ops/orders'
    },
    {
      label: '充值套餐数',
      source: 'plans' as Source,
      value: String(planTotal.value),
      icon: Document,
      to: '/platform-ops/plans'
    },
    {
      label: '支付异常记录',
      source: 'anomalies' as Source,
      value: anomalyTotal.value === 0 ? '无' : String(anomalyTotal.value),
      icon: Warning,
      to: '/platform-ops/anomalies'
    },
    {
      label: '生效设计定价',
      source: 'prices' as Source,
      value: String(priceTotal.value),
      icon: PriceTag,
      to: '/platform-ops/design-prices'
    },
    {
      label: '调用成本记录',
      source: 'costs' as Source,
      value: String(costTotal.value),
      icon: DataLine,
      to: '/platform-ops/design-costs'
    },
    {
      label: '配置审计条数',
      source: 'audit' as Source,
      value: String(auditTotal.value),
      icon: DocumentChecked,
      to: '/platform-ops/audit'
    }
  ].map((card) => ({
    ...card,
    icon: markRaw(card.icon),
    value: display(card.source, card.value),
    to: sourceStates.value[card.source] === 'denied' ? '' : card.to,
    alert:
      card.source === 'anomalies' &&
      sourceStates.value.anomalies === 'ready' &&
      anomalyTotal.value > 0
  }))
)

const quickEntries = computed(() =>
  [
    { path: '/platform-pricing', title: '定价管理', permission: 'platform:org-point:price:query' },
    { path: '/platform-org/onboard', title: '组织开通', permission: 'platform:org:onboard' },
    {
      path: '/platform-org-points',
      title: '公司积分池',
      permission: 'platform:org-point:query'
    },
    { path: '/platform-ops/wallets', title: '历史组织钱包', permission: permissions.wallets },
    { path: '/platform-ops/plans', title: '历史充值套餐', permission: permissions.plans },
    { path: '/platform-ops/orders', title: '历史充值订单', permission: permissions.orders },
    { path: '/platform-ops/ledger', title: '历史钱包流水', permission: permissions.ledger },
    { path: '/platform-ops/anomalies', title: '支付异常', permission: permissions.anomalies },
    { path: '/platform-ops/design-prices', title: '历史设计定价', permission: permissions.prices },
    { path: '/platform-ops/design-costs', title: '调用成本', permission: permissions.costs },
    { path: '/platform-ops/audit', title: '配置审计', permission: permissions.audit }
  ].filter((entry) => checkPermi([entry.permission]))
)

const BRAND = '#714320'
const BRAND_LIGHT = '#a67b4f'

// 各组织钱包可用点数条形图
const walletChartOptions = computed<EChartsOption | null>(() => {
  if (!wallets.value.length) return null
  const rows = [...wallets.value]
    .sort((a, b) => Number(b.availablePoints) - Number(a.availablePoints))
    .slice(0, 10)
  return {
    grid: { left: 8, right: 28, top: 12, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0ece6' } } },
    yAxis: {
      type: 'category',
      data: rows.map((w) => `组织 ${w.orgId}`),
      axisLine: { lineStyle: { color: '#e5e0d8' } }
    },
    series: [
      {
        type: 'bar',
        data: rows.map((w) => Number(w.availablePoints)),
        barMaxWidth: 18,
        itemStyle: { color: BRAND, borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', color: '#8a7d72' }
      }
    ]
  }
})

const toDay = (raw: unknown): string => {
  if (raw === null || raw === undefined || raw === '') return ''
  const ms = Number(raw)
  const date = Number.isFinite(ms) && ms > 0 ? new Date(ms) : new Date(String(raw))
  if (Number.isNaN(date.getTime())) return ''
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

// 积分流水按日趋势
const ledgerChartOptions = computed<EChartsOption | null>(() => {
  if (!ledger.value.length) return null
  const byDay = new Map<string, number>()
  for (const row of ledger.value) {
    const day = toDay(row.createTime)
    if (!day) continue
    byDay.set(day, (byDay.get(day) || 0) + (Number(row.delta) || 0))
  }
  const days = [...byDay.keys()].sort()
  return {
    grid: { left: 8, right: 16, top: 20, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: days, boundaryGap: false },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0ece6' } } },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: days.map((d) => byDay.get(d) || 0),
        lineStyle: { color: BRAND, width: 2 },
        itemStyle: { color: BRAND },
        areaStyle: { color: 'rgba(113, 67, 32, 0.08)' }
      }
    ]
  }
})

// 流水类型分布（环形）
const bizTypeChartOptions = computed<EChartsOption | null>(() => {
  if (!ledger.value.length) return null
  const byType = new Map<string, number>()
  for (const row of ledger.value) {
    const key = String(row.bizType || '未知')
    byType.set(key, (byType.get(key) || 0) + 1)
  }
  const data = [...byType.entries()].map(([name, value]) => ({ name, value }))
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, icon: 'circle' },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '45%'],
        data,
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        color: [BRAND, BRAND_LIGHT, '#d4b48c', '#8a7d72', '#c9b7a2']
      }
    ]
  }
})

// 定价点数对比（提示词点数）
const priceChartOptions = computed<EChartsOption | null>(() => {
  if (!prices.value.length) return null
  const rows = prices.value.slice(0, 10)
  return {
    grid: { left: 8, right: 24, top: 12, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0ece6' } } },
    yAxis: {
      type: 'category',
      data: rows.map((p) => p.operation),
      axisLine: { lineStyle: { color: '#e5e0d8' } }
    },
    series: [
      {
        type: 'bar',
        name: '提示词点数',
        data: rows.map((p) => Number(p.promptPoints) || 0),
        barMaxWidth: 14,
        itemStyle: { color: BRAND, borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', color: '#8a7d72' }
      },
      {
        type: 'bar',
        name: '图像点数',
        data: rows.map((p) => Number(p.imagePoints) || 0),
        barMaxWidth: 14,
        itemStyle: { color: BRAND_LIGHT, borderRadius: [0, 3, 3, 0] }
      }
    ]
  }
})

let loadGeneration = 0
async function load() {
  const generation = ++loadGeneration
  loading.value = true
  async function fetchSource<T>(
    source: Source,
    request: () => Promise<T>,
    apply: (result: T) => void
  ) {
    if (!checkPermi([permissions[source]])) {
      sourceStates.value[source] = 'denied'
      return
    }
    sourceStates.value[source] = 'pending'
    try {
      const result = await request()
      if (generation !== loadGeneration) return
      apply(result)
      sourceStates.value[source] = 'ready'
    } catch {
      if (generation === loadGeneration) sourceStates.value[source] = 'error'
    }
  }
  await Promise.all([
    fetchSource('wallets', getWalletSummary, (result) => {
      wallets.value = result.topWallets
      walletCount.value = result.walletCount
      totalAvailablePoints.value = result.totalAvailablePoints
    }),
    fetchSource(
      'ledger',
      () => getLedger({ pageNo: 1, pageSize: 100 }),
      (result) => {
        ledger.value = result.list
      }
    ),
    fetchSource(
      'prices',
      () => getActiveDesignPrices({ pageNo: 1, pageSize: 10 }),
      (result) => {
        prices.value = result.list
        priceTotal.value = result.total
      }
    ),
    fetchSource(
      'plans',
      () => getRechargePlans({ pageNo: 1, pageSize: 1 }),
      (result) => {
        planTotal.value = result.total
      }
    ),
    fetchSource(
      'orders',
      () => getRechargeOrders({ pageNo: 1, pageSize: 1 }),
      (result) => {
        orderTotal.value = result.total
      }
    ),
    fetchSource(
      'anomalies',
      () => getAnomalies({ pageNo: 1, pageSize: 1 }),
      (result) => {
        anomalyTotal.value = result.total
      }
    ),
    fetchSource(
      'costs',
      () => getDesignCosts({ pageNo: 1, pageSize: 1 }),
      (result) => {
        costTotal.value = result.total
      }
    ),
    fetchSource(
      'audit',
      () => getAudit({ pageNo: 1, pageSize: 1 }),
      (result) => {
        auditTotal.value = result.total
      }
    )
  ])
  if (generation === loadGeneration) loading.value = false
}
onMounted(load)
</script>

<style scoped>
.organization-card {
  margin-bottom: 20px;
}
.organization-card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.organization-card-heading h2 {
  margin: 0;
  font-size: 18px;
}
.organization-card-heading h2 span {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 15px;
  font-weight: 400;
}
.legacy-statistics {
  margin-bottom: 20px;
}
.zs-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.btn-icon {
  margin-right: 4px;
}

.zs-stat-card {
  cursor: pointer;
}

.zs-stat-card--alert {
  border-color: #e8c8c0;
}

.zs-stat-card--alert .zs-stat-icon {
  color: #d0342c;
  background: #fceceb;
}

.chart-card {
  padding: 14px 16px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 12px;
}

.chart-card__title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #282728;
}

.quick-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.quick-row__title {
  font-size: 13px;
  font-weight: 600;
  color: #282728;
}

.quick-tag {
  cursor: pointer;
}

@media (width <= 768px) {
  .zs-page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
}
</style>
