<template>
  <div class="zs-page">
    <div class="zs-page-header identity-header">
      <div>
        <h1 class="zs-page-title">部门与员工</h1>
        <div class="zs-page-subtitle"
          >{{ currentPost?.orgName }} · {{ roleLabel(currentPost?.roleCode || '') }}</div
        >
      </div>
      <el-select
        :model-value="currentPost?.postId"
        :loading="identity.loading"
        style="width: 260px"
        @change="switchPost"
      >
        <el-option
          v-for="post in identity.posts"
          :key="post.postId"
          :value="post.postId"
          :label="`${post.orgName} · ${roleLabel(post.roleCode)}`"
        />
      </el-select>
    </div>

    <el-alert v-if="!canQuery" title="当前工作身份没有组织人员查看权限" type="warning" show-icon />
    <template v-else>
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon class="mb-16px" />
      <el-alert v-if="actionError" :title="actionError" type="error" show-icon class="mb-16px" />
      <el-alert
        v-if="canManage && !loading && !postTemplates.length"
        title="当前组织暂无可指派的岗位模板，请联系平台管理员配置。"
        type="warning"
        show-icon
        class="mb-16px"
      />
      <div class="summary-grid">
        <div class="summary-card"
          ><span>部门</span><strong>{{ teams.length }}</strong></div
        >
        <div class="summary-card"
          ><span>有效任职</span><strong>{{ staff.length }}</strong></div
        >
        <div class="summary-card"
          ><span>待分配事项</span><strong>{{ pending.length }}</strong></div
        >
      </div>

      <div class="zs-table-card toolbar">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <template v-if="canManage">
          <el-button :disabled="saving" @click="openTeam">新建部门</el-button>
          <el-button type="primary" :disabled="saving || !postTemplates.length" @click="openStaff"
            >录入员工 / 恢复任职</el-button
          >
        </template>
      </div>

      <div class="zs-table-card team-list">
        <h2>部门</h2>
        <el-tag v-for="team in teams" :key="team.deptId" effect="plain">
          {{ team.name }}<span v-if="team.line"> · {{ team.line }}</span>
        </el-tag>
        <el-empty
          v-if="!teams.length && !loading"
          description="当前组织暂无部门"
          :image-size="64"
        />
      </div>

      <div class="zs-table-card">
        <el-table v-loading="loading" :data="pagedStaff" empty-text="当前组织暂无有效任职">
          <el-table-column prop="name" label="员工" min-width="120" />
          <el-table-column prop="mobile" label="手机号" min-width="140" />
          <el-table-column label="业务岗位" min-width="130"
            ><template #default="{ row }">{{ roleLabel(row.roleCode) }}</template></el-table-column
          >
          <el-table-column prop="teamName" label="部门" min-width="120" />
          <el-table-column label="账号状态" width="100"
            ><template #default="{ row }"
              ><el-tag :type="row.active ? 'success' : 'danger'">{{
                row.active ? '可用' : '全局停用'
              }}</el-tag></template
            ></el-table-column
          >
          <el-table-column v-if="canManage" label="操作" min-width="340" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAssign(row)">追加岗位</el-button>
              <el-button link @click="openHandover(row)">离职交接</el-button>
              <el-button link type="warning" @click="emergency(row)">紧急撤权</el-button>
              <el-button link type="danger" @click="revoke(row)">撤销本岗位</el-button>
              <el-button link type="danger" @click="stopInOrg(row)">本组织停职</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="staff.length > pageSize"
          v-model:current-page="staffPage"
          :page-size="pageSize"
          :total="staff.length"
          layout="prev, pager, next, total"
          class="staff-pagination"
        />
      </div>

      <div class="zs-table-card">
        <h2>待分配事项</h2>
        <el-table :data="pending" empty-text="暂无紧急撤权后的待分配事项">
          <el-table-column prop="domainLabel" label="业务域" />
          <el-table-column prop="fromUserId" label="原负责人ID" />
          <el-table-column prop="inFlight" label="待分配数量" />
          <el-table-column prop="createTime" label="生成时间" min-width="180" />
          <el-table-column v-if="canManage" label="操作"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="openPendingHandover(row)"
                >补交接</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </div>
    </template>

    <el-dialog v-model="teamDialog" title="新建部门" width="460px">
      <el-form label-position="top"
        ><el-form-item label="部门名称"
          ><el-input v-model="teamForm.name" maxlength="32" /></el-form-item
        ><el-form-item label="业务线"
          ><el-input v-model="teamForm.line" maxlength="32" /></el-form-item
      ></el-form>
      <template #footer
        ><el-button @click="teamDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveTeam">创建</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="staffDialog" title="录入员工 / 恢复任职" width="520px">
      <el-alert
        title="停职员工请填写原手机号；系统会复用已有账号并重新指派本组织岗位。"
        type="info"
        show-icon
        class="mb-16px"
      />
      <el-form label-position="top"
        ><el-form-item label="姓名"><el-input v-model="staffForm.name" /></el-form-item
        ><el-form-item label="手机号"
          ><el-input v-model="staffForm.mobile" maxlength="11" /></el-form-item
        ><el-form-item label="岗位"
          ><el-select v-model="staffForm.roleCode" class="w-full"
            ><el-option
              v-for="item in roleOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value" /></el-select></el-form-item
        ><el-form-item label="部门"
          ><el-select v-model="staffForm.teamId" clearable class="w-full"
            ><el-option
              v-for="team in teams"
              :key="team.deptId"
              :label="team.name"
              :value="team.deptId" /></el-select></el-form-item
      ></el-form>
      <template #footer
        ><el-button @click="staffDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveStaff">保存</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="assignDialog" title="追加员工岗位" width="500px">
      <el-alert
        title="此入口仅为当前列表中的员工追加岗位；停职恢复请使用原手机号重新录入。"
        type="info"
        show-icon
        class="mb-16px"
      />
      <el-form label-position="top"
        ><el-form-item label="员工用户ID（系统带入）"
          ><el-input v-model="assignForm.userId" disabled /></el-form-item
        ><el-form-item label="岗位"
          ><el-select v-model="assignForm.roleCode" class="w-full"
            ><el-option
              v-for="item in roleOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value" /></el-select></el-form-item
        ><el-form-item label="部门"
          ><el-select v-model="assignForm.teamId" clearable class="w-full"
            ><el-option
              v-for="team in teams"
              :key="team.deptId"
              :label="team.name"
              :value="team.deptId" /></el-select></el-form-item
      ></el-form>
      <template #footer
        ><el-button @click="assignDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveAssign"
          >追加岗位</el-button
        ></template
      >
    </el-dialog>

    <el-dialog v-model="handoverDialog" title="离职交接" width="500px">
      <el-form label-position="top"
        ><el-form-item label="离职员工"
          ><el-input
            :model-value="handoverForm.fromName || String(handoverForm.fromUserId)"
            disabled /></el-form-item
        ><el-form-item label="接收人"
          ><el-select v-model="handoverForm.toUserId" class="w-full"
            ><el-option
              v-for="person in receivers"
              :key="person.userId"
              :label="`${person.name} · ${roleLabel(person.roleCode)}`"
              :value="person.userId" /></el-select></el-form-item
      ></el-form>
      <template #footer
        ><el-button @click="handoverDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="saveHandover"
          >确认交接并撤权</el-button
        ></template
      >
    </el-dialog>

    <el-dialog v-model="resultDialog" title="交接结果" width="680px">
      <el-alert
        :title="resultTitle"
        :type="resultHasUnknown ? 'warning' : 'success'"
        show-icon
        class="mb-16px"
      />
      <el-table :data="handoverResult?.items || []"
        ><el-table-column prop="label" label="业务域" /><el-table-column
          prop="inFlight"
          label="在途"
        /><el-table-column prop="transferred" label="已转移" /><el-table-column label="结果"
          ><template #default="{ row }"
            ><el-tag :type="statusMeta[row.status]?.type">{{
              statusMeta[row.status]?.label || row.status
            }}</el-tag></template
          ></el-table-column
        ></el-table
      >
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import { usePermissionStore } from '@/store/modules/permission'
import * as api from '@/api/platform/staff'
import type { HandoverResult, PendingRow, PostTemplate, StaffRow, Team } from '@/api/platform/staff'
import { isCurrentGeneration, postGeneration, type PlatformId } from '@/utils/platformIdentity'
import { roleLabel } from '@/utils/platformRoles'

