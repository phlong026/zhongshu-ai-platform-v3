<template>
  <div class="training-uploader" @dragover.prevent @drop.prevent="drop">
    <el-icon :size="32"><UploadFilled /></el-icon>
    <strong>拖拽视频与课件到这里</strong>
    <span>支持批量上传、暂停和续传；重新选择原文件可恢复上传</span>
    <div class="upload-actions"><el-button type="primary" @click="input?.click()">选择文件</el-button><el-button @click="folderInput?.click()">选择文件夹</el-button></div>
    <small>视频、音频、PDF、Word、PPT、Excel与授权资料包 · 每批最多100个</small>
    <input ref="input" type="file" multiple hidden @change="choose" />
    <input ref="folderInput" type="file" multiple webkitdirectory hidden @change="choose" />
  </div>
  <div v-if="jobs.length" class="upload-jobs" aria-live="polite">
    <div v-for="job in jobs" :key="job.key" class="upload-job">
      <div class="job-main">
        <strong>{{ job.file.name }}</strong>
        <span class="job-message"><el-icon v-if="job.state === 'processing'" class="is-loading"><Loading /></el-icon>{{ job.message }}</span>
        <el-progress :percentage="job.percent" :status="job.state === 'error' ? 'exception' : job.state === 'done' ? 'success' : undefined" />
      </div>
      <el-button v-if="job.state === 'running'" link @click="job.paused = true">暂停</el-button>
      <el-button v-if="['paused', 'error'].includes(job.state)" link type="primary" @click="resume(job)">继续 / 重试</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Loading, UploadFilled } from '@element-plus/icons-vue'
