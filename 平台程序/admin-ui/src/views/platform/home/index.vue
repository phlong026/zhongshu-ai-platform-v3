<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">总部运营工作台</h1>
        <div class="zs-page-subtitle">组织开通、员工支持与平台钱包运营</div>
      </div>
      <el-button type="primary" @click="router.push('/platform-org/onboard')">
        <el-icon class="btn-icon"><Plus /></el-icon>
        开通新组织
      </el-button>
    </div>

    <!-- 关键数字 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card" :class="{ 'stat-card--alert': card.alert }" @click="card.to && router.push(card.to)">
          <div class="stat-card__label">{{ card.label }}</div>
          <div class="stat-card__value" v-loading="loading">{{ card.value }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区 -->
    <el-row :gutter="12">
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">各组织钱包可用点数</div>
          <Echart v-if="walletChartOptions" :options="walletChartOptions" :height="280" />
          <el-empty v-else-if="!loading" description="暂无钱包数据" :image-size="60" />
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-card__title">积分流水趋势（近30日）</div>
          <Echart v-if="ledgerChartOptions" :options="ledgerChartOptions" :height="280" />
          <el-empty v-else-if="!loading" description="暂无流水数据" :image-size="60" />
        </div>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <div class="quick-row">
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
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import Echart from '@/components/Echart/src/Echart.vue'
import type { EChartsOption } from 'echarts'
import {
  getWallets, getRechargeOrders, getAnomalies, getDesignPrices, getLedger,
  type WalletRow, type LedgerRow
} from '@/api/platform/operations'

defineOptions({ name: 'PlatformOperationsHome' })
const router = useRouter()
const loading = ref(true)

const wallets = ref<WalletRow[]>([])
const ledger = ref<LedgerRow[]>([])
const orderTotal = ref(0)
const anomalyTotal = ref(0)
const priceTotal = ref(0)

const totalAvailablePoints = computed(() =>
  wallets.value.reduce((sum, w) => sum + (Number(w.availablePoints) || 0), 0)
)

const statCards = computed(() => [
  { label: '钱包总可用点数', value: totalAvailablePoints.value.toLocaleString(), to: '/platform-ops/wallets', alert: false },
  { label: '充值订单总数', value: String(orderTotal.value), to: '/platform-ops/orders', alert: false },
  { label: '待核对支付异常', value: anomalyTotal.value === 0 ? '无' : String(anomalyTotal.value), to: '/platform-ops/anomalies', alert: anomalyTotal.value > 0 },
  { label: '生效设计定价', value: String(priceTotal.value), to: '/platform-ops/design-prices', alert: false }
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

// 各组织钱包可用点数条形图
const walletChartOptions = computed<EChartsOption | null>(() => {
  if (!wallets.value.length) return null
  const rows = [...wallets.value]
    .sort((a, b) => Number(b.availablePoints) - Number(a.availablePoints))
    .slice(0, 10)
  return {
    grid: { left: 8, right: 24, top: 12, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f0f0' } } },
    yAxis: {
      type: 'category',
      data: rows.map((w) => `组织 ${w.orgId}`),
      axisLine: { lineStyle: { color: '#e5e7eb' } }
    },
    series: [
      {
        type: 'bar',
        data: rows.map((w) => Number(w.availablePoints)),
        barMaxWidth: 18,
        itemStyle: { color: '#2563eb', borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', color: '#646a73' }
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
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f0f0' } } },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: days.map((d) => byDay.get(d) || 0),
        lineStyle: { color: '#2563eb', width: 2 },
        itemStyle: { color: '#2563eb' },
        areaStyle: { color: 'rgba(37, 99, 235, 0.08)' }
      }
    ]
  }
})

onMounted(async () => {
  try {
    const [walletsRes, ledgerRes, ordersRes, anomaliesRes, pricesRes] = await Promise.all([
      getWallets({ pageNo: 1, pageSize: 100 }),
      getLedger({ pageNo: 1, pageSize: 100 }),
      getRechargeOrders({ pageNo: 1, pageSize: 1 }),
      getAnomalies({ pageNo: 1, pageSize: 1 }),
      getDesignPrices({ pageNo: 1, pageSize: 1 })
    ])
    wallets.value = walletsRes.list || []
    ledger.value = ledgerRes.list || []
    orderTotal.value = ordersRes.total || 0
    anomalyTotal.value = anomaliesRes.total || 0
    priceTotal.value = pricesRes.total || 0
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
.stat-row {
  margin-bottom: 12px;
}
.stat-card {
  padding: 12px 16px;
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  cursor: pointer;
}
.stat-card:hover {
  border-color: #b8cbf0;
}
.stat-card--alert {
  border-color: #f3c4bc;
  background: #fef5f3;
}
.stat-card__label {
  font-size: 12px;
  color: #646a73;
}
.stat-card__value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  color: #1f2329;
}
.chart-card {
  padding: 14px 16px;
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}
.chart-card__title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #1f2329;
}
.quick-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}
.quick-row__title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2329;
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
