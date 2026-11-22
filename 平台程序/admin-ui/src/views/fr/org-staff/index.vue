<template>
  <div v-loading="identity.loading" class="zs-page">
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
        :disabled="saving"
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

    <el-alert v-if="switchError" :title="switchError" type="error" show-icon class="mb-16px">
      <template #default>
        <el-button v-if="pendingPostId" link type="primary" @click="retrySwitch"
          >重试切换</el-button
        >
      </template>
    </el-alert>
    <el-alert
      v-else-if="!canQuery"
      title="当前工作身份没有组织人员查看权限"
      type="warning"
      show-icon
    />
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
          <el-button :disabled="saving || !postTemplates.length || !canImportStaff" @click="openImport"
            >批量导入</el-button
          >
          <span v-if="isStoreOrg && !canImportStaff" class="hint"
            >员工账号导入由门店老板或负责人办理</span
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
              <el-button v-if="!hasReservedRole(row.userId)" link @click="openHandover(row)">离职交接</el-button>
              <el-button v-if="!hasReservedRole(row.userId)" link type="warning" @click="emergency(row)">紧急撤权</el-button>
              <el-button v-if="!isReservedRole(row)" link type="danger" @click="revoke(row)">撤销本岗位</el-button>
              <el-button v-if="!hasReservedRole(row.userId)" link type="danger" @click="stopInOrg(row)">本组织停职</el-button>
              <el-text v-if="hasReservedRole(row.userId)" type="info" size="small">{{ reservedRoleReason }}</el-text>
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
        <el-table :data="pending" empty-text="暂无待分配事项">
          <el-table-column prop="domainLabel" label="业务域" />
          <el-table-column prop="fromUserId" label="原负责人ID" />
          <el-table-column label="待分配数量">
            <template #default="{ row }">{{ row.inFlight < 0 ? '数量待核' : row.inFlight }}</template>
          </el-table-column>
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

      <div v-if="canManage" class="zs-table-card">
        <h2>查找原员工补交接</h2>
        <p>按姓名、手机号或员工编号查找本组织有任职记录的人员，补交接会重新核验其在途事项。</p>
        <div class="toolbar">
          <el-input
            v-model="historyQuery"
            placeholder="姓名、手机号或员工编号"
            maxlength="64"
            clearable
            @input="invalidateHandoverHistory"
            @keyup.enter="searchHandoverHistory()"
          />
          <el-button :loading="historyBusy" @click="searchHandoverHistory()">查找</el-button>
        </div>
        <el-alert v-if="historyError" :title="historyError" type="warning" show-icon />
        <el-table
          v-if="historyPage > 0"
          :data="historyRows"
          empty-text="未找到本组织有任职记录的人员"
        >
          <el-table-column prop="name" label="员工" />
          <el-table-column prop="mobile" label="手机号" />
          <el-table-column prop="userId" label="员工编号" />
          <el-table-column label="操作">
            <template #default="{ row }">
              <el-text v-if="hasReservedRole(row.userId)" type="info" size="small">{{ reservedRoleReason }}</el-text>
              <el-button v-else link type="primary" @click="openHistoricalHandover(row)">补交接</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button v-if="historyHasMore" :loading="historyBusy" @click="searchHandoverHistory(true)">加载更多</el-button>
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

    <el-dialog
      v-model="staffDialog"
      :title="isStoreOrg ? '开通员工独立账号' : '录入员工 / 恢复任职'"
      width="520px"
    >
      <el-alert
        :title="
          isStoreOrg
            ? '总部只开通门店老板账号；此处由门店老板为每位员工开通独立账号。原员工请填写原手机号复用账号。'
            : '停职员工请填写原手机号；系统会复用已有账号并重新指派本组织岗位。'
        "
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
        ><el-button type="primary" :loading="saving" @click="saveStaff">{{
          isStoreOrg ? '开通员工账号' : '保存'
        }}</el-button></template
      >
    </el-dialog>

    <el-dialog
      v-model="staffAccountDialog"
      title="员工账号交付"
      width="620px"
      :close-on-click-modal="false"
      @closed="clearStaffAccountCredential"
    >
      <template v-if="staffAccountResult">
        <el-alert
          :title="
            staffAccountResult.reusedUser
              ? '已复用该员工原账号，密码保持不变。'
              : '员工独立账号已开通，初始密码仅在本次显示，48小时内登录并改密；微信登录后初始密码失效。'
          "
          :type="staffAccountResult.reusedUser ? 'info' : 'warning'"
          show-icon
          :closable="false"
          class="mb-16px"
        />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="员工">{{ staffAccountResult.name }}</el-descriptions-item>
          <el-descriptions-item label="所属门店">{{
            staffAccountResult.orgName
          }}</el-descriptions-item>
          <el-descriptions-item label="登录账号">{{
            staffAccountResult.loginName
          }}</el-descriptions-item>
          <el-descriptions-item label="快捷查找">{{
            staffAccountResult.aliases.join('、') || '使用完整账号'
          }}</el-descriptions-item>
          <el-descriptions-item label="密码">
            <template v-if="staffAccountResult.reusedUser">沿用原账号密码</template>
            <strong v-else class="credential-password">{{
              staffAccountResult.initialPassword
            }}</strong>
          </el-descriptions-item>
        </el-descriptions>
        <el-input
          v-if="staffManualCopyVisible"
          v-model="staffCredentialCopyText"
          type="textarea"
          :rows="7"
          readonly
          class="mt-16px"
        />
      </template>
      <template #footer>
        <el-button @click="staffAccountDialog = false">关闭</el-button>
        <el-button type="primary" @click="copyStaffCredential">一键复制员工账号</el-button>
      </template>
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
        :type="resultHasUnknown ? 'warning' : resultHasShared ? 'info' : 'success'"
        show-icon
        class="mb-16px"
      />
      <el-table :data="handoverResult?.items || []"
        ><el-table-column prop="label" label="业务域" /><el-table-column label="需承接"
          ><template #default="{ row }">{{ row.status === 'ORGANIZATION_SHARED' ? '—' : row.inFlight < 0 ? '数量待核' : row.inFlight }}</template
        ></el-table-column><el-table-column label="已转移"
          ><template #default="{ row }">{{ row.status === 'ORGANIZATION_SHARED' ? '—' : row.transferred }}</template
        ></el-table-column><el-table-column label="结果" min-width="270"
          ><template #default="{ row }"
            ><el-tag :type="statusMeta[row.status]?.type">{{
              statusMeta[row.status]?.label || row.status
            }}</el-tag></template
          ></el-table-column
        ></el-table
      >
    </el-dialog>

    <el-dialog
      v-model="importDialog"
      title="员工批量导入"
      width="880px"
      :close-on-click-modal="!importBusy"
      :close-on-press-escape="!importBusy"
      :show-close="!importBusy"
      @closed="resetImport"
    >
      <div class="import-toolbar">
        <el-button @click="downloadImportTemplate">下载模板（CSV）</el-button>
        <el-button :disabled="importBusy" @click="importFileInput?.click()"
          >选择 CSV 文件</el-button
        >
        <input
          ref="importFileInput"
          type="file"
          accept=".csv,text/csv"
          class="import-file"
          @change="onImportFile"
        />
        <span v-if="importFileName" class="import-file-name">{{ importFileName }}</span>
      </div>
      <div class="hint">
        模板列：姓名 / 手机 / 角色码 / 团队名；单批 1-{{ STAFF_IMPORT_MAX_ROWS }} 行（文件上限
        {{ STAFF_IMPORT_MAX_FILE_BYTES / 1024 / 1024 }}MB）；行号为数据行（表头不算，从 1 开始）。
      </div>
      <el-alert
        :title="
          isStoreOrg
            ? '门店导入与逐个开户使用相同账号规则。新用户交付一次性初始密码，已有用户沿用原密码；也可使用小程序官方微信手机号授权登录。'
            : '本组织导入只建立人员与岗位，不生成门店密码账号。员工使用平台已开通的微信或短信登录方式。'
        "
        type="info"
        show-icon
        :closable="false"
        class="import-error"
      />
      <el-alert
        v-if="importNotice"
        :title="importNotice"
        type="warning"
        show-icon
        class="import-error"
      />
      <el-alert
        v-for="error in importParseErrors"
        :key="error"
        :title="error"
        type="error"
        show-icon
        class="import-error"
      />
      <el-table v-if="importRows.length" :data="importRows" max-height="240" size="small">
        <el-table-column prop="line" label="行号" width="70" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="mobile" label="手机" width="130" />
        <el-table-column prop="roleCode" label="角色码" width="110" />
        <el-table-column prop="teamName" label="团队名" min-width="120" />
        <el-table-column prop="error" label="解析提示" min-width="220" />
      </el-table>
      <div v-if="importRows.length" class="hint">
        共 {{ importRows.length }} 行；带解析提示的行需修正后重新上传。
      </div>

      <template v-if="importPrecheckResult">
        <div class="import-summary">
          预检结果：共 {{ importPrecheckResult.total }} 行，可创建
          {{ importPrecheckResult.created }}，跳过 {{ importPrecheckResult.skipped }}，失败
          {{ importPrecheckResult.failed }}（提交时服务端会逐行重新校验）
        </div>
        <el-table :data="importPrecheckResult.rows" max-height="320" size="small">
          <el-table-column prop="line" label="行号" width="70" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="mobile" label="手机" width="125" />
          <el-table-column prop="roleCode" label="角色码" width="105" />
          <el-table-column prop="teamName" label="团队名" min-width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="importStatusMeta[row.status]?.type || 'info'">
                {{ importStatusMeta[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              {{ row.message || row.note || '' }}
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="importSubmitResult">
        <el-alert
          :title="`提交完成：成功 ${importSubmitResult.created + importSubmitResult.skipped}（新建 ${
            importSubmitResult.created
          } / 跳过 ${importSubmitResult.skipped}），失败 ${importSubmitResult.failed}`"
          :type="importSubmitResult.failed ? 'warning' : 'success'"
          show-icon
          class="import-error"
        />
        <el-table :data="importSubmitResult.rows" max-height="320" size="small">
          <el-table-column prop="line" label="行号" width="70" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="mobile" label="手机" width="125" />
          <el-table-column prop="roleCode" label="角色码" width="105" />
          <el-table-column prop="teamName" label="团队名" min-width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="importStatusMeta[row.status]?.type || 'info'">
                {{ importStatusMeta[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              {{ row.message || row.note || '' }}
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="importAccounts.length">
        <h3>员工账号交付（含本批此前成功行）</h3>
        <el-alert
          title="初始密码仅本次显示，48小时内登录并改密；微信登录后初始密码失效。重提失败行会保留已成功账号，关闭、离页、换身份或换文件会清除本页凭据。若未收到或未保存初始密码，请让员工使用小程序官方微信手机号授权登录，或联系总部核验后重置；重复导入不会重新发放密码。"
          type="warning"
          show-icon
          :closable="false"
          class="import-error"
        />
        <el-table :data="importAccounts" max-height="320" size="small">
          <el-table-column prop="name" label="员工" width="100" />
          <el-table-column prop="orgName" label="所属门店" min-width="160" />
          <el-table-column prop="loginName" label="登录账号" min-width="120" />
          <el-table-column label="密码 / 登录方式" min-width="220">
            <template #default="{ row }">
              <strong v-if="row.initialPassword" class="credential-password">{{
                row.initialPassword
              }}</strong>
              <span v-else>沿用原密码；也可官方微信手机号授权登录</span>
            </template>
          </el-table-column>
          <el-table-column label="交付" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="copyImportCredential(row)">复制账号</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-input
          v-if="importManualCopyVisible"
          v-model="importCredentialCopyText"
          type="textarea"
          :rows="8"
          readonly
          class="mt-16px"
        />
      </template>

      <template #footer>
        <el-button :disabled="importBusy" @click="importDialog = false">关闭</el-button>
        <el-button :disabled="!canPrecheckImport" :loading="importBusy" @click="runImportPrecheck"
          >预检</el-button
        >
        <el-button
          type="primary"
          :disabled="!canSubmitImport"
          :loading="importBusy"
          @click="submitImport"
          >确认提交</el-button
        >
        <el-button
          v-if="importSubmitResult && importSubmitResult.failed > 0"
          :disabled="importBusy"
          @click="retryFailedImport"
          >重提失败行</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePlatformIdentityStore } from '@/store/modules/platformIdentity'
import { usePermissionStore } from '@/store/modules/permission'
import * as api from '@/api/platform/staff'
import type {
  HandoverResult,
  HandoverSource,
  PendingRow,
  PostTemplate,
  StaffAccountResult,
  StaffImportAccountDelivery,
  StaffImportResult,
  StaffRow,
  Team
} from '@/api/platform/staff'
import { buildStaffCredentialCopyText, isRestrictedOwnerRole } from './staffAccountDelivery'
import {
  STAFF_IMPORT_MAX_FILE_BYTES,
  STAFF_IMPORT_MAX_ROWS,
  buildStaffImportTemplateCsv,
  createImportGenerationGuard,
  failedRowsForRetry,
  parseStaffImportCsv,
  runGuardedImportRequest,
  withinFileSizeLimit,
  withinImportLimit,
  type StaffImportRow
} from './importModel'
import { isCurrentGeneration, postGeneration, type PlatformId } from '@/utils/platformIdentity'
import { roleLabel } from '@/utils/platformRoles'
import { generateUUID } from '@/utils'

defineOptions({ name: 'FranchiseOrgStaff' })
const identity = usePlatformIdentityStore()
const permissions = usePermissionStore()
const router = useRouter()
const currentPost = computed(() => identity.currentPost)
const isStoreOrg = computed(() => currentPost.value?.orgType === 'store')
const canQuery = computed(() => identity.can('platform:org:query'))
const canManage = computed(() => identity.can('platform:org:manage'))
const canImportStaff = computed(
  () => canManage.value && (!isStoreOrg.value || isRestrictedOwnerRole(currentPost.value?.roleCode || ''))
)
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
const reservedRoleReason = '老板和门店负责人身份由总部维护，请联系总部办理交接或撤权'
const isReservedRole = (row: StaffRow) => isStoreOrg.value && isRestrictedOwnerRole(row.roleCode)
const hasReservedRole = (userId: PlatformId) =>
  staff.value.some((row) => String(row.userId) === String(userId) && isReservedRole(row))
const blockReservedRemoval = (blocked: boolean) => {
  if (blocked) actionError.value = reservedRoleReason
  return blocked
}
const teamDialog = ref(false),
  staffDialog = ref(false),
  assignDialog = ref(false),
  handoverDialog = ref(false),
  resultDialog = ref(false),
  importDialog = ref(false)
const staffAccountDialog = ref(false)
const staffAccountResult = ref<StaffAccountResult | null>(null)
const staffCredentialCopyText = ref('')
const staffManualCopyVisible = ref(false)
const staffPageActive = ref(true)
const importFileInput = ref<HTMLInputElement>()
const importBusy = ref(false)
const importSubmitting = ref(false)
const importFileName = ref('')
const importRows = ref<StaffImportRow[]>([])
const importParseErrors = ref<string[]>([])
const importPrecheckResult = ref<StaffImportResult | null>(null)
const importSubmitResult = ref<StaffImportResult | null>(null)
const importNotice = ref('')
const importAccounts = ref<StaffImportAccountDelivery[]>([])
const importCredentialCopyText = ref('')
const importManualCopyVisible = ref(false)
const importStatusMeta: Record<
  string,
  { label: string; type: 'success' | 'warning' | 'danger' | 'info' }
> = {
  OK: { label: '可创建', type: 'success' },
  CREATED: { label: '已创建', type: 'success' },
  SKIPPED: { label: '任职已存在', type: 'info' },
  FAILED: { label: '失败', type: 'danger' }
}
const teamForm = reactive({ name: '', line: '' })
let teamRequest = { fingerprint: '', key: '' }
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
const historyQuery = ref('')
const historyRows = ref<HandoverSource[]>([])
const historyPage = ref(0)
const historyHasMore = ref(false)
const historyBusy = ref(false)
const historyError = ref('')
let historyGeneration = 0
let loadGeneration = 0
const importGuard = createImportGenerationGuard()
let staffAccountGeneration = 0

const roleOptions = computed(() =>
  postTemplates.value
    .filter((template) => !isRestrictedOwnerRole(template.roleCode))
    .map((template) => ({
      value: template.roleCode,
      label: roleLabel(template.roleCode)
    }))
)
const importRowsReady = computed(
  () =>
    importRows.value.length > 0 &&
    importParseErrors.value.length === 0 &&
    !importRows.value.some((row) => row.error)
)
const canPrecheckImport = computed(
  () => canImportStaff.value && !importBusy.value && importRowsReady.value
)
const canSubmitImport = computed(
  () =>
    canImportStaff.value &&
    !importBusy.value &&
    importRowsReady.value &&
    importPrecheckResult.value !== null
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
  ORGANIZATION_SHARED: { label: '组织共享事项，需财务岗继续处理', type: 'info' },
  PENDING_UNASSIGNED: { label: '已转待分配', type: 'warning' },
  NOT_INTEGRATED: { label: '业务域未接入', type: 'warning' },
  SCAN_FAILED: { label: '扫描失败（待核）', type: 'danger' }
}
const resultHasUnknown = computed(
  () =>
    handoverResult.value?.items.some((item) =>
      ['PENDING_UNASSIGNED', 'NOT_INTEGRATED', 'SCAN_FAILED'].includes(item.status)
    ) || false
)
const resultHasShared = computed(() =>
  handoverResult.value?.items.some((item) => item.status === 'ORGANIZATION_SHARED') || false
)
const resultTitle = computed(() =>
  resultHasUnknown.value
    ? '人员权限已处理，部分业务事项仍需人工核对。'
    : resultHasShared.value
      ? '本组织权限已处理；财务为组织共享事项，需财务岗继续处理。'
      : handoverResult.value?.mode === 'EMERGENCY'
        ? '本组织权限已撤销，未发现待交接事项。'
        : '交接与撤权已完成。'
)
const orgId = () => {
  if (!currentPost.value) throw new Error('当前工作身份已失效')
  return currentPost.value.orgId
}

const clearStaffAccountCredential = () => {
  staffAccountGeneration += 1
  if (staffAccountResult.value) staffAccountResult.value.initialPassword = null
  staffAccountResult.value = null
  staffCredentialCopyText.value = ''
  staffManualCopyVisible.value = false
  staffAccountDialog.value = false
}

const invalidateHandoverHistory = () => {
  ++historyGeneration
  historyRows.value = []
  historyPage.value = 0
  historyHasMore.value = false
  historyBusy.value = false
  historyError.value = ''
}
const resetHandoverHistory = () => {
  invalidateHandoverHistory()
  historyQuery.value = ''
}

const handleStaffRouteLeave = () => {
  if (saving.value || importSubmitting.value) {
    ElMessage.warning('员工账号开通或导入处理中，请稍候，完成后再离开页面')
    return false
  }
  clearStaffAccountCredential()
  resetHandoverHistory()
  resetImport()
  return true
}

const handleStaffDeactivated = () => {
  staffPageActive.value = false
  resetHandoverHistory()
  if (!saving.value) clearStaffAccountCredential()
  resetImport()
}

const handleStaffActivated = () => {
  staffPageActive.value = true
  if (staffAccountResult.value) staffAccountDialog.value = true
}

const copyStaffCredential = async () => {
  if (!staffAccountResult.value) return
  staffCredentialCopyText.value = buildStaffCredentialCopyText(staffAccountResult.value)
  try {
    await navigator.clipboard.writeText(staffCredentialCopyText.value)
    staffManualCopyVisible.value = false
    ElMessage.success('员工账号信息已复制')
  } catch {
    staffManualCopyVisible.value = true
    ElMessage.error('自动复制失败，请在下方文本框中手动选择复制')
  }
}

// ===== P1 批2：员工批量导入（预检→提交→失败行重提） =====

const clearImportCredentials = () => {
  for (const account of importAccounts.value) account.initialPassword = null
  for (const row of importSubmitResult.value?.rows || []) {
    if (row.account) row.account.initialPassword = null
  }
  importAccounts.value = []
  importCredentialCopyText.value = ''
  importManualCopyVisible.value = false
}

const keepImportAccounts = (result: StaffImportResult) => {
  for (const row of result.rows) {
    const account = row.account
    if (account && !importAccounts.value.some((saved) => saved.accountId === account.accountId)) {
      importAccounts.value.push({ ...account })
    }
  }
}

const copyImportCredential = async (account: StaffImportAccountDelivery) => {
  if (!importAccounts.value.includes(account)) return
  const generation = postGeneration()
  importCredentialCopyText.value = buildStaffCredentialCopyText(account)
  try {
    await navigator.clipboard.writeText(importCredentialCopyText.value)
    if (!isCurrentGeneration(generation) || !importAccounts.value.includes(account)) return
    importManualCopyVisible.value = false
    ElMessage.success('员工账号信息已复制')
  } catch {
    if (!isCurrentGeneration(generation) || !importAccounts.value.includes(account)) return
    importManualCopyVisible.value = true
    ElMessage.error('自动复制失败，请在下方文本框中手动选择复制')
  }
}

const resetImport = () => {
  importGuard.next()
  clearImportCredentials()
  importBusy.value = false
  importSubmitting.value = false
  importFileName.value = ''
  importRows.value = []
  importParseErrors.value = []
  importPrecheckResult.value = null
  importSubmitResult.value = null
  importNotice.value = ''
  if (importFileInput.value) importFileInput.value.value = ''
}
const openImport = () => {
  if (!canImportStaff.value) return
  resetImport()
  importDialog.value = true
}
const downloadImportTemplate = () => {
  const blob = new Blob([buildStaffImportTemplateCsv()], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '员工导入模板.csv'
  link.click()
  URL.revokeObjectURL(url)
}
const onImportFile = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || importBusy.value) return
  const generation = importGuard.next()
  importFileName.value = file.name
  importBusy.value = true
  importNotice.value = ''
  clearImportCredentials()
  // 换文件即清空旧批次：读取/超限失败保持全空，提交按钮因 precheck=null 而禁用。
  importRows.value = []
  importParseErrors.value = []
  importPrecheckResult.value = null
  importSubmitResult.value = null
  if (!withinFileSizeLimit(file.size)) {
    ElMessage.error(`文件超过大小上限（${STAFF_IMPORT_MAX_FILE_BYTES / 1024 / 1024}MB）`)
    importBusy.value = false
    input.value = ''
    return
  }
  try {
    const parsed = parseStaffImportCsv(await file.text())
    if (!importGuard.isCurrent(generation)) return
    importRows.value = parsed.rows
    importParseErrors.value = parsed.errors
    if (parsed.errors.length) ElMessage.error('CSV 解析失败，请检查表头与列数')
  } catch {
    if (importGuard.isCurrent(generation)) ElMessage.error('文件读取失败，请重试')
  } finally {
    input.value = ''
    if (importGuard.isCurrent(generation)) importBusy.value = false
  }
}
const importPayloadRows = () =>
  importRows.value.map(({ line, name, mobile, roleCode, teamName }) => ({
    line,
    name,
    mobile,
    roleCode,
    teamName
  }))
const runImportPrecheck = async () => {
  if (importBusy.value || !canImportStaff.value) return
  if (!importRows.value.length) {
    ElMessage.warning('请先选择包含数据的 CSV 文件')
    return
  }
  if (importParseErrors.value.length) {
    ElMessage.error('存在解析错误，请修正后重新上传')
    return
  }
  if (importRows.value.some((row) => row.error)) {
    ElMessage.error('存在重复行，请修正后重新上传')
    return
  }
  if (!withinImportLimit(importRows.value)) {
    ElMessage.error(`单批须为 1-${STAFF_IMPORT_MAX_ROWS} 行，当前 ${importRows.value.length} 行`)
    return
  }
  const generation = importGuard.next()
  importBusy.value = true
  try {
    // P2-6：代次校验与写回在同一同步回合（apply），await 之后不再赋值。
    await runGuardedImportRequest(
      () =>
        api.precheckStaffImport({
          orgId: orgId(),
          rows: importPayloadRows()
        }),
      generation,
      importGuard.isCurrent,
      (result) => {
        importPrecheckResult.value = result
        importSubmitResult.value = null
        importBusy.value = false
      }
    )
  } catch {
    if (importGuard.isCurrent(generation)) {
      ElMessage.error('预检失败，请检查网络或当前工作身份后重试')
    }
  } finally {
    if (importGuard.isCurrent(generation)) importBusy.value = false
  }
}
const submitImport = async () => {
  if (importBusy.value || !canImportStaff.value) return
  if (!importPrecheckResult.value) {
    ElMessage.warning('请先完成预检')
    return
  }
  const generation = importGuard.next()
  importBusy.value = true
  importSubmitting.value = true
  try {
    // P2-6：代次校验与写回同一同步回合；提交结果/提示在 apply 内完成。
    await runGuardedImportRequest(
      () =>
        api.submitStaffImport({
          orgId: orgId(),
          rows: importPayloadRows()
        }),
      generation,
      importGuard.isCurrent,
      (value) => {
        keepImportAccounts(value)
        importSubmitResult.value = value
        importPrecheckResult.value = null
        importBusy.value = false
        importSubmitting.value = false
        if (value.failed === 0) {
          ElMessage.success(`导入完成：成功 ${value.created + value.skipped} 行`)
        } else {
          ElMessage.warning(
            `部分成功：成功 ${value.created + value.skipped} 行，失败 ${value.failed} 行`
          )
        }
      }
    )
  } catch {
    if (importGuard.isCurrent(generation)) {
      ElMessage.error('提交失败，请稍后重试')
      importNotice.value =
        '若服务端已完成部分开户但未收到结果，重试会复用账号且不会重新发放初始密码；员工可使用官方微信手机号授权登录，或联系总部核验后重置。'
      importSubmitting.value = false
    }
    if (importGuard.isCurrent(generation)) importBusy.value = false
    return
  }
  // N-P2-4：刷新列表失败单独提示，不影响提交结果；提交本身的错误提示保持不变。
  if (importSubmitResult.value && importSubmitResult.value.created > 0) {
    try {
      await load()
    } catch {
      loadError.value = '组织人员数据加载失败，请检查当前工作身份后重试。'
    }
    if (loadError.value) {
      importNotice.value = '导入已提交，列表刷新失败，请手动刷新'
      ElMessage.warning(importNotice.value)
    }
  }
}
const retryFailedImport = async () => {
  if (!importSubmitResult.value) return
  const failed = failedRowsForRetry(importSubmitResult.value.rows)
  if (!failed.length) return
  importRows.value = failed
  importSubmitResult.value = null
  importPrecheckResult.value = null
  await runImportPrecheck()
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
const switchError = ref('')
const pendingPostId = ref<PlatformId>()
const resetTransientState = () => {
  clearStaffAccountCredential()
  teamDialog.value = false
  staffDialog.value = false
  assignDialog.value = false
  handoverDialog.value = false
  resultDialog.value = false
  importDialog.value = false
  resetImport()
  resetHandoverHistory()
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
  if (saving.value) {
    ElMessage.warning('员工账号开通处理中，请稍候后再切换工作身份')
    return
  }
  switchError.value = ''
  pendingPostId.value = postId
  try {
    const context = await identity.selectPost(postId)
    if (!context) return
  } catch {
    switchError.value = identity.error || '工作身份切换失败，请检查网络后重试。'
    return
  }
  ++loadGeneration
  resetTransientState()
  teams.value = []
  postTemplates.value = []
  staff.value = []
  pending.value = []
  pendingPostId.value = undefined
  try {
    await permissions.generateRoutes()
  } catch {
    switchError.value = '工作身份已切换，页面权限加载失败，请刷新后重试。'
    return
  }
  if (!identity.can('platform:org:query')) return router.replace('/fr/identity')
  await load()
}
const retrySwitch = () => pendingPostId.value && switchPost(pendingPostId.value)
const openTeam = () => {
  Object.assign(teamForm, { name: '', line: '' })
  teamDialog.value = true
}
const openStaff = () => {
  clearStaffAccountCredential()
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
  if (blockReservedRemoval(hasReservedRole(row.userId))) return
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
const openHistoricalHandover = (row: HandoverSource) => {
  if (!canManage.value || blockReservedRemoval(hasReservedRole(row.userId))) return
  Object.assign(handoverForm, { fromUserId: row.userId, fromName: row.name, toUserId: undefined })
  handoverDialog.value = true
}
const actionScope = () => ({ generation: postGeneration(), orgId: orgId() })
const searchHandoverHistory = async (more = false) => {
  if (!canManage.value || historyBusy.value) return
  const keyword = historyQuery.value.trim()
  if (!keyword) {
    historyError.value = '请输入姓名、手机号或员工编号'
    return
  }
  const scope = actionScope()
  const generation = ++historyGeneration
  const pageNo = more ? historyPage.value + 1 : 1
  const isCurrent = () =>
    generation === historyGeneration &&
    staffPageActive.value &&
    isCurrentGeneration(scope.generation) &&
    String(currentPost.value?.orgId) === String(scope.orgId)
  historyBusy.value = true
  historyError.value = ''
  if (!more) {
    historyRows.value = []
    historyPage.value = 0
    historyHasMore.value = false
  }
  try {
    const result = await api.getHandoverSources(scope.orgId, keyword, pageNo)
    if (!isCurrent()) return
    historyRows.value = more ? [...historyRows.value, ...result.items] : result.items
    historyHasMore.value = result.hasMore
    historyPage.value = pageNo
  } catch (error) {
    if (isCurrent() && (error as Error)?.message !== 'STALE_POST_RESPONSE')
      historyError.value = '历史任职查询失败，请重试'
  } finally {
    if (isCurrent()) historyBusy.value = false
  }
}
const confirmed = (message: string, title: string) =>
  ElMessageBox.confirm(message, title, { type: 'warning' })
    .then(() => true)
    .catch(() => false)
const saveTeam = async () => {
  if (!teamForm.name.trim()) return ElMessage.warning('请填写部门名称')
  const scope = actionScope()
  const payload = { orgId: scope.orgId, name: teamForm.name.trim(), line: teamForm.line.trim() }
  const fingerprint = JSON.stringify([scope.generation, payload])
  if (teamRequest.fingerprint !== fingerprint) {
    teamRequest = { fingerprint, key: generateUUID() }
  }
  const request = teamRequest
  await run(scope, async () => {
    await api.createTeam(payload, request.key)
    if (teamRequest === request) teamRequest = { fingerprint: '', key: '' }
    teamDialog.value = false
  })
}
const saveStaff = async () => {
  if (!staffForm.name || !/^1\d{10}$/.test(staffForm.mobile) || !staffForm.roleCode)
    return ElMessage.warning('请完整填写员工信息')
  const scope = actionScope()
  await run(scope, async () => {
    if (isStoreOrg.value) {
      const credentialGeneration = ++staffAccountGeneration
      const result = await api.createStaffAccount({ orgId: scope.orgId, ...staffForm })
      if (
        credentialGeneration !== staffAccountGeneration ||
        !isCurrentGeneration(scope.generation) ||
        String(currentPost.value?.orgId) !== String(scope.orgId)
      )
        return
      staffAccountResult.value = result
      staffCredentialCopyText.value = buildStaffCredentialCopyText(result)
      staffAccountDialog.value = staffPageActive.value
    } else {
      await api.createStaff({ orgId: scope.orgId, ...staffForm })
    }
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
  if (blockReservedRemoval(hasReservedRole(handoverForm.fromUserId))) return
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
  if (blockReservedRemoval(isReservedRole(row))) return
  const scope = actionScope()
  if (!(await confirmed(`仅撤销 ${row.name} 的“${roleLabel(row.roleCode)}”任职？`, '撤销岗位')))
    return
  await run(scope, async () => {
    try {
      await api.revokePost(row.postId)
    } catch (error) {
      if (isCurrentGeneration(scope.generation)) await load()
      throw error
    }
  }, '请按错误提示处理，并核对最新任职与待分配清单；如岗位已撤销，可查找原员工补交接。')
}
const stopInOrg = async (row: StaffRow) => {
  if (blockReservedRemoval(hasReservedRole(row.userId))) return
  const scope = actionScope()
  if (!(await confirmed(`将立即撤销 ${row.name} 在本组织的全部任职，并生成待交接清单。请继续处理待分配或待核事项。`, '本组织停职')))
    return
  if (blockReservedRemoval(hasReservedRole(row.userId))) return
  await run(scope, async () => {
    handoverResult.value = await api.emergencyRevokeStaff({ orgId: scope.orgId, userId: row.userId })
    resultDialog.value = true
  })
}
const emergency = async (row: StaffRow) => {
  if (blockReservedRemoval(hasReservedRole(row.userId))) return
  const scope = actionScope()
  if (
    !(await confirmed('将立即撤销本组织权限，再扫描在途事项。扫描失败不代表撤权失败。', '紧急撤权'))
  )
    return
  if (blockReservedRemoval(hasReservedRole(row.userId))) return
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
  action: () => Promise<unknown>,
  errorMessage = '操作失败，服务器未确认本次变更，请核对后重试。'
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
      actionError.value = errorMessage
  } finally {
    if (isCurrentGeneration(scope.generation)) saving.value = false
  }
}

onMounted(async () => {
  if (!identity.context) await identity.restoreContext()
  await load()
})
onDeactivated(handleStaffDeactivated)
onActivated(handleStaffActivated)
onBeforeRouteLeave(handleStaffRouteLeave)
onBeforeUnmount(handleStaffDeactivated)
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

.import-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.import-file {
  display: none;
}

.import-file-name {
  font-size: 12px;
  color: #8a7d72;
}

.hint {
  margin: 6px 0;
  font-size: 12px;
  color: #9b9b9b;
}

.import-error {
  margin-top: 8px;
}

.import-summary {
  margin: 12px 0 8px;
  font-size: 13px;
  color: #4d301e;
}

.credential-password {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  letter-spacing: 0.04em;
  color: #8d4f24;
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
