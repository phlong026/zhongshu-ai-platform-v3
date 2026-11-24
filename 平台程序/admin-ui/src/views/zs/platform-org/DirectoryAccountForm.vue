<template>
  <section class="directory-account">
    <el-form label-width="120px" class="directory-account-form" @submit.prevent>
      <el-form-item label="选择加盟商">
        <el-select
          v-model="selectedId"
          filterable
          remote
          clearable
          :remote-method="searchCandidates"
          :loading="searching"
          :disabled="busy"
          placeholder="搜索名录名称、品牌或地区"
          @change="selectRecord"
        >
          <el-option
            v-for="item in candidates"
            :key="item.id"
            :value="item.id"
            :label="`${item.brandName || item.brand} · ${item.name || item.originalRegionText}`"
            :disabled="!!item.linkedOrgId"
          />
        </el-select>
        <span v-if="searchError" class="field-error">{{ searchError }}</span>
      </el-form-item>
      <el-alert
        v-if="record"
        :title="`${record.brandName || record.brand} · ${record.originalRegionText}`"
        type="info"
        :closable="false"
        class="selection-summary"
      />
      <el-alert
        v-if="record && !eligible"
        title="请先在加盟商名录中确认区县信息，再从这条记录开通账号。"
        type="warning"
        :closable="false"
        class="selection-summary"
      />
      <el-form-item label="开户区县">
        <el-select
          v-model="districtCode"
          :disabled="busy || !eligible"
          placeholder="选择该记录对应的开户区县"
        >
          <el-option
            v-for="region in districtOptions"
            :key="region.code"
            :value="region.code"
            :label="`${region.provinceName} / ${region.cityName} / ${region.name}`"
          />
        </el-select>
        <span v-if="regionError" class="field-error">地区加载失败，请重新打开本页。</span>
      </el-form-item>
      <el-form-item label="老板姓名">
        <el-input
          v-model.trim="adminName"
          :disabled="busy"
          maxlength="20"
          placeholder="请输入老板姓名"
        />
      </el-form-item>
      <el-form-item label="老板手机号">
        <el-input
          v-model.trim="adminMobile"
          :disabled="busy"
          maxlength="11"
          placeholder="请输入11位手机号"
        />
      </el-form-item>
      <el-form-item label="姓名拼音校正">
        <el-input
          v-model.trim="ownerPinyin"
          :disabled="busy"
          maxlength="40"
          placeholder="留空自动生成"
        />
      </el-form-item>
      <el-form-item label="内部备注">
        <el-input
          v-model.trim="remark"
          :disabled="busy"
          type="textarea"
          :rows="2"
          maxlength="200"
        />
      </el-form-item>
      <el-form-item label="账号预览">
        <div class="directory-preview">
          <span v-if="previewLoading">正在生成账号预览…</span>
          <template v-else-if="preview">
            <strong>{{ preview.orgName }}</strong>
            <span>登录账号：{{ preview.loginName }}</span>
          </template>
          <span v-else>{{ previewError || '选择名录和区县，填写姓名后自动生成' }}</span>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">
          开通账号并关联名录
        </el-button>
      </el-form-item>
    </el-form>
    <div v-if="result" class="directory-credentials">
      <h2>开通完成</h2>
      <p>{{ result.orgName }}</p>
      <p
        >登录账号：<strong>{{ result.loginName }}</strong></p
      >
      <p v-if="result.reusedUser">沿用该用户原账号密码。</p>
      <p v-else
        >初始密码：<strong>{{ result.initialPassword }}</strong
        >，首次登录后请修改。</p
      >
      <p>名录已关联此组织，地图不会重复增加一条记录。</p>
      <el-button type="primary" @click="copyCredentials">复制账号信息</el-button>
      <el-input
        v-if="copyFallback"
        :model-value="copyFallback"
        type="textarea"
        :rows="7"
        readonly
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getDirectoryCandidates,
  getDirectoryRegions,
  onboardFromDirectory,
  type DirectoryItem,
  type MapRegion
} from '@/api/zs/franchise-directory'
import {
  previewFranchise,
  type FranchisePreviewResult,
  type FranchiseOnboardResult
} from '@/api/zs/platform-org'
import { buildCredentialCopyText } from './franchiseAccountForm'