defineOptions({ name: 'FranchiseOrgStaff' })
const identity = usePlatformIdentityStore()
const permissions = usePermissionStore()
const router = useRouter()
const currentPost = computed(() => identity.currentPost)
const canQuery = computed(() => identity.can('platform:org:query'))
const canManage = computed(() => identity.can('platform:org:manage'))
const loading = ref(false),
  saving = ref(false)
const loadError = ref('')
const actionError = ref('')
const teams = ref<Team[]>([]),
  postTemplates = ref<PostTemplate[]>([]),
  staff = ref<StaffRow[]>([]),
  pending = ref<PendingRow[]>([])
const staffPage = ref(1)
const pageSize = 20
const pagedStaff = computed(() =>
  staff.value.slice((staffPage.value - 1) * pageSize, staffPage.value * pageSize)
)
const teamDialog = ref(false),
  staffDialog = ref(false),
  assignDialog = ref(false),
  handoverDialog = ref(false),
  resultDialog = ref(false)
const teamForm = reactive({ name: '', line: '' })
const staffForm = reactive({
  name: '',
  mobile: '',
  roleCode: '',
  teamId: undefined as PlatformId | undefined
})
const assignForm = reactive({
  userId: '',
  roleCode: '',
  teamId: undefined as PlatformId | undefined
})
const handoverForm = reactive({
  fromUserId: '' as PlatformId,
  fromName: '',
  toUserId: undefined as PlatformId | undefined
})
const handoverResult = ref<HandoverResult | null>(null)
let loadGeneration = 0

