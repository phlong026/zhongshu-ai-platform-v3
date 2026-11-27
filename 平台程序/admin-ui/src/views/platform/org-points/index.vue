<template>
  <div class="zs-page" :class="{ 'embedded-points': embedded }">
    <div v-if="!embedded" class="zs-page-header">
      <div>
        <h1 class="zs-page-title">公司积分池</h1>
      </div>
      <el-button :loading="loading" @click="loadAll">重新加载</el-button>
    </div>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <el-alert
      v-if="!canCreditDirectly"
      title="调点由一人提交、另一名有复核权限的总部人员确认后生效。"
      type="info"
      :closable="false"
      class="page-alert"
    />
    <el-alert
      v-if="adjustmentForm.attempted"
      title="存在结果未确认的调点申请。只能按原申请重试或先刷新对账，不能切换公司或新建申请。"
      type="warning"
      :closable="false"
      class="page-alert"
    />
    <el-alert
      v-if="priceForm.attempted"
      title="存在结果未确认的价格发布。只能按原请求重试或先刷新对账，不能改发其他价格。"
      type="warning"
      :closable="false"
      class="page-alert"
    />

    <div v-loading="loading" class="pool-layout">
      <section v-if="!embedded" class="zs-table-card company-card">
        <div class="section-title">公司</div>
        <el-empty v-if="!companies.length && !loading" description="暂无公司积分数据" />
        <button
          v-for="company in companies"
          :key="company.orgId"
          type="button"
          class="company-item"
          :class="{ active: company.orgId === selectedOrgId }"
          @click="selectCompany(company.orgId)"
        >
          <span class="company-name">{{ company.name }}</span>
          <span class="company-balance">{{ formatPoints(company.balance) }} 积分</span>
          <span class="company-id">组织 {{ company.orgId }}</span>
        </button>
      </section>

      <main class="pool-main">
        <template v-if="selectedCompany">
          <div class="tab-actions">
            <DirectCreditControl
              :org-id="selectedOrgId"
              :org-name="selectedCompany.name"
              @credited="onCredited"
            />
          </div>
          <div class="summary-grid">
            <div class="summary-card">
              <span>测试积分 {{ selectedCompany?.simulatedPoints || 0 }}</span>
              <span>套餐模拟收款（元）</span><strong>{{ simulatedYuan }}</strong>
            </div>
            <div class="summary-card">
              <span>当前可用</span><strong>{{ formatPoints(summary?.balance) }}</strong>
            </div>
            <div class="summary-card">
              <span>在途预扣</span><strong>{{ formatPoints(summary?.inFlight) }}</strong>
            </div>
            <div class="summary-card">
              <span>本月已使用</span><strong>{{ formatPoints(summary?.monthUsedTotal) }}</strong>
            </div>
            <div class="summary-card">
              <span>账户状态</span><strong>{{ statusText(summary?.status) }}</strong>
            </div>
          </div>

          <el-tabs v-model="activeTab" class="content-card">
            <el-tab-pane label="积分流水" name="ledger">
              <el-table
                native-scrollbar
                max-height="max(280px, calc(100dvh - 410px))"
                :data="ledger"
                empty-text="暂无积分流水"
              >
                <el-table-column prop="createTime" label="时间" min-width="170">
                  <template #default="{ row }">{{ adminDateTime(row.createTime) }}</template>
                </el-table-column>
                <el-table-column prop="type" label="类型" width="110">
                  <template #default="{ row }">{{ ledgerTypeText(row.type) }}</template>
                </el-table-column>
                <el-table-column
                  prop="subject"
                  label="业务科目"
                  min-width="140"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="availableDelta"
                  label="可用积分变动"
                  min-width="150"
                  align="right"
                >
                  <template #default="{ row }">{{ formatInteger(row.availableDelta) }}</template>
                </el-table-column>
                <el-table-column
                  prop="actorUserId"
                  label="使用人编号"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column prop="note" label="说明" min-width="180" show-overflow-tooltip />
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="积分调整记录" name="adjustments">
              <div class="tab-actions">
                <el-button
                  v-if="canSubmit && !canCreditDirectly"
                  type="primary"
                  @click="openAdjustment"
                  >提交调点申请</el-button
                >
              </div>
              <el-table
                native-scrollbar
                max-height="max(280px, calc(100dvh - 410px))"
                :data="adjustments"
                empty-text="暂无调点申请"
              >
                <el-table-column prop="id" label="申请编号" min-width="180" show-overflow-tooltip />
                <el-table-column prop="delta" label="积分变动" width="120">
                  <template #default="{ row }">{{ formatInteger(row.delta) }}</template>
                </el-table-column>
                <el-table-column
                  prop="reason"
                  label="申请原因"
                  min-width="180"
                  show-overflow-tooltip
                />
                <el-table-column
                  prop="makerId"
                  label="提交人"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column prop="status" label="状态" width="110">
                  <template #default="{ row }">{{ adjustmentStatusText(row.status) }}</template>
                </el-table-column>
                <el-table-column
                  prop="checkerId"
                  label="复核人"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column v-if="canReview" label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="canReviewAdjustment(row)"
                      type="primary"
                      link
                      @click="openReview(row)"
                      >复核</el-button
                    >
                    <el-tag v-else-if="isOwnPendingAdjustment(row)" type="info"
                      >需其他人员复核</el-tag
                    >
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane v-if="canQueryPrices && !embedded" label="业务价格" name="prices">
              <div class="tab-actions">
                <el-button type="primary" @click="$router.push('/platform-pricing')"
                  >打开统一定价管理</el-button
                >
              </div>
              <el-table
                native-scrollbar
                max-height="max(280px, calc(100dvh - 410px))"
                :data="prices"
                empty-text="暂无业务价格"
              >
                <el-table-column prop="label" label="能力" min-width="160" />
                <el-table-column
                  prop="subject"
                  label="科目"
                  min-width="150"
                  show-overflow-tooltip
                />
                <el-table-column prop="unit" label="计量单位" width="110" />
                <el-table-column label="单价（积分）" min-width="130">
                  <template #default="{ row }">{{ formatMicros(row.priceMicros) }}</template>
                </el-table-column>
                <el-table-column prop="minCharge" label="最低收费" width="120" />
                <el-table-column
                  prop="versionText"
                  label="版本"
                  min-width="120"
                  show-overflow-tooltip
                />
                <el-table-column prop="configured" label="来源" width="110">
                  <template #default="{ row }">{{
                    row.configured ? '总部配置' : '系统基准'
                  }}</template>
                </el-table-column>
                <el-table-column v-if="canUpdatePrices" label="操作" width="130" fixed="right">
                  <template #default="{ row }">
                    <el-button type="primary" link @click="openPrice(row)">发布新价格</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else description="请选择公司查看积分数据" class="empty-main" />
      </main>
    </div>

    <el-dialog
      v-model="adjustmentVisible"
      title="提交公司积分调点"
      width="520px"
      :before-close="closeAdjustment"
    >
      <el-alert title="提交后需要另一名总部人员复核才会生效。" type="warning" :closable="false" />
      <el-form label-width="110px" :disabled="saving || adjustmentForm.attempted">
        <el-form-item label="目标公司">{{ selectedCompany?.name }}</el-form-item>
        <el-form-item label="积分变动">
          <el-input-number
            v-model="adjustmentForm.delta"
            :precision="0"
            :step="100"
            :min="-Number.MAX_SAFE_INTEGER"
            :max="Number.MAX_SAFE_INTEGER"
          />
        </el-form-item>
        <el-form-item label="申请原因">
          <el-input
            v-model="adjustmentForm.reason"
            type="textarea"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="adjustmentForm.attempted"
        title="上次提交结果未确认，将按原金额、原原因和同一幂等键重试。"
        type="warning"
        :closable="false"
      />
      <template #footer>
        <el-button v-if="adjustmentForm.attempted" :disabled="saving" @click="reconcilePending"
          >先刷新对账</el-button
        >
        <el-button :disabled="saving" @click="cancelAdjustment">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAdjustment">{{
          adjustmentForm.attempted ? '按原申请重试' : '提交复核'
        }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="复核公司积分调点" width="520px">
      <el-alert
        title="提交人与复核人必须不同；决定提交后不可修改。"
        type="warning"
        :closable="false"
      />
      <el-form label-width="110px" :disabled="saving">
        <el-form-item label="积分变动">{{
          reviewing ? formatInteger(reviewing.delta) : '—'
        }}</el-form-item>
        <el-form-item label="申请原因">{{ reviewing?.reason }}</el-form-item>
        <el-form-item label="复核意见">
          <el-input v-model="reviewForm.comment" type="textarea" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="saving" @click="saveReview(false)">拒绝</el-button>
        <el-button type="primary" :loading="saving" @click="saveReview(true)">同意并执行</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="priceVisible"
      title="发布公司积分价格"
      width="540px"
      :before-close="closePrice"
    >
      <el-alert
        title="新价格仅影响后续预扣；已产生的预扣继续使用原价格快照。"
        type="info"
        :closable="false"
      />
      <el-form label-width="130px" :disabled="saving || priceForm.attempted">
        <el-form-item label="业务能力">{{ editingPrice?.label }}</el-form-item>
        <el-form-item label="单价（积分）">
          <el-input v-model="priceForm.points" placeholder="最多 6 位小数" />
        </el-form-item>
        <el-form-item label="最低收费积分">
          <el-input v-model="priceForm.minCharge" placeholder="正整数" />
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="priceForm.reason" type="textarea" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="priceForm.attempted"
        title="上次发布结果未确认，将按原价格、原原因和同一幂等键重试。"
        type="warning"
        :closable="false"
      />
      <template #footer>
        <el-button v-if="priceForm.attempted" :disabled="saving" @click="reconcilePrice"
          >先刷新对账</el-button
        >
        <el-button :disabled="saving" @click="cancelPrice">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePrice">{{
          priceForm.attempted ? '按原请求重试' : '发布新版本'
        }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DirectCreditControl from './DirectCreditControl.vue'
