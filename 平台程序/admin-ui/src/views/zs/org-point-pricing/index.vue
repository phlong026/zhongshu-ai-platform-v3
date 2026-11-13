<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">公司池价格</h1>
        <div class="zs-page-subtitle">
          设计 / AI 能力的按量计费单价；调价只影响新任务，已预扣的在途任务按下单时快照结算。
        </div>
      </div>
      <el-button v-if="canManage" type="primary" @click="openEditor()">调价</el-button>
    </div>

    <el-alert
      v-if="!canQuery"
      title="没有公司池价格查询权限，请联系管理员"
      type="warning"
      :closable="false"
    />

    <template v-else>
      <div class="zs-table-card">
        <div class="section-title">
          各科目现价
          <span class="tip"
            >「系统默认」为内置价；在后台发布后变为「后台配置」，停用后回退系统默认</span
          >
        </div>
        <el-alert
          v-if="loadError"
          title="价格加载失败，请重试"
          type="error"
          :closable="false"
          class="mb-16px"
        />
        <el-table v-loading="loading" :data="subjects" stripe>
          <el-table-column label="计费科目" min-width="170">
            <template #default="{ row }">{{ row.label }}</template>
          </el-table-column>
          <el-table-column label="计量单位" width="100">
            <template #default="{ row }">{{ unitLabel(row.unit) }}</template>
          </el-table-column>
          <el-table-column label="当前单价" min-width="150">
            <template #default="{ row }">
              {{ fmtCredits(row.priceMicros) }} 积分/{{ unitLabel(row.unit) }}
            </template>
          </el-table-column>
          <el-table-column label="最低收费" width="110">
            <template #default="{ row }">{{ Number(row.minCharge) }} 积分</template>
          </el-table-column>
          <el-table-column label="价格来源" min-width="140">
            <template #default="{ row }">
              <el-tag v-if="row.configured" type="success" size="small"
                >后台配置（{{ row.versionText }}）</el-tag
              >
              <el-tag v-else type="info" size="small">系统默认（{{ row.versionText }}）</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button v-if="canManage" link type="primary" @click="openEditor(row)"
                >调价</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="zs-table-card mt-16px">
        <div class="section-title">版本历史</div>
        <el-form inline>
          <el-form-item label="科目">
            <el-select
              v-model="filters.subject"
              clearable
              placeholder="全部科目"
              style="width: 200px"
              @change="loadRules"
            >
              <el-option
                v-for="item in subjects"
                :key="item.subject"
                :label="item.label"
                :value="item.subject"
              />
            </el-select>
          </el-form-item>
          <el-button :loading="ruleLoading" @click="loadRules">刷新</el-button>
        </el-form>
        <el-table v-loading="ruleLoading" :data="historyRows" stripe>
          <el-table-column label="科目" min-width="170">
            <template #default="{ row }">{{ subjectLabel(row.subject) }}</template>
          </el-table-column>
          <el-table-column label="单价" min-width="140">
            <template #default="{ row }">
              {{ fmtCredits(row.priceMicros) }} 积分/{{ unitLabel(row.unit) }}
            </template>
          </el-table-column>
          <el-table-column label="最低收费" width="100">
            <template #default="{ row }">{{ Number(row.minCharge) }} 积分</template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '生效中' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生效时间" min-width="170">
            <template #default="{ row }">{{ fmtTime(row.effectiveAt) }}</template>
          </el-table-column>
          <el-table-column label="停用时间" min-width="170">
            <template #default="{ row }">{{
              row.retiredAt ? fmtTime(row.retiredAt) : '—'
            }}</template>
          </el-table-column>
          <el-table-column label="发布人" width="110">
            <template #default="{ row }">{{ row.operator || '—' }}</template>
          </el-table-column>
          <el-table-column label="停用人" width="110">
            <template #default="{ row }">{{ row.retiredBy || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button
                v-if="canManage && row.status === 'ACTIVE'"
                link
                type="primary"
                @click="openEditor(subjectPriceOf(row.subject), row)"
              >
                调价
              </el-button>
              <el-button
                v-if="canManage && row.status === 'ACTIVE'"
                link
                type="danger"
                @click="retire(row)"
              >
                停用
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <el-dialog
      v-model="editorOpen"
      title="发布新版本价格"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="130px">
        <el-form-item label="计费科目" prop="subject">
          <el-select v-model="form.subject" :disabled="subjectLocked" style="width: 100%">
            <el-option
              v-for="item in subjects"
              :key="item.subject"
              :label="item.label"
              :value="item.subject"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="`单价（积分/${unitLabel(formUnit)}）`" prop="priceCredits">
          <el-input-number
            v-model="form.priceCredits"
            :min="0.000001"
            :max="1000"
            :step="0.5"
            :precision="6"
            style="width: 100%"
          />
          <div class="tip">
            支持 6 位小数（如数字人口播 0.05 积分/秒），单单位最多 1000 积分；当前生效：{{
              currentEffectiveText
            }}
          </div>
        </el-form-item>
        <el-form-item label="最低收费（积分）" prop="minCharge">
          <el-input-number
            v-model="form.minCharge"
            :min="1"
            :max="1000000000"
            :precision="0"
            style="width: 100%"
          />
          <div class="tip">单笔至少扣这么多积分；上限 10 亿，防止异常值直接锁死余额</div>
        </el-form-item>
        <el-alert
          title="发布后旧版自动停用，只影响后续新任务；在途任务按预扣时快照结算，不会被重算。"
          type="warning"
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="editorOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">发布价格</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as Api from '@/api/zs/org-point-pricing'
import type { PriceUnit, SubjectPrice, SubjectPriceRule } from '@/api/zs/org-point-pricing'
import { checkPermi } from '@/utils/permission'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

