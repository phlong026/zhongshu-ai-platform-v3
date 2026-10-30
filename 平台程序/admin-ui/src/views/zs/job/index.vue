<template>
  <div class="zs-page">
    <div v-if="!embedded" class="zs-page-header">
      <div>
        <h1 class="zs-page-title">AI 任务</h1>
        <div class="zs-page-subtitle"
          >生成任务运行状态：排队/进行中/成功/失败；只读运维，不参与扣点与审核</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <p class="zs-protection-note"
        >为便于核对生成结果和费用，任务及相关费用记录会保留，不支持删除。</p
      >
      <el-form inline class="zs-filter">
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            clearable
            placeholder="全部"
            style="width: 180px"
            @change="search"
          >
            <el-option
              v-for="(label, value) in statusText"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="阶段">
          <el-select
            v-model="query.phase"
            clearable
            placeholder="全部"
            style="width: 150px"
            @change="search"
          >
            <el-option label="平面" value="FLAT" />
            <el-option label="立面" value="ELEVATION" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="AI 任务加载失败，请重试后再排查"
        style="margin-bottom: 12px"
      />

      <div v-if="selectedRows.length" style="margin-bottom: 10px">
        <el-button size="small" type="danger" plain disabled @click="deleteSelected"
          >批量删除（{{ selectedRows.length }}）</el-button
        >
      </div>
      <el-table
        :data="list"
        v-loading="loading"
        stripe
        @row-click="openDetail"
        @selection-change="(rows: any[]) => (selectedRows = rows)"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column label="任务号" prop="jobId" width="190" />
        <el-table-column label="客户" width="110">
          <template #default="{ row }">{{ row.nickname || row.userId }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="80">
          <template #default="{ row }">{{ row.phase === 'ELEVATION' ? '立面' : '平面' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.status === 'FAILED' && row.lastError"
              :content="row.lastError"
              placement="top"
            >
              <span class="zs-tag zs-tag--red">{{ statusText[row.status] || row.status }}</span>
            </el-tooltip>
            <span v-else class="zs-tag" :class="statusClass(row.status)">{{
              statusText[row.status] || row.status
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="120">
          <template #default="{ row }">
            <el-progress :percentage="progressOf(row)" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column label="档位" width="100">
          <template #default="{ row }">{{
            (row.imageOptions?.resolution || '2K') +
            ' · ' +
            (row.imageOptions?.orientation === 'PORTRAIT' ? '竖' : '横')
          }}</template>
        </el-table-column>
        <el-table-column label="出图/收图" width="90">
          <template #default="{ row }">{{ row.requestedCount }}/{{ row.acceptedCount }}</template>
        </el-table-column>
        <el-table-column label="扣点" width="80">
          <template #default="{ row }">{{ row.totalPointCost ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="总耗时（含排队）" width="130">
          <template #default="{ row }">{{ fmtDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="120">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openDetail(row)"
              >详情</el-button
            ><el-button link type="danger" disabled @click.stop="deleteRow(row)">删除</el-button>
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

    <el-dialog v-model="detail.visible" title="任务详情" width="min(680px, calc(100vw - 32px))">
      <el-descriptions
        v-if="detail.data"
        class="zs-job-description"
        :column="isNarrow ? 1 : 2"
        border
      >
        <el-descriptions-item label="任务号">{{ detail.data.jobId }}</el-descriptions-item>
        <el-descriptions-item label="项目编号">{{
          detail.data.projectId || '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{
          detail.data.nickname || detail.data.userId
        }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{
          detail.data.phase === 'ELEVATION' ? '立面' : '平面'
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          statusText[detail.data.status] || detail.data.status
        }}</el-descriptions-item>
        <el-descriptions-item label="进度">{{ detail.data.progress || 0 }}%</el-descriptions-item>
        <el-descriptions-item label="清晰度 / 方向">{{
          (detail.data.imageOptions?.resolution || '2K') +
          ' / ' +
          (detail.data.imageOptions?.orientation === 'PORTRAIT' ? '竖版' : '横版')
        }}</el-descriptions-item>
        <el-descriptions-item label="出图/收图">{{
          detail.data.requestedCount + ' / ' + detail.data.acceptedCount
        }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{
          fmtTime(detail.data.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{
          fmtTime(detail.data.finishedAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="总耗时（含排队）">{{
          fmtDuration(detail.data.durationSeconds)
        }}</el-descriptions-item>
        <el-descriptions-item label="最近失败原因" :span="2">{{
          detail.data.lastError || '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="生图单价（设计点/张）">{{
          detail.data.unitPointCost ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="提示词调用（文本模型）">{{
          detail.data.promptPointCost ?? '未扣点'
        }}</el-descriptions-item>
        <el-descriptions-item label="生图扣点快照">{{
          detail.data.totalPointCost ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="结算退点">{{
          detail.data.refundedPointCost ?? '暂无结算'
        }}</el-descriptions-item>
        <el-descriptions-item label="结算净消耗（生图）" :span="2">{{
          detail.data.netPointCost ?? '暂无结算'
        }}</el-descriptions-item>
        <el-descriptions-item label="计划阶段文本模型" :span="2">{{
          promptInfo.model
        }}</el-descriptions-item>
        <el-descriptions-item label="计划阶段输出提示词" :span="2">
          <pre v-if="promptInfo.recorded" class="zs-prompt-text">{{ promptInfo.text }}</pre>
          <span v-else>{{ promptInfo.text }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <section
        v-if="detail.data?.phase === 'FLAT'"
        class="zs-call-traces"
        v-loading="detail.traceLoading"
      >
        <h3>实际调用记录</h3>
        <el-alert
          v-if="detail.traceError"
          type="error"
          :closable="false"
          title="调用记录加载失败，请重试"
        />
        <el-button
          v-if="detail.traceError"
          text
          type="primary"
          @click="loadCallTraces(detail.data.jobId)"
          >重新加载</el-button
        >
        <p v-else-if="!detail.traceLoading && !detail.calls.length"
          >历史调用未记录，无法还原实际发送提示词、模型或模板版本。</p
        >
        <el-collapse v-else>
          <el-collapse-item
            v-for="call in detail.calls"
            :key="call.callId"
            :name="call.callId"
            :title="`${call.stage === 'PLAN' ? '计划调用' : `生图候选 ${call.slot}`} · 原尝试 ${call.sourceAttemptNo}`"
          >
            <el-descriptions :column="1" border class="zs-job-description">
              <el-descriptions-item label="调用状态">{{
                traceStateText[call.state] || '未知'
              }}</el-descriptions-item>
              <el-descriptions-item label="请求模型">{{
                call.request?.model || '未知'
              }}</el-descriptions-item>
              <el-descriptions-item label="提示词模板版本">{{
                call.request?.promptVersion || '未知'
              }}</el-descriptions-item>
              <el-descriptions-item v-if="call.stage === 'GENERATE'" label="请求尺寸">{{
                call.request?.size || '未知'
              }}</el-descriptions-item>
              <el-descriptions-item v-if="call.stage === 'GENERATE'" label="入库实际像素">{{
                tracePixelText(call)
              }}</el-descriptions-item>
              <el-descriptions-item v-if="call.errorCode" label="诊断码">{{
                call.errorCode
              }}</el-descriptions-item>
              <el-descriptions-item v-if="call.request?.systemPrompt" label="实际系统提示词">
                <pre class="zs-prompt-text">{{ call.request.systemPrompt }}</pre>
              </el-descriptions-item>
              <el-descriptions-item
                :label="call.stage === 'PLAN' ? '实际计划请求文字' : '生图请求完整提示词'"
              >
                <pre class="zs-prompt-text">{{ call.request?.promptText || '未记录' }}</pre>
              </el-descriptions-item>
            </el-descriptions>
            <el-table
              v-if="call.request?.references?.length"
              :data="call.request.references"
              class="mt-12px"
            >
              <el-table-column label="参考资产" prop="assetId" min-width="160" />
              <el-table-column label="楼层" width="110"
                ><template #default="{ row }">{{
                  row.floorNo == null ? '未标注' : `第 ${row.floorNo} 层`
                }}</template></el-table-column
              >
              <el-table-column label="来源" min-width="100"
                ><template #default="{ row }">{{
                  row.sourceType === 'CASE_FLOOR_PLAN'
                    ? '案例平面'
                    : row.sourceType === 'USER_SKETCH'
                      ? '用户草图'
                      : '未知'
                }}</template></el-table-column
              >
              <el-table-column label="冻结案例版本" min-width="160"
                ><template #default="{ row }">{{
                  row.sourceCaseVersionId || '未知'
                }}</template></el-table-column
              >
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </section>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { useMediaQuery } from '@vueuse/core'
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsAiJob' })
// embedded=true 时内嵌于设计点流水页的“生成任务”tab，标题交给外层
withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const message = useMessage()
const isNarrow = useMediaQuery('(max-width: 600px)')

/** 秒 → 「x分y秒」；未终止任务无耗时展示 — */
const fmtDuration = (seconds?: number | null) => {
  if (seconds === null || seconds === undefined) return '—'
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m > 0 ? `${m}分${s}秒` : `${s}秒`
}

const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ status: '', phase: '', pageNo: 1, pageSize: 10 })

// 2026-10-02 运营决策：全模块可删（单个+批量，逻辑删除+审计）
const selectedRows = ref<any[]>([])
async function deleteRow(row: any) {
  try {
    await message.confirm('删除该任务？任务及其费用快照将从列表隐藏（逻辑删除，留审计）。')
  } catch {
    return
  }
  try {
    const ok = await ZsApi.deleteAdminData('ai-job', row.jobId)
    if (!ok) {
      message.warning('任务不存在或已删除')
      return
    }
    message.success('已删除')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '删除失败，请重试')
  }
}
async function deleteSelected() {
  if (!selectedRows.value.length) return
  try {
    await message.confirm(
      '批量删除选中的 ' + selectedRows.value.length + ' 个任务？（逻辑删除，留审计）'
    )
  } catch {
    return
  }
  try {
    const res = await ZsApi.batchDeleteAdminData(
      'ai-job',
      selectedRows.value.map((r) => r.jobId)
    )
    message.success('已删除 ' + (res?.deleted ?? 0) + ' / ' + selectedRows.value.length)
    await load()
  } catch (e: any) {
    message.error(e?.msg || '批量删除失败，请重试')
  }
}

const detail = reactive({
  visible: false,
  data: null as any,
  calls: [] as any[],
  acceptedOutputs: [] as any[],
  traceLoading: false,
  traceError: false
})
const traceStateText: Record<string, string> = {
  REQUESTED: '已登记',
  DISPATCHED: '已发起',
  SUCCEEDED: '已收到响应',
  UNKNOWN: '调用结果待确认',
  NOT_SENT: '未发送'
}
const tracePixelText = (call: any) => {
  const output = detail.acceptedOutputs.find(
    (item) => item.slot === call.slot && item.sourceSha256 === call.response?.sha256
  )
  return Number.isInteger(output?.width) &&
    output.width > 0 &&
    Number.isInteger(output?.height) &&
    output.height > 0
    ? `${output.width} × ${output.height} px`
    : '未知（尚无匹配的入库扫描结果）'
}
let detailRequest = 0
const loadCallTraces = async (jobId: string) => {
  const current = detailRequest
  detail.traceLoading = true
  detail.traceError = false
  detail.calls = []
  detail.acceptedOutputs = []
  try {
    const result = await ZsApi.getAiJobCallTraces(jobId)
    if (current !== detailRequest || detail.data?.jobId !== jobId) return
    detail.calls = result?.calls || []
    detail.acceptedOutputs = result?.acceptedOutputs || []
  } catch {
    if (current === detailRequest) detail.traceError = true
  } finally {
    if (current === detailRequest) detail.traceLoading = false
  }
}

/** 旧计划输出记录：与下方实际生图请求分开；历史缺失值明确展示。 */
const promptInfo = computed(() => {
  const text = typeof detail.data?.promptText === 'string' ? detail.data.promptText : ''
  const recorded = text.length > 0
  return {
    recorded,
    text: recorded ? text : '未记录（历史任务，或生成引擎尚未升级到回传提示词的版本）',
    model: detail.data?.providerModel || (recorded ? '未回传' : '—')
  }
})

const statusText: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  VALIDATING: '校验中',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '已成功',
  PARTIALLY_SUCCEEDED: '部分成功',
  FAILED: '已失败',
  CANCELLED: '已取消'
}
const progressOf = (row: any) =>
  ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED'].includes(row.status)
    ? 100
    : row.progress || 0
