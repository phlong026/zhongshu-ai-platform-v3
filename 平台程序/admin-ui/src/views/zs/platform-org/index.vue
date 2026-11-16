<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">组织管理</h1>
      </div>
    </div>

    <div
      class="zs-table-card onboard-card"
      :class="{ 'onboard-card--directory': mode === 'directory' }"
    >
      <el-tabs v-model="mode" @tab-change="changeMode">
        <el-tab-pane label="已开通组织" name="directory" :disabled="submitting">
          <OrgDirectory v-if="mode === 'directory'" @create="mode = 'franchise'" />
        </el-tab-pane>
        <el-tab-pane label="加盟门店开通" name="franchise" :disabled="submitting">
          <el-form
            ref="franchiseFormRef"
            :model="franchiseForm"
            :rules="franchiseRules"
            label-width="126px"
            class="onboard-form"
          >
            <el-form-item label="所属品牌" prop="brand">
              <el-input v-model="franchiseForm.brand" disabled />
            </el-form-item>
            <el-form-item label="省市" prop="areaPath">
              <el-cascader
                v-model="franchiseForm.areaPath"
                :options="areaTree"
                :props="areaProps"
                :loading="areaLoading"
                :disabled="submitting"
                filterable
                clearable
                placeholder="请选择省、市"
                style="width: 100%"
              />
              <div v-if="areaError" class="field-error">地区加载失败，请刷新页面重试</div>
            </el-form-item>
            <el-form-item label="区县 / 门店片区" prop="area">
              <el-input
                v-model="franchiseForm.area"
                :disabled="submitting"
                maxlength="20"
                placeholder="如：高新"
                show-word-limit
              />
            </el-form-item>
            <el-form-item label="老板姓名" prop="adminName">
              <el-input
                v-model="franchiseForm.adminName"
                :disabled="submitting"
                maxlength="20"
                placeholder="请输入老板姓名"
              />
            </el-form-item>
            <el-form-item label="老板手机号" prop="adminMobile">
              <el-input
                v-model="franchiseForm.adminMobile"
                :disabled="submitting"
                maxlength="11"
                placeholder="请输入11位手机号"
              />
            </el-form-item>
            <el-form-item label="片区拼音校正">
              <el-input
                v-model="franchiseForm.areaPinyin"
                :disabled="submitting"
                maxlength="40"
                placeholder="留空自动生成；多音字可校正，如 gaoxin"
              />
            </el-form-item>
            <el-form-item label="姓名拼音校正">
              <el-input
                v-model="franchiseForm.ownerPinyin"
                :disabled="submitting"
                maxlength="40"
                placeholder="留空自动生成；多音字可校正，如 lizhong"
              />
            </el-form-item>
            <el-form-item label="内部备注">
              <el-input
                v-model="franchiseForm.remark"
                :disabled="submitting"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="仅总部内部留档，可不填"
              />
            </el-form-item>
            <el-form-item label="账号预览">
              <div class="preview-panel" :class="{ 'is-error': previewError }">
                <span v-if="previewLoading" class="preview-muted">正在生成账号预览…</span>
                <template v-else-if="preview">
                  <div
                    ><span>加盟商</span><strong>{{ preview.orgName }}</strong></div
                  >
                  <div
                    ><span>预计账号</span><strong>{{ preview.loginName }}</strong></div
                  >
                  <div
                    ><span>快捷查找</span><strong>{{ preview.aliases.join('、') }}</strong></div
                  >
                  <div
                    ><span>拼音结果</span
                    ><strong>{{ preview.areaPinyin }} / {{ preview.ownerPinyin }}</strong></div
                  >
                </template>
                <span v-else class="preview-muted">
                  {{ previewError || '填写省市、片区和老板姓名后自动生成' }}
                </span>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                class="zs-btn-primary"
                :loading="submitting"
                :disabled="submitting || previewLoading || !preview"
                @click="submitFranchise"
              >
                正式开通并生成账号
              </el-button>
            </el-form-item>
          </el-form>

          <div v-if="franchiseResult" class="credential-card">
            <div class="credential-heading">
              <div>
                <h2>开通完成</h2>
                <p>{{ franchiseResult.reusedOrg ? '已复用现有加盟组织' : '已创建加盟组织' }}</p>
              </div>
              <el-button type="primary" @click="copyCredentials">一键复制账号信息</el-button>
            </div>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="加盟商名称">{{
                franchiseResult.orgName
              }}</el-descriptions-item>
              <el-descriptions-item label="登录账号">{{
                franchiseResult.loginName
              }}</el-descriptions-item>
              <el-descriptions-item label="快捷查找">
                {{ franchiseResult.aliases.join('、') || '使用完整账号' }}
              </el-descriptions-item>
              <el-descriptions-item label="密码">
                <template v-if="franchiseResult.reusedUser">沿用该用户原账号密码</template>
                <template v-else>
                  <strong class="password-value">{{ franchiseResult.initialPassword }}</strong>
                  <span>（首次登录后必须修改）</span>
                </template>
              </el-descriptions-item>
            </el-descriptions>
            <el-alert
              v-if="!franchiseResult.reusedUser"
              title="初始密码仅在本页显示一次，请立即复制并安全发送给加盟商。离开页面后无法再次查看。"
              type="warning"
              :closable="false"
              show-icon
              class="result-alert"
            />
            <el-input
              v-if="manualCopyVisible"
              v-model="copyText"
              type="textarea"
              :rows="7"
              readonly
              class="manual-copy"
            />
            <el-alert v-if="!franchiseResult.reusedOrg" title="新组织尚未开通 AI 能力，请在“组织列表 → 详情 → 能力开通”中设置。" type="info" :closable="false" class="result-alert" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="其他组织开通" name="legacy" :disabled="submitting">
          <el-form
            ref="legacyFormRef"
            :model="legacyForm"
            :rules="legacyRules"
            label-width="120px"
            class="onboard-form compact"
          >
            <el-form-item label="组织类型" prop="orgType">
              <el-input model-value="总部" disabled />
            </el-form-item>
            <el-form-item label="组织名称" prop="orgName">
              <el-input v-model="legacyForm.orgName" placeholder="填写完整组织名称" />
            </el-form-item>
            <el-form-item label="首管理员姓名" prop="adminName">
              <el-input v-model="legacyForm.adminName" />
            </el-form-item>
            <el-form-item label="首管理员手机号" prop="adminMobile">
              <el-input v-model="legacyForm.adminMobile" maxlength="11" />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                class="zs-btn-primary"
                :loading="submitting"
                :disabled="submitting"
                @click="submitLegacy"
              >
                正式开通
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="legacyResultText"
            :title="legacyResultText"
            type="success"
            show-icon
            class="legacy-result"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { getAreaTree } from '@/api/system/area'
