<template>
  <div class="org-detail">
    <header class="detail-header">
      <div>
        <h2>{{ organization.orgName }}</h2>
        <el-tag :type="organization.status === 'ACTIVE' ? 'success' : 'info'">{{
          statusName(organization.status)
        }}</el-tag>
        <span class="detail-type">{{
          organization.orgType === 'store' ? '加盟门店' : '总部'
        }}</span>
      </div>
    </header>
    <div class="detail-stats">
      <div
        ><span>可用积分</span
        ><strong>{{ points.balance == null ? '未开通' : integer(points.balance) }}</strong></div
      >
      <div
        ><span>本月消耗</span><strong>{{ integer(points.month_used) }}</strong></div
      >
      <div
        ><span>在途预扣</span><strong>{{ integer(points.in_flight) }}</strong></div
      >
      <div
        ><span>成员人数</span><strong>{{ memberTotal ?? '—' }}</strong></div
      >
    </div>
    <el-alert v-if="summaryError" :title="summaryError" type="error" :closable="false" />
    <el-tabs v-model="tab" @tab-change="changeTab">
      <el-tab-pane label="组织概览" name="overview">
        <el-descriptions :column="summaryColumns" :label-width="110" border>
          <el-descriptions-item label="组织名称" :span="summaryColumns">{{
            organization.orgName
          }}</el-descriptions-item>
          <el-descriptions-item label="所属品牌">{{
            organization.brand || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="所在地区">{{ region }}</el-descriptions-item>
          <el-descriptions-item label="老板姓名">{{
            organization.ownerName || '未设置'
          }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{
            organization.mobile || '未设置'
          }}</el-descriptions-item>
          <el-descriptions-item label="登录账号" :span="summaryColumns">
            {{ organization.loginName || '未设置' }}
            <el-button v-if="organization.loginName" link type="primary" @click="copyAccount"
              >复制</el-button
            >
          </el-descriptions-item>
          <el-descriptions-item label="查找简称" :span="summaryColumns">{{
            current.aliases
              .filter((a) => a.length <= 24)
              .slice(0, 3)
              .join('、') || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="开通时间">{{
            date(organization.createdAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="summaryColumns">{{
            organization.remark || '—'
          }}</el-descriptions-item>
        </el-descriptions>
        <OrganizationProfilePanel
          v-if="canManage"
          :org-id="organization.orgId"
          @updated="profileUpdated"
        />
        <ResetAccountPassword
          :key="String(organization.accountId) + organization.ownerName"
          v-if="
            organization.orgType === 'store' &&
            organization.status !== 'DELETED' &&
            organization.accountId
          "
          :org-id="organization.orgId"
          :org-name="organization.orgName"
        />
        <section v-if="canManage" class="organization-actions">
          <h3>组织状态</h3>
          <p>{{
            organization.status === 'ACTIVE'
              ? '关闭后成员无法使用该组织，历史数据保留。'
              : organization.status === 'DELETED'
                ? '恢复后组织保持关闭，可再次启用。'
                : '启用后成员可继续使用；删除前需结清积分。'
          }}</p>
          <el-button v-if="organization.status === 'ACTIVE'" @click="prepare('close')"
            >关闭组织</el-button
          >
          <template v-else-if="organization.status === 'DISABLED'">
            <el-button type="primary" @click="prepare('reopen')">启用组织</el-button>
            <el-button type="danger" plain @click="prepare('delete')">删除组织</el-button>
          </template>
          <el-button v-else @click="prepare('restore')">恢复组织</el-button>
        </section>
      </el-tab-pane>
      <el-tab-pane label="成员与岗位" name="members" />
      <el-tab-pane v-if="canManage" label="能力开通" name="capabilities">
        <OrganizationCapabilityPanel
          v-if="tab === 'capabilities'"
          :org-id="organization.orgId"
          :status="organization.status"
        />
      </el-tab-pane>
      <el-tab-pane label="积分账户" name="points">
        <DirectCreditControl
          v-if="canCreditOrganization"
          ref="directCredit"
          :org-id="String(organization.orgId)"
          :org-name="organization.orgName"
          @credited="refreshPoints"
        />
      </el-tab-pane>
      <el-tab-pane label="生成记录" name="generations">
        <el-button
          v-if="canManage"
          link
          type="primary"
          @click="
            $router.push({ path: '/platform-task-ops', query: { orgId: organization.orgId } })
          "
          >查看本组织任务与积分核对</el-button
        >
      </el-tab-pane>
      <el-tab-pane label="操作记录" name="audits" />
    </el-tabs>

    <div v-if="tableTab" v-loading="tableLoading">
      <el-alert v-if="tableError" :title="tableError" type="error" :closable="false" />
      <el-table native-scrollbar v-if="tab === 'members'" :data="tableRows" empty-text="暂无成员">
        <el-table-column prop="name" label="姓名" min-width="100" />
        <el-table-column prop="mobile" label="手机号" min-width="140" />
        <el-table-column prop="roles" label="岗位" min-width="160" />
        <el-table-column prop="teams" label="部门" min-width="110" />
        <el-table-column label="状态" width="90"
          ><template #default="{ row }">{{
            row.status === 0 ? '正常' : '已停用'
          }}</template></el-table-column
        >
        <el-table-column v-if="canCreditOrganization" label="操作" width="132" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 0"
              type="primary"
              link
              @click="directCredit?.open(row.name)"
              >添加公司积分</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <el-table
        native-scrollbar
        v-else-if="tab === 'generations'"
        :data="tableRows"
        empty-text="暂无生成记录"
      >
        <el-table-column label="生成时间" min-width="170"
          ><template #default="{ row }">{{ date(row.create_time) }}</template></el-table-column
        >
        <el-table-column prop="id" label="任务编号" min-width="190" show-overflow-tooltip />
        <el-table-column label="生成类型" min-width="120"
          ><template #default="{ row }">{{
            operationName(row.operation)
          }}</template></el-table-column
        >
        <el-table-column prop="actor_name" label="操作人" width="110" />
        <el-table-column label="状态" min-width="100"
          ><template #default="{ row }">{{
            generationStatus(row.status)
          }}</template></el-table-column
        >
        <el-table-column prop="error" label="失败原因" min-width="200" show-overflow-tooltip />
      </el-table>
      <el-table
        native-scrollbar
        v-else-if="tab === 'audits'"
        :data="tableRows"
        empty-text="暂无操作记录"
      >
        <el-table-column label="时间" min-width="170"
          ><template #default="{ row }">{{ date(row.create_time) }}</template></el-table-column
        >
        <el-table-column label="操作" width="110"
          ><template #default="{ row }">{{ actionName(row.action) }}</template></el-table-column
        >
        <el-table-column prop="actor_name" label="操作人" width="110" />
        <el-table-column prop="reason" label="原因" min-width="220" />
      </el-table>
      <el-table native-scrollbar v-else :data="tableRows" empty-text="暂无积分流水">
        <el-table-column label="时间" min-width="170"
          ><template #default="{ row }">{{ date(row.create_time) }}</template></el-table-column
        >
        <el-table-column label="类型" width="110"
          ><template #default="{ row }">{{ ledgerType(row.type) }}</template></el-table-column
        >
        <el-table-column prop="actor_name" label="使用人" width="110" />
        <el-table-column label="积分变动" min-width="120"
          ><template #default="{ row }">{{ integer(row.delta) }}</template></el-table-column
        >
        <el-table-column prop="note" label="说明" min-width="200" />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="tableTotal"
        layout="total, prev, pager, next"
        class="section-pagination"
        @current-change="loadTable(tab)"
      />
    </div>

    <el-dialog
      v-model="actionVisible"
      :title="actionName(action)"
      width="min(520px, 94vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <p>{{ organization.orgName }}</p>
      <el-alert
        :title="
          action === 'delete'
            ? '删除后隐藏该组织，保留历史数据，可在已删除组织中恢复。'
            : action === 'close'
              ? '关闭后成员立即失去该组织的使用权限。'
              : '请确认组织信息后继续。'
        "
        type="warning"
        :closable="false"
      />
      <el-form label-position="top" class="action-form" :disabled="saving">
        <el-form-item label="输入完整组织名称确认"
          ><el-input v-model="confirmation"
        /></el-form-item>
        <el-form-item label="操作原因"
          ><el-input v-model="reason" type="textarea" maxlength="500" :rows="3"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="actionVisible = false">取消</el-button>
        <el-button
          :type="action === 'delete' ? 'danger' : 'primary'"
          :loading="saving"
          :disabled="confirmation !== organization.orgName || !reason.trim()"
          @click="saveState"
          >确认{{ actionName(action).replace('组织', '') }}</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import DirectCreditControl from '@/views/platform/org-points/DirectCreditControl.vue'
import ResetAccountPassword from './ResetAccountPassword.vue'
import { formatInteger } from '@/views/platform/org-points/int64'
import OrganizationProfilePanel from './OrganizationProfilePanel.vue'
import OrganizationCapabilityPanel from './OrganizationCapabilityPanel.vue'
import {
  getOrganizationOperationsAudit,
  type OrganizationProfile
} from '@/api/platform/organizationOperations'
import {
  changeOrganizationState,
  getOrganizationPoints,
  getOrganizationSection,
  type OrganizationDetail
} from '@/api/zs/platform-org'

const props = defineProps<{ detail: OrganizationDetail }>()
const emit = defineEmits<{ changed: [value: OrganizationDetail] }>()
const current = ref(props.detail)
const organization = computed(() => current.value.organization)
const canManage = computed(() => useUserStore().getRoles.includes('super_admin'))
const canCreditOrganization = computed(
  () =>
    canManage.value &&
    organization.value.status === 'ACTIVE' &&
    organization.value.orgType === 'store'
)
const directCredit = ref<InstanceType<typeof DirectCreditControl> | null>(null)
const region = computed(
  () =>
    [organization.value.province, organization.value.city, organization.value.area]
      .filter(Boolean)
      .join(' / ') || '—'
)
const points = ref<Record<string, string | null>>({})
const memberTotal = ref<number | null>(null)
const summaryError = ref('')
const wideSummary = useMediaQuery('(min-width: 760px)')
const summaryColumns = computed(() => (wideSummary.value ? 2 : 1))
const tab = ref('overview')
const tableTab = computed(() => ['members', 'points', 'generations', 'audits'].includes(tab.value))
const tableRows = ref<Record<string, any>[]>([])
const tableTotal = ref(0),
  page = ref(1),
  tableLoading = ref(false),
  tableError = ref('')
let generation = 0
const action = ref(''),
  actionVisible = ref(false),
  saving = ref(false),
  confirmation = ref(''),
  reason = ref(''),
  requestKey = ref('')
const errorText = (e: any) => e?.msg || e?.message || '加载失败，请重试'
const integer = (v: string | number | null | undefined) => (v == null ? '—' : formatInteger(v))
const date = (v: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—')
const actionName = (v: string) =>
  ({
    close: '关闭组织',
    reopen: '启用组织',
    delete: '删除组织',
    restore: '恢复组织',
    'reset-password': '重置登录密码',
    profile: '维护组织资料',
    capabilities: '设置组织能力',
    'owner-transfer': '老板交接',
    'task-review': '核对任务积分'
  })[v] || v
const statusName = (v: string) =>
  ({ ACTIVE: '正常', DISABLED: '已关闭', DELETED: '已删除' })[v] || v
const ledgerType = (v: string) =>
  ({
    RESERVE: '预扣',
    SETTLE: '结算',
    RELEASE: '退回',
    ADJUST: '积分调整',
    MANUAL_ADJUST: '人工调整',
    GRANT: '赠送'
  })[v] || v
const generationStatus = (v: string) =>
  ({
    SUCCEEDED: '成功',
    SUCCESS: '成功',
    COMPLETED: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    PENDING: '等待中',
    RUNNING: '生成中',
    PROCESSING: '生成中',
    SUBMITTED: '已提交'
  })[v] || v
const operationName = (v: string) =>
  ({
    IMAGE: '图片',
    VIDEO: '视频',
    TEXT: '文案',
    SCRIPT: '文案',
    image: '图片',
    video: '视频',
    text: '文案',
    EXTERIOR: '外观设计',
    FLOOR_PLAN: '户型设计'
  })[v] || v
async function loadSummary() {
  summaryError.value = ''
  const results = await Promise.allSettled([
    getOrganizationPoints(organization.value.orgId),
    getOrganizationSection(organization.value.orgId, 'members')
  ])
  if (results[0].status === 'fulfilled') points.value = results[0].value
  else summaryError.value = errorText(results[0].reason)
  if (results[1].status === 'fulfilled') memberTotal.value = results[1].value.total
  else summaryError.value = errorText(results[1].reason)
}
function changeTab() {
  page.value = 1
  generation++
  tableRows.value = []
  tableTotal.value = 0
  tableError.value = ''
  if (tableTab.value) void loadTable(tab.value)
}
async function refreshPoints() {
  await loadSummary()
  if (tab.value === 'points') await loadTable('points')
}
async function loadTable(section: string) {
  if (!['members', 'points', 'generations', 'audits'].includes(section)) return
  const expected = ++generation
  tableLoading.value = true
  tableError.value = ''
  try {
    const result =
      section === 'audits' && canManage.value
        ? await getOrganizationOperationsAudit(organization.value.orgId, page.value)
        : await getOrganizationSection(
            organization.value.orgId,
            (section === 'points' ? 'ledger' : section) as 'members',
            page.value
          )
    if (expected === generation) {
      tableRows.value = result.list
      tableTotal.value = result.total
    }
  } catch (e) {
    if (expected === generation) tableError.value = errorText(e)
  } finally {
    if (expected === generation) tableLoading.value = false
  }
}
async function copyAccount() {
  try {
    await navigator.clipboard.writeText(organization.value.loginName || '')
    ElMessage.success('登录账号已复制')
  } catch {
    ElMessage.warning('请选中账号后手动复制')
  }
}
function profileUpdated(profile: OrganizationProfile) {
  current.value = { ...current.value, organization: profile.organization }
  emit('changed', current.value)
  void loadSummary()
}
function prepare(value: string) {
  action.value = value
  confirmation.value = ''
  reason.value = ''
  requestKey.value = crypto.randomUUID()
  actionVisible.value = true
}
async function saveState() {
  if (saving.value) return
  saving.value = true
  try {
    const result = await changeOrganizationState(organization.value.orgId, {
      action: action.value,
      confirmedName: confirmation.value,
      reason: reason.value.trim(),
      requestKey: requestKey.value
    })
    current.value = result
    emit('changed', result)
    actionVisible.value = false
    ElMessage.success('组织状态已更新')
    void loadSummary()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    saving.value = false
  }
}
onMounted(loadSummary)
</script>

<style scoped>
.org-detail {
  padding: 0 6px 24px;
}

.detail-header {
  margin-bottom: 16px;
}

.detail-header h2 {
  margin: 0 0 12px;
  font-size: 22px;
  line-height: 1.5;
}

.detail-type {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
}

.detail-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.detail-stats > div {
  padding: 14px 16px;
  background: var(--el-fill-color-light);
  border-radius: 10px;
}

.detail-stats span {
  display: block;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.detail-stats strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
}

.organization-actions {
  padding-top: 20px;
  margin-top: 24px;
  border-top: 1px solid var(--el-border-color);
}

.organization-actions h3 {
  font-size: 16px;
}

.organization-actions p {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.section-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}

.action-form {
  margin-top: 20px;
}

:deep(.el-descriptions__table) {
  table-layout: auto;
}

:deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}

:deep(.el-descriptions__label) {
  width: 110px;
  white-space: nowrap;
}

:deep(.embedded-points) {
  padding: 0;
}

:deep(.embedded-points .pool-layout) {
  display: block;
}

@media (width <= 600px) {
  .detail-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-header h2 {
    font-size: 19px;
  }
}
</style>
