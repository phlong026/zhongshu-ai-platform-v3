<template>
  <Dialog
    v-model="dialogVisible"
    :max-height="500"
    :scroll="true"
    title="操作日志详情"
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
        {{ detailData.traceId || '—' }}
      </el-descriptions-item>
      <el-descriptions-item label="操作人编号">
        {{ detailData.userId }}
      </el-descriptions-item>
      <el-descriptions-item label="操作人名字">
        {{ detailData.userName }}
      </el-descriptions-item>
      <el-descriptions-item label="操作人 IP">
        {{ detailData.userIp }}
      </el-descriptions-item>
      <el-descriptions-item label="操作时间">
        {{ formatDate(detailData.createTime) }}
      </el-descriptions-item>
      <el-descriptions-item label="操作人 UA" :span="summaryColumns">
        {{ detailData.userAgent }}
      </el-descriptions-item>
      <el-descriptions-item label="操作模块">
        {{ detailData.type }}
      </el-descriptions-item>
      <el-descriptions-item label="操作名">
        {{ detailData.subType }}
      </el-descriptions-item>
      <el-descriptions-item label="操作内容" :span="summaryColumns">
        {{ detailData.action }}
      </el-descriptions-item>
      <el-descriptions-item v-if="detailData.extra" label="操作拓展参数" :span="summaryColumns">
        {{ detailData.extra }}
      </el-descriptions-item>
      <el-descriptions-item label="请求 URL" :span="summaryColumns">
        {{ detailData.requestMethod }} {{ detailData.requestUrl }}
      </el-descriptions-item>
      <el-descriptions-item label="业务编号" :span="summaryColumns">
        {{ detailData.bizId }}
      </el-descriptions-item>
    </el-descriptions>
  </Dialog>
</template>
<script lang="ts" setup>
import { useMediaQuery } from '@vueuse/core'
const wideSummary = useMediaQuery('(min-width: 760px)')
const summaryColumns = computed(() => (wideSummary.value ? 2 : 1))
import { formatDate } from '@/utils/formatTime'
import * as OperateLogApi from '@/api/system/operatelog'

defineOptions({ name: 'SystemOperateLogDetail' })

const dialogVisible = ref(false) // 弹窗的是否展示
const detailLoading = ref(false) // 表单的加载中
const detailData = ref({} as OperateLogApi.OperateLogVO) // 详情数据

/** 打开弹窗 */
const open = async (data: OperateLogApi.OperateLogVO) => {
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
