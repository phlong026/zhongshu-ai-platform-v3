<template>
  <div class="training-page" v-loading="loading">
    <template v-if="!editing">
      <div class="training-heading"><div><h1>课程管理</h1><p>上传课程、关联资料，按组织与岗位开放学习</p></div><el-button type="primary" @click="create">新建课程</el-button></div>
      <div class="training-toolbar"><el-input v-model="keyword" placeholder="搜索课程名称" clearable @keyup.enter="loadList" /><el-button @click="loadList">查询</el-button><el-button @click="$router.push('/training/materials')">打开资料库</el-button></div>
      <el-table :data="rows" table-layout="auto">
        <el-table-column prop="title" label="课程名称" min-width="220" /><el-table-column prop="category" label="分类" min-width="115" /><el-table-column prop="lecturer" label="内容负责人" min-width="120" /><el-table-column prop="lesson_count" label="课时" width="70" />
        <el-table-column label="状态" min-width="140"><template #default="{ row }"><el-tag :type="row.state === 'PUBLISHED' ? 'success' : 'info'">{{ stateText(row.state) }}</el-tag><span v-if="row.review_state !== row.state" class="sub-status">{{ stateText(row.review_state) }}</span></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="edit(row.id)">编辑课程</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无课程，先创建一门课程并上传课件" /></template>
      </el-table>
      <el-pagination v-model:current-page="page" :total="total" :page-size="50" layout="prev, pager, next" @current-change="loadList" />
    </template>
    <template v-else>
      <div class="training-heading"><div><el-button link @click="back">返回课程列表</el-button><h1>{{ form.title || '新建课程' }} <el-tag>{{ stateText(meta?.reviewState || 'DRAFT') }}</el-tag></h1></div><el-button @click="step = 3">预览学员端</el-button></div>
      <el-steps :active="step" finish-status="success" simple class="training-steps"><el-step title="基本信息" /><el-step title="课程内容" /><el-step title="学习权限" /><el-step title="预览发布" /></el-steps>
      <el-form v-if="step === 0" label-position="top" class="basic-form">
        <el-form-item label="课程名称" required><el-input v-model="form.title" maxlength="40" show-word-limit placeholder="例如：AI短视频获客实战" /></el-form-item>
        <div class="two-fields"><el-form-item label="培训分类" required><el-select v-model="form.category"><el-option v-for="c in options.categories" :key="c" :label="c" :value="c" /></el-select></el-form-item><el-form-item label="讲师或内容负责人" required><el-input v-model="form.lecturer" maxlength="80" /></el-form-item></div>
        <el-form-item label="课程简介"><el-input v-model="form.summary" type="textarea" :rows="3" maxlength="200" show-word-limit /></el-form-item>
        <el-form-item label="学习目标"><el-input v-model="form.objective" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="课程封面"><div class="cover-choice"><img v-if="coverUrl" :src="coverUrl" alt="课程封面" /><el-button @click="pick('cover')">从资料库选择图片</el-button><el-button v-if="form.coverVersionId" link @click="form.coverVersionId = ''; coverUrl = ''">移除封面</el-button></div><span class="field-hint">推荐16:9图片，可先在资料库上传；未设封面时显示课程分类图标。</span></el-form-item>
      </el-form>
      <div v-show="step === 1" class="editor-grid">
        <div class="editor-main"><TrainingUploader ref="uploader" :category="form.category" @complete="uploaded" />
          <div class="section-heading"><h2>课程目录</h2><div><el-button @click="addLesson">添加课时</el-button><el-button @click="pick('new-main')">从资料库添加</el-button></div></div>
          <p v-if="!form.lessons.length" class="muted">上传视频或讲义后生成待编排课时；可拖拽调整顺序。</p>
          <draggable v-model="form.lessons" item-key="id" handle=".drag-handle" class="lesson-list"><template #item="{ element: lesson, index }"><button class="lesson-row" :class="{ selected: selectedId === lesson.id }" @click="selectedId = lesson.id"><el-icon class="drag-handle"><Rank /></el-icon><span class="lesson-number">{{ String(index + 1).padStart(2, '0') }}</span><span class="lesson-title">{{ lesson.title || '未命名课时' }}<small>{{ lesson.chapter }}</small></span><el-tag size="small" :type="material(lesson.mainVersionId)?.state === 'READY' ? 'success' : 'warning'">{{ lesson.mainVersionId ? stateText(material(lesson.mainVersionId)?.state || 'UPLOADED') : '未选课件' }}</el-tag><span class="attachment-count">资料 {{ lesson.attachments.length }}</span></button></template></draggable>
          <div class="section-heading"><h2>课程通用资料</h2><el-button link type="primary" @click="pick('course-attachment')">添加资料</el-button></div>
          <div v-for="(a, index) in form.attachments" :key="a.versionId" class="attachment-row"><span>{{ material(a.versionId)?.name || a.versionId }}</span><el-checkbox v-model="a.download">允许下载</el-checkbox><el-button link @click="form.attachments.splice(index, 1)">移除</el-button></div>
        </div>
        <el-form v-if="selected" label-position="top" class="lesson-settings"><h2>课时设置</h2><el-form-item label="课时名称" required><el-input v-model="selected.title" maxlength="80" /></el-form-item><el-form-item label="所属章节" required><el-input v-model="selected.chapter" maxlength="80" placeholder="例如：第一章 获客基础" /></el-form-item>
          <el-form-item label="主课件"><div class="main-file">{{ material(selected.mainVersionId)?.file_name || '尚未选择课件' }}</div><el-button link type="primary" @click="pick('main')">选择 / 更换主课件</el-button></el-form-item>
          <el-form-item label="完成条件"><el-input-number v-model="selected.completionPercent" :min="50" :max="100" /><span class="field-hint">视频有效观看覆盖百分比；文档为阅读至少30秒后确认已读。</span></el-form-item>
          <div class="section-heading"><h3>配套资料</h3><el-button link type="primary" @click="pick('lesson-attachment')">添加资料</el-button></div>
          <div v-for="(a, index) in selected.attachments" :key="a.versionId" class="lesson-attachment"><strong>{{ material(a.versionId)?.name || a.versionId }}</strong><div><el-checkbox v-model="a.download">允许下载原件</el-checkbox><el-button link @click="selected?.attachments.splice(index, 1)">移除</el-button></div></div>
          <el-button type="danger" plain @click="removeLesson">移除此课时</el-button>
        </el-form><aside v-else class="lesson-settings muted">选择一个课时进行设置</aside>
      </div>
      <el-form v-if="step === 2" label-position="top" class="basic-form">
        <el-form-item label="组织范围" required><el-switch v-model="form.allOrganizations" active-text="全部组织（包含未来新组织）" /><el-select v-if="!form.allOrganizations" v-model="form.organizationIds" multiple filterable placeholder="选择可学习的组织"><el-option v-for="org in options.organizations" :key="org.id" :value="org.id" :label="org.name" /></el-select></el-form-item>
        <el-form-item label="可学习岗位" required><el-checkbox-group v-model="form.roles" class="role-choices"><el-checkbox v-for="role in options.roles" :key="role" :value="role">{{ roleText[role] || role }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="指定人员（可选）"><el-select v-model="form.userIds" multiple filterable allow-create default-first-option placeholder="输入人员编号，留空表示组织与岗位范围内所有人员" /><span class="field-hint">人员限制会进一步收窄范围，不会扩大组织或岗位授权。</span></el-form-item>
        <div class="two-fields"><el-form-item label="开放时间（留空立即开放）"><el-date-picker :model-value="form.opensAt ? new Date(form.opensAt) : null" type="datetime" @update:model-value="setTime('opensAt', $event)" /></el-form-item><el-form-item label="结束时间（可选）"><el-date-picker :model-value="form.closesAt ? new Date(form.closesAt) : null" type="datetime" @update:model-value="setTime('closesAt', $event)" /></el-form-item></div>
      </el-form>
      <div v-if="step === 3" class="preview-grid">
        <div><h2>发布检查</h2><p>修改内容会创建新草稿，正式发布前学员继续使用旧版本。</p><el-button :loading="saving" @click="runChecks">保存并检查</el-button><ul class="check-list"><li v-for="issue in issues" :key="issue">{{ issue }}</li><li v-if="checked && !issues.length" class="pass">必要课件与开放范围检查通过</li></ul><h3>开放范围</h3><p>{{ form.allOrganizations ? '全部组织，包含未来新建组织' : `已选择 ${form.organizationIds.length} 个组织` }}</p><p>{{ form.roles.map(r => roleText[r] || r).join('、') || '尚未选择岗位' }}</p><h3>操作记录</h3><div v-for="item in meta?.history?.slice(0, 8)" :key="`${item.created_at}-${item.action}`" class="audit-row">{{ item.action }} · {{ item.detail || '课程内容维护' }}</div>
          <el-button v-if="meta?.state === 'PUBLISHED'" type="danger" plain @click="act('withdraw')">下架课程</el-button>
        </div>
        <div class="learner-preview"><span class="muted">学员展示预览 · 不记录学习进度</span><img v-if="coverUrl" :src="coverUrl" alt="课程封面预览" /><h2>{{ form.title || '课程名称' }}</h2><p>{{ form.category }} · 共 {{ form.lessons.length }} 节</p><p>{{ form.summary }}</p><h3>课程目录</h3><div v-for="(l, i) in form.lessons" :key="l.id" class="preview-lesson"><span>{{ String(i + 1).padStart(2, '0') }}</span><div><strong>{{ l.title }}</strong><small>{{ l.chapter }} · 配套资料 {{ l.attachments.length }}</small></div><el-button link :disabled="!l.mainVersionId" @click="previewLesson(l)">预览</el-button></div></div>
      </div>
      <div class="editor-footer"><span :class="{ 'save-error': saveError }">{{ saveError || (saving ? '正在保存…' : dirty ? '有未保存修改' : meta ? '草稿已保存' : '填写课程名称后保存草稿') }}</span><div><el-button v-if="step > 0" @click="previous">上一步</el-button><el-button :loading="saving" @click="persist">保存草稿</el-button><el-button v-if="step < 3" type="primary" :loading="saving" @click="next">下一步：{{ ['课程内容', '学习权限', '预览发布'][step] }}</el-button><template v-else-if="meta?.reviewState === 'PENDING'"><el-button type="warning" plain @click="act('reject')">退回修改</el-button><el-button type="primary" :disabled="dirty" @click="act('publish')">审核通过并发布</el-button></template><el-button v-else type="primary" @click="act('submit')">提交审核</el-button></div></div>
    </template>
    <MaterialPicker ref="picker" @select="picked" />
    <el-dialog v-model="mediaOpen" title="课件预览" width="min(900px,94vw)" destroy-on-close><video v-if="previewKind === 'play'" :src="previewUrl" controls style="width:100%;max-height:65vh" /><img v-else-if="previewKind === 'image'" :src="previewUrl" alt="课件预览" style="display:block;max-width:100%;max-height:65vh;margin:auto" /><PdfPreview v-else :src="previewUrl" /></el-dialog>
  </div>
</template>

<script setup lang="ts">
import draggable from 'vuedraggable'
import PdfPreview from './PdfPreview.vue'
import { Rank } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import TrainingUploader from './TrainingUploader.vue'
import MaterialPicker from './MaterialPicker.vue'
import { listCourses, getCourse, saveCourse, checkCourse, courseAction, trainingOptions, getMaterial, materialTicket, newTrainingCourse, stateText, roleText, type CourseDetail, type TrainingCourse, type TrainingOptions, type TrainingMaterial, type TrainingLesson } from '@/api/platform/training'
const loading = ref(false), editing = ref(false), saving = ref(false), dirty = ref(false), saveError = ref(''), step = ref(0)
const keyword = ref(''), page = ref(1), total = ref(0), rows = ref<Record<string, any>[]>([])
const meta = ref<CourseDetail>(), form = ref<TrainingCourse>(newTrainingCourse()), selectedId = ref('')
const options = ref<TrainingOptions>({ categories: [], roles: [], organizations: [], chunkSize: 4194304 })
const versions = reactive<Record<string, any>>({}), coverUrl = ref(''), issues = ref<string[]>([]), checked = ref(false)
const picker = ref<InstanceType<typeof MaterialPicker>>(), uploader = ref<InstanceType<typeof TrainingUploader>>()
const mediaOpen = ref(false), previewUrl = ref(''), previewKind = ref('')
const selected = computed(() => form.value.lessons.find(l => l.id === selectedId.value))
let editorGeneration = 0
let pickTarget = '', saveTimer: ReturnType<typeof setTimeout> | undefined, pollTimer: ReturnType<typeof setInterval> | undefined, savePromise: Promise<void> | undefined, closed = false
const material = (id: string) => versions[id]
async function loadList() { loading.value = true; try { const r = await listCourses(keyword.value, page.value); rows.value = r.list; total.value = r.total } finally { loading.value = false } }
function create() { editorGeneration++; meta.value = undefined; loading.value = true; form.value = newTrainingCourse(); selectedId.value = ''; coverUrl.value = ''; step.value = 0; editing.value = true; dirty.value = false; saveError.value = ''; loading.value = false }
async function edit(id: string) { const gen = ++editorGeneration; loading.value = true; try { const loaded = await getCourse(id); if (gen !== editorGeneration) return; meta.value = loaded; form.value = JSON.parse(JSON.stringify(meta.value.content)); dirty.value = false; selectedId.value = form.value.lessons[0]?.id || ''; editing.value = true; step.value = 0; checked.value = false; saveError.value = ''; await refreshVersions(); if (form.value.coverVersionId) coverUrl.value = await materialTicket(form.value.coverVersionId, 'poster') } finally { loading.value = false } }
function addLesson() { const id = crypto.randomUUID(); form.value.lessons.push({ id, title: '新课时', chapter: '第一章', mainVersionId: '', attachments: [], completionPercent: 90 }); selectedId.value = id }
async function removeLesson() { await ElMessageBox.confirm('从草稿移除此课时？已发布版本和历史学习记录会保留。', '移除课时'); form.value.lessons = form.value.lessons.filter(l => l.id !== selectedId.value); selectedId.value = form.value.lessons[0]?.id || '' }
function pick(target: string) { pickTarget = target; picker.value?.show() }
async function picked(row: TrainingMaterial) { versions[row.version_id] = row; const id = row.version_id; if (pickTarget === 'cover') { if (!['jpg', 'jpeg', 'png', 'webp'].includes(row.extension)) { ElMessage.warning('请选择图片封面'); return } form.value.coverVersionId = id; coverUrl.value = await materialTicket(id, 'poster') }
  else if (pickTarget === 'new-main') { addLesson(); selected.value!.title = row.name; selected.value!.mainVersionId = id }
  else if (pickTarget === 'main' && selected.value) selected.value.mainVersionId = id
  else { const target = pickTarget === 'course-attachment' ? form.value.attachments : selected.value?.attachments; if (target && !target.some(a => a.versionId === id)) target.push({ versionId: id, download: false }) }
}
async function uploaded(id: string, name: string) { const gen = editorGeneration; const v = await getMaterial(id); if (gen !== editorGeneration) return; versions[id] = { ...v, version_id: id }; if (['mp4', 'mov', 'm4v', 'avi', 'mkv', 'mp3', 'm4a', 'pdf', 'docx', 'pptx'].includes(v.extension)) { if (form.value.lessons.some(l => l.mainVersionId === id)) return; addLesson(); const base = name.replace(/\.[^.]+$/, ''); selected.value!.title = (base.replace(/^\d+[_\-\s]*/, '').trim() || base || '新课时').slice(0, 80); selected.value!.mainVersionId = id } else if (!form.value.attachments.some(a => a.versionId === id)) form.value.attachments.push({ versionId: id, download: false }) }
async function refreshVersions() { const ids = new Set([form.value.coverVersionId, ...form.value.attachments.map(a => a.versionId), ...form.value.lessons.flatMap(l => [l.mainVersionId, ...l.attachments.map(a => a.versionId)])].filter(Boolean)); await Promise.allSettled([...ids].map(async id => { const v = await getMaterial(id); if (!closed) versions[id] = { ...v, version_id: id } })) }
async function persist() { if (savePromise) { await savePromise; if (!dirty.value) return } if (!form.value.title.trim()) { saveError.value = '请先填写课程名称'; return } const gen = editorGeneration; const snapshot = JSON.stringify(form.value); saving.value = true; saveError.value = ''; clearTimeout(saveTimer)
  savePromise = (async () => { try { const result = await saveCourse(meta.value?.id, JSON.parse(snapshot), meta.value?.revision); if (closed || gen !== editorGeneration) return; meta.value = result; dirty.value = JSON.stringify(form.value) !== snapshot } catch (e: any) { saveError.value = e?.message || '保存失败，请重试'; throw e } finally { saving.value = false; savePromise = undefined } })(); await savePromise
}
async function next() { await persist(); if (saveError.value) return; step.value++; if (step.value === 3) await runChecks() }
function previous() { step.value-- }
async function runChecks() { await persist(); if (!meta.value || saveError.value) return; issues.value = await checkCourse(meta.value.id); checked.value = true }
async function act(action: string) { if (['submit', 'publish'].includes(action) && uploader.value?.pending()) { ElMessage.warning('请先完成上传，再提交审核或发布课程'); return } if (!meta.value || dirty.value) { await persist(); if (!meta.value || saveError.value) return } if (action === 'publish') await ElMessageBox.confirm('确认审核通过并向所选组织、岗位发布此版本？', '发布课程'); let reason = ''; if (['withdraw', 'reject'].includes(action)) reason = (await ElMessageBox.prompt(action === 'reject' ? '请输入退回原因' : '请输入下架原因', action === 'reject' ? '退回修改' : '下架课程', { inputPattern: /\S+/, inputErrorMessage: '请填写原因' })).value; const result = await courseAction(meta.value!.id, action, meta.value!.revision, reason); meta.value = result; ElMessage.success(action === 'publish' ? '课程已发布' : action === 'withdraw' ? '课程已下架' : action === 'reject' ? '课程已退回修改' : '已提交审核'); dirty.value = false }
async function previewLesson(l: TrainingLesson) { const m = await getMaterial(l.mainVersionId); previewKind.value = Number(m.duration) > 0 ? 'play' : ['jpg', 'jpeg', 'png', 'webp'].includes(m.extension) ? 'image' : 'pdf'; previewUrl.value = await materialTicket(l.mainVersionId, previewKind.value === 'play' ? 'play' : 'preview'); mediaOpen.value = true }
function setTime(key: 'opensAt' | 'closesAt', value: Date | null) { form.value[key] = value ? value.toISOString() : '' }
async function back() { if (dirty.value || uploader.value?.pending()) await ElMessageBox.confirm('存在未保存修改或未完成上传，确认离开？已完成分片会保留。', '返回课程列表'); clearTimeout(saveTimer); editorGeneration++; editing.value = false; meta.value = undefined; dirty.value = false; await loadList() }
watch(form, () => { if (loading.value || !editing.value) return; dirty.value = true; checked.value = false; clearTimeout(saveTimer); if (meta.value && meta.value.reviewState !== 'PENDING') saveTimer = setTimeout(() => { persist().catch(() => {}) }, 1500) }, { deep: true, flush: 'sync' })
onMounted(async () => { options.value = await trainingOptions(); await loadList(); pollTimer = setInterval(() => { if (editing.value && Object.values(versions).some(v => ['UPLOADING', 'UPLOADED', 'PROCESSING'].includes(v.state))) refreshVersions() }, 5000) })
onBeforeUnmount(() => { closed = true; clearTimeout(saveTimer); clearInterval(pollTimer) })
</script>

<style scoped src="./training.css"></style>
