<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">总部运营工作台</h1>
        <div class="zs-page-subtitle">组织开通、员工支持与平台钱包运营</div>
      </div>
      <el-button type="primary" class="zs-btn-primary" @click="router.push('/platform-org/onboard')">
        <el-icon class="btn-icon"><Plus /></el-icon>
        开通新组织
      </el-button>
    </div>

    <!-- 关键数字（8卡） -->
    <div class="zs-stat-cards">
      <div
        v-for="card in statCards"
        :key="card.label"
        class="zs-stat-card"
        :class="{ 'zs-stat-card--alert': card.alert }"
        @click="card.to && router.push(card.to)"
      >
        <div class="zs-stat-icon"><el-icon><component :is="card.icon" /></el-icon></div>
        <div>
          <div class="zs-stat-label">{{ card.label }}</div>
          <div class="zs-stat-value" v-loading="loading">{{ card.value }}</div>
        </div>
      </div>
    </div>

    <!-- 图表区（2×2） -->
    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">各组织钱包可用点数</div>
          <Echart v-if="walletChartOptions" :options="walletChartOptions" :height="300" />
          <el-empty v-else-if="!loading" description="暂无钱包数据" :image-size="60" />
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">积分流水趋势（近30日）</div>
          <Echart v-if="ledgerChartOptions" :options="ledgerChartOptions" :height="300" />
          <el-empty v-else-if="!loading" description="暂无流水数据" :image-size="60" />
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">流水类型分布</div>
          <Echart v-if="bizTypeChartOptions" :options="bizTypeChartOptions" :height="300" />
          <el-empty v-else-if="!loading" description="暂无流水数据" :image-size="60" />
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">设计能力定价点数</div>
          <Echart v-if="priceChartOptions" :options="priceChartOptions" :height="300" />
          <el-empty v-else-if="!loading" description="暂无定价数据" :image-size="60" />
        </div>
      </el-col>
    </el-row>

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
import {
  Coin, Wallet, Document, Warning, PriceTag, DataLine, DocumentChecked, Plus
} from '@element-plus/icons-vue'
import Echart from '@/components/Echart/src/Echart.vue'
import type { EChartsOption } from 'echarts'
import {
  getWallets, getRechargeOrders, getAnomalies, getDesignPrices, getLedger,
  getDesignCosts, getAudit,
  type WalletRow, type LedgerRow, type DesignPriceRow
} from '@/api/platform/operations'

defineOptions({ name: 'PlatformOperationsHome' })
const router = useRouter()
const loading = ref(true)

const wallets = ref<WalletRow[]>([])
const ledger = ref<LedgerRow[]>([])
const prices = ref<DesignPriceRow[]>([])
const orderTotal = ref(0)
const anomalyTotal = ref(0)
const priceTotal = ref(0)
const costTotal = ref(0)
const auditTotal = ref(0)

const totalAvailablePoints = computed(() =>
  wallets.value.reduce((sum, w) => sum + (Number(w.availablePoints) || 0), 0)
)

const statCards = computed(() => [
  { label: '钱包总可用点数', value: totalAvailablePoints.value.toLocaleString(), icon: markRaw(Coin), to: '/platform-ops/wallets', alert: false },
  { label: '组织钱包数', value: String(wallets.value.length), icon: markRaw(Wallet), to: '/platform-ops/wallets', alert: false },
  { label: '充值订单总数', value: String(orderTotal.value), icon: markRaw(Document), to: '/platform-ops/orders', alert: false },
  { label: '充值套餐数', value: String(0), icon: markRaw(Document), to: '/platform-ops/plans', alert: false },
  { label: '待核对支付异常', value: anomalyTotal.value === 0 ? '无' : String(anomalyTotal.value), icon: markRaw(Warning), to: '/platform-ops/anomalies', alert: anomalyTotal.value > 0 },
  { label: '生效设计定价', value: String(priceTotal.value), icon: markRaw(PriceTag), to: '/platform-ops/design-prices', alert: false },
  { label: '调用成本记录', value: String(costTotal.value), icon: markRaw(DataLine), to: '/platform-ops/design-costs', alert: false },
  { label: '配置审计条数', value: String(auditTotal.value), icon: markRaw(DocumentChecked), to: '/platform-ops/audit', alert: false }
])

const quickEntries = [
  { path: '/platform-org/onboard', title: '组织开通' },
  { path: '/platform-ops/wallets', title: '组织钱包' },
  { path: '/platform-ops/plans', title: '充值套餐' },
  { path: '/platform-ops/orders', title: '充值订单' },
  { path: '/platform-ops/ledger', title: '积分流水' },
  { path: '/platform-ops/anomalies', title: '支付异常' },
  { path: '/platform-ops/design-prices', title: '设计定价' },
  { path: '/platform-ops/design-costs', title: '调用成本' },
  { path: '/platform-ops/audit', title: '配置审计' }
]

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
  const days = [...byDay.keys()].sort().slice(-30)
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

onMounted(async () => {
  try {
    const [walletsRes, ledgerRes, pricesRes, ordersRes, anomaliesRes, costsRes, auditRes] = await Promise.all([
      getWallets({ pageNo: 1, pageSize: 100 }),
      getLedger({ pageNo: 1, pageSize: 100 }),
      getDesignPrices({ pageNo: 1, pageSize: 100 }),
      getRechargeOrders({ pageNo: 1, pageSize: 1 }),
      getAnomalies({ pageNo: 1, pageSize: 1 }),
      getDesignCosts({ pageNo: 1, pageSize: 1 }),
      getAudit({ pageNo: 1, pageSize: 1 })
    ])
    wallets.value = walletsRes.list || []
    ledger.value = ledgerRes.list || []
    prices.value = pricesRes.list || []
    orderTotal.value = ordersRes.total || 0
    anomalyTotal.value = anomaliesRes.total || 0
    priceTotal.value = pricesRes.total || 0
    costTotal.value = costsRes.total || 0
    auditTotal.value = auditRes.total || 0
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
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
@media (max-width: 768px) {
  .zs-page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
}
</style>
