<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">公司开通</h1>
        <div class="zs-page-subtitle">
          正式开通加盟组织并授予首任管理员（boss），可同时开通设计能力并赠点；激活码方式已停用
        </div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
        style="max-width: 560px"
      >
        <el-form-item label="组织类型" prop="orgType">
          <el-select v-model="form.orgType" placeholder="选择组织类型" style="width: 100%">
            <el-option label="加盟门店" value="store" />
            <el-option label="总部" value="headquarters" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织名称" prop="orgName">
          <el-input v-model="form.orgName" placeholder="如：湖北XX建材加盟门店" />
        </el-form-item>
        <el-form-item label="首管理员姓名" prop="adminName">
          <el-input v-model="form.adminName" placeholder="首任组织管理员真实姓名" />
        </el-form-item>
        <el-form-item label="首管理员手机号" prop="adminMobile">
          <el-input v-model="form.adminMobile" placeholder="11 位手机号；已存在账号将被复用" />
        </el-form-item>
        <el-form-item label="开通设计能力">
          <el-switch v-model="form.designEnabled" />
          <span class="tip">开启后建立公司积分池（设计/AI 能力按量扣点）</span>
        </el-form-item>
        <el-form-item v-if="form.designEnabled" label="开通赠点">
          <el-input-number v-model="form.initialCredits" :min="0" :max="100000" :step="10" />
          <span class="tip">仅首次生效，可填 0</span>
        </el-form-item>
        <el-form-item>
          <el-button
            class="zs-btn-primary"
            type="primary"
            :loading="submitting"
            @click="submit"
          >
            正式开通
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="result"
        :title="resultText"
        :type="result.reusedOrg ? 'info' : 'success'"
        show-icon
        style="max-width: 560px; margin-top: 12px"
      />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { onboardOrg, type OnboardResult } from '@/api/zs/platform-org'

defineOptions({ name: 'PlatformOrgOnboard' })

const formRef = ref<FormInstance>()
const submitting = ref(false)
const result = ref<OnboardResult | null>(null)
const resultText = ref('')

const form = reactive({
  orgType: 'store',
  orgName: '',
  adminName: '',
  adminMobile: '',
  // 24 号 B-8：设计能力开通与首次赠点（省略/关闭=不改变既有开通状态）
  designEnabled: false,
  initialCredits: 0
})

const rules: FormRules = {
  orgType: [{ required: true, message: '请选择组织类型', trigger: 'change' }],
  orgName: [{ required: true, message: '请填写组织名称', trigger: 'blur' }],
  adminName: [{ required: true, message: '请填写首管理员姓名', trigger: 'blur' }],
  adminMobile: [
    { required: true, message: '请填写首管理员手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

const submit = async () => {
  await formRef.value?.validate().catch(() => Promise.reject(new Error('invalid')))
  submitting.value = true
  try {
    result.value = await onboardOrg({ ...form })
    resultText.value = result.value.reusedOrg
      ? `该组织已存在，本次为幂等开通：组织编号 ${result.value.orgId}；首管理员已确认授权。`
      : `开通成功：组织编号 ${result.value.orgId}；首管理员（boss）已授权，可通知其经小程序登录。`
    ElMessage.success('开通完成')
    formRef.value?.resetFields()
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tip {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
