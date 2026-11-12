<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">总部运营工作台</h1>
        <div class="zs-page-subtitle">组织开通、员工支持与平台钱包运营</div>
      </div>
      <el-button type="primary" size="large" @click="router.push('/platform-org/onboard')">
        <el-icon class="onboard-icon"><Plus /></el-icon>
        开通新组织
      </el-button>
    </div>

    <!-- 数据概览 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card" :class="{ 'stat-card--alert': card.alert }" @click="card.to && router.push(card.to)">
          <div class="stat-card__icon"><el-icon :size="22"><component :is="card.icon" /></el-icon></div>
          <div class="stat-card__meta">
            <div class="stat-card__label">{{ card.label }}</div>
            <div class="stat-card__value" v-loading="loading">{{ card.value }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 平台运营入口 -->
    <div class="section-title">平台运营</div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="entry in operationEntries" :key="entry.path">
        <div class="entry-card" @click="router.push(entry.path)">
          <div class="entry-card__icon"><el-icon :size="24"><component :is="entry.icon" /></el-icon></div>
          <div class="entry-card__name">{{ entry.title }}</div>
          <div class="entry-card__desc">{{ entry.desc }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 组织管理入口 -->
    <div class="section-title">组织与账号</div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="entry in orgEntries" :key="entry.path">
        <div class="entry-card entry-card--muted" @click="router.push(entry.path)">
          <div class="entry-card__icon"><el-icon :size="24"><component :is="entry.icon" /></el-icon></div>
          <div class="entry-card__name">{{ entry.title }}</div>
          <div class="entry-card__desc">{{ entry.desc }}</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import {
  Wallet, Ticket, Document, Coin, WarningFilled, PriceTag, DataLine, DocumentChecked,
  User, UserFilled, OfficeBuilding, Plus
} from '@element-plus/icons-vue'
import {
  getWallets, getRechargeOrders, getAnomalies, getDesignPrices,
  type WalletRow
} from '@/api/platform/operations'

defineOptions({ name: 'PlatformOperationsHome' })
const router = useRouter()
const loading = ref(true)

const wallets = ref<WalletRow[]>([])
const orderTotal = ref(0)
const anomalyTotal = ref(0)
const priceTotal = ref(0)

const totalAvailablePoints = computed(() =>
  wallets.value.reduce((sum, w) => sum + (Number(w.availablePoints) || 0), 0)
)

const statCards = computed(() => [
  { label: '组织钱包总可用点数', value: totalAvailablePoints.value.toLocaleString(), icon: markRaw(Coin), to: '/platform-ops/wallets', alert: false },
  { label: '充值订单总数', value: String(orderTotal.value), icon: markRaw(Document), to: '/platform-ops/orders', alert: false },
  { label: '待核对支付异常', value: anomalyTotal.value === 0 ? '无异常' : String(anomalyTotal.value), icon: markRaw(WarningFilled), to: '/platform-ops/anomalies', alert: anomalyTotal.value > 0 },
  { label: '生效设计定价', value: String(priceTotal.value), icon: markRaw(PriceTag), to: '/platform-ops/design-prices', alert: false }
])

const operationEntries = [
  { path: '/platform-ops/wallets', title: '组织钱包', desc: '各组织点数余额与预留', icon: markRaw(Wallet) },
  { path: '/platform-ops/plans', title: '充值套餐', desc: '套餐价格与赠送点数配置', icon: markRaw(Ticket) },
  { path: '/platform-ops/orders', title: '充值订单', desc: '订单状态与到账核销', icon: markRaw(Document) },
  { path: '/platform-ops/ledger', title: '积分流水', desc: '全平台点数变动明细', icon: markRaw(DataLine) },
  { path: '/platform-ops/anomalies', title: '支付异常核对', desc: '到账异常的核对与处理', icon: markRaw(WarningFilled) },
  { path: '/platform-ops/design-prices', title: '设计能力定价', desc: '调设计能力的扣费单价', icon: markRaw(PriceTag) },
  { path: '/platform-ops/design-costs', title: '设计调用成本', desc: '逐次调用的成本与结算', icon: markRaw(DataLine) },
  { path: '/platform-ops/audit', title: '配置审计', desc: '定价与套餐变更留痕', icon: markRaw(DocumentChecked) }
]

const orgEntries = [
  { path: '/platform-org/onboard', title: '组织开通', desc: '新加盟组织入驻与首管理员', icon: markRaw(OfficeBuilding) },
  { path: '/system/user', title: '用户管理', desc: '平台后台账号', icon: markRaw(User) },
  { path: '/system/role', title: '角色管理', desc: '后台角色与权限', icon: markRaw(UserFilled) },
  { path: '/system/dept', title: '部门管理', desc: '后台组织架构', icon: markRaw(OfficeBuilding) }
]

onMounted(async () => {
  const page = { pageNo: 1, pageSize: 100 }
  try {
    const [walletsRes, ordersRes, anomaliesRes, pricesRes] = await Promise.all([
      getWallets(page),
      getRechargeOrders({ pageNo: 1, pageSize: 1 }),
      getAnomalies(page),
      getDesignPrices({ pageNo: 1, pageSize: 1 })
    ])
    wallets.value = walletsRes.list || []
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
.onboard-icon {
  margin-right: 6px;
}
.stat-row {
  margin-bottom: 8px;
}
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 10px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}
.stat-card:hover {
  box-shadow: 0 6px 18px rgb(139 101 62 / 12%);
  transform: translateY(-2px);
}
.stat-card--alert {
  border-color: #f3d1c4;
  background: #fdf6f3;
}
.stat-card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  color: #8b5e3c;
  background: #f6efe8;
  border-radius: 10px;
}
.stat-card--alert .stat-card__icon {
  color: #d04a2a;
  background: #fbe9e2;
}
.stat-card__label {
  font-size: 13px;
  color: #8a7d72;
}
.stat-card__value {
  font-size: 22px;
  font-weight: 600;
  color: #4a3c30;
  line-height: 1.3;
}
.section-title {
  margin: 20px 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: #4a3c30;
}
.entry-card {
  padding: 20px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 10px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}
.entry-card:hover {
  box-shadow: 0 6px 18px rgb(139 101 62 / 14%);
  transform: translateY(-2px);
  border-color: #d8c3ac;
}
.entry-card--muted .entry-card__icon {
  color: #a08c76;
  background: #f4f1ee;
}
.entry-card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-bottom: 10px;
  color: #8b5e3c;
  background: #f6efe8;
  border-radius: 10px;
}
.entry-card__name {
  margin-bottom: 4px;
  font-size: 15px;
  font-weight: 600;
  color: #4a3c30;
}
.entry-card__desc {
  font-size: 12px;
  color: #9b8d80;
  line-height: 1.5;
}
</style>