import CryptoJS from 'crypto-js'
import { ElMessage } from 'element-plus'
import { getCurrentUserId, getTenantId } from '@/utils/auth'
import { startUpload, getUpload, uploadPart, completeUpload, retryMaterial } from '@/api/platform/training'
import { TrainingUploadFailure, waitForTrainingUpload } from './trainingUploadLifecycle'
const props = defineProps<{ category: string; materialId?: string; materialName?: string }>()
const emit = defineEmits<{ complete: [versionId: string, filename: string] }>()
type Job = { key: string; file: File; state: string; percent: number; message: string; paused: boolean; category: string; materialId?: string; materialName?: string; uploadId?: string; versionId?: string; retryMode?: 'upload' | 'poll' | 'process' }
const input = ref<HTMLInputElement>(), folderInput = ref<HTMLInputElement>()
const jobs = reactive<Job[]>([])
let running = 0, disposed = false
const CHUNK = 4 * 1024 * 1024
function queue(files: File[]) {
  if (files.length > 100) { ElMessage.warning('每批最多100个文件'); return }
  for (const file of files) jobs.push({ key: crypto.randomUUID(), file, state: 'queued', percent: 0, message: '等待上传', paused: false, category: props.category, materialId: props.materialId, materialName: props.materialName, retryMode: 'upload' })
  pump()
}
function choose(e: Event) { const el = e.target as HTMLInputElement; queue(Array.from(el.files || [])); el.value = '' }
function drop(e: DragEvent) { queue(Array.from(e.dataTransfer?.files || [])) }
async function resume(job: Job) {
  if (job.retryMode !== 'upload' && job.versionId) {
    job.state = 'processing'; job.message = '正在重新确认处理状态'
    try {
      if (job.retryMode === 'process') {
        job.message = '已重新提交处理，正在等待可用版本'
        await retryMaterial(job.versionId)
      }
      await pollProcessing(job)
    } catch (error: any) {
      fail(job, error, 'process')
    }
    return
  }
  job.paused = false; job.state = 'queued'; job.retryMode = 'upload'; pump()
}
function pump() { if (disposed) return; while (running < 3) { const job = jobs.find(j => j.state === 'queued'); if (!job) break; job.state = 'running'; running++; run(job).finally(() => { running--; pump() }) } }
function errorText(error: any, fallback: string) {
  return error?.message === 'Network Error' ? '网络已中断，恢复网络后点击继续 / 重试' : error?.message || fallback
}
function fail(job: Job, error: any, mode: 'upload' | 'poll' | 'process') {
  if (disposed) return
  job.state = 'error'; job.retryMode = mode; job.message = errorText(error, '上传未完成，请重试')
}
async function pollProcessing(job: Job) {
  try {
    const result = await waitForTrainingUpload({
      uploadId: job.uploadId!,
      getStatus: () => getUpload(job.uploadId!),
      isAlive: () => !disposed,
      onStatus: (state, elapsedMs) => {
        const seconds = Math.max(0, Math.round(elapsedMs / 1000))
        job.state = 'processing'
        job.message = state === 'PROCESSING'
          ? `正在生成小程序可查看版本，已用 ${seconds} 秒；可关闭窗口，后台会继续处理`
          : `文件已上传，等待后台处理，已用 ${seconds} 秒；可关闭窗口`
      }
    })
    if (disposed) return
    job.versionId = result.versionId; job.state = 'done'; job.percent = 100
    job.message = `处理完成，已加入资料库（用时 ${Math.max(1, Math.ceil(result.elapsedMs / 1000))} 秒）`
    emit('complete', result.versionId, job.file.name)
  } catch (error) {
    const mode = error instanceof TrainingUploadFailure
      ? error.kind === 'processing' ? 'process' : 'poll'
      : 'poll'
    fail(job, error, mode)
  }
}
async function run(job: Job) {
  try {
    const identity = `${getTenantId()}:${getCurrentUserId()}`
    const alive = () => !disposed && identity === `${getTenantId()}:${getCurrentUserId()}`
    job.message = '正在校验文件'; const hasher = CryptoJS.algo.SHA256.create()
    for (let offset = 0; offset < job.file.size; offset += CHUNK) {
      if (!alive() || job.paused) { job.state = 'paused'; job.message = '已暂停'; return }
      hasher.update(CryptoJS.lib.WordArray.create(await job.file.slice(offset, offset + CHUNK).arrayBuffer()))
    }
    const sha256 = hasher.finalize().toString()
    const cacheKey = 'zs-training-upload:' + JSON.stringify([identity, job.materialId || 'new', job.category, job.materialName || '', job.file.name, sha256, job.file.size])
    let remembered: { requestKey: string; uploadId?: string } | null = null
    try { remembered = JSON.parse(localStorage.getItem(cacheKey) || 'null') } catch { /* Recreate an invalid local hint. */ }
    const hint = remembered || { requestKey: crypto.randomUUID() }
    localStorage.setItem(cacheKey, JSON.stringify(hint))
    let upload = hint.uploadId ? await getUpload(hint.uploadId) : await startUpload({ name: job.materialName || job.file.name.replace(/\.[^.]+$/, '').slice(0, 120), category: job.category, materialId: job.materialId || null, fileName: job.file.name, size: job.file.size, sha256, requestKey: hint.requestKey })
    if (!alive()) return
    localStorage.setItem(cacheKey, JSON.stringify({ requestKey: hint.requestKey, uploadId: upload.id }))
    job.uploadId = upload.id; job.versionId = upload.version_id
    const done = new Set<number>(upload.parts)
    if (upload.state !== 'COMPLETE') for (let i = 0; i < upload.chunk_count; i++) {
      if (!alive() || job.paused) { job.state = 'paused'; job.message = '已暂停，可继续上传'; return }
      if (!done.has(i)) await uploadPart(upload.id, i, job.file.slice(i * CHUNK, (i + 1) * CHUNK))
      done.add(i); job.percent = Math.round(100 * done.size / upload.chunk_count); job.message = `上传中 ${job.percent}%`
    }
    if (!alive()) return
    upload = await completeUpload(upload.id)
    if (!alive()) return
    job.uploadId = upload.id; job.versionId = upload.version_id; job.percent = 100
    job.state = 'processing'; job.retryMode = 'poll'
    job.message = '文件上传完成，正在生成小程序可查看版本；可关闭窗口，后台会继续处理'
    await pollProcessing(job)
  } catch (error) { fail(job, error, 'upload') }
}
function pending() { return jobs.some(j => ['queued', 'running', 'processing', 'paused', 'error'].includes(j.state)) }
function hasActiveTransfer() { return jobs.some(j => ['queued', 'running', 'paused'].includes(j.state) || (j.state === 'error' && j.retryMode === 'upload')) }
defineExpose({ pending, hasActiveTransfer })
onBeforeUnmount(() => { disposed = true; jobs.forEach(j => { j.paused = true }) })
</script>

<style scoped>
.training-uploader{border:1px dashed #d8c8b6;background:#fdfbf8;display:flex;align-items:center;flex-direction:column;gap:10px;padding:26px 16px;border-radius:8px;color:#89512a}.training-uploader strong{color:#30291f;font-size:17px}.training-uploader span,.training-uploader small{color:#776e64;line-height:1.6;text-align:center}.upload-actions{display:flex;gap:8px}.upload-jobs{margin-top:16px}.upload-job{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid #eee8df}.job-main{flex:1;min-width:0}.job-main strong{font-size:14px;display:block;overflow-wrap:anywhere}.job-main span{font-size:12px;color:#6f665a}.el-progress{max-width:500px}
.job-message{display:flex;align-items:center;gap:6px;padding:5px 0}.job-message .is-loading{flex:none;color:#a25d2a}
</style>
