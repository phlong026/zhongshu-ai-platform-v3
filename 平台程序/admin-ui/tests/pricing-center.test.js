const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')
function load(file, imports = {}) {
  const filename = path.join(__dirname, '../src', file)
  const module = { exports: {} }
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  vm.runInNewContext(code, { module, exports: module.exports, require: (key) => imports[key] })
  return module.exports
}
const int64 = load('views/platform/org-points/int64.ts')
const pricing = load('views/platform/pricing/catalog.ts', { '../org-points/int64': int64 })
const base = (subject, priceMicros = '333333', unit = 'SECOND') => ({
  subject,
  label: subject,
  priceMicros,
  minCharge: '1',
  unit,
  versionText: 'v1',
  configured: false
})

test('缺少克隆能力的旧服务保留入口但不伪造可发布价格', () => {
  const rows = pricing.buildCatalog([base('digital')])
  assert.equal(rows.length, 3)
  for (const row of rows.slice(1)) {
    assert.equal(row.available, false)
    assert.equal(pricing.priceState(row), '待服务发布')
    assert.equal(pricing.estimateCredits(row, '1'), '—')
  }
})
test('服务返回的形象与语音科目直接接入，未定价不显示免费', () => {
  const rows = pricing.buildCatalog([
    base('avatar_clone', '0', 'CALL'),
    base('voice_clone', '5000000', 'CALL')
  ])
  assert.equal(rows.length, 2)
  assert.equal(pricing.priceState(rows[0]), '待定价')
  assert.equal(pricing.estimateCredits(rows[0], '1'), '—')
  assert.equal(pricing.estimateCredits(rows[1], '1'), '5')
})
test('费用精确向上取整并遵守每笔最低收费，不出现浮点误差', () => {
  const row = pricing.buildCatalog([base('ai_video_2k')])[0]
  assert.equal(pricing.estimateCredits(row, '3'), '1')
  assert.equal(pricing.estimateCredits(row, '30'), '10')
  assert.equal(pricing.estimateCredits({ ...row, minCharge: '12' }, '30'), '12')
  assert.equal(pricing.estimateCredits({ ...row, priceMicros: '166666' }, '6'), '1')
})
test('试算拒绝空值、小数、负数和超出计量上限的输入', () => {
  const row = pricing.buildCatalog([base('copy', '2000000', 'CALL')])[0]
  for (const quantity of ['', '0', '-1', '1.5', '1e3', '1000000001'])
    assert.equal(pricing.estimateCredits(row, quantity), '—')
  assert.equal(pricing.estimateCredits(row, '1000000000'), '2000000000')
})
test('新服务扩展能力仍展示服务端价格，不被前端分类清单丢弃', () => {
  const row = pricing.buildCatalog([
    { ...base('new_service', '7000000', 'CALL'), configured: true }
  ])[0]
  assert.equal(row.category, 'other')
  assert.equal(pricing.priceState(row), '已配置')
  assert.equal(pricing.estimateCredits(row, '2'), '14')
})
test('定价菜单按查询权限显示，价格入口不要求先选公司', () => {
  const navigation = load('utils/projectNavigation.ts')
  const allowed = navigation.buildPlatformMenus([{ path: '/', meta: {} }], [], (permissions) =>
    permissions.includes('platform:org-point:price:query')
  )
  assert.equal(allowed[1].path, '/platform-pricing')
  assert.equal(navigation.buildPlatformMenus([], [], () => true).length, 0)
  assert.equal(navigation.buildPlatformMenus([], [], () => false).length, 0)
})

function editorHarness(publish, allowed = true, brokenStorage = false) {
  const filename = path.join(__dirname, '../src/views/platform/pricing/PriceEditor.vue')
  const script = compileScript(parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor, {
    id: 'pricing-editor'
  }).content
  const values = new Map()
  const storage = {
    get length() {
      return values.size
    },
    key(index) {
      return [...values.keys()][index] || null
    },
    getItem(key) {
      return values.get(key) || null
    },
    setItem(key, value) {
      if (brokenStorage) throw new Error('无法保存待确认记录')
      values.set(key, value)
    },
    removeItem(key) {
      values.delete(key)
    }
  }
  const imports = {
    vue,
    'element-plus': { ElMessage: { error() {}, success() {} } },
    '@/store/modules/user': { useUserStore: () => ({ getUser: { id: 88 } }) },
    '@/utils/auth': { getTenantId: () => 1 },
    '@/utils/permission': { checkPermi: () => allowed },
    '@/api/platform/orgPoints': { publishSubjectPrice: publish },
    '../org-points/priceDraft': load('views/platform/org-points/priceDraft.ts'),
    '../org-points/pricePending': load('views/platform/org-points/pricePending.ts'),
    '../org-points/persistedRequest': load('views/platform/org-points/persistedRequest.ts'),
    '../org-points/int64': int64,
    './catalog': pricing
  }
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(script, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      Error,
      require: (key) => imports[key],
      localStorage: storage,
      crypto: require('node:crypto')
    }
  )
  return { state: module.exports.default.setup({}, { expose() {}, emit() {} }), values }
}

test('价格发布丢失响应后保持原价格、原因和请求键，重试成功才清除', async () => {
  const requests = []
  const { state, values } = editorHarness(async (payload) => {
    requests.push({ ...payload })
    if (requests.length === 1) throw new Error('响应丢失')
    return 'price-id'
  })
  state.open(pricing.buildCatalog([base('copy', '2000000', 'CALL')])[0])
  state.draft.value.reason = '运营调价'
  await state.save()
  assert.equal(state.visible.value, true)
  assert.equal(state.draft.value.attempted, true)
  assert.equal(values.size, 1)
  state.draft.value.points = '999'
  await state.save()
  assert.deepEqual(requests[0], requests[1])
  assert.equal(requests[1].priceMicros, 2000000)
  assert.equal(values.size, 0)
  assert.equal(state.visible.value, false)
})

test('无法持久保存或没有调价权限时不发起价格写入', async () => {
  let requests = 0
  const item = pricing.buildCatalog([base('copy', '2000000', 'CALL')])[0]
  const broken = editorHarness(
    async () => {
      requests++
    },
    true,
    true
  ).state
  broken.open(item)
  broken.draft.value.reason = '运营调价'
  await broken.save()
  assert.equal(requests, 0)
  assert.equal(broken.draft.value.attempted, false)
  assert.match(broken.error.value, /无法保存/)
  const readonly = editorHarness(async () => {
    requests++
  }, false).state
  readonly.open(item)
  await readonly.save()
  assert.equal(requests, 0)
  assert.equal(readonly.visible.value, false)
})
