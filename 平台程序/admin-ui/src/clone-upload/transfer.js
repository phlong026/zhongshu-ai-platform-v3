import 'crypto-js/sha256.js'
import CryptoJS from 'crypto-js/core.js'

const maxSize = 20 * 1024 * 1024
const mimeTypes = {
  mp4: ['video/mp4'], mov: ['video/mov', 'video/quicktime'], mp3: ['audio/mp3', 'audio/mpeg'],
  m4a: ['audio/m4a', 'audio/mp4', 'audio/x-m4a'], wav: ['audio/wav', 'audio/x-wav', 'audio/wave']
}

export function parseGrant(fragment) {
  const grant = JSON.parse(decodeURIComponent(fragment))
  const url = new URL(grant.uploadUrl)
  if (url.origin !== 'https://upload.hifly.cc' || url.username || url.password ||
      !/^\d+$/.test(grant.uploadId) || !mimeTypes[grant.extension]?.includes(grant.contentType) ||
      !Number.isFinite(grant.expiresAt) || Date.now() >= grant.expiresAt) throw Error('上传凭证无效，请返回重新选择')
  return grant
}

function read(blob) {
  if (blob.arrayBuffer) return blob.arrayBuffer()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(Error('文件读取失败，请重新选择'))
    reader.readAsArrayBuffer(blob)
  })
}

export function validateFile(file, grant) {
  if (!file || file.size <= 0 || file.size > maxSize) throw Error('文件须在 20 MiB 以内')
  if (file.name.split('.').pop().toLowerCase() !== grant.extension) throw Error('请选择 ' + grant.extension.toUpperCase() + ' 文件')
}

export async function fileDigest(file, alive) {
  // Importing SHA256 registers the incremental hasher on CryptoJS.algo.
  const hash = CryptoJS.algo.SHA256.create()
  for (let offset = 0; offset < file.size; offset += 256 * 1024) {
    if (!alive()) throw Error('已取消上传')
    const chunk = new Uint8Array(await read(file.slice(offset, offset + 256 * 1024)))
    const words = []
    for (let i = 0; i < chunk.length; i++) words[i >>> 2] |= chunk[i] << (24 - (i % 4) * 8)
    hash.update(CryptoJS.lib.WordArray.create(words, chunk.length))
  }
  return hash.finalize().toString()
}

// Read only the ISO-BMFF boxes on the path to the video sample description.
export async function hasH264(file) {
  let budget = 4096
  async function boxes(start, end, level) {
    while (start + 8 <= end && --budget > 0) {
      const bytes = new Uint8Array(await read(file.slice(start, Math.min(start + 16, end))))
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
      const type = String.fromCharCode(...bytes.subarray(4, 8))
      let length = view.getUint32(0), header = 8
      if (length === 1) {
        if (bytes.length < 16 || view.getUint32(8) !== 0) return false
        length = view.getUint32(12); header = 16
      } else if (length === 0) length = end - start
      if (length < header || start + length > end) return false
      if (level === 6 && (type === 'avc1' || type === 'avc3')) return true
      if (['moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd'][level] === type &&
          await boxes(start + header + (type === 'stsd' ? 8 : 0), start + length, level + 1)) return true
      start += length
    }
    return false
  }
  return boxes(0, file.size, 0)
}

export async function sendFile(file, grant, alive, onRetry) {
  validateFile(file, grant)
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!alive() || Date.now() >= grant.expiresAt) throw Error('上传凭证已过期或已离开页面，请返回重试')
    const abort = new AbortController()
    const cancel = () => abort.abort()
    window.addEventListener('pagehide', cancel)
    const timer = setTimeout(cancel, 120000)
    try {
      const res = await fetch(grant.uploadUrl, {method:'PUT', body:file, headers:{'Content-Type':grant.contentType},
        credentials:'omit', referrerPolicy:'no-referrer', redirect:'error', signal:abort.signal})
      if (res.ok) return
      if (res.status < 500) throw Object.assign(Error('上传凭证被拒绝，请返回重试'), {permanent:true})
    } catch (e) {
      if (e.permanent || !alive()) throw e
    } finally {
      clearTimeout(timer)
      window.removeEventListener('pagehide', cancel)
    }
    if (!attempt) onRetry()
  }
  throw Error('网络中断或超时，请重试')
}
