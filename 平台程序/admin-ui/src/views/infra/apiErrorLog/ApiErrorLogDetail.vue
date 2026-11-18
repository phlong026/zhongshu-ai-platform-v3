<template>
  <Dialog
    v-model="dialogVisible"
    :max-height="500"
    :scroll="true"
    title="错误日志详情"
    width="min(1040px,96vw)"
  >
    <el-descriptions
      :column="summaryColumns"
      :label-width="110"
      class="admin-detail-summary"
      border
    >
      <el-descriptions-item label="日志主键" min-width="120">
        {{ detailData.id }}
      </el-descriptions-item>
      <el-descriptions-item label="链路追踪">
        {{ detailData.traceId }}
      </el-descriptions-item>
      <el-descriptions-item label="应用名">
        {{ detailData.applicationName }}
      </el-descriptions-item>
      <el-descriptions-item label="用户编号">
        {{ detailData.userId }}
        <dict-tag :type="DICT_TYPE.USER_TYPE" :value="detailData.userType" />
      </el-descriptions-item>
      <el-descriptions-item label="用户 IP">
        {{ detailData.userIp }}
      </el-descriptions-item>
      <el-descriptions-item label="处理状态">
        <dict-tag
          :type="DICT_TYPE.INFRA_API_ERROR_LOG_PROCESS_STATUS"
          :value="detailData.processStatus"
        />
      </el-descriptions-item>
      <el-descriptions-item label="用户 UA" :span="summaryColumns">
        {{ detailData.userAgent }}
      </el-descriptions-item>
      <el-descriptions-item label="请求信息" :span="summaryColumns">
        {{ detailData.requestMethod }} {{ detailData.requestUrl }}
      </el-descriptions-item>
      <el-descriptions-item label="请求参数" :span="summaryColumns">
        {{ detailData.requestParams }}
      </el-descriptions-item>
      <el-descriptions-item label="异常时间" :span="summaryColumns">
        {{ formatDate(detailData.exceptionTime) }}
      </el-descriptions-item>
      <el-descriptions-item label="异常名" :span="summaryColumns">
        {{ detailData.exceptionName }}
      </el-descriptions-item>
      <el-descriptions-item
        v-if="detailData.exceptionStackTrace"
        label="异常堆栈"
        :span="summaryColumns"
      >
        <el-input
          v-model="detailData.exceptionStackTrace"
          :autosize="{ maxRows: 20 }"
          :readonly="true"
          type="textarea"
        />
      </el-descriptions-item>
      <el-descriptions-item v-if="detailData.processUserId" label="处理人">
        {{ detailData.processUserId }}
      </el-descriptions-item>
      <el-descriptions-item v-if="detailData.processTime" label="处理时间">
        {{ formatDate(detailData.processTime) }}
      </el-descriptions-item>
    </el-descriptions>
  </Dialog>
</template>
<script lang="ts" setup>
import { useMediaQuery } from '@vueuse/core'
const wideSummary = useMediaQuery('(min-width: 760px)')
const summaryColumns = computed(() => (wideSummary.value ? 2 : 1))
import { DICT_TYPE } from '@/utils/dict'
import { formatDate } from '@/utils/formatTime'
import * as ApiErrorLog from '@/api/infra/apiErrorLog'

defineOptions({ name: 'ApiErrorLogDetail' })

const dialogVisible = ref(false) // 弹窗的是否展示
const detailLoading = ref(false) // 表单的加载中
const detailData = ref({} as ApiErrorLog.ApiErrorLogVO) // 详情数据

/** 打开弹窗 */
const open = async (data: ApiErrorLog.ApiErrorLogVO) => {
  dialogVisible.value = true
  // 设置数据
  detailLoading.value = true
  try {
    detailData.value = data
  } finally {
    detailLoading.value = false
  }
}
defineExpose({ open }) // 提供 open 方法，用于打开弹窗
</script>