import { checkPermi } from '@/utils/permission'
import { adminDateTime } from '@/utils/adminDisplay'
import { useUserStore } from '@/store/modules/user'
import { getTenantId } from '@/utils/auth'
import { beginAttempt, createDraft, resetDraft, restoreDraft } from './adjustmentDraft'
import {
  clearPending,
  findPending,
  loadPending,
  savePending,
  type AdjustmentScope
} from './adjustmentPending'
import { formatInteger, formatMicros, integerText } from './int64'
import { runPersistedRequest } from './persistedRequest'
import {
  beginPriceAttempt,
  createPriceDraft,
  resetPriceDraft,
  restorePriceDraft
} from './priceDraft'
import {
  clearPricePending,
  findPricePending,
  savePricePending,
  type PriceScope
} from './pricePending'
import {
  getCompanyAdjustments,
  getCompanyPoolLedger,
  getCompanyPools,
  getCompanyPoolSummary,
  getSubjectPrices,
  publishSubjectPrice,
  reviewCompanyAdjustment,
  submitCompanyAdjustment,
  type Adjustment,
  type CompanyPool,
  type PoolLedgerEntry,
  type PoolSummary,
  type SubjectPrice
} from '@/api/platform/orgPoints'
import { isActivePricingSubject } from '../pricing/catalog'