defineOptions({ name: 'ZsOrgPointPricing' })

const message = useMessage()
/**
 * axios 拦截器对业务码（reject('error')）、500、网络错误都会全局提示；
 * 只有 HTTP 200 空响应会抛空 message 的 Error 且无提示——页面仅此时兜底，避免二次弹窗与全局文案冲突。
 */
const notifyIfSilent = (e: unknown, text: string) => {
  if (e instanceof Error && !e.message) message.error(text)
}
const canQuery = computed(() => checkPermi(['commerce:org-point-price:query']))
const canManage = computed(() => checkPermi(['commerce:org-point-price:manage']))

const loading = ref(false)
const ruleLoading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const editorOpen = ref(false)
const subjectLocked = ref(false)
const subjects = ref<SubjectPrice[]>([])
const historyRows = ref<SubjectPriceRule[]>([])
const formRef = ref<FormInstance>()
const filters = reactive({ subject: '' })

const form = reactive({
  subject: '',
  unit: 'CALL' as PriceUnit,
  priceCredits: undefined as number | undefined,
  minCharge: 1
})

const formRules: FormRules = {
  subject: [{ required: true, message: '请选择计费科目', trigger: 'change' }],
  priceCredits: [{ required: true, message: '请填写单价', trigger: 'blur' }],
  minCharge: [{ required: true, message: '请填写最低收费', trigger: 'blur' }]
}

const unitLabel = (unit: PriceUnit | string) => (unit === 'SECOND' ? '秒' : '次')

/** 微积分 → 积分（最多 6 位小数，去尾零） */
const fmtCredits = (micros: number | string) => {
  const value = Number(micros) / 1_000_000
  if (!Number.isFinite(value)) return '—'
  return String(Number(value.toFixed(6)))
}

const subjectLabel = (key: string) =>
  subjects.value.find((item) => item.subject === key)?.label || key
const subjectPriceOf = (key: string) => subjects.value.find((item) => item.subject === key)

