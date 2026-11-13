const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const source = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
function load(file, imports = {}) {
  const code = ts.transpileModule(source(file), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    require: (name) => {
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

function walletView(api, allowed = true) {
  const filename = 'views/platform/wallet/index.vue'
  const script = compileScript(parse(source(filename), { filename }).descriptor, {
    id: 'wallet-ops'
  }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS }
  }).outputText
  const module = { exports: {} }
  const route = vue.reactive({ path: '/platform-ops/wallets' })
  const imports = {
    vue: { ...vue, watch() {}, onBeforeUnmount() {} },
    'vue-router': { useRoute: () => route },
    '@/utils/permission': { checkPermi: () => allowed },
    '@/api/platform/operations': api
  }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    Error,
    require: (name) => imports[name]
  })
  return { state: module.exports.default.setup({}, { expose() {} }), route }
}

test('运营查询失败保留明确错误，清除旧结果，不把失败展示为空数据成功', async () => {
  const { state } = walletView({
    getWallets: async () => {
      throw new Error('服务未就绪')
    }
  })
  await state.load()
  assert.equal(state.rows.value.length, 0)
  assert.equal(state.total.value, 0)
  assert.match(state.error.value, /服务未就绪/)
  assert.equal(state.loading.value, false)
})

test('运营列表分页及迟到响应隔离，不向无权限账号发请求', async () => {
  let resolveFirst
  let calls = 0
  const { state } = walletView({
    getWallets: async () => {
      calls++
      return calls === 1
        ? new Promise((resolve) => {
            resolveFirst = resolve
          })
        : { list: [{ id: '9007199254740993' }], total: 1 }
    }
  })
  const old = state.load()
  state.pageNo.value = 2
  await state.load()
  resolveFirst({ list: [{ id: 'old' }], total: 2 })
  await old
  assert.equal(state.rows.value[0].id, '9007199254740993')
  assert.equal(state.total.value, 1)
  const denied = walletView(
    {
      getWallets: async () => {
        throw new Error('must not call')
      }
    },
    false
  ).state
  await denied.load()
  assert.match(denied.error.value, /权限/)
})

test('B运行入口不注册A管理路由，首页不请求A运营数据', () => {
  assert.doesNotMatch(source('router/index.ts'), /import zsRouter|\.\.\.zsRouter/)
  assert.doesNotMatch(source('router/modules/remaining.ts'), /views\/zs\/dashboard/)
  assert.match(source('router/modules/remaining.ts'), /views\/platform\/home\/index.vue/)
  assert.doesNotMatch(source('store/modules/permission.ts'), /import zsRouter|buildProjectMenus/)
  assert.match(source('utils/routerHelper.ts'), /!\.\.\/views\/zs\/\*\*/)
  assert.match(source('utils/routerHelper.ts'), /\.\.\/views\/zs\/platform-org\/index.vue/)
})

test('总部菜单按明确权限过滤，保留已有组织和员工支持，不暴露A或岗位权限', () => {
  const { buildPlatformMenus } = load('utils/projectNavigation.ts')
  const base = [{ path: '/', children: [{ path: 'index' }] }]
  const authorized = [
    { path: '/zs', children: [{ path: 'recharge' }] },
    {
      path: '/platform-ops',
      children: [
        { path: 'wallets', meta: { title: '组织钱包' } },
        { path: 'orders', meta: { title: '充值订单' } }
      ]
    },
    { path: '/platform-org', children: [{ path: 'onboard', meta: { title: '组织开通' } }] },
    { path: '/system', children: [{ path: 'user' }, { path: 'menu' }] },
    { path: '/fr', children: [{ path: 'org/staff' }] }
  ]
  const before = JSON.stringify(authorized)
  const result = buildPlatformMenus(base, authorized, (permissions) =>
    permissions.includes('platform:wallet:order:query')
  )
  assert.deepEqual(
    Array.from(result, (row) => row.path),
    ['/', '/platform-ops', '/platform-org', '/system']
  )
  assert.deepEqual(
    Array.from(result[1].children, (row) => row.path),
    ['orders']
  )
  assert.equal(result[1].redirect, '/platform-ops/orders')
  assert.deepEqual(
    Array.from(result[3].children, (row) => row.path),
    ['user']
  )
  assert.equal(JSON.stringify(authorized), before)
  const none = buildPlatformMenus(base, authorized, () => false)
  assert.equal(
    none.some((row) => row.path === '/platform-ops'),
    false
  )
})