defineOptions({ name: 'PlatformOrgPoints' })
const props = withDefaults(defineProps<{ orgId?: string; embedded?: boolean }>(), {
  orgId: '',
  embedded: false
})
const message = useMessage()
const userStore = useUserStore()
const emit = defineEmits<{ credited: [] }>()
const canCreditDirectly = computed(() => userStore.getRoles.includes('super_admin'))
function onCredited() {
  emit('credited')
  void loadAll()
}
const canSubmit = computed(() => checkPermi(['platform:org-point:adjustment:submit']))
const canReview = computed(() => checkPermi(['platform:org-point:adjustment:review']))
const canQueryPrices = computed(() => checkPermi(['platform:org-point:price:query']))
const canUpdatePrices = computed(() => checkPermi(['platform:org-point:price:update']))
const companies = ref<CompanyPool[]>([])
const selectedOrgId = ref(props.orgId)
const summary = ref<PoolSummary>()
const ledger = ref<PoolLedgerEntry[]>([])
const adjustments = ref<Adjustment[]>([])
const prices = ref<SubjectPrice[]>([])
const loadPrices = async () =>
  (await getSubjectPrices()).filter((row) => isActivePricingSubject(row.subject))
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const activeTab = ref('ledger')
const adjustmentPendingScope = ref<AdjustmentScope>()
const pricePendingScope = ref<PriceScope>()
let generation = 0

