<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">钱包对账</h1>
        <div class="zs-page-subtitle">
          当前工作身份所属组织的点数账户与流水（只读核对；充值/消费在小程序办理）。
        </div>
      </div>
      <el-button :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-alert
      v-if="!canRead"
      title="当前工作身份无钱包读取权限（需 platform:wallet:read；财务岗默认未授予）"
      type="warning"
      show-icon
    />

    <template v-else>
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon class="mb-16px" />

      <div v-loading="loading" class="wallet-cards">
        <div class="zs-table-card wallet-card">
          <div class="wallet-label">可用点数</div>
          <div class="wallet-value">{{ formatPoints(account?.availablePoints) }}</div>
        </div>
        <div class="zs-table-card wallet-card">
          <div class="wallet-label">预留点数</div>
          <div class="wallet-value">{{ formatPoints(account?.reservedPoints) }}</div>
        </div>
        <div class="zs-table-card wallet-card">
          <div class="wallet-label">账户状态</div>
          <div class="wallet-value wallet-status">
            <el-tag :type="walletStatusTag(account?.status)">
              {{ walletStatusLabel(account?.status) }}
            </el-tag>
          </div>
        </div>
        <div class="zs-table-card wallet-card">
          <div class="wallet-label">账户版本</div>
          <div class="wallet-value">{{ account ? account.version : '-' }}</div>
        </div>
      </div>

      <div class="zs-table-card dash-section">
        <div class="section-head">
          <span class="section-title">点数流水</span>
          <span class="hint">按创建时间倒序；余额列为该笔后的快照</span>
        </div>
        <el-table v-loading="loading" :data="ledger.list" empty-text="暂无流水">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tooltip
                v-if="ledgerTypeLabel(row.type) !== row.type"
                :content="row.type"
                placement="top"
              >
                <span>{{ ledgerTypeLabel(row.type) }}</span>
              </el-tooltip>
              <span v-else>{{ ledgerTypeLabel(row.type) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="增减" width="120" align="right">
            <template #default="{ row }">
              <span :class="row.delta > 0 ? 'delta-up' : 'delta-down'">{{
                signedPoints(row.delta)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可用余额" width="130" align="right">
            <template #default="{ row }">{{ formatPoints(row.availableAfter) }}</template>
          </el-table-column>
          <el-table-column label="预留余额" width="130" align="right">
            <template #default="{ row }">{{ formatPoints(row.reservedAfter) }}</template>
          </el-table-column>
          <el-table-column prop="bizType" label="业务类型" width="130" show-overflow-tooltip />
          <el-table-column prop="bizId" label="业务单号" min-width="150" show-overflow-tooltip />
          <el-table-column prop="reason" label="说明" min-width="160" show-overflow-tooltip />
        </el-table>
        <el-pagination
          v-if="ledger.total > pageSize"
          class="wallet-pager"
          background
          layout="total, prev, pager, next"
          :total="ledger.total"
          :page-size="pageSize"
          :current-page="pageNo"
          :disabled="loading"
          @current-change="onPageChange"
        />
      </div>

      <div class="hint wallet-footnote">
        口径：点数为组织维度（当前岗位 orgId），与设计端客户数据、加盟商财务账表相互独立。
        账户与流水的写入方为充值/设计消费等业务动作，本页不提供任何写操作。
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import * as api from '@/api/platform/wallet'
import type { WalletAccount, WalletLedgerItem } from '@/api/platform/wallet'
import {
  formatPoints,
  ledgerTypeLabel,
  normalizeLedgerPage,
  signedPoints,
  walletStatusLabel,
  walletStatusTag
} from './model'

defineOptions({ name: 'FranchiseWallet' })

const identity = usePlatformIdentityStore()
const canRead = computed(() => identity.can('platform:wallet:read'))

const loading = ref(false)
const loadError = ref('')
const account = ref<WalletAccount | null>(null)
const ledger = ref<{ list: WalletLedgerItem[]; total: number }>({ list: [], total: 0 })
const pageNo = ref(1)
const pageSize = 20
/** 请求代次：分页/刷新乱序时只允许最新一次响应写回。 */
let loadGeneration = 0

const formatTime = (value?: string) => {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`
}

const load = async (targetPage = pageNo.value): Promise<boolean> => {
  if (!canRead.value) return false
  const generation = ++loadGeneration
  loading.value = true
  loadError.value = ''
  try {
    const [acc, page] = await Promise.all([
      api.getWalletAccount(),
      api.getWalletLedger(normalizeLedgerPage(targetPage, pageSize))
    ])
    if (generation !== loadGeneration) return false
    account.value = acc
    ledger.value = { list: page?.list || [], total: page?.total || 0 }
    // 成功后才切页码：失败时页码与表格仍停在上一页，避免"第 3 页显示第 2 页数据"。
    pageNo.value = normalizeLedgerPage(targetPage, pageSize).pageNo
    return true
  } catch {
    if (generation !== loadGeneration) return false
    loadError.value = '钱包数据加载失败，请检查网络或当前工作身份后重试'
    return false
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

const reload = async () => {
  await load(1)
}

const onPageChange = async (page: number) => {
  await load(page)
}

onMounted(() => {
  if (canRead.value) void load()
})
</script>

<style scoped lang="scss">
.wallet-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.wallet-card {
  padding: 18px 20px;
}

.wallet-label {
  font-size: 13px;
  color: #8a7a6d;
}

.wallet-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 600;
  color: #4d301e;
}

.wallet-status {
  font-size: 16px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #4d301e;
}

.hint {
  font-size: 12px;
  color: #8a7a6d;
}

.delta-up {
  color: #2f7d4f;
}

.delta-down {
  color: #b3453a;
}

.wallet-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.wallet-footnote {
  margin-top: 16px;
}

@media (width <= 768px) {
  .wallet-value {
    font-size: 20px;
  }
}
</style>
