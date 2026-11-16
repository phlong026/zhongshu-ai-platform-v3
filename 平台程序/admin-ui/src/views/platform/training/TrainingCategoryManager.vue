<template>
  <el-button @click="show">分类管理</el-button>
  <el-dialog v-model="open" title="资料分类管理" width="min(650px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" append-to-body>
    <form class="category-add" @submit.prevent="create"><el-input v-model="name" placeholder="输入新分类名称" maxlength="40" show-word-limit :disabled="busy" aria-label="新分类名称" /><el-button type="primary" native-type="submit" :disabled="busy || !name.trim()">新增分类</el-button></form>
    <el-table class="training-compact-table" v-loading="loading" :data="rows" max-height="420">
      <el-table-column prop="name" label="分类名称" min-width="180" />
      <el-table-column prop="material_count" label="资料数" width="85" />
      <el-table-column label="操作" width="150"><template #default="{ row }"><el-tag v-if="row.system_default" type="info">保留分类</el-tag><template v-else><el-button link type="primary" :disabled="busy" @click="rename(row)">重命名</el-button><el-button link type="danger" :disabled="busy" @click="remove(row)">删除</el-button></template></template></el-table-column>
    </el-table>
    <p class="category-note">删除分类后，其中的资料自动归入“未分类”，文件和课程引用保持可用。“未分类”为保留分类。</p>
    <template #footer><el-button :disabled="busy" @click="open = false">完成</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMaterialCategories, createMaterialCategory, renameMaterialCategory, deleteMaterialCategory, type TrainingCategory } from '@/api/platform/training'
const emit = defineEmits<{ changed: [] }>()
const open = ref(false), loading = ref(false), busy = ref(false), name = ref('')
const rows = ref<TrainingCategory[]>([])
async function refresh() { loading.value = true; try { rows.value = await listMaterialCategories() } finally { loading.value = false } }
async function show() { open.value = true; name.value = ''; await refresh() }
async function mutate(action: () => Promise<unknown>, message: string) {
  if (busy.value) return; busy.value = true
  try { await action(); ElMessage.success(message); name.value = ''; emit('changed'); await refresh() }
  catch { await refresh() }
  finally { busy.value = false }
}
async function create() { if (!name.value.trim()) return; const value = name.value.trim(); await mutate(() => createMaterialCategory(value), '分类已新增') }
async function rename(row: TrainingCategory) {
  if (busy.value) return
  let value: string
  try { const result = await ElMessageBox.prompt('修改后，该分类下所有资料将同步更新。', '重命名分类', { inputValue: row.name, inputValidator: (v: string) => !!v?.trim() && v.trim().length <= 40 || '请输入1至40个字符', confirmButtonText: '保存名称', cancelButtonText: '取消', closeOnClickModal: false }); value = result.value.trim() } catch { return }
  await mutate(() => renameMaterialCategory(row.id, Number(row.revision), value), '分类名称已更新')
}
async function remove(row: TrainingCategory) {
  if (busy.value) return
  try { await ElMessageBox.confirm(`确认删除分类“${row.name}”？其中 ${row.material_count} 份资料会移至“未分类”，文件和课程引用保持可用。`, '删除分类', { type: 'warning', confirmButtonText: '删除并移至未分类', cancelButtonText: '取消', closeOnClickModal: false }) } catch { return }
  await mutate(() => deleteMaterialCategory(row.id, Number(row.revision), Number(row.material_count)), '分类已删除，资料已移至未分类')
}
</script>
<style scoped>.category-add{display:flex;gap:12px;margin-bottom:18px}.category-note{font-size:13px;color:#776e64;line-height:1.8;margin-top:18px}</style>