const props = defineProps<{ initialRecord?: DirectoryItem | null }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const candidates = ref<DirectoryItem[]>([])
const regions = ref<MapRegion[]>([])
const selectedId = ref('')
const record = ref<DirectoryItem | null>(null)
const districtCode = ref('')
const adminName = ref('')
const adminMobile = ref('')
const ownerPinyin = ref('')
const remark = ref('')
const busy = ref(false)
const searching = ref(false)
const searchError = ref('')
const regionError = ref(false)
const preview = ref<FranchisePreviewResult | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const result = ref<FranchiseOnboardResult | null>(null)
const copyFallback = ref('')
let active = true
let searchGeneration = 0
let previewGeneration = 0
let submitGeneration = 0
let credentialGeneration = 0
let regionGeneration = 0
let timer: ReturnType<typeof setTimeout> | undefined

const districtOptions = computed(() =>
  regions.value.filter((r) => r.level === 'district' && record.value?.regionCodes.includes(r.code))
)
const selectedDistrict = computed(() =>
  districtOptions.value.find((r) => r.code === districtCode.value)
)
const eligible = computed(
  () =>
    !!record.value &&
    record.value.matchStatus === 'RESOLVED' &&
    !record.value.linkedOrgId &&
    districtOptions.value.length > 0 &&
    !regionError.value
)
const canSubmit = computed(
  () =>
    eligible.value &&
    !!selectedDistrict.value &&
    !!preview.value &&
    !previewLoading.value &&
    !busy.value &&
    /^1\d{10}$/.test(adminMobile.value)
)

function clearCredentials() {
  ++credentialGeneration
  if (result.value) result.value.initialPassword = null
  result.value = null
  copyFallback.value = ''
}

async function searchCandidates(keyword = '') {
  if (!active) return
  const generation = ++searchGeneration
  searching.value = true
  searchError.value = ''
  try {
    const rows = await getDirectoryCandidates(keyword)
    if (!active || generation !== searchGeneration) return
    candidates.value =
      record.value && !rows.some((r) => r.id === record.value?.id) ? [record.value, ...rows] : rows
  } catch (error: any) {
    if (!active || generation !== searchGeneration) return
    searchError.value = error?.msg || error?.message || '名录加载失败，请重新搜索'
  } finally {
    if (active && generation === searchGeneration) searching.value = false
  }
}

function selectRecord(id: string) {
  if (busy.value) return
  record.value = candidates.value.find((r) => r.id === id) || null
  districtCode.value = districtOptions.value.length === 1 ? districtOptions.value[0].code : ''
  clearCredentials()
  schedulePreview()
}

function schedulePreview() {
  if (timer) clearTimeout(timer)
  const generation = ++previewGeneration
  preview.value = null
  previewError.value = ''
  previewLoading.value = false
  if (!active || !eligible.value || !selectedDistrict.value || !adminName.value.trim()) return
  previewLoading.value = true
  timer = setTimeout(async () => {
    const region = selectedDistrict.value
    const selected = record.value
    if (!region || !selected) return
    try {
      const data = await previewFranchise({
        brand: selected.brandName || selected.brand,
        province: region.provinceName,
        city: region.cityName,
        area: region.name,
        adminName: adminName.value.trim(),
        ownerPinyin: ownerPinyin.value.trim() || undefined
      })
      if (active && generation === previewGeneration) preview.value = data
    } catch (error: any) {
      if (active && generation === previewGeneration)
        previewError.value = error?.msg || error?.message || '账号预览失败，请检查姓名和区县'
    } finally {
      if (active && generation === previewGeneration) previewLoading.value = false
    }
  }, 300)
}