const currentActorScope = (): PriceScope => {
  const tenantId = String(getTenantId() ?? '')
  const actorId = String(userStore.getUser.id ?? '')
  if (!tenantId || !actorId || actorId === '0') throw new Error('当前租户或账号身份无效')
  return { tenantId, actorId }
}

const adjustmentScope = (orgId: string): AdjustmentScope => {
  const { tenantId, actorId } = currentActorScope()
  if (!orgId) throw new Error('当前公司身份无效')
  return { tenantId, actorId, orgId }
}

const sameActor = (left: PriceScope, right: PriceScope) =>
  left.tenantId === right.tenantId && left.actorId === right.actorId

const simulatedYuan = computed(() => {
  const fen = String(selectedCompany.value?.simulatedReceiptFen || '0').padStart(3, '0')
  return fen.slice(0, -2) + '.' + fen.slice(-2)
})
const selectedCompany = computed(() =>
  companies.value.find((row) => row.orgId === selectedOrgId.value)
)
const formatPoints = (value: string | number | null | undefined) =>
  value === null || value === undefined ? '未开通' : formatInteger(value)
const statusText = (value?: string) =>
  ({ ACTIVE: '正常', SUSPENDED: '已暂停' })[value || ''] || value || '未开通'
const ledgerTypeText = (value: string) =>
  ({
    RESERVE: '预扣',
    SETTLE: '结算',
    RELEASE: '释放',
    ADJUST: '总部调点',
    SIMULATED_PACKAGE: '套餐模拟收款',
    MANUAL_ADJUST: '人工调整',
    GRANT: '开通赠点'
  })[value] || value
const adjustmentStatusText = (value: Adjustment['status']) =>
  ({ SUBMITTED: '待复核', EXECUTED: '已执行', REJECTED: '已拒绝' })[value]
const isOwnPendingAdjustment = (row: Adjustment) =>
  row.status === 'SUBMITTED' && String(row.makerId) === String(userStore.getUser.id)
const canReviewAdjustment = (row: Adjustment) =>
  canReview.value && row.status === 'SUBMITTED' && !isOwnPendingAdjustment(row)
const requestKey = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? `hq:${crypto.randomUUID()}`
    : `hq:${Date.now()}:${Math.random().toString(36).slice(2)}`

async function loadCompany(orgId: string, current: number) {
  const tasks: Promise<unknown>[] = [
    getCompanyPoolSummary(orgId).then((value) => {
      if (current === generation) summary.value = value
    }),
    getCompanyPoolLedger(orgId, { page: 1, size: 100 }).then((value) => {
      if (current === generation) ledger.value = value
    }),
    getCompanyAdjustments(orgId).then((value) => {
      if (current === generation) adjustments.value = value
    })
  ]
  if (canQueryPrices.value) {
    tasks.push(
      loadPrices().then((value) => {
        if (current === generation) prices.value = value
      })
    )
  }
  await Promise.all(tasks)
}