import OrgDirectory from './OrgDirectory.vue'
import {
  onboardFranchise,
  onboardOrg,
  previewFranchise,
  type FranchiseOnboardResult,
  type FranchisePreviewResult,
  type OnboardResult
} from '@/api/zs/platform-org'
import {
  buildCredentialCopyText,
  buildPreviewPayload,
  canLeaveFranchiseOnboard,
  createPreviewGenerationGuard,
  findAreaNames,
  resetOverrideWhenSourceChanges,
  toProvinceCityTree,
  type AreaNode
} from './franchiseAccountForm'

defineOptions({ name: 'PlatformOrgOnboard' })

const mode = ref('directory')
const submitting = ref(false)
const franchiseFormRef = ref<FormInstance>()
const legacyFormRef = ref<FormInstance>()
const areaTree = ref<AreaNode[]>([])
const areaLoading = ref(false)
const areaError = ref(false)
const preview = ref<FranchisePreviewResult | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const franchiseResult = ref<FranchiseOnboardResult | null>(null)
const legacyResultText = ref('')
const manualCopyVisible = ref(false)
const copyText = ref('')
const previewGuard = createPreviewGenerationGuard()
const submitGuard = createPreviewGenerationGuard()
let previewTimer: ReturnType<typeof setTimeout> | undefined

const areaProps = { label: 'name', value: 'id', children: 'children', emitPath: true }

const franchiseForm = reactive({
  brand: '合家美宅',
  areaPath: [] as number[],
  province: '',
  city: '',
  area: '',
  adminName: '',
  adminMobile: '',
  areaPinyin: '',
  ownerPinyin: '',
  remark: ''
})