async function submit() {
  if (!canSubmit.value || !record.value) return
  busy.value = true
  emit('busy', true)
  clearCredentials()
  const selected = record.value
  const generation = ++submitGeneration
  try {
    const response = await onboardFromDirectory({
      recordId: selected.id,
      expectedVersion: selected.version,
      districtCode: districtCode.value,
      adminName: adminName.value.trim(),
      adminMobile: adminMobile.value.trim(),
      ownerPinyin: ownerPinyin.value.trim() || undefined,
      remark: remark.value.trim() || undefined
    })
    if (!active || generation !== submitGeneration) return
    result.value = response
    record.value = { ...selected, linkedOrgId: String(response.orgId) }
    preview.value = null
    ElMessage.success('账号已开通，名录已关联')
  } catch (error: any) {
    if (active && generation === submitGeneration)
      ElMessage.error(error?.msg || error?.message || '开通失败，请刷新名录后重试')
  } finally {
    if (generation === submitGeneration) {
      busy.value = false
      emit('busy', false)
    }
  }
}

async function copyCredentials() {
  if (!result.value) return
  const text = buildCredentialCopyText(result.value)
  const generation = credentialGeneration
  try {
    await navigator.clipboard.writeText(text)
    if (active && generation === credentialGeneration) ElMessage.success('账号信息已复制')
  } catch {
    if (active && generation === credentialGeneration) copyFallback.value = text
  }
}

watch([districtCode, adminName, ownerPinyin], () => {
  clearCredentials()
  schedulePreview()
})
watch(adminName, () => {
  ownerPinyin.value = ''
})
watch([adminMobile, remark], clearCredentials)
watch(
  () => props.initialRecord,
  (value) => {
    if (!value || busy.value) return
    candidates.value = [value, ...candidates.value.filter((r) => r.id !== value.id)]
    selectedId.value = value.id
    selectRecord(value.id)
  },
  { immediate: true }
)

async function loadData() {
  const generation = ++regionGeneration
  regionError.value = false
  void searchCandidates()
  try {
    const rows = await getDirectoryRegions()
    if (!active || generation !== regionGeneration) return
    regions.value = rows
    if (districtOptions.value.length === 1) districtCode.value = districtOptions.value[0].code
  } catch {
    if (active && generation === regionGeneration) regionError.value = true
  }
}
onMounted(loadData)
onActivated(() => {
  if (active) return
  active = true
  void loadData()
})
function dispose() {
  active = false
  ++searchGeneration
  ++previewGeneration
  ++submitGeneration
  ++regionGeneration
  if (timer) clearTimeout(timer)
  clearCredentials()
  selectedId.value = ''
  record.value = null
  candidates.value = []
  regions.value = []
  districtCode.value = ''
  adminName.value = ''
  adminMobile.value = ''
  ownerPinyin.value = ''
  remark.value = ''
  preview.value = null
  busy.value = false
  emit('busy', false)
}
onDeactivated(dispose)
onBeforeUnmount(dispose)
</script>

<style scoped lang="scss">
.directory-account {
  padding-top: 18px;
  max-width: 680px;
}
.directory-account :deep(.el-select) {
  width: 100%;
}
.selection-summary {
  margin-bottom: 18px;
}
.directory-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  padding: 14px 16px;
  background: #fbf5ec;
  border: 1px solid #eadccf;
  border-radius: 10px;
  color: #4b2a16;
  overflow-wrap: anywhere;
}
.directory-credentials {
  padding: 20px;
  margin-top: 20px;
  background: #fffaf2;
  border: 1px solid #e1ceb9;
  border-radius: 12px;
  overflow-wrap: anywhere;
}
.directory-credentials h2 {
  margin: 0;
  font-size: 18px;
}
.directory-credentials :deep(.el-textarea) {
  margin-top: 12px;
}
.field-error {
  color: var(--el-color-danger);
}
@media (width <= 600px) {
  .directory-account :deep(.el-form-item) {
    display: block;
  }
  .directory-account :deep(.el-form-item__label) {
    display: block;
    width: auto !important;
    text-align: left;
  }
  .directory-account :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
}
</style>