async function loadAll() {
  if (!checkPermi(['platform:org-point:query'])) {
    error.value = '当前账号没有公司积分池查询权限。'
    return
  }
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const actorScope = currentActorScope()
    if (adjustmentPendingScope.value && !sameActor(adjustmentPendingScope.value, actorScope)) {
      adjustmentVisible.value = false
      adjustmentPendingScope.value = undefined
      resetDraft(adjustmentForm.value, requestKey())
    }
    if (pricePendingScope.value && !sameActor(pricePendingScope.value, actorScope)) {
      priceVisible.value = false
      editingPrice.value = undefined
      pricePendingScope.value = undefined
      resetPriceDraft(priceForm.value, requestKey())
    }
    const result = (await getCompanyPools()).map((row) => ({ ...row, orgId: String(row.orgId) }))
    if (current !== generation) return
    companies.value = result
    const pendingAdjustment = findPending(localStorage, actorScope.tenantId, actorScope.actorId)
    const pendingPrice = findPricePending(
      localStorage,
      actorScope.tenantId,
      actorScope.actorId,
      isActivePricingSubject
    )
    if (props.orgId && pendingAdjustment && pendingAdjustment.scope.orgId !== props.orgId) {
      throw new Error('其他组织有待确认的积分申请，请先在公司积分池核对')
    }
    if (props.orgId && !result.some((row) => row.orgId === props.orgId)) {
      throw new Error('当前组织不可办理积分调整')
    }
    if (pendingAdjustment && pendingPrice) {
      throw new Error('同时存在未确认调点和价格发布，请先联系管理员对账')
    }
    if (pendingAdjustment) {
      if (!result.some((row) => row.orgId === pendingAdjustment.scope.orgId)) {
        throw new Error('存在未确认调点申请，但当前账号已无法访问对应公司，请联系管理员核对')
      }
      selectedOrgId.value = pendingAdjustment.scope.orgId
      adjustmentPendingScope.value = pendingAdjustment.scope
      restoreDraft(adjustmentForm.value, pendingAdjustment.frozen)
      adjustmentVisible.value = true
      activeTab.value = 'adjustments'
    } else if (!result.some((row) => row.orgId === selectedOrgId.value)) {
      selectedOrgId.value = result[0]?.orgId || ''
    }
    if (selectedOrgId.value) await loadCompany(selectedOrgId.value, current)
    if (pendingPrice) {
      const row = prices.value.find((price) => price.subject === pendingPrice.frozen.subject)
      if (!row) throw new Error('存在未确认价格发布，但当前账号已无法访问对应科目')
      editingPrice.value = row
      pricePendingScope.value = pendingPrice.scope
      restorePriceDraft(priceForm.value, pendingPrice.frozen)
      priceVisible.value = true
      activeTab.value = 'prices'
    }
  } catch (failure) {
    if (current === generation) {
      error.value = `公司积分加载失败：${failure instanceof Error ? failure.message : '服务不可用或权限不足，请稍后重试。'}`
    }
  } finally {
    if (current === generation) loading.value = false
  }
}

async function selectCompany(orgId: string) {
  if (adjustmentForm.value.attempted && orgId !== selectedOrgId.value) {
    message.warning('存在结果未确认的调点申请，请先按原申请重试或对账')
    return
  }
  if (selectedOrgId.value === orgId && summary.value) return
  selectedOrgId.value = orgId
  summary.value = undefined
  ledger.value = []
  adjustments.value = []
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    await loadCompany(orgId, current)
  } catch (failure) {
    if (current === generation)
      error.value = `公司积分加载失败：${failure instanceof Error ? failure.message : '请稍后重试。'}`
  } finally {
    if (current === generation) loading.value = false
  }
}