const roleOptions = computed(() =>
  postTemplates.value.map((template) => ({
    value: template.roleCode,
    label: roleLabel(template.roleCode)
  }))
)
const receivers = computed(() =>
  Array.from(
    new Map(
      staff.value
        .filter((row) => String(row.userId) !== String(handoverForm.fromUserId) && row.active)
        .map((row) => [String(row.userId), row])
    ).values()
  )
)
const statusMeta: Record<
  string,
  { label: string; type: 'success' | 'warning' | 'danger' | 'info' }
> = {
  HANDED_OVER: { label: '已交接', type: 'success' },
  NONE: { label: '无在途事项', type: 'info' },
  PENDING_UNASSIGNED: { label: '已转待分配', type: 'warning' },
  NOT_INTEGRATED: { label: '业务域未接入', type: 'warning' },
  SCAN_FAILED: { label: '扫描失败', type: 'danger' }
}
const resultHasUnknown = computed(
  () =>
    handoverResult.value?.items.some((item) =>
      ['PENDING_UNASSIGNED', 'NOT_INTEGRATED', 'SCAN_FAILED'].includes(item.status)
    ) || false
)
const resultTitle = computed(() =>
  resultHasUnknown.value ? '人员权限已处理，部分业务事项仍需人工核对。' : '交接与撤权已完成。'
)
const orgId = () => {
  if (!currentPost.value) throw new Error('当前工作身份已失效')
  return currentPost.value.orgId
}

