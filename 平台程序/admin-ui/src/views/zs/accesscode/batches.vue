<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">授权码批次</h1>
        <div class="zs-page-subtitle"
          >批次级视图：数量、已兑换、交付方式与用途；点「查看授权码」按批次筛码</div
        >
      </div>
      <el-button class="zs-btn-primary" @click="openCompanySummary">
        <Icon icon="ep:data-analysis" class="mr-4px" /> 按公司汇总
      </el-button>
      <el-button @click="$router.push('/zs/access-code/batch')">
        <Icon icon="ep:plus" class="mr-4px" /> 批量生成授权码
      </el-button>
    </div>

    <div class="zs-table-card">
      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="批次加载失败，请重试"
        style="margin-bottom: 12px"
        ><el-button @click="load">重新加载</el-button></el-alert
      >
      <el-alert
        v-if="!loading && !loadError && !list.length"
        type="info"
        :closable="false"
        title="还没有授权码批次；点击右上角批量生成创建第一批"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="批次号" prop="id" width="190" />
        <el-table-column label="数量" prop="quantity" width="80" />
        <el-table-column label="已兑换" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.redeemedCount >= row.quantity ? 'zs-tag--green' : 'zs-tag--blue'"
              >{{ row.redeemedCount }}/{{ row.quantity }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="交付方式" width="100">
          <template #default="{ row }">{{
            row.deliveryMode === 'TICKET' ? '加密票据' : '明文即时'
          }}</template>
        </el-table-column>
        <el-table-column label="明文暴露数" prop="exposedCount" width="95" />
        <el-table-column
          label="用途备注"
          prop="purposeNote"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column label="发行人" prop="issuedBy" width="110" />
        <el-table-column label="有效期至" width="120">
          <template #default="{ row }">{{
            row.expiresAt ? fmtDate(row.expiresAt) : '长期'
          }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <span
              class="zs-link"
              @click="$router.push({ path: '/zs/access-code', query: { batchId: row.id } })"
              >查看授权码 ›</span
            >
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-16px"
        layout="total, sizes, prev, pager, next"
        :total="total"
        v-model:page-size="query.pageSize"
        @size-change="search"
        v-model:current-page="query.pageNo"
        @current-change="load"
      />
    </div>

    <!-- 按公司汇总：公司 → 码数/账号数/有效授权/点数消耗/最近兑换 -->
    <el-dialog v-model="summary.visible" title="按公司汇总（加盟商维度）" width="720px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="码数和账号按当前登记公司统计；业务净消耗按扣点时的绑定历史归属，扣除任务退点，不含人工调点和充值退款。归属冲突或缺失计入「归属待核实」。"
        style="margin-bottom: 12px"
      />
      <el-table :data="summary.rows" v-loading="summary.loading" stripe max-height="480">
        <el-table-column label="公司" prop="company" min-width="220" show-overflow-tooltip />
        <el-table-column label="兑换码数" prop="codeCount" width="100" />
        <el-table-column label="账号数" prop="accountCount" width="90" />
        <el-table-column label="有效授权" prop="activeGrantCount" width="100" />
        <el-table-column label="业务净消耗" prop="netPointsConsumed" width="110" />
        <el-table-column label="最近兑换" width="160">
          <template #default="{ row }">{{ row.lastRedeemedAt || '—' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="summary.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtDate, fmtTime } from '@/utils/zsFormat'
import { useMessage } from '@/hooks/web/useMessage'

defineOptions({ name: 'ZsAccessCodeBatches' })

const message = useMessage()
const loading = ref(false)
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20 })

// ---- 按公司汇总 ----
const summary = reactive({ visible: false, loading: false, rows: [] as any[] })
const openCompanySummary = async () => {
  summary.visible = true
  summary.loading = true
  try {
    summary.rows = (await ZsApi.getAccessCodeCompanySummary()) || []
  } catch (e: any) {
    summary.rows = []
    message.error(e?.msg || '汇总加载失败，请重试')
  } finally {
    summary.loading = false
  }
}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getBatchPage({ ...query })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