const legacyForm = reactive({
  orgType: 'headquarters',
  orgName: '',
  adminName: '',
  adminMobile: ''
})

const franchiseRules: FormRules = {
  brand: [{ required: true, message: '请选择所属品牌', trigger: 'blur' }],
  areaPath: [
    {
      validator: (_rule, value: number[], callback) =>
        value?.length === 2 ? callback() : callback(new Error('请选择省和市')),
      trigger: 'change'
    }
  ],
  area: [{ required: true, message: '请填写区县或门店片区', trigger: 'blur' }],
  adminName: [{ required: true, message: '请填写老板姓名', trigger: 'blur' }],
  adminMobile: [
    { required: true, message: '请填写老板手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

const legacyRules: FormRules = {
  orgType: [{ required: true, message: '请选择组织类型', trigger: 'change' }],
  orgName: [{ required: true, message: '请填写组织名称', trigger: 'blur' }],
  adminName: [{ required: true, message: '请填写首管理员姓名', trigger: 'blur' }],
  adminMobile: [
    { required: true, message: '请填写首管理员手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

function clearCredentialResult() {
  if (franchiseResult.value) franchiseResult.value.initialPassword = null
  franchiseResult.value = null
  copyText.value = ''
  manualCopyVisible.value = false
}

function clearSensitiveState() {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = undefined
  previewGuard.invalidate()
  submitGuard.invalidate()
  previewLoading.value = false
  clearCredentialResult()
}

function handleRouteLeave() {
  if (!canLeaveFranchiseOnboard(submitting.value)) {
    ElMessage.warning('开通处理中，请稍候，完成后再离开页面')
    return false
  }
  clearSensitiveState()
  return true
}

function handleDeactivated() {
  if (canLeaveFranchiseOnboard(submitting.value)) clearSensitiveState()
}

function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  const names = findAreaNames(areaTree.value, franchiseForm.areaPath)
  franchiseForm.province = names.province
  franchiseForm.city = names.city
  clearCredentialResult()
  previewGuard.invalidate()
  preview.value = null
  previewError.value = ''
  previewLoading.value = false
  if (
    !names.province ||
    !names.city ||
    !franchiseForm.area.trim() ||
    !franchiseForm.adminName.trim()
  )
    return
  previewTimer = setTimeout(runPreview, 350)
}

async function runPreview() {
  const generation = previewGuard.next()
  previewLoading.value = true
  previewError.value = ''
  try {
    const result = await previewFranchise(buildPreviewPayload(franchiseForm))
    if (!previewGuard.isCurrent(generation)) return
    preview.value = result
  } catch (error: any) {
    if (!previewGuard.isCurrent(generation)) return
    preview.value = null
    previewError.value = error?.msg || error?.message || '账号预览生成失败，请重试'
  } finally {
    if (previewGuard.isCurrent(generation)) previewLoading.value = false
  }
}

async function submitFranchise() {
  if (submitting.value) return
  submitting.value = true
  const valid = await franchiseFormRef.value?.validate().catch(() => false)
  if (!valid) {
    submitting.value = false
    return
  }
  if (!preview.value) {
    submitting.value = false
    ElMessage.error('请先等待账号预览生成完成')
    return
  }
  const generation = submitGuard.next()
  const payload = {
    ...buildPreviewPayload(franchiseForm),
    adminMobile: franchiseForm.adminMobile.trim(),
    remark: franchiseForm.remark.trim() || undefined
  }
  clearCredentialResult()
  try {
    const result = await onboardFranchise(payload)
    if (!submitGuard.isCurrent(generation)) return
    franchiseResult.value = result
    copyText.value = buildCredentialCopyText(franchiseResult.value)
    ElMessage.success(`门店老板账号已开通：${result.loginName}`)
  } catch (error: any) {
    if (!submitGuard.isCurrent(generation)) return
    ElMessage.error(error?.msg || error?.message || '开通失败，请核对信息后重试')
  } finally {
    if (submitGuard.isCurrent(generation)) submitting.value = false
  }
}

async function copyCredentials() {
  if (!franchiseResult.value) return
  copyText.value = buildCredentialCopyText(franchiseResult.value)
  try {
    await navigator.clipboard.writeText(copyText.value)
    manualCopyVisible.value = false
    ElMessage.success('账号信息已复制')
  } catch {
    manualCopyVisible.value = true
    ElMessage.error('自动复制失败，请在下方文本框中手动选择复制')
  }
}

async function submitLegacy() {
  if (submitting.value) return
  await legacyFormRef.value?.validate()
  submitting.value = true
  try {
    const result: OnboardResult = await onboardOrg({ ...legacyForm })
    legacyResultText.value = result.reusedOrg
      ? `该组织已存在，组织编号 ${result.orgId}；首管理员已确认授权。`
      : `开通成功：组织编号 ${result.orgId}；首管理员（boss）已授权。`
    ElMessage.success('开通完成')
  } catch (error: any) {
    ElMessage.error(error?.msg || error?.message || '开通失败，请重试')
  } finally {
    submitting.value = false
  }
}

function changeMode() {
  clearCredentialResult()
  previewGuard.invalidate()
  submitGuard.invalidate()
}

async function loadAreas() {
  areaLoading.value = true
  areaError.value = false
  try {
    areaTree.value = toProvinceCityTree((await getAreaTree()) || [])
  } catch {
    areaTree.value = []
    areaError.value = true
    ElMessage.error('地区加载失败，请刷新页面重试')
  } finally {
    areaLoading.value = false
  }
}

watch(
  () => [
    franchiseForm.areaPath?.join(',') || '',
    franchiseForm.area,
    franchiseForm.adminName,
    franchiseForm.areaPinyin,
    franchiseForm.ownerPinyin
  ],
  schedulePreview
)

watch(
  () => franchiseForm.area,
  (nextSource, previousSource) => {
    franchiseForm.areaPinyin = resetOverrideWhenSourceChanges(
      previousSource,
      nextSource,
      franchiseForm.areaPinyin
    )
  }
)

watch(
  () => franchiseForm.adminName,
  (nextSource, previousSource) => {
    franchiseForm.ownerPinyin = resetOverrideWhenSourceChanges(
      previousSource,
      nextSource,
      franchiseForm.ownerPinyin
    )
  }
)

watch(() => [franchiseForm.adminMobile, franchiseForm.remark], clearCredentialResult)

onMounted(loadAreas)
onDeactivated(handleDeactivated)
onBeforeRouteLeave(handleRouteLeave)
onBeforeUnmount(handleDeactivated)
</script>

<style lang="scss" scoped>
.onboard-card {
  max-width: 920px;
}
.onboard-card--directory {
  max-width: none;
}

.onboard-form {
  max-width: 680px;
  padding-top: 18px;
}

.onboard-form.compact {
  max-width: 560px;
}

.mode-alert {
  max-width: 680px;
  margin-top: 8px;
}

.field-error {
  width: 100%;
  font-size: 12px;
  color: var(--el-color-danger);
}

.preview-panel {
  width: 100%;
  padding: 14px 16px;
  background: #fbf5ec;
  border: 1px solid #eadccf;
  border-radius: 10px;
}

.preview-panel.is-error {
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-5);
}

.preview-panel > div {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 12px;
  line-height: 28px;
}

.preview-panel span,
.preview-panel small,
.credential-heading p {
  color: #7b6a5b;
}

.preview-panel strong {
  overflow-wrap: anywhere;
  color: #4b2a16;
}

.preview-panel small {
  display: block;
  margin-top: 8px;
}

.preview-muted {
  font-size: 13px;
}

.credential-card {
  max-width: 680px;
  padding: 20px;
  margin-top: 22px;
  background: #fffaf2;
  border: 1px solid #e1ceb9;
  border-radius: 12px;
}

.credential-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.credential-heading h2,
.credential-heading p {
  margin: 0;
}

.credential-heading h2 {
  font-size: 18px;
  color: #4b2a16;
}

.credential-heading p {
  margin-top: 4px;
  font-size: 13px;
}

.password-value {
  margin-right: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  letter-spacing: 0.04em;
  color: #824019;
}

.result-alert,
.manual-copy,
.legacy-result {
  margin-top: 14px;
}

@media (width <= 720px) {
  .credential-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