test('B运营客户端只访问B总部API，保留字符串ID与整数分页', async () => {
  const requests = []
  const api = load('api/platform/operations.ts', {
    '@/config/axios': {
      __esModule: true,
      default: {
        get: async (request) => {
          requests.push(request)
          return { list: [], total: 0 }
        }
      }
    }
  })
  await api.getWallets({ pageNo: 2, pageSize: 20, orgId: '9007199254740993' })
  await api.getRechargePlans({ pageNo: 1, pageSize: 20 })
  await api.getRechargeOrders({ pageNo: 1, pageSize: 20 })
  await api.getLedger({ pageNo: 1, pageSize: 20 })
  await api.getAnomalies({ pageNo: 1, pageSize: 20 })
  await api.getWalletSummary()
  await api.getActiveDesignPrices({ pageNo: 1, pageSize: 10 })
  assert.deepEqual(
    requests.map((request) => request.url),
    [
      '/platform/ops/wallets',
      '/platform/ops/recharge-plans',
      '/platform/ops/recharge-orders',
      '/platform/ops/ledger',
      '/platform/ops/anomalies',
      '/platform/ops/wallet-summary',
      '/platform/ops/design-prices/active'
    ]
  )
  assert.equal(requests[0].params.orgId, '9007199254740993')
  assert.equal(requests[0].params.pageNo, 2)
  assert.equal(
    requests.some((request) => request.params && Object.hasOwn(request.params, 'tenantId')),
    false
  )
})

test('配置写入带原版本且使用本地B API，核对备注不触发支付动作', async () => {
  const calls = []
  const request = {}
  for (const method of ['get', 'post', 'put'])
    request[method] = async (config) => {
      calls.push({ method, ...config })
      return {}
    }
  const api = load('api/platform/operations.ts', {
    '@/config/axios': { __esModule: true, default: request }
  })
  const data = {
    expectedVersion: 3,
    name: '新名称',
    priceFen: 199,
    basePoints: 10,
    bonusPoints: 0,
    status: 'DISABLED',
    sort: 0,
    wxProductId: null,
    reason: '价格调整'
  }
  await api.updateRechargePlan('9007199254740993', data)
  await api.reviewAnomaly('9007199254740995', '已核对渠道事实，等待自动恢复')
  await api.getAudit({ pageNo: 1, pageSize: 20 })
  await api.getDesignPrices({ pageNo: 1, pageSize: 20 })
  await api.getDesignCosts({ pageNo: 1, pageSize: 20 })
  assert.equal(calls[0].url, '/platform/ops/recharge-plans/9007199254740993')
  assert.equal(calls[0].method, 'put')
  assert.equal(calls[0].data.expectedVersion, 3)
  assert.equal(calls[1].url, '/platform/ops/anomalies/9007199254740995/reviews')
  assert.equal(
    calls.some((call) => /pay-params|refund|credit|design\/commerce/.test(call.url)),
    false
  )
})

test('套餐保存保留原版本，冲突保留表单并提示，无写权限不发请求', async () => {
  let payload
  const { state, route } = walletView({
    updateRechargePlan: async (id, data) => {
      payload = { id, ...data }
      throw new Error('套餐版本已变化')
    }
  })
  route.path = '/platform-ops/plans'
  state.openPlan({
    id: '9007199254740993',
    name: '套餐',
    priceFen: 199,
    basePoints: 10,
    bonusPoints: 0,
    version: 3,
    status: 'DISABLED',
    sort: 0,
    wxProductId: null
  })
  state.planForm.value.reason = '改价'
  await state.savePlan()
  assert.equal(payload.expectedVersion, 3)
  assert.equal(payload.id, '9007199254740993')
  assert.equal(state.planVisible.value, true)
  assert.match(state.editError.value, /版本已变化/)
  const denied = walletView(
    {
      createRechargePlan: async () => {
        throw new Error('must not call')
      }
    },
    false
  ).state
  await denied.savePlan()
  assert.match(denied.editError.value, /权限/)
})

test('重新打开同一异常时，旧核对保存响应不能清空新说明', async () => {
  let finish
  const { state } = walletView({
    getAnomalyReviews: async () => ({ list: [], total: 0 }),
    reviewAnomaly: async () =>
      new Promise((resolve) => {
        finish = resolve
      })
  })
  state.openReviews('7')
  state.reviewNote.value = '第一次核对'
  const old = state.saveReview()
  state.reviewVisible.value = false
  state.openReviews('7')
  state.reviewNote.value = '新的核对说明'
  finish('saved')
  await old
  assert.equal(state.reviewNote.value, '新的核对说明')
})

test('设计成本与配置审计菜单有真实查询，未取得成本不显示零', async () => {
  const { state, route } = walletView({
    getDesignCosts: async () => ({
      list: [{ id: '7', chargedCostMicros: null, costState: 'UNAVAILABLE' }],
      total: 1
    }),
    getAudit: async () => ({ list: [{ id: '8', reason: '实际审计' }], total: 1 })
  })
  route.path = '/platform-ops/design-costs'
  await state.load()
  assert.equal(state.rows.value[0].chargedCostMicros, null)
  assert.equal(state.displayValue('costState', 'UNAVAILABLE'), '未取得成本事实')
  route.path = '/platform-ops/audit'
  await state.load()
  assert.equal(state.rows.value[0].reason, '实际审计')
  assert.equal(state.error.value, '')
})