const load = async () => {
  if (!canQuery.value) return
  const generation = ++loadGeneration
  loading.value = true
  loadError.value = ''
  try {
    const [teamRows, templateRows, staffRows, pendingRows] = await Promise.all([
      api.getTeams(orgId()),
      api.getPostTemplates(orgId()),
      api.getStaff(orgId()),
      api.getHandoverPending(orgId())
    ])
    if (generation !== loadGeneration) return
    teams.value = teamRows
    postTemplates.value = templateRows
    staff.value = staffRows
    pending.value = pendingRows
    staffPage.value = 1
  } catch (error) {
    if (generation === loadGeneration && (error as Error)?.message !== 'STALE_POST_RESPONSE') {
      loadError.value = '组织人员数据加载失败，请检查当前工作身份后重试。'
    }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
const resetTransientState = () => {
  teamDialog.value = false
  staffDialog.value = false
  assignDialog.value = false
  handoverDialog.value = false
  resultDialog.value = false
  handoverResult.value = null
  actionError.value = ''
  loadError.value = ''
  saving.value = false
  Object.assign(teamForm, { name: '', line: '' })
  Object.assign(staffForm, { name: '', mobile: '', roleCode: '', teamId: undefined })
  Object.assign(assignForm, { userId: '', roleCode: '', teamId: undefined })
  Object.assign(handoverForm, { fromUserId: '', fromName: '', toUserId: undefined })
  staffPage.value = 1
}
const switchPost = async (postId: PlatformId) => {
  ++loadGeneration
  resetTransientState()
  teams.value = []
  postTemplates.value = []
  staff.value = []
  pending.value = []
  await identity.selectPost(postId)
  await permissions.generateRoutes()
  if (!identity.can('platform:org:query')) return router.replace('/fr/identity')
  await load()
}
const openTeam = () => {
  Object.assign(teamForm, { name: '', line: '' })
  teamDialog.value = true
}
const openStaff = () => {
  Object.assign(staffForm, {
    name: '',
    mobile: '',
    roleCode: '',
    teamId: undefined
  })
  staffDialog.value = true
}
const openAssign = (row: StaffRow) => {
  Object.assign(assignForm, {
    userId: String(row.userId),
    roleCode: '',
    teamId: row?.teamId || undefined
  })
  assignDialog.value = true
}
const openHandover = (row: StaffRow) => {
  Object.assign(handoverForm, { fromUserId: row.userId, fromName: row.name, toUserId: undefined })
  handoverDialog.value = true
}
const openPendingHandover = (row: PendingRow) => {
  Object.assign(handoverForm, {
    fromUserId: row.fromUserId,
    fromName: `用户 ${row.fromUserId}`,
    toUserId: undefined
  })
  handoverDialog.value = true
}
const actionScope = () => ({ generation: postGeneration(), orgId: orgId() })
const confirmed = (message: string, title: string) =>
  ElMessageBox.confirm(message, title, { type: 'warning' })
    .then(() => true)
    .catch(() => false)
const saveTeam = async () => {
  if (!teamForm.name.trim()) return ElMessage.warning('请填写部门名称')
  const scope = actionScope()
  await run(scope, async () => {
    await api.createTeam({ orgId: scope.orgId, ...teamForm })
    teamDialog.value = false
  })
}
const saveStaff = async () => {
  if (!staffForm.name || !/^1\d{10}$/.test(staffForm.mobile) || !staffForm.roleCode)
    return ElMessage.warning('请完整填写员工信息')
  const scope = actionScope()
  await run(scope, async () => {
    await api.createStaff({ orgId: scope.orgId, ...staffForm })
    staffDialog.value = false
  })
}
const saveAssign = async () => {
  if (!/^\d+$/.test(assignForm.userId) || !assignForm.roleCode)
    return ElMessage.warning('请填写正确的员工用户ID和岗位')
  const scope = actionScope()
  await run(scope, async () => {
    await api.assignPost({ orgId: scope.orgId, ...assignForm })
    assignDialog.value = false
  })
}
const saveHandover = async () => {
  if (!handoverForm.toUserId) return ElMessage.warning('请选择接收人')
  const scope = actionScope()
  await run(scope, async () => {
    handoverResult.value = await api.handoverStaff({
      orgId: scope.orgId,
      fromUserId: handoverForm.fromUserId,
      toUserId: handoverForm.toUserId!
    })
    handoverDialog.value = false
    resultDialog.value = true
  })
}
const revoke = async (row: StaffRow) => {
  const scope = actionScope()
  if (!(await confirmed(`仅撤销 ${row.name} 的“${roleLabel(row.roleCode)}”任职？`, '撤销岗位')))
    return
  await run(scope, () => api.revokePost(row.postId))
}
const stopInOrg = async (row: StaffRow) => {
  const scope = actionScope()
  if (!(await confirmed(`将撤销 ${row.name} 在本组织的全部任职，其他组织不受影响。`, '本组织停职')))
    return
  await run(scope, () => api.setStaffStatus(row.userId, false))
}
const emergency = async (row: StaffRow) => {
  const scope = actionScope()
  if (
    !(await confirmed('将立即撤销本组织权限，再扫描在途事项。扫描失败不代表撤权失败。', '紧急撤权'))
  )
    return
  await run(scope, async () => {
    handoverResult.value = await api.emergencyRevokeStaff({
      orgId: scope.orgId,
      userId: row.userId
    })
    resultDialog.value = true
  })
}
const run = async (
  scope: { generation: number; orgId: PlatformId },
  action: () => Promise<unknown>
) => {
  if (saving.value) return
  actionError.value = ''
  if (
    !isCurrentGeneration(scope.generation) ||
    String(currentPost.value?.orgId) !== String(scope.orgId)
  )
    return
  saving.value = true
  try {
    await action()
    if (!isCurrentGeneration(scope.generation)) return
    ElMessage.success('操作完成')
    await load()
  } catch (error) {
    if ((error as Error)?.message !== 'STALE_POST_RESPONSE')
      actionError.value = '操作失败，服务器未确认本次变更，请核对后重试。'
  } finally {
    if (isCurrentGeneration(scope.generation)) saving.value = false
  }
}

onMounted(async () => {
  if (!identity.context) await identity.restoreContext()
  await load()
})
</script>

<style scoped lang="scss">
.identity-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 18px 0;
}

.summary-card {
  display: flex;
  padding: 20px;
  color: #76695f;
  background: #fff;
  border: 1px solid #eee4da;
  border-radius: 12px;
  justify-content: space-between;
  align-items: center;
}

.summary-card strong {
  font-size: 28px;
  color: #8d4f24;
}

.toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 16px;
}

.zs-table-card + .zs-table-card {
  margin-top: 16px;
}

.team-list .el-tag {
  margin: 0 10px 10px 0;
}

.staff-pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

h2 {
  margin: 0 0 16px;
  font-size: 18px;
  color: #4d301e;
}

@media (width <= 760px) {
  .identity-header {
    align-items: flex-start;
    gap: 12px;
    flex-direction: column;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .toolbar {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
