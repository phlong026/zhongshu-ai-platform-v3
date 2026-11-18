import {parseGrant, validateFile, fileDigest, hasH264, sendFile} from './transfer'
import {saveSample} from './replay-store'

const el = id => document.getElementById(id)
const keepPreview = new URLSearchParams(location.search).has('keepPreview')
const input = el('file'), send = el('send'), back = el('back'), status = el('status')
let grant, fileUrl, receipt, generation = 0
try {
  grant = parseGrant(location.hash.slice(1))
  history.replaceState(null, '', location.pathname)
  input.accept = '.' + grant.extension
  el('instructions').textContent = '选择 ' + grant.extension.toUpperCase() + ' 文件（最多 20 MiB），核对后直传飞影。'
} catch (e) { input.disabled = true; status.textContent = e.message }
window.addEventListener('pagehide', () => { generation++; if (fileUrl) URL.revokeObjectURL(fileUrl) })

input.onchange = () => {
  const current = ++generation, file = input.files[0]
  send.disabled = true; status.textContent = ''
  if (fileUrl) URL.revokeObjectURL(fileUrl)
  const video = grant.contentType.startsWith('video/')
  const preview = el(video ? 'preview' : 'voice')
  preview.hidden = true
  try { validateFile(file, grant) } catch (e) { status.textContent = e.message; return }
  preview.onloadedmetadata = async () => {
    if (current !== generation) return
    try {
      const duration = preview.duration
      if (!Number.isFinite(duration) || duration < 5 || duration > (video ? 1800 : 180)) throw Error(video ? '视频须为 5 秒至 30 分钟' : '音频须为 5 秒至 3 分钟')
      if (video) {
        const short = Math.min(preview.videoWidth, preview.videoHeight)
        if (short < 360 || short > 2160 || Math.max(preview.videoWidth, preview.videoHeight) > 4096) throw Error('视频分辨率须为 360p 至 4K')
        if (!await hasH264(file)) throw Error('请使用 H.264 编码的 MP4 或 MOV 视频')
      }
      if (current === generation) { send.disabled = false; status.textContent = file.name + ' · ' + Math.round(duration) + ' 秒' }
    } catch (e) { if (current === generation) status.textContent = e.message }
  }
  preview.onerror = () => { if (current === generation) { send.disabled = true; status.textContent = '无法读取媒体信息，请更换文件' } }
  fileUrl = URL.createObjectURL(file)
  preview.preload = 'metadata'; preview.src = fileUrl; preview.hidden = false
}

function deliver() {
  window.wx.miniProgram.postMessage({data:receipt})
  window.wx.miniProgram.navigateBack({delta:1, fail:() => { status.textContent = '已上传，请点左上角返回确认' }})
}
back.onclick = deliver
send.onclick = async () => {
  if (send.disabled) return
  if (!window.wx?.miniProgram) { status.textContent = '微信通信未就绪，请返回后重试'; return }
  send.disabled = true; input.disabled = true
  const current = generation, file = input.files[0], alive = () => current === generation
  try {
    status.textContent = '正在校验文件…'
    const digest = await fileDigest(file, alive)
    status.textContent = '正在直传飞影…'
    await sendFile(file, grant, alive, () => { status.textContent = '连接中断，正在用原凭证重试…' })
    if (!alive()) return
    receipt = {uploadId:grant.uploadId, sizeBytes:file.size, sha256:digest, name:file.name.slice(0,180)}
    if (keepPreview) {
      receipt.previewKey = await saveSample(grant.uploadId, file, grant.contentType).catch(() => '')
      if (!alive()) return
    }
    status.textContent = '客户端已上传，返回后可提交制作。'
    send.hidden = true; back.hidden = false
    deliver()
  } catch (e) {
    if (alive()) { input.disabled = false; send.disabled = false; status.textContent = e.message }
  }
}
