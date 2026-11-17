<template>
  <div class="zs-table-card" style="margin: 20px 0; padding: 20px">
    <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 16px"
      ><strong>项目账本与员工报销</strong
      ><el-select v-model="scope" placeholder="选择账本" @change="loadLedger"
        ><el-option v-for="b in books" :key="b.scope" :label="b.name" :value="b.scope" /></el-select
      ><el-button @click="load">刷新</el-button></div
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="ledger"
      ><div style="display: flex; gap: 32px; margin: 16px 0"
        ><span>累计实收 ¥{{ yuan(ledger.summary.received_cents) }}</span
        ><span>公司实付 ¥{{ yuan(ledger.summary.paid_cents) }}</span
        ><span
          >收支净额 ¥{{
            yuan(
              Number(ledger.summary.received_cents) +
                Number(ledger.summary.expense_refunded_cents) -
                Number(ledger.summary.paid_cents) -
                Number(ledger.summary.refunded_cents)
            )
          }}</span
        ></div
      >
      <el-table :data="ledger.entries" max-height="360"
        ><el-table-column prop="date" label="实际日期" /><el-table-column label="性质"
          ><template #default="{ row }">{{
            labels[row.type] || row.type
          }}</template></el-table-column
        ><el-table-column prop="name" label="事项" /><el-table-column
          prop="category"
          label="分类"
        /><el-table-column label="金额（元）"
          ><template #default="{ row }">{{ yuan(row.amount_cents) }}</template></el-table-column
        ></el-table
      >
      <el-alert
        v-if="ledger.truncated"
        title="展示最近500条；完整原始收付款可使用原账表筛选导出。"
        type="info"
        :closable="false"
      />
    </template>
    <h3>员工垫付与报销</h3
    ><el-table :data="reimbursements"
      ><el-table-column prop="payer_name" label="垫付人" /><el-table-column
        prop="pay_date"
        label="垫付日期"
      /><el-table-column label="垫付净额（元）"
        ><template #default="{ row }">{{ yuan(row.eligible_cents) }}</template></el-table-column
      ><el-table-column label="公司已报销（元）"
        ><template #default="{ row }">{{ yuan(row.reimbursed_cents) }}</template></el-table-column
      ><el-table-column label="往来余额（元）"
        ><template #default="{ row }">{{
          yuan(Number(row.eligible_cents) - Number(row.reimbursed_cents)+Number(row.recovered_cents||0))
        }}</template></el-table-column
      ></el-table
    >
    <p
      >个人垫付与公司现金分别展示；往来余额小于0表示员工应归还公司。登记及报销沿小程序原事项办理。</p
    >
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import request from '@/config/axios'
interface Book {
  scope: string
  name: string
}
interface Ledger {
  summary: Record<string, number>
  entries: Record<string, unknown>[]
  truncated: boolean
}
const books = ref<Book[]>([]),
  scope = ref('company'),
  ledger = ref<Ledger>(),
  reimbursements = ref<Record<string, unknown>[]>([]),
  error = ref('')
const labels: Record<string, string> = {
  RECEIPT: '收款',
  PAYMENT: '公司付款',
  ADVANCE: '员工垫付',
  PAYABLE: '应付依据',
  REFUND: '客户退款',
  EXPENSE_REFUND: '支出退回'
}
const yuan = (v: unknown) => (Number(v || 0) / 100).toFixed(2)
let generation = 0
async function loadLedger() {
  const seq = ++generation
  try {
    const result = await request.get({
      url: '/franchise/finance/workbench/ledger',
      params: { scope: scope.value }
    })
    if (seq === generation) ledger.value = result
  } catch (e) {
    if (seq === generation) error.value = e instanceof Error ? e.message : '账本加载失败'
  }
}
async function load() {
  error.value = ''
  try {
    const result = await Promise.all([
      request.get({ url: '/franchise/finance/workbench/books' }),
      request.get({ url: '/franchise/finance/workbench/reimbursements' })
    ])
    books.value = result[0]
    reimbursements.value = result[1]
    await loadLedger()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '财务数据加载失败'
  }
}
onMounted(load)
</script>
