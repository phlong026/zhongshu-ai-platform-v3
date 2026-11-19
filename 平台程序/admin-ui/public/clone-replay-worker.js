// Ephemeral only: worker termination releases every sample, including when no page is open.
const samples = new Map()
const lifetime = 24 * 60 * 60 * 1000
const limit = 4

function forget(key) {
  const sample = samples.get(key)
  if (sample) clearTimeout(sample.timer)
  samples.delete(key)
}

self.addEventListener('message', event => {
  const {action, key, uploadId, blob} = event.data || {}
  if (!/^[a-f0-9]{32}$/.test(key) || !/^\d+$/.test(uploadId)) return
  if (action === 'save' && blob instanceof Blob && blob.size > 0 && blob.size <= 20 * 1024 * 1024) {
    forget(key)
    if (samples.size >= limit) forget(samples.keys().next().value)
    samples.set(key, {uploadId, blob, expiresAt:Date.now() + lifetime,
      timer:setTimeout(() => forget(key), lifetime)})
    return
  }
  if (action === 'read') {
    const sample = samples.get(key)
    if (sample && Date.now() >= sample.expiresAt) forget(key)
    const valid = samples.has(key) && sample.uploadId === uploadId
    event.ports[0]?.postMessage(valid ? {blob:sample.blob, expiresAt:sample.expiresAt} : null)
  }
})
