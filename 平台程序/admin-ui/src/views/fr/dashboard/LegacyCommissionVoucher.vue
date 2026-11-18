<template>
  <details class="zs-table-card mb-12px">
    <summary>旧版提成凭据</summary>
    <p>用当前岗位上传凭据。若要保存提成规则，请同时填写三期金额。将生成的凭据码粘贴到小程序「项目回款提成」的依据说明末尾，再点击保存或人工核对。</p>
    <el-radio-group v-model="mode">
      <el-radio-button value="policy">保存规则</el-radio-button>
      <el-radio-button value="review">人工核对</el-radio-button>
    </el-radio-group>
    <div v-if="mode === 'policy'" class="amounts">
      <label v-for="(label, index) in labels" :key="label">{{ label }}（元）
        <el-input v-model="values[index]" inputmode="decimal" placeholder="0.00" />
      </label>
    </div>
    <p><label>选择凭据（PDF、JPEG 或 PNG，最多 20 MiB）
      <input type="file" accept=".pdf,.jpg,.jpeg,.png" :disabled="busy" @change="upload" />
    </label></p>
    <p v-if="busy" role="status">正在上传并校验凭据</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="marker"><output>{{ marker }}</output> <el-button @click="copy">复制凭据码</el-button></div>
    <p v-else-if="assetId && mode === 'policy'">凭据已上传，请填写三期金额，三期合计须等于小程序中的提成总额。</p>
  </details>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/config/axios'
import { currentPostId, postGeneration } from '@/utils/platformIdentity'

const labels = ['签约后', '基础完成后', '封顶后']
const values = ref(['', '', ''])
const mode = ref('policy')
const busy = ref(false)
const error = ref('')
const assetId = ref('')
let generation = 0
onBeforeUnmount(() => { generation++ })
const marker = computed(() => {
  if (!assetId.value) return ''
  if (mode.value === 'review') return '[凭据#' + assetId.value + ']'
  if (!values.value.every(v => /^\d{1,9}(\.\d{1,2})?$/.test(v))) return ''
  const cents = values.value.map(v => Math.round(Number(v) * 100))
  if (cents.reduce((a, b) => a + b, 0) <= 0) return ''
  return '[凭据#' + assetId.value + ' 分期 ' + cents.join('/') + ']'
})
async function copy() {
  try { await navigator.clipboard.writeText(marker.value); ElMessage.success('凭据码已复制') }
  catch { error.value = '请选中并复制上方凭据码' }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  assetId.value = ''
  error.value = ''
  const mime = ({ png: 'image/png', pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg' } as Record<string,string>)[file.name.split('.').pop()?.toLowerCase() || '']
  if (!mime || file.size <= 0 || file.size > 20 * 1024 * 1024) {
    error.value = '请选择 20 MiB 以内的 PDF、JPEG 或 PNG 文件'
    return
  }
  const sequence = ++generation, post = currentPostId(), session = postGeneration()
  const active = () => sequence === generation && currentPostId() === post && postGeneration() === session
  busy.value = true
  try {
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    const sha256 = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
    if (!active()) return
    const ticket = await request.post({ url: '/franchise/assets/upload-tickets',
      data: { assetType: 'FIN_VOUCHER', sizeBytes: file.size, mimeType: mime, sha256 }, platformGeneration: session })
    if (!active()) return
    const id = String(ticket.assetId)
    if (!/^\d+$/.test(id) || (typeof ticket.assetId === 'number' && !Number.isSafeInteger(ticket.assetId))) throw Error('凭据编号无效')
    const url = String(ticket.uploadUrl)
    if (url.startsWith('/admin-api/franchise/assets/' + id + '/content?token=') && /^\/admin-api\/franchise\/assets\/\d+\/content\?token=[\w-]+$/.test(url)) {
      await request.put({ url: url.slice('/admin-api'.length), data: file, headersType: mime, platformGeneration: session })
    } else if (/^https:\/\//i.test(url)) {
      const res = await fetch(url, { method: 'PUT', body: file, headers: { 'Content-Type': mime }, credentials: 'omit', redirect: 'error' })
      if (!res.ok) throw Error('凭据上传失败')
    } else throw Error('服务器没有返回可用的上传地址')
    if (!active()) return
    const result = await request.post({ url: '/franchise/assets/' + id + '/complete', platformGeneration: session })
    if (!active()) return
    if (result.uploadStatus !== 'ACCEPTED') throw Error(result.rejectedReason || '凭据校验失败')
    assetId.value = id
  } catch (e) {
    if (active()) error.value = e instanceof Error ? e.message : '凭据上传失败，请重试'
  } finally { if (sequence === generation) busy.value = false }
}
</script>
<style scoped>
summary { cursor: pointer; font-weight: 600; }
.amounts { display: flex; flex-wrap: wrap; gap: 12px; margin: 12px 0; }
output { user-select: all; }
</style>