const statusClass = (s: string) =>
  ({
    QUEUED: 'zs-tag--yellow',
    RUNNING: 'zs-tag--yellow',
    VALIDATING: 'zs-tag--yellow',
    CANCEL_REQUESTED: 'zs-tag--yellow',
    SUCCEEDED: 'zs-tag--green',
    PARTIALLY_SUCCEEDED: 'zs-tag--green',
    FAILED: 'zs-tag--red',
    CANCELLED: 'zs-tag--red'
  })[s] || ''

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getAiJobPage({
      ...query,
      status: query.status || undefined,
      phase: query.phase || undefined
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"没有任务"区分，避免排障时误判为队列为空
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.status = ''
  query.phase = ''
  query.pageNo = 1
  load()
}

const openDetail = async (row: any) => {
  const current = ++detailRequest
  const data = await ZsApi.getAiJob(row.jobId)
  if (current !== detailRequest) return
  detail.data = data
  detail.calls = []
  detail.acceptedOutputs = []
  detail.traceLoading = false
  detail.traceError = false
  detail.visible = true
  if (data?.phase === 'FLAT') await loadCallTraces(row.jobId)
}

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-protection-note {
  margin: 0 0 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.zs-job-description :deep(.el-descriptions__table) {
  table-layout: fixed;
}

.zs-job-description :deep(.el-descriptions__cell) {
  overflow-wrap: anywhere;
}

.zs-filter {
  margin-bottom: 6px;
}

.zs-call-traces {
  margin-top: 20px;
}

.zs-prompt-text {
  max-height: 240px;
  margin: 0;
  overflow: auto;
  font-family: inherit;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
