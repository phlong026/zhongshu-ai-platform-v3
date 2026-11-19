// Only a live worker holds the sample. The mini program receives an opaque handle and a digest.
let workerPromise
function worker() {
  if (!workerPromise) {
    workerPromise = navigator.serviceWorker.register('./clone-replay-worker.js', {scope:'./clone-replay-session/'}).then(reg => {
      if (reg.active) return reg.active
      const installing = reg.installing || reg.waiting
      if (!installing) throw Error('回放暂存不可用')
      return new Promise((resolve, reject) => {
        function changed() {
          if (installing.state === 'activated') resolve(installing)
          else if (installing.state === 'redundant') reject(Error('回放暂存不可用'))
        }
        installing.addEventListener('statechange', changed)
        changed()
      })
    })
  }
  return workerPromise
}

export function prepareReplay() {
  Promise.resolve().then(worker).catch(() => {})
}

export function saveSample(uploadId, file, mime) {
  try {
    const key = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')
    let timer
    const deadline = new Promise(resolve => { timer = setTimeout(() => resolve(null), 1200) })
    Promise.race([worker(), deadline]).then(w => {
      if (w) w.postMessage({action:'save', key, uploadId, blob:file.slice(0, file.size, mime)})
    }).catch(() => {}).finally(() => clearTimeout(timer))
    return key
  } catch { return '' }
}

export function readSample(key, uploadId) {
  if (!/^[a-f0-9]{32}$/.test(key)) return Promise.resolve(null)
  return new Promise(resolve => {
    const channel = new MessageChannel()
    let done = false
    function finish(value) {
      if (done) return
      done = true
      clearTimeout(timer)
      channel.port1.close()
      channel.port2.close()
      resolve(value)
    }
    const timer = setTimeout(() => finish(null), 1200)
    channel.port1.onmessage = event => finish(event.data)
    Promise.resolve().then(worker).then(w => {
      if (!done) w.postMessage({action:'read', key, uploadId}, [channel.port2])
    }).catch(() => finish(null))
  })
}
