import {readSample} from './replay-store'
import {fileDigest} from './transfer'

const el = id => document.getElementById(id)
const input = el('file'), status = el('status'), back = el('back')
el('send').hidden = true
input.hidden = true
back.hidden = false
back.textContent = '返回制作页'
back.onclick = () => window.wx?.miniProgram?.navigateBack({delta:1})
document.title = document.querySelector('h1').textContent = '回放上传素材'

let objectUrl, generation = 0
window.addEventListener('pagehide', () => {
  generation++
  if (objectUrl) URL.revokeObjectURL(objectUrl)
})

async function openReplay() {
  const raw = location.hash.slice('#replay='.length)
  history.replaceState(null, '', location.pathname)
  const source = JSON.parse(decodeURIComponent(raw))
  const mimeTypes = {mp4:'video/mp4', mov:'video/quicktime', mp3:'audio/mpeg', m4a:'audio/mp4', wav:'audio/wav'}
  const mime = mimeTypes[source.extension]
  if (!Object.prototype.hasOwnProperty.call(mimeTypes, source.extension) || !/^\d+$/.test(source.uploadId) ||
      !/^[a-f0-9]{64}$/.test(source.sha256) || !Number.isSafeInteger(source.sizeBytes) ||
      source.sizeBytes <= 0 || source.sizeBytes > 20 * 1024 * 1024) throw Error('回放信息无效，请返回制作页')
  const player = el(mime.startsWith('video/') ? 'preview' : 'voice')
  function play(blob) {
    if (objectUrl) URL.revokeObjectURL(objectUrl)
    // Use a playback MIME even if the picker or provider uses a nonstandard alias.
    objectUrl = URL.createObjectURL(blob.slice(0, blob.size, mime))
    player.src = objectUrl
    player.hidden = false
    input.hidden = true
    status.textContent = ''
  }
  el('instructions').textContent = source.name || '上传素材'
  const current = generation
  const blob = await readSample(source.previewKey, source.uploadId).catch(() => null)
  if (current !== generation) return
  if (blob && blob.size === source.sizeBytes) return play(blob)
  // Private browsing, storage eviction and older upload pages can leave a receipt without a local copy.
  status.textContent = '本机暂存不可用，请选择刚上传的原文件回放。'
  input.accept = '.' + source.extension
  input.hidden = false
  input.onchange = async () => {
    const attempt = ++generation, file = input.files[0]
    if (!file) return
    status.textContent = '正在核对原文件…'
    try {
      if (file.size !== source.sizeBytes || await fileDigest(file, () => attempt === generation) !== source.sha256)
        throw Error('与刚上传的素材不一致，请选择原文件')
      if (attempt === generation) play(file)
    } catch (e) { if (attempt === generation) status.textContent = e.message }
  }
}

openReplay().catch(e => { status.textContent = e.message })
