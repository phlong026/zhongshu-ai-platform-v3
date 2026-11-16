<template>
  <el-dialog v-model="open" title="从资料库选择" width="min(950px, 94vw)" destroy-on-close>
    <div class="picker-search"><el-input v-model="keyword" placeholder="搜索资料名称" clearable @keyup.enter="load" /><el-button @click="load">查询</el-button></div>
    <el-table v-loading="loading" :data="rows" max-height="480">
      <el-table-column prop="name" label="资料名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="extension" label="格式" width="85" />
      <el-table-column label="版本" width="85"><template #default="{ row }">V{{ row.version_no }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.state === 'READY' ? 'success' : 'warning'">{{ stateText(row.state) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :disabled="row.state !== 'READY'" @click="select(row)">选择</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" layout="prev, pager, next" :total="total" :page-size="50" @current-change="load" />
  </el-dialog>
</template>
<script setup lang="ts">
import { listMaterials, stateText, type TrainingMaterial } from '@/api/platform/training'
const emit = defineEmits<{ select: [row: TrainingMaterial] }>()
const open = ref(false), loading = ref(false), keyword = ref(''), page = ref(1), total = ref(0)
const rows = ref<TrainingMaterial[]>([])
let generation = 0
async function load() { const gen = ++generation; loading.value = true; try { const result = await listMaterials(keyword.value, '', page.value); if (gen === generation) { rows.value = result.list; total.value = result.total } } finally { if (gen === generation) loading.value = false } }
function show() { page.value = 1; keyword.value = ''; open.value = true; load() }
function select(row: TrainingMaterial) { emit('select', row); open.value = false }
defineExpose({ show })
</script>
<style scoped>.picker-search{display:flex;gap:12px;margin-bottom:18px}.el-pagination{margin-top:18px;justify-content:flex-end}</style>
