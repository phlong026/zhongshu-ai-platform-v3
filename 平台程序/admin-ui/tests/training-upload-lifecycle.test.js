const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function loadLifecycle() {
  const filename = path.join(
    __dirname,
    '../src/views/platform/training/trainingUploadLifecycle.ts'
  )
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, setTimeout, Error })
  return module.exports
}

const immediateSleep = async () => {}

test('上传完成后持续查询后台，直到资料真正可用才返回', async () => {
  const { waitForTrainingUpload } = loadLifecycle()
  const states = [
    { version_id: 'v1', processing_state: 'UPLOADED' },
    { version_id: 'v1', processing_state: 'PROCESSING' },
    { version_id: 'v1', processing_state: 'READY' }
  ]
  const observed = []
  let clock = 0
  const result = await waitForTrainingUpload({
    uploadId: 'u1',
    getStatus: async () => states.shift(),
    onStatus: (state) => observed.push(state),
    sleep: immediateSleep,
    now: () => (clock += 1000)
  })
  assert.equal(result.versionId, 'v1')
  assert.deepEqual(observed, ['UPLOADED', 'PROCESSING', 'READY'])
})

test('处理失败返回具体原因，状态查询短暂失败会继续恢复', async () => {
  const { TrainingUploadFailure, waitForTrainingUpload } = loadLifecycle()
  let calls = 0
  const result = await waitForTrainingUpload({
    uploadId: 'u2',
    getStatus: async () => {
      calls++
      if (calls === 1) throw new Error('temporary network error')
      return { version_id: 'v2', processing_state: 'READY' }
    },
    sleep: immediateSleep
  })
  assert.equal(result.versionId, 'v2')
  assert.equal(calls, 2)

  await assert.rejects(
    () =>
      waitForTrainingUpload({
        uploadId: 'u3',
        getStatus: async () => ({
          version_id: 'v3',
          processing_state: 'FAILED',
          error: '视频编码不受支持'
        }),
        sleep: immediateSleep
      }),
    (error) =>
      error instanceof TrainingUploadFailure &&
      error.kind === 'processing' &&
      /视频编码不受支持/.test(error.message)
  )
})

test('资料库在版本可用后刷新上传分类并自动收起上传窗口', () => {
  const source = fs.readFileSync(
    path.join(__dirname, '../src/views/platform/training/materials.vue'),
    'utf8'
  )
  assert.match(source, /category\.value = uploadCategory\.value/)
  assert.match(source, /await select\(\{ version_id: id \}\)/)
  assert.match(source, /uploadOpen\.value = false/)
})
