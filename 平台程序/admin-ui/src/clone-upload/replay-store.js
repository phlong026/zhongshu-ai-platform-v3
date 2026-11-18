// Only this device holds the selected Blob. The small, short-lived key can cross the web-view bridge.
const lifetime = 24 * 60 * 60 * 1000
const limit = 4

function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('zs-clone-replay', 1)
    request.onupgradeneeded = () => {
      request.result.createObjectStore('samples', {keyPath:'key'}).createIndex('created', 'created')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function saveSample(uploadId, file, mime) {
  const key = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')
  const db = await database()
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction('samples', 'readwrite')
      tx.oncomplete = resolve
      tx.onabort = () => reject(tx.error || Error('素材暂存失败'))
      const store = tx.objectStore('samples')
      const now = Date.now()
      store.put({key, uploadId, created:now, blob:file.slice(0, file.size, mime)})
      let seen = 0
      const request = store.index('created').openKeyCursor(null, 'prev')
      request.onsuccess = () => {
        const cursor = request.result
        if (!cursor) return
        if (++seen > limit || cursor.key <= now - lifetime) store.delete(cursor.primaryKey)
        cursor.continue()
      }
    })
    return key
  } finally { db.close() }
}

export async function readSample(key, uploadId) {
  if (!/^[a-f0-9]{32}$/.test(key)) return null
  const db = await database()
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('samples', 'readwrite')
      let blob = null
      tx.oncomplete = () => resolve(blob)
      tx.onabort = () => reject(tx.error || Error('暂存素材不可用'))
      const store = tx.objectStore('samples')
      const request = store.get(key)
      request.onsuccess = () => {
        const record = request.result
        if (!record || record.uploadId !== uploadId) return
        if (Date.now() - record.created >= lifetime) store.delete(key)
        else blob = record.blob
      }
    })
  } finally { db.close() }
}
