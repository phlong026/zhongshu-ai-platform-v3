<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">财务核对</h1>
        <div class="zs-page-subtitle">
          月度对账、账表核对与导出（只读；登记/修订/撤销等业务写操作请在小程序完成）
        </div>
      </div>
      <div class="zs-actions">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          placeholder="选择月份"
          :clearable="false"
          @change="loadReconciliation"
        />
        <el-button
          class="zs-btn-primary"
          :loading="exporting === 'reconciliation'"
          @click="exportCsv('reconciliation')"
        >
          导出月度对账 CSV
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!canQuery"
      type="warning"
      :closable="false"
      title="当前工作身份无财务查询权限（franchise:fin:query），请联系组织管理员授权。"
      show-icon
    />

    <template v-else>
      <!-- 月度对账汇总卡（MonthReconciliation 8 分量，金额单位：分） -->
      <div class="recon-grid" v-loading="reconLoading">
        <div class="recon-cell">
          <div class="recon-label">对账月份</div>
          <div class="recon-value">{{ reconciliation?.month || '-' }}</div>
        </div>
        <div class="recon-cell">
          <div class="recon-label">毛实收（分）</div>
          <div class="recon-value">{{ fmt(reconciliation?.grossReceiptsCents) }}</div>
        </div>
        <div class="recon-cell">
          <div class="recon-label">客户退款（分）</div>
          <div class="recon-value">{{ fmt(reconciliation?.clientRefundsCents) }}</div>
        </div>
        <div class="recon-cell recon-cell--strong">
          <div class="recon-label">净实收（分）</div>
          <div class="recon-value">{{ fmt(reconciliation?.netReceivedCents) }}</div>
        </div>
        <div class="recon-cell">
          <div class="recon-label">异常待关联（分 / 笔）</div>
          <div class="recon-value">
            {{ fmt(reconciliation?.abnormalPendingCents) }} /
            {{ reconciliation?.abnormalPendingCount ?? 0 }}
          </div>
        </div>
        <div class="recon-cell">
          <div class="recon-label">待归月（分 / 笔）</div>
          <div class="recon-value">
            {{ fmt(reconciliation?.abnormalDatePendingCents) }} /
            {{ reconciliation?.abnormalDatePendingCount ?? 0 }}
          </div>
        </div>
      </div>

      <!-- 异常资金提示区（只读） -->
      <div class="zs-table-card section-card">
        <div class="section-head">
          <span class="section-title">异常资金</span>
          <div class="section-actions">
            <el-button v-if="canRegisterFund" class="zs-btn-primary" @click="openRegister"
              >登记异常资金</el-button
            >
            <el-switch
              v-model="abnormalPendingOnly"
              active-text="仅看日期待核（待归月）"
              @change="onAbnormalPendingOnlyChange"
            />
          </div>
        </div>
        <el-alert
          v-if="actionNotice"
          :title="actionNotice"
          :type="actionError ? 'error' : 'success'"
          show-icon
          class="mb-12px"
        />
        <el-form inline :model="abnormalQuery" class="filter-form">
          <el-form-item label="状态">
            <el-input
              v-model="abnormalQuery.status"
              placeholder="如 已核待关联"
              clearable
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item label="业务日期">
            <el-date-picker
              v-model="abnormalDateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              :disabled="abnormalPendingOnly"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 240px"
            />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input
              v-model="abnormalQuery.keyword"
              placeholder="对方/类别/交易号"
              clearable
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item>
            <el-button class="zs-btn-primary" @click="loadAbnormal(1)">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="abnormal.list" v-loading="abnormal.loading" size="small">
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="金额（分）" width="110">
            <template #default="{ row }">{{ fmt(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="业务日期" width="120">
            <template #default="{ row }">
              <el-tag v-if="!row.businessDate" size="small" type="warning">待归月</el-tag>
              <span v-else>{{ row.businessDate }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="counterparty" label="对方" min-width="140" show-overflow-tooltip />
          <el-table-column
            prop="stageOrCategory"
            label="期次/类别"
            min-width="110"
            show-overflow-tooltip
          />
          <el-table-column
            prop="transactionNo"
            label="交易号"
            min-width="140"
            show-overflow-tooltip
          />
          <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canRegisterFund && isPendingAbnormal(row)"
                link
                type="primary"
                @click="openLink(row)"
                >关联</el-button
              >
              <el-button
                v-if="canVoidFund && isVoidableAbnormal(row)"
                link
                type="danger"
                @click="openVoid(row)"
                >作废</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="abnormalQuery.pageNo"
          v-model:page-size="abnormalQuery.pageSize"
          :total="abnormal.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="pager"
          @change="loadAbnormal()"
        />
      </div>

      <!-- 四账表 -->
      <div class="zs-table-card section-card">
        <el-tabs v-model="activeTable" @tab-change="onTabChange">
          <el-tab-pane label="实收" name="receipts">
            <el-form inline class="filter-form">
              <el-form-item label="状态">
                <el-input
                  v-model="receiptQuery.status"
                  placeholder="如 已登记"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="收款日期">
                <el-date-picker
                  v-model="receiptDateRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  style="width: 240px"
                />
              </el-form-item>
              <el-form-item label="项目">
                <el-input
                  v-model="receiptQuery.projectId"
                  placeholder="项目ID"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="方式">
                <el-input
                  v-model="receiptQuery.method"
                  placeholder="method"
                  clearable
                  style="width: 110px"
                />
              </el-form-item>
              <el-form-item label="来源">
                <el-input
                  v-model="receiptQuery.sourceType"
                  placeholder="sourceType"
                  clearable
                  style="width: 120px"
                />
              </el-form-item>
              <el-form-item label="关键词">
                <el-input
                  v-model="receiptQuery.keyword"
                  placeholder="名称/单号"
                  clearable
                  style="width: 140px"
                />
              </el-form-item>
              <el-form-item>
                <el-button class="zs-btn-primary" @click="loadTable('receipts', 1)">查询</el-button>
                <el-button :loading="exporting === 'receipts'" @click="exportCsv('receipts')"
                  >导出 CSV</el-button
                >
              </el-form-item>
            </el-form>
            <el-table :data="receipts.list" v-loading="receipts.loading" size="small">
              <el-table-column prop="id" label="单号" width="150" show-overflow-tooltip />
              <el-table-column label="金额（分）" width="110">
                <template #default="{ row }">{{ fmt(row.amountCents) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column prop="sourceType" label="来源" width="110" show-overflow-tooltip />
              <el-table-column
                prop="sourceRef"
                label="来源单号"
                min-width="130"
                show-overflow-tooltip
              />
              <el-table-column prop="receivedDate" label="收款日期" width="110" />
              <el-table-column prop="projectId" label="项目" width="140" show-overflow-tooltip />
              <el-table-column prop="revisionVersion" label="修订" width="70" />
              <el-table-column
                prop="voidReason"
                label="作废原因"
                min-width="120"
                show-overflow-tooltip
              />
            </el-table>
            <el-pagination
              v-model:current-page="receiptQuery.pageNo"
              v-model:page-size="receiptQuery.pageSize"
              :total="receipts.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              class="pager"
              @change="loadTable('receipts')"
            />
          </el-tab-pane>

          <el-tab-pane label="付款" name="payments">
            <el-form inline class="filter-form">
              <el-form-item label="状态">
                <el-input
                  v-model="paymentQuery.status"
                  placeholder="如 已核实"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="付款日期">
                <el-date-picker
                  v-model="paymentDateRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  style="width: 240px"
                />
              </el-form-item>
              <el-form-item label="项目">
                <el-input
                  v-model="paymentQuery.projectId"
                  placeholder="项目ID"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="关键词">
                <el-input
                  v-model="paymentQuery.keyword"
                  placeholder="交易号/作废原因"
                  clearable
                  style="width: 150px"
                />
              </el-form-item>
              <el-form-item>
                <el-button class="zs-btn-primary" @click="loadTable('payments', 1)">查询</el-button>
                <el-button :loading="exporting === 'payments'" @click="exportCsv('payments')"
                  >导出 CSV</el-button
                >
              </el-form-item>
            </el-form>
            <el-table :data="payments.list" v-loading="payments.loading" size="small">
              <el-table-column prop="id" label="单号" width="150" show-overflow-tooltip />
              <el-table-column label="金额（分）" width="110">
                <template #default="{ row }">{{ fmt(row.amountCents) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column label="待关联（分）" width="110">
                <template #default="{ row }">{{ fmt(row.pendingLinkCents) }}</template>
              </el-table-column>
              <el-table-column prop="linkStatus" label="关联状态" width="100" />
              <el-table-column prop="payDate" label="付款日期" width="110" />
              <el-table-column prop="projectId" label="项目" width="140" show-overflow-tooltip />
              <el-table-column
                prop="transactionNo"
                label="交易号"
                min-width="130"
                show-overflow-tooltip
              />
              <el-table-column
                prop="voidReason"
                label="作废原因"
                min-width="120"
                show-overflow-tooltip
              />
            </el-table>
            <el-pagination
              v-model:current-page="paymentQuery.pageNo"
              v-model:page-size="paymentQuery.pageSize"
              :total="payments.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              class="pager"
              @change="loadTable('payments')"
            />
          </el-tab-pane>

          <el-tab-pane label="退款" name="refunds">
            <el-form inline class="filter-form">
              <el-form-item label="状态">
                <el-input
                  v-model="refundQuery.status"
                  placeholder="如 已核实"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="方向">
                <el-select
                  v-model="refundQuery.refundDirection"
                  placeholder="全部"
                  clearable
                  style="width: 120px"
                >
                  <el-option label="客户退款" value="client" />
                  <el-option label="费用回流" value="expense" />
                </el-select>
              </el-form-item>
              <el-form-item label="退款日期">
                <el-date-picker
                  v-model="refundDateRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  start-placeholder="开始"
                  end-placeholder="结束"
                  style="width: 240px"
                />
              </el-form-item>
              <el-form-item label="原收款单">
                <el-input
                  v-model="refundQuery.receiptId"
                  placeholder="收款单ID"
                  clearable
                  style="width: 150px"
                />
              </el-form-item>
              <el-form-item label="关键词">
                <el-input
                  v-model="refundQuery.keyword"
                  placeholder="原因/交易号"
                  clearable
                  style="width: 140px"
                />
              </el-form-item>
              <el-form-item>
                <el-button class="zs-btn-primary" @click="loadTable('refunds', 1)">查询</el-button>
                <el-button :loading="exporting === 'refunds'" @click="exportCsv('refunds')"
                  >导出 CSV</el-button
                >
              </el-form-item>
            </el-form>
            <el-table :data="refunds.list" v-loading="refunds.loading" size="small">
              <el-table-column prop="id" label="单号" width="150" show-overflow-tooltip />
              <el-table-column label="金额（分）" width="110">
                <template #default="{ row }">{{ fmt(row.amountCents) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column label="方向" width="100">
                <template #default="{ row }">
                  {{ row.refundDirection === 'client' ? '客户退款' : '费用回流' }}
                </template>
              </el-table-column>
              <el-table-column prop="date" label="退款日期" width="110" />
              <el-table-column
                prop="receiptId"
                label="原收款单"
                width="140"
                show-overflow-tooltip
              />
              <el-table-column label="更正链" min-width="180">
                <template #default="{ row }">
                  <el-tag v-if="row.replacesId" size="small" type="info"
                    >更正自 #{{ row.replacesId }}</el-tag
                  >
                  <el-tag v-if="row.replacedById" size="small" type="warning">
                    已由 #{{ row.replacedById }} 替代
                  </el-tag>
                  <span v-if="!row.replacesId && !row.replacedById" class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column
                prop="voidReason"
                label="作废原因"
                min-width="110"
                show-overflow-tooltip
              />
            </el-table>
            <el-pagination
              v-model:current-page="refundQuery.pageNo"
              v-model:page-size="refundQuery.pageSize"
              :total="refunds.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              class="pager"
              @change="loadTable('refunds')"
            />
          </el-tab-pane>

          <el-tab-pane label="应付" name="payables">
            <el-form inline class="filter-form">
              <el-form-item label="状态">
                <el-input
                  v-model="payableQuery.status"
                  placeholder="状态"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="项目">
                <el-input
                  v-model="payableQuery.projectId"
                  placeholder="项目ID"
                  clearable
                  style="width: 130px"
                />
              </el-form-item>
              <el-form-item label="关键词">
                <el-input
                  v-model="payableQuery.keyword"
                  placeholder="收款方/阶段/协议"
                  clearable
                  style="width: 160px"
                />
              </el-form-item>
              <el-form-item>
                <el-button class="zs-btn-primary" @click="loadTable('payables', 1)">查询</el-button>
                <el-button :loading="exporting === 'payables'" @click="exportCsv('payables')"
                  >导出 CSV</el-button
                >
              </el-form-item>
            </el-form>
            <el-table :data="payables.list" v-loading="payables.loading" size="small">
              <el-table-column prop="id" label="单号" width="150" show-overflow-tooltip />
              <el-table-column label="金额（分）" width="110">
                <template #default="{ row }">{{ fmt(row.totalCents) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column prop="stage" label="阶段" width="110" />
              <el-table-column
                prop="recipient"
                label="收款方"
                min-width="120"
                show-overflow-tooltip
              />
              <el-table-column prop="projectId" label="项目" width="140" show-overflow-tooltip />
              <el-table-column
                prop="agreementRef"
                label="协议依据"
                min-width="130"
                show-overflow-tooltip
              />
            </el-table>
            <el-pagination
              v-model:current-page="payableQuery.pageNo"
              v-model:page-size="payableQuery.pageSize"
              :total="payables.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              class="pager"
              @change="loadTable('payables')"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <el-dialog v-model="registerDialog" title="登记异常资金" width="560px">
      <el-form label-width="96px">
        <el-form-item label="资金方向" required>
          <el-select v-model="registerForm.fundDirection" placeholder="请选择">
            <el-option v-for="d in ABNORMAL_DIRECTIONS" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额（元）" required>
          <el-input v-model="registerForm.amountYuan" placeholder="如 1234.56" />
        </el-form-item>
        <el-form-item label="对方">
          <el-input v-model="registerForm.counterparty" maxlength="64" />
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker
            v-model="registerForm.businessDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="不填=日期待核（待归月）"
          />
        </el-form-item>
        <el-form-item label="方式">
          <el-select v-model="registerForm.method" clearable placeholder="可选">
            <el-option
              v-for="m in ABNORMAL_METHODS"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关联项目">
          <el-input v-model="registerForm.relatedProjectId" placeholder="项目编号（可选）" />
        </el-form-item>
        <el-form-item label="期次/类别">
          <el-input v-model="registerForm.stageOrCategory" maxlength="32" />
        </el-form-item>
        <el-form-item label="交易号">
          <el-input v-model="registerForm.transactionNo" maxlength="64" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="registerForm.reason" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="registerErrors.length"
        :title="registerErrors.join('；')"
        type="error"
        show-icon
      />
      <div class="hint">
        异常资金是原单缺失/超上限/日期待核时的真实资金留痕，登记后不影响有效账，需在核对后关联原单。
      </div>
      <template #footer>
        <el-button @click="registerDialog = false">取消</el-button>
        <el-button type="primary" :loading="registerBusy" @click="submitRegister"
          >确认登记</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="linkDialog" title="关联原单" width="460px">
      <p>
        将 <strong>{{ linkRow?.fundDirection }}</strong> 异常资金
        {{ fmt(linkRow?.amountCents) }} 分关联到：
      </p>
      <el-form label-width="96px">
        <el-form-item label="单据类型">
          <el-tag>{{ LINK_TYPE_LABEL[linkTypeFor(linkRow?.fundDirection)] || '-' }}</el-tag>
        </el-form-item>
        <el-form-item label="单据编号" required>
          <el-input v-model="linkIdText" placeholder="原单 id（正整数）" />
        </el-form-item>
      </el-form>
      <el-alert v-if="linkError" :title="linkError" type="error" show-icon />
      <template #footer>
        <el-button @click="linkDialog = false">取消</el-button>
        <el-button type="primary" :loading="linkBusy" @click="submitLink">确认关联</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="voidDialog" title="作废异常资金" width="480px">
      <p>
        作废 <strong>{{ voidRow?.fundDirection }}</strong> 异常资金
        {{ fmt(voidRow?.amountCents) }} 分（误录留痕，可追溯）。
      </p>
      <el-input
        v-model="voidReason"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        placeholder="请填写作废原因（必填）"
      />
      <el-alert v-if="voidError" :title="voidError" type="error" show-icon />
      <template #footer>
        <el-button @click="voidDialog = false">取消</el-button>
        <el-button type="danger" :loading="voidBusy" @click="submitVoid">确认作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import {
  getReceiptPage,
  getPaymentPage,
  getRefundPage,
  getPayablePage,
  getAbnormalFundPage,
  getMonthReconciliation,
  exportFinanceCsv,
  registerAbnormalFund,
  linkAbnormalFund,
  voidAbnormalFund,
  type ReceiptRow,
  type PaymentRow,
  type RefundRow,
  type PayableRow,
  type AbnormalFundRow,
  type MonthReconciliation,
  type AbnormalFundQuery,
  type ReceiptQuery,
  type PaymentQuery,
  type RefundQuery,
  type PayableQuery,
  type FinanceExportQuery
} from '@/api/platform/franchiseFinance'
import {
  ABNORMAL_DIRECTIONS,
  ABNORMAL_METHODS,
  LINK_TYPE_LABEL,
  buildRegisterCommand,
  canRegisterAbnormal,
  canVoidAbnormal,
  isPendingAbnormal,
  isVoidableAbnormal,
  linkTargetError,
  linkTypeFor,
  newAbnormalRegisterKey,
  voidReasonError,
  type AbnormalRegisterForm
} from './abnormalModel'

defineOptions({ name: 'FranchiseFinance' })

const identity = usePlatformIdentityStore()
const canQuery = computed(() => identity.can('franchise:fin:query'))

const month = ref(currentMonth())
const reconciliation = ref<MonthReconciliation | null>(null)
const reconLoading = ref(false)
const activeTable = ref('receipts')
const exporting = ref('')

const pageSizes = { pageNo: 1, pageSize: 10 }

const receiptQuery = reactive<ReceiptQuery>({ ...pageSizes })
const paymentQuery = reactive<PaymentQuery>({ ...pageSizes })
const refundQuery = reactive<RefundQuery>({ ...pageSizes })
const payableQuery = reactive<PayableQuery>({ ...pageSizes })
const abnormalQuery = reactive<AbnormalFundQuery>({ ...pageSizes })

const receiptDateRange = ref<[string, string] | null>(null)
const paymentDateRange = ref<[string, string] | null>(null)
const refundDateRange = ref<[string, string] | null>(null)
const abnormalDateRange = ref<[string, string] | null>(null)
const abnormalPendingOnly = ref(false)

const receipts = reactive({ list: [] as ReceiptRow[], total: 0, loading: false })
const payments = reactive({ list: [] as PaymentRow[], total: 0, loading: false })
const refunds = reactive({ list: [] as RefundRow[], total: 0, loading: false })
const payables = reactive({ list: [] as PayableRow[], total: 0, loading: false })
const abnormal = reactive({ list: [] as AbnormalFundRow[], total: 0, loading: false })

// ===== 异常资金写操作（P1 尾批①）=====
const currentRole = computed(() => identity.currentPost?.roleCode)
const canRegisterFund = computed(() => canRegisterAbnormal(currentRole.value))
const canVoidFund = computed(() => canVoidAbnormal(currentRole.value))
const actionNotice = ref('')
const actionError = ref(false)
const registerDialog = ref(false)
const registerBusy = ref(false)
const registerErrors = ref<string[]>([])
const registerKey = ref('')
const registerForm = reactive<AbnormalRegisterForm>({
  fundDirection: '',
  amountYuan: '',
  counterparty: '',
  relatedProjectId: '',
  stageOrCategory: '',
  businessDate: '',
  method: '',
  transactionNo: '',
  reason: ''
})
const linkDialog = ref(false)
const linkBusy = ref(false)
const linkRow = ref<AbnormalFundRow | null>(null)
const linkIdText = ref('')
const linkError = ref('')
const voidDialog = ref(false)
const voidBusy = ref(false)
const voidRow = ref<AbnormalFundRow | null>(null)
const voidReason = ref('')
const voidError = ref('')

function resetRegisterForm() {
  registerForm.fundDirection = ''
  registerForm.amountYuan = ''
  registerForm.counterparty = ''
  registerForm.relatedProjectId = ''
  registerForm.stageOrCategory = ''
  registerForm.businessDate = ''
  registerForm.method = ''
  registerForm.transactionNo = ''
  registerForm.reason = ''
  registerErrors.value = []
  registerKey.value = newAbnormalRegisterKey()
}

function openRegister() {
  resetRegisterForm()
  registerDialog.value = true
}

async function refreshAbnormalAndRecon() {
  await Promise.all([loadAbnormal(), loadReconciliation()])
}

async function submitRegister() {
  const built = buildRegisterCommand(registerForm)
  registerErrors.value = built.errors
  if (!built.ok || !built.payload) return
  registerBusy.value = true
  let written = false
  try {
    await registerAbnormalFund(built.payload, registerKey.value)
    written = true
    registerDialog.value = false
  } catch {
    // 失败也要刷新：并发场景下列表可能与服务端不一致；刷新结果决定提示是否叠加"刷新失败"。
    const refreshed = await refreshAbnormalAndRecon()
      .then(() => true)
      .catch(() => false)
    actionNotice.value = refreshed
      ? '登记失败，请稍后重试'
      : '登记失败，且列表刷新失败，请手动刷新后重试'
    actionError.value = true
  }
  try {
    if (written) {
      const refreshed = await refreshAbnormalAndRecon()
        .then(() => true)
        .catch(() => false)
      actionNotice.value = refreshed
        ? '异常资金已登记（不影响有效账，待关联原单）'
        : '异常资金已登记，列表刷新失败，请手动刷新'
      actionError.value = false
    }
  } finally {
    registerBusy.value = false
  }
}

function openLink(row: AbnormalFundRow) {
  linkRow.value = row
  linkIdText.value = ''
  linkError.value = ''
  linkDialog.value = true
}

async function submitLink() {
  if (!linkRow.value) return
  linkError.value = linkTargetError(linkIdText.value)
  if (linkError.value) return
  const linkedType = linkTypeFor(linkRow.value.fundDirection)
  if (!linkedType) {
    linkError.value = '该资金方向没有可关联的单据类型'
    return
  }
  if (!isPendingAbnormal(linkRow.value)) {
    linkError.value = '该异常资金已被处理，请刷新后重试'
    await refreshAbnormalAndRecon().catch(() => undefined)
    return
  }
  linkBusy.value = true
  let written = false
  try {
    // 19 位单据 id 必须原样作为字符串传给后端 Long，Number() 会丢精度。
    await linkAbnormalFund(linkRow.value.id, {
      linkedType,
      linkedId: linkIdText.value.trim()
    })
    written = true
    linkDialog.value = false
  } catch {
    linkError.value = ''
  }
  try {
    if (written) {
      const refreshed = await refreshAbnormalAndRecon()
        .then(() => true)
        .catch(() => false)
      actionNotice.value = refreshed
        ? '关联成功（仅标注，不影响有效账）'
        : '关联已成功，列表刷新失败，请手动刷新'
      actionError.value = false
    } else {
      const refreshed = await refreshAbnormalAndRecon()
        .then(() => true)
        .catch(() => false)
      actionNotice.value = refreshed
        ? '关联失败：原单不存在、已被占用或状态不符，列表已刷新'
        : '关联失败，且列表刷新失败，请手动刷新后重试'
      actionError.value = true
    }
  } finally {
    linkBusy.value = false
  }
}

function openVoid(row: AbnormalFundRow) {
  voidRow.value = row
  voidReason.value = ''
  voidError.value = ''
  voidDialog.value = true
}

async function submitVoid() {
  if (!voidRow.value) return
  voidError.value = voidReasonError(voidReason.value)
  if (voidError.value) return
  if (!isVoidableAbnormal(voidRow.value)) {
    voidError.value = '该异常资金已作废，请刷新后重试'
    await refreshAbnormalAndRecon().catch(() => undefined)
    return
  }
  voidBusy.value = true
  let written = false
  try {
    await voidAbnormalFund(voidRow.value.id, voidReason.value.trim())
    written = true
    voidDialog.value = false
  } catch {
    voidError.value = ''
  }
  try {
    if (written) {
      const refreshed = await refreshAbnormalAndRecon()
        .then(() => true)
        .catch(() => false)
      actionNotice.value = refreshed
        ? '异常资金已作废（留痕可追溯）'
        : '异常资金已作废，列表刷新失败，请手动刷新'
      actionError.value = false
    } else {
      const refreshed = await refreshAbnormalAndRecon()
        .then(() => true)
        .catch(() => false)
      actionNotice.value = refreshed
        ? '作废失败：该记录可能已被处理，列表已刷新'
        : '作废失败，且列表刷新失败，请手动刷新后重试'
      actionError.value = true
    }
  } finally {
    voidBusy.value = false
  }
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}
function fmt(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : String(value)
}
function applyRange(
  query: { receivedDateFrom?: string; receivedDateTo?: string } | any,
  range: [string, string] | null
) {
  query.receivedDateFrom = range?.[0]
  query.receivedDateTo = range?.[1]
}
function applyPaymentRange(query: PaymentQuery, range: [string, string] | null) {
  query.payDateFrom = range?.[0]
  query.payDateTo = range?.[1]
}
function applyRefundRange(query: RefundQuery, range: [string, string] | null) {
  query.dateFrom = range?.[0]
  query.dateTo = range?.[1]
}
function applyAbnormalRange(query: AbnormalFundQuery, range: [string, string] | null) {
  // L3：待归月模式下不传区间（API 层另有防御性互斥）
  query.datePendingOnly = abnormalPendingOnly.value || undefined
  query.businessDateFrom = abnormalPendingOnly.value ? undefined : range?.[0]
  query.businessDateTo = abnormalPendingOnly.value ? undefined : range?.[1]
}

/** 月份查询请求代次：快速切月时旧响应不得覆盖新结果（Codex P2 批4） */
let reconGeneration = 0
async function loadReconciliation() {
  if (!canQuery.value) return
  const generation = ++reconGeneration
  reconLoading.value = true
  try {
    const result = await getMonthReconciliation(month.value)
    if (generation === reconGeneration) reconciliation.value = result
  } finally {
    if (generation === reconGeneration) reconLoading.value = false
  }
}

let abnormalGeneration = 0
async function loadAbnormal(pageNo?: number) {
  if (!canQuery.value) return
  if (pageNo) abnormalQuery.pageNo = pageNo
  applyAbnormalRange(abnormalQuery, abnormalDateRange.value)
  const generation = ++abnormalGeneration
  abnormal.loading = true
  try {
    const page = await getAbnormalFundPage({ ...abnormalQuery })
    if (generation !== abnormalGeneration) return
    abnormal.list = page.list || []
    abnormal.total = page.total || 0
  } finally {
    if (generation === abnormalGeneration) abnormal.loading = false
  }
}

function onAbnormalPendingOnlyChange() {
  if (abnormalPendingOnly.value) {
    abnormalDateRange.value = null
  }
  loadAbnormal(1)
}

async function loadTable(table: string, pageNo?: number) {
  if (!canQuery.value) return
  if (table === 'receipts') {
    if (pageNo) receiptQuery.pageNo = pageNo
    applyRange(receiptQuery, receiptDateRange.value)
    receipts.loading = true
    try {
      const page = await getReceiptPage({ ...receiptQuery })
      receipts.list = page.list || []
      receipts.total = page.total || 0
    } finally {
      receipts.loading = false
    }
  } else if (table === 'payments') {
    if (pageNo) paymentQuery.pageNo = pageNo
    applyPaymentRange(paymentQuery, paymentDateRange.value)
    payments.loading = true
    try {
      const page = await getPaymentPage({ ...paymentQuery })
      payments.list = page.list || []
      payments.total = page.total || 0
    } finally {
      payments.loading = false
    }
  } else if (table === 'refunds') {
    if (pageNo) refundQuery.pageNo = pageNo
    applyRefundRange(refundQuery, refundDateRange.value)
    refunds.loading = true
    try {
      const page = await getRefundPage({ ...refundQuery })
      refunds.list = page.list || []
      refunds.total = page.total || 0
    } finally {
      refunds.loading = false
    }
  } else if (table === 'payables') {
    if (pageNo) payableQuery.pageNo = pageNo
    payables.loading = true
    try {
      const page = await getPayablePage({ ...payableQuery })
      payables.list = page.list || []
      payables.total = page.total || 0
    } finally {
      payables.loading = false
    }
  }
}

function onTabChange(name: string) {
  if (name === 'receipts') loadTable('receipts')
  else if (name === 'payments') loadTable('payments')
  else if (name === 'refunds') loadTable('refunds')
  else if (name === 'payables') loadTable('payables')
}

function exportParams(table: FinanceExportQuery['table']): FinanceExportQuery {
  if (table === 'reconciliation') return { table, month: month.value }
  if (table === 'receipts') {
    applyRange(receiptQuery, receiptDateRange.value)
    return {
      table,
      status: receiptQuery.status,
      projectId: receiptQuery.projectId,
      keyword: receiptQuery.keyword,
      method: receiptQuery.method,
      sourceType: receiptQuery.sourceType,
      receivedDateFrom: receiptQuery.receivedDateFrom,
      receivedDateTo: receiptQuery.receivedDateTo
    }
  }
  if (table === 'payments') {
    applyPaymentRange(paymentQuery, paymentDateRange.value)
    return {
      table,
      status: paymentQuery.status,
      projectId: paymentQuery.projectId,
      keyword: paymentQuery.keyword,
      payDateFrom: paymentQuery.payDateFrom,
      payDateTo: paymentQuery.payDateTo
    }
  }
  if (table === 'refunds') {
    applyRefundRange(refundQuery, refundDateRange.value)
    return {
      table,
      status: refundQuery.status,
      receiptId: refundQuery.receiptId,
      refundDirection: refundQuery.refundDirection,
      keyword: refundQuery.keyword,
      dateFrom: refundQuery.dateFrom,
      dateTo: refundQuery.dateTo
    }
  }
  return {
    table,
    status: payableQuery.status,
    projectId: payableQuery.projectId,
    keyword: payableQuery.keyword
  }
}

async function exportCsv(table: FinanceExportQuery['table']) {
  if (!canQuery.value || exporting.value) return
  exporting.value = table
  try {
    const blob = await exportFinanceCsv(exportParams(table))
    downloadBlob(
      blob,
      `finance-${table}-${table === 'reconciliation' ? month.value : 'result'}.csv`
    )
  } catch (error: any) {
    // 失败形态为 HTTP 200 + CommonResult code=400（5 万上限收窄提示等）；拦截器已解析并抛错
    const message = typeof error === 'string' ? error : error?.message
    ElMessage.error(message || '导出失败，请收窄筛选条件后重试')
  } finally {
    exporting.value = ''
  }
}

function downloadBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

onMounted(async () => {
  if (!canQuery.value) return
  await Promise.all([loadReconciliation(), loadAbnormal(1), loadTable('receipts', 1)])
})
</script>

<style scoped>
.recon-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.recon-cell {
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 12px;
}

.recon-cell--strong .recon-value {
  font-weight: 700;
  color: #714320;
}

.recon-label {
  font-size: 12px;
  color: #8a7d72;
}

.recon-value {
  margin-top: 4px;
  font-size: 18px;
  font-weight: 600;
  color: #282728;
}

.section-card {
  margin-bottom: 12px;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #282728;
}

.filter-form {
  margin-bottom: 4px;
}

.pager {
  margin-top: 8px;
  justify-content: flex-end;
}

.muted {
  color: #9b8d80;
}

@media (width <= 1200px) {
  .recon-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (width <= 768px) {
  .recon-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .section-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .section-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