const adjustmentVisible = ref(false)
const adjustmentForm = ref(createDraft(requestKey()))
const openAdjustment = () => {
  try {
    const pending = loadPending(localStorage, adjustmentScope(selectedOrgId.value))
    if (pending) {
      adjustmentPendingScope.value = pending.scope
      restoreDraft(adjustmentForm.value, pending.frozen)
    } else {
      adjustmentPendingScope.value = undefined
      resetDraft(adjustmentForm.value, requestKey())
    }
  } catch (failure) {
    message.error(failure instanceof Error ? failure.message : '未确认调点记录读取失败')
    return
  }
  adjustmentVisible.value = true
}
const cancelAdjustment = () => {
  if (adjustmentForm.value.attempted) {
    message.warning('结果未确认的申请不能取消，请按原申请重试或先对账')
    return
  }
  adjustmentVisible.value = false
  adjustmentPendingScope.value = undefined
  resetDraft(adjustmentForm.value, requestKey())
}
const closeAdjustment = (done: () => void) => {
  if (saving.value) return
  if (adjustmentForm.value.attempted) {
    message.warning('结果未确认的申请不能关闭，请按原申请重试或先对账')
    return
  }
  adjustmentPendingScope.value = undefined
  resetDraft(adjustmentForm.value, requestKey())
  done()
}
const reconcilePending = async () => {
  await loadAll()
  message.info('已刷新调点记录；未确认申请仍需使用原幂等键重试确认')
}
const saveAdjustment = async () => {
  if (!selectedOrgId.value) return
  const wasAttempted = adjustmentForm.value.attempted
  let payload
  try {
    payload = beginAttempt(adjustmentForm.value)
  } catch (failure) {
    message.warning(failure instanceof Error ? failure.message : '调点参数无效')
    return
  }
  let scope: AdjustmentScope
  try {
    scope = adjustmentScope(selectedOrgId.value)
    if (adjustmentPendingScope.value && !sameActor(adjustmentPendingScope.value, scope)) {
      throw new Error('未确认调点属于其他租户或账号，已阻止重试')
    }
  } catch (failure) {
    if (!wasAttempted) {
      adjustmentForm.value.attempted = false
      delete adjustmentForm.value.frozen
    }
    message.error(failure instanceof Error ? failure.message : '未确认调点保存失败，未发起请求')
    return
  }
  saving.value = true
  let requestStarted = false
  try {
    await runPersistedRequest(
      () => {
        savePending(localStorage, scope, payload)
        adjustmentPendingScope.value = scope
      },
      () => {
        requestStarted = true
        return submitCompanyAdjustment(selectedOrgId.value, payload)
      },
      () => clearPending(localStorage, scope)
    )
    adjustmentVisible.value = false
    adjustmentPendingScope.value = undefined
    resetDraft(adjustmentForm.value, requestKey())
    message.success('调点申请已提交，等待其他总部人员复核')
    await loadAll()
  } catch (failure) {
    if (!requestStarted && !wasAttempted) {
      adjustmentForm.value.attempted = false
      delete adjustmentForm.value.frozen
      message.error(failure instanceof Error ? failure.message : '未确认调点保存失败，未发起请求')
    } else {
      message.error(
        `提交结果未确认：${failure instanceof Error ? failure.message : '请按原申请重试。'}`
      )
    }
  } finally {
    saving.value = false
  }
}

const reviewVisible = ref(false)
const reviewing = ref<Adjustment>()
const reviewForm = ref({ comment: '' })
const openReview = (row: Adjustment) => {
  reviewing.value = row
  reviewForm.value = { comment: '' }
  reviewVisible.value = true
}
const saveReview = async (approve: boolean) => {
  if (!reviewing.value || !reviewForm.value.comment.trim()) {
    message.warning('请填写复核意见')
    return
  }
  saving.value = true
  try {
    await reviewCompanyAdjustment(reviewing.value.id, {
      approve,
      comment: reviewForm.value.comment.trim()
    })
    reviewVisible.value = false
    message.success(approve ? '调点已复核并执行' : '调点申请已拒绝')
    await loadAll()
  } finally {
    saving.value = false
  }
}

