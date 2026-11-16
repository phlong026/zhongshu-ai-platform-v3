const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function home(overrides = {}, allowed = () => true) {
  const filename = path.join(__dirname, '../src/views/platform/home/index.vue')
  const script = compileScript(parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor, {
    id: 'hq-home'
  }).content
  const api = {
    getWalletSummary: async () => ({
      walletCount: 200,
      totalAvailablePoints: 200100,
      topWallets: []
    }),
    getLedger: async () => ({ list: [], total: 2000 }),
    getActiveDesignPrices: async () => ({ list: [], total: 3 }),
    getRechargePlans: async () => ({ list: [], total: 7 }),
    getRechargeOrders: async () => ({ list: [], total: 4 }),
    getAnomalies: async () => ({ list: [], total: 0 }),
    getDesignCosts: async () => ({ list: [], total: 9 }),
    getAudit: async () => ({ list: [], total: 1 }),
    ...overrides
  }
  const imports = {
    vue: { ...vue, onMounted() {} },
    'vue-router': { useRouter: () => ({ push() {} }) },
    '@element-plus/icons-vue': Object.fromEntries(
      [
        'Coin',
        'Wallet',
        'Document',
        'Warning',
        'PriceTag',
        'DataLine',
        'DocumentChecked',
        'Plus'
      ].map((k) => [k, {}])
    ),
    '@/components/Echart/src/Echart.vue': {},
    '@/utils/permission': { checkPermi: allowed },
    '@/api/platform/operations': api,
    '@/api/zs/platform-org': { getOrganizationPage: async () => ({ list: [], total: 0 }) }
  }
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(script, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      console,
      require(name) {
        if (name in imports) return imports[name]
        throw new Error(name)
      }
    }
  )
  const state = module.exports.default.setup({}, { expose() {} })
  const card = (label) => state.statCards.value.find((c) => c.label === label)
  return { state, card }
}

test('总部全量钱包200组织不截成100，套餐与生效价格读取真实总数', async () => {
  const { state, card } = home()
  await state.load()
  assert.equal(card('历史组织钱包数').value, '200')
  assert.equal(card('历史钱包总可用点数').value, Number(200100).toLocaleString())
  assert.equal(card('充值套餐数').value, '7')
  assert.equal(card('生效设计定价').value, '3')
})

test('单项失败不清空成功数据，失败/等待不显示业务零，允许重试', async () => {
  let fail = true
  const { state, card } = home({
    getAnomalies: async () => {
      if (fail) throw new Error('network')
      return { total: 0, list: [] }
    }
  })
  assert.equal(card('支付异常记录').value, '—')
  await state.load()
  assert.equal(card('历史组织钱包数').value, '200')
  assert.equal(card('支付异常记录').value, '加载失败')
  assert.equal(state.hasErrors.value, true)
  fail = false
  await state.load()
  assert.equal(card('支付异常记录').value, '无')
  assert.equal(state.hasErrors.value, false)
})

test('权限不足不发总部数据请求，卡片展示无权限且入口不可点击', async () => {
  let calls = 0
  const { state, card } = home(
    {
      getWalletSummary: async () => {
        calls++
        throw new Error('forbidden')
      }
    },
    () => false
  )
  await state.load()
  assert.equal(calls, 0)
  assert.equal(card('历史组织钱包数').value, '无权限')
  assert.equal(card('历史组织钱包数').to, '')
  assert.equal(state.quickEntries.value.length, 0)
})

test('重试后的新结果不被旧请求覆盖', async () => {
  let resolveFirst
  let calls = 0
  const { state, card } = home({
    getWalletSummary: () =>
      ++calls === 1
        ? new Promise((resolve) => {
            resolveFirst = resolve
          })
        : Promise.resolve({ walletCount: 201, totalAvailablePoints: 1, topWallets: [] })
  })
  const old = state.load()
  await state.load()
  resolveFirst({ walletCount: 10, totalAvailablePoints: 10, topWallets: [] })
  await old
  assert.equal(card('历史组织钱包数').value, '201')
})