const currentEffectiveText = computed(() => {
  const row = subjectPriceOf(form.subject)
  if (!row) return '—'
  return `${fmtCredits(row.priceMicros)} 积分/${unitLabel(row.unit)}（${row.versionText}）`
})

/** 单位以科目目录为准（后端发布时也会用目录单位，前端不自行提交 unit） */
const formUnit = computed(() => subjectPriceOf(form.subject)?.unit || form.unit)

// 竞态保护：快速切换科目时丢弃旧响应
let rulesSeq = 0
async function load() {
  if (!canQuery.value) return
  loading.value = true
  loadError.value = false
  try {
    subjects.value = await Api.listSubjectPrices()
    await loadRules()
  } catch {
    subjects.value = []
    historyRows.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadRules() {
  if (!canQuery.value) return
  const seq = ++rulesSeq
  ruleLoading.value = true
  loadError.value = false
  try {
    const rows = await Api.listPriceRules(filters.subject || undefined)
    if (seq !== rulesSeq) return
    historyRows.value = rows
  } catch {
    if (seq !== rulesSeq) return
    historyRows.value = []
    loadError.value = true
  } finally {
    if (seq === rulesSeq) ruleLoading.value = false
  }
}

function openEditor(subject?: SubjectPrice, activeRule?: SubjectPriceRule) {
  const target = subject || subjects.value.find((item) => item.subject === form.subject)
  Object.assign(form, {
    subject: activeRule?.subject || target?.subject || '',
    unit: (activeRule?.unit || target?.unit || 'CALL') as PriceUnit,
    priceCredits: activeRule
      ? Number(activeRule.priceMicros) / 1_000_000
      : target
        ? Number(target.priceMicros) / 1_000_000
        : undefined,
    minCharge: Number(activeRule?.minCharge ?? target?.minCharge ?? 1)
  })
  // 从行进入时锁定科目，避免误改到别的科目；顶部「调价」按钮允许自由选择
  subjectLocked.value = Boolean(subject || activeRule)
  editorOpen.value = true
  formRef.value?.clearValidate()
}

async function save() {
  if (!canManage.value || saving.value) return
  await formRef.value?.validate().catch(() => Promise.reject(new Error('invalid')))
  const credits = Number(form.priceCredits)
  if (!Number.isFinite(credits) || credits <= 0) {
    message.warning('单价必须大于 0')
    return
  }
  const priceMicros = Math.round(credits * 1_000_000)
  if (priceMicros < 1) {
    message.warning('单价精度最多 6 位小数')
    return
  }
  saving.value = true
  try {
    await Api.publishPrice({
      subject: form.subject,
      priceMicros,
      minCharge: Math.round(Number(form.minCharge))
    })
    message.success('已发布新版本价格，旧版已自动停用')
    editorOpen.value = false
    await load()
  } catch (e) {
    // 业务错误（拦截器 ElNotification 后 reject('error')）、500、网络错误均已全局提示；
    // 仅 HTTP 200 空响应（拦截器 throw 空 message 的 Error）没有任何提示，这里兜底一次
    notifyIfSilent(e, '发布结果未确认，请刷新后核对价格是否已生效')
  } finally {
    saving.value = false
  }
}

async function retire(row: SubjectPriceRule) {
  if (!canManage.value || saving.value) return
  try {
    await ElMessageBox.confirm(
      `停用「${subjectLabel(row.subject)}」v${row.version} 价格后，将回退系统默认价；在途任务仍按快照结算。确认停用？`,
      '停用价格',
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    await Api.retirePrice(row.id)
    message.success('已停用，新任务按系统默认价计费')
    await load()
  } catch (e) {
    notifyIfSilent(e, '停用结果未确认，请刷新后核对当前状态')
    // 拦截器已提示服务端文案；失败后回读真实状态（可能已被他人停用），避免页面显示过期 ACTIVE
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 8px;
}
.mt-16px {
  margin-top: 16px;
}
</style>