const priceVisible = ref(false)
const editingPrice = ref<SubjectPrice>()
const priceForm = ref(createPriceDraft(requestKey()))
const openPrice = (row: SubjectPrice) => {
  if (priceForm.value.attempted) {
    message.warning('存在结果未确认的价格发布，请按原请求重试或先对账')
    return
  }
  editingPrice.value = row
  resetPriceDraft(priceForm.value, requestKey())
  priceForm.value.points = formatMicros(row.priceMicros)
  priceForm.value.minCharge = integerText(row.minCharge)
  priceVisible.value = true
}
const cancelPrice = () => {
  if (priceForm.value.attempted) {
    message.warning('结果未确认的价格发布不能取消，请按原请求重试或先对账')
    return
  }
  priceVisible.value = false
  editingPrice.value = undefined
  pricePendingScope.value = undefined
  resetPriceDraft(priceForm.value, requestKey())
}
const closePrice = (done: () => void) => {
  if (saving.value) return
  if (priceForm.value.attempted) {
    message.warning('结果未确认的价格发布不能关闭，请按原请求重试或先对账')
    return
  }
  editingPrice.value = undefined
  pricePendingScope.value = undefined
  resetPriceDraft(priceForm.value, requestKey())
  done()
}
const reconcilePrice = async () => {
  prices.value = await loadPrices()
  message.info('已刷新价格版本；未确认发布仍需使用原幂等键重试确认')
}
const savePrice = async () => {
  const row = editingPrice.value
  if (!row) return
  const wasAttempted = priceForm.value.attempted
  let payload
  let scope: PriceScope
  try {
    payload = beginPriceAttempt(priceForm.value, row.subject)
    scope = currentActorScope()
    if (pricePendingScope.value && !sameActor(pricePendingScope.value, scope)) {
      throw new Error('未确认价格发布属于其他租户或账号，已阻止重试')
    }
  } catch (failure) {
    if (!wasAttempted) {
      priceForm.value.attempted = false
      delete priceForm.value.frozen
    }
    message.warning(failure instanceof Error ? failure.message : '价格发布参数无效')
    return
  }
  saving.value = true
  let requestStarted = false
  try {
    await runPersistedRequest(
      () => {
        savePricePending(localStorage, scope, payload)
        pricePendingScope.value = scope
      },
      () => {
        requestStarted = true
        return publishSubjectPrice(payload)
      },
      () => clearPricePending(localStorage, scope, payload.subject)
    )
    priceVisible.value = false
    editingPrice.value = undefined
    pricePendingScope.value = undefined
    resetPriceDraft(priceForm.value, requestKey())
    message.success('新价格版本已发布')
    prices.value = await loadPrices()
  } catch (failure) {
    if (!requestStarted && !wasAttempted) {
      priceForm.value.attempted = false
      delete priceForm.value.frozen
      message.error(
        failure instanceof Error ? failure.message : '未确认价格发布保存失败，未发起请求'
      )
    } else {
      message.error(
        `发布结果未确认：${failure instanceof Error ? failure.message : '请按原请求重试。'}`
      )
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.zs-page-header,
.tab-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-alert {
  margin-bottom: 12px;
}

.pool-layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
  min-height: 520px;
}

.company-card {
  max-height: max(360px, calc(100dvh - 190px));
  padding: 14px;
  overflow-y: auto;
}

.section-title {
  margin-bottom: 10px;
  font-weight: 600;
  color: #282728;
}

.company-item {
  display: grid;
  width: 100%;
  padding: 12px;
  margin-bottom: 8px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 9px;
}

.company-item.active {
  background: #faf5ef;
  border-color: #714320;
}

.company-name {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 600;
  color: #282728;
}

.company-balance {
  margin-top: 5px;
  font-size: 17px;
  color: #714320;
}

.company-id {
  margin-top: 3px;
  font-size: 12px;
  color: #8a7d72;
}

.pool-main {
  min-width: 0;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 10px;
}

.summary-card span {
  font-size: 13px;
  color: #8a7d72;
}

.summary-card strong {
  overflow-wrap: anywhere;
  font-size: 22px;
  color: #282728;
}

.content-card {
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 12px;
}

.tab-actions {
  justify-content: flex-end;
  margin-bottom: 10px;
}

.empty-main {
  height: 420px;
  background: #fff;
  border: 1px solid #efe6dd;
  border-radius: 12px;
}

@media (width <= 1180px) {
  .pool-layout {
    grid-template-columns: minmax(0, 1fr);
    min-height: 0;
  }

  .company-card {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    max-height: 240px;
  }

  .section-title {
    flex-basis: 100%;
    margin-bottom: 0;
  }

  .company-item {
    flex: 1 1 240px;
    width: auto;
    margin-bottom: 0;
  }

  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (width <= 560px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
