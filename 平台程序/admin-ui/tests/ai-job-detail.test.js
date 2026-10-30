const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')

// 需求四二期：生成记录详情展示最终提示词与所用模型；历史任务/引擎未回传时要明说"未记录"，而不是显示空白。
function component(api) {
  const filename = path.join(__dirname, '../src/views/zs/job/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const compiled = compileScript(descriptor, { id: 'ai-job' })
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: 'ai-job', compilerOptions: { bindingMetadata: compiled.bindings } }).errors, [])
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }, hooks = {}
  vm.runInNewContext(code, {
    module, exports: module.exports, ...vue,
    onMounted: fn => { hooks.mount = fn },
    useMessage: () => ({ error() {}, success() {}, warning() {}, confirm: async () => {} }),
    require(name) {
      if (name === 'vue') return vue
      if (name === '@vueuse/core') return { useMediaQuery: () => vue.ref(false) }
      if (name === '@/api/zs') return api
      if (name === '@/utils/zsFormat') return { fmtTime: value => (value ? '北京时间' : '—') }
      throw Error(name)
    }
  })
  return { state: module.exports.default.setup({ embedded: false }, { expose() {} }), hooks, template: descriptor.template.content }
}

const listApi = { getAiJobPage: async () => ({ list: [], total: 0 }) }

test('详情打开后展示后端返回的提示词与模型', async () => {
  const app = component({ ...listApi, getAiJob: async id => ({ jobId: id, promptText: '两层别墅，白墙灰瓦\n坡屋顶', providerModel: 'gpt-fixture' }) })
  await app.state.openDetail({ jobId: '9007199254740993' })
  assert.equal(app.state.detail.visible, true)
  assert.deepEqual({ ...app.state.promptInfo.value }, { recorded: true, text: '两层别墅，白墙灰瓦\n坡屋顶', model: 'gpt-fixture' })
})

test('没有记录时明说原因，而不是显示空白', async () => {
  const app = component({ ...listApi, getAiJob: async id => ({ jobId: id }) })
  await app.state.openDetail({ jobId: '1' })
  const info = app.state.promptInfo.value
  assert.equal(info.recorded, false)
  assert.match(info.text, /未记录/)
  assert.equal(info.model, '—')
})

test('有提示词但没有模型名时，模型显示为未回传', async () => {
  const app = component({ ...listApi, getAiJob: async () => ({ promptText: '提示词' }) })
  await app.state.openDetail({ jobId: '1' })
  assert.equal(app.state.promptInfo.value.model, '未回传')
})

test('提示词按纯文本渲染，不用 v-html', () => {
  const app = component(listApi)
  assert.match(app.template, /promptInfo\.text/)
  assert.doesNotMatch(app.template, /v-html/)
})

test('平面详情按需读取实际调用，计划输出与实际生图提示词分开，像素只取匹配的入库资产', async () => {
  const ids = []
  const call = { callId: 'image-11-1', stage: 'GENERATE', slot: 1, sourceAttemptNo: 1, state: 'SUCCEEDED',
    request: { model: 'image-fixture', promptVersion: 'flat-fixture', promptText: '原需求与各层核对后的实际文本', size: '1536x1024' },
    response: { sha256: 'a'.repeat(64), width: 1, height: 1 } }
  const app = component({ ...listApi,
    getAiJob: async id => ({ jobId: id, phase: 'FLAT', promptText: '仅计划输出', providerModel: 'text-fixture' }),
    getAiJobCallTraces: async id => { ids.push(id); return { calls: [call], acceptedOutputs: [{ slot: 1, assetId: '22', sourceSha256: 'a'.repeat(64), width: 13, height: 7 }] } }
  })
  await app.state.openDetail({ jobId: '11' })
  assert.deepEqual(ids, ['11'])
  assert.equal(app.state.promptInfo.value.text, '仅计划输出')
  assert.equal(app.state.detail.calls[0].request.promptText, call.request.promptText)
  assert.equal(app.state.tracePixelText(call), '13 × 7 px')
  assert.match(app.template, /计划阶段输出提示词/)
  assert.match(app.template, /生图请求完整提示词/)
  assert.equal(app.state.detail.traceError, false)
})

test('历史任务没有调用记录时不根据旧计划提示词和请求档位补造实际数据', async () => {
  const app = component({ ...listApi,
    getAiJob: async id => ({ jobId: id, phase: 'FLAT', promptText: '旧计划输出', imageOptions: { resolution: '4K' } }),
    getAiJobCallTraces: async () => ({ calls: [], acceptedOutputs: [] })
  })
  await app.state.openDetail({ jobId: '11' })
  assert.equal(app.state.detail.calls.length, 0)
  assert.match(app.state.tracePixelText({ slot: 1, response: {} }), /未知/)
  assert.match(app.template, /历史调用未记录/)
  assert.equal(app.state.detail.traceError, false)
})

test('调用记录加载错误与历史未记录分开，并允许重新加载', async () => {
  let fail = true
  const app = component({ ...listApi,
    getAiJob: async id => ({ jobId: id, phase: 'FLAT' }),
    getAiJobCallTraces: async () => { if (fail) throw Error('network'); return { calls: [], acceptedOutputs: [] } }
  })
  await app.state.openDetail({ jobId: '11' })
  assert.equal(app.state.detail.traceError, true)
  assert.equal(app.state.detail.traceLoading, false)
  fail = false
  await app.state.loadCallTraces('11')
  assert.equal(app.state.detail.traceError, false)
})

test('槽位一致但摘要不符或扫描宽高缺失时保持未知，不用回包解析或请求尺寸替代', async () => {
  const app = component({ ...listApi,
    getAiJob: async id => ({ jobId: id, phase: 'FLAT' }),
    getAiJobCallTraces: async () => ({ calls: [], acceptedOutputs: [{ slot: 1, sourceSha256: 'other', width: 3840, height: 2160 }, { slot: 2, sourceSha256: 'same', width: null, height: null }] })
  })
  await app.state.openDetail({ jobId: '11' })
  assert.match(app.state.tracePixelText({ slot: 1, request: { size: '1536x1024' }, response: { sha256: 'same', width: 1536, height: 1024 } }), /未知/)
  assert.match(app.state.tracePixelText({ slot: 2, response: { sha256: 'same' } }), /未知/)
})

test('立面详情不新增调用记录请求', async () => {
  let traces = 0
  const app = component({ ...listApi, getAiJob: async id => ({ jobId: id, phase: 'ELEVATION' }),
    getAiJobCallTraces: async () => { traces++; return {} } })
  await app.state.openDetail({ jobId: '11' })
  assert.equal(traces, 0)
})

test('快速切换任务时迟到的旧任务记录不能覆盖新任务详情', async () => {
  let release, started
  const waiting = new Promise(resolve => { started = resolve })
  const first = new Promise(resolve => { release = resolve })
  const app = component({ ...listApi, getAiJob: async id => ({ jobId: id, phase: 'FLAT' }),
    getAiJobCallTraces: async id => { if (id === '11') { started(); return first } return { calls: [{ callId: 'image-22-1' }], acceptedOutputs: [] } } })
  const old = app.state.openDetail({ jobId: '11' })
  await waiting
  await app.state.openDetail({ jobId: '22' })
  release({ calls: [{ callId: 'image-11-1' }], acceptedOutputs: [] })
  await old
  assert.equal(app.state.detail.data.jobId, '22')
  assert.equal(app.state.detail.calls[0].callId, 'image-22-1')
})
