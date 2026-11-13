const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/** P1 批5：钱包对账纯函数 + 页面关键路径（零新依赖，与 fr-dashboard 同型）。 */
function transpile(source) {
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
}

function loadTsModule(file) {
  const filename = path.join(__dirname, '../src', file)
  const module = { exports: {} }
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8')), {
    module,
    exports: module.exports,
    console,
    require(name) {
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

const model = loadTsModule('views/fr/wallet/model.ts')

test('formatPoints adds thousands separators and handles null', () => {
  assert.equal(model.formatPoints(0), '0')
  assert.equal(model.formatPoints(1234), '1,234')
  assert.equal(model.formatPoints(-1234567), '-1,234,567')
  assert.equal(model.formatPoints(null), '-')
  assert.equal(model.formatPoints(undefined), '-')
})

test('signedPoints always shows the sign for non-zero deltas', () => {
  assert.equal(model.signedPoints(1200), '+1,200')
  assert.equal(model.signedPoints(-300), '-300')
  assert.equal(model.signedPoints(0), '0')
  assert.equal(model.signedPoints(null), '-')
})

test('ledgerTypeLabel maps only types actually written to pf_point_ledger', () => {
  assert.equal(model.ledgerTypeLabel('RECHARGE_BASE_CREDIT'), '充值本金')
  assert.equal(model.ledgerTypeLabel('RECHARGE_BONUS_CREDIT'), '充值赠送')
  assert.equal(model.ledgerTypeLabel('CHARGE'), '消费扣减')
  assert.equal(model.ledgerTypeLabel('REFUND_BASE_REVERSAL'), '退款冲正（本金）')
  assert.equal(model.ledgerTypeLabel('REFUND_BONUS_REVERSAL'), '退款冲正（赠送）')
  assert.equal(model.ledgerTypeLabel('MANUAL_CREDIT'), '人工增加')
  assert.equal(model.ledgerTypeLabel('MANUAL_DEBIT'), '人工扣减')
  // RESERVE/RELEASE 只改余额不写流水；CONSUME/MANUAL_ADJUSTMENT 不是 ledger.type
  assert.equal(model.ledgerTypeLabel('RESERVE'), 'RESERVE')
  assert.equal(model.ledgerTypeLabel('RELEASE'), 'RELEASE')
  assert.equal(model.ledgerTypeLabel('CONSUME'), 'CONSUME')
  assert.equal(model.ledgerTypeLabel('MANUAL_ADJUSTMENT'), 'MANUAL_ADJUSTMENT')
  // 前缀相似但未登记的类型不得被误标
  assert.equal(model.ledgerTypeLabel('REFUND_FUTURE_UNKNOWN'), 'REFUND_FUTURE_UNKNOWN')
  assert.equal(model.ledgerTypeLabel('RECHARGE_V2'), 'RECHARGE_V2')
  assert.equal(model.ledgerTypeLabel('MANUAL_FUTURE_UNKNOWN'), 'MANUAL_FUTURE_UNKNOWN')
  assert.equal(model.ledgerTypeLabel('CHARGED'), 'CHARGED')
  // 原型链成员不得被当成类型标签
  assert.equal(model.ledgerTypeLabel('toString'), 'toString')
  assert.equal(model.ledgerTypeLabel('__proto__'), '__proto__')
  assert.equal(model.ledgerTypeLabel('constructor'), 'constructor')
  assert.equal(model.ledgerTypeLabel(''), '-')
})

test('walletStatusLabel/Tag cover the real ACTIVE/FROZEN pair and unknown', () => {
  assert.equal(model.walletStatusLabel('ACTIVE'), '正常')
  assert.equal(model.walletStatusLabel('FROZEN'), '已冻结')
  // 账户接口不返回 DISABLED/CLOSED（那是 pf_wallet.status，不是 account 派生状态）
  assert.equal(model.walletStatusLabel('DISABLED'), 'DISABLED')
  assert.equal(model.walletStatusLabel('OTHER'), 'OTHER')
  assert.equal(model.walletStatusLabel('toString'), 'toString')
  assert.equal(model.walletStatusTag('ACTIVE'), 'success')
  assert.equal(model.walletStatusTag('FROZEN'), 'warning')
  assert.equal(model.walletStatusTag('OTHER'), 'info')
})

test('normalizeLedgerPage clamps to server bounds', () => {
  // VM realm 原型不同，逐字段断言（跨 realm deepEqual 会因 prototype 不等而失败）。
  const a = model.normalizeLedgerPage(0, 0)
  assert.equal(a.pageNo, 1)
  assert.equal(a.pageSize, 20)
  const b = model.normalizeLedgerPage(2, 500)
  assert.equal(b.pageNo, 2)
  assert.equal(b.pageSize, 100)
  const c = model.normalizeLedgerPage(Number.NaN, Number.NaN)
  assert.equal(c.pageNo, 1)
  assert.equal(c.pageSize, 20)
})

// ===== 页面级 =====
function loadPage(apiStub, identityStore) {
  const file = path.join(__dirname, '../src/views/fr/wallet/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'fr-wallet-page' }).content
  const walletModel = loadTsModule('views/fr/wallet/model.ts')
  const module = { exports: {} }
  const mounted = []
  const vueStub = { ...vue, onMounted: (cb) => mounted.push(cb) }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue: vueStub,
    require(name) {
      if (name === 'vue') return vueStub
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identityStore }
      }
      if (name === '@/api/platform/wallet') return apiStub
      if (name === './model') return walletModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const bindings = module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
  bindings.__mounted = mounted
  return bindings
}

function identity(canRead) {
  return {
    currentPost: { postId: 1, orgId: 151, roleCode: 'boss' },
    can: (p) => (p === 'platform:wallet:read' ? canRead : false)
  }
}

test('wallet page: no permission means no requests', async () => {
  let calls = 0
  const api = {
    getWalletAccount: async () => {
      calls += 1
      return {}
    },
    getWalletLedger: async () => {
      calls += 1
      return { list: [], total: 0 }
    }
  }
  const page = loadPage(api, identity(false))
  await page.load()
  assert.equal(page.canRead.value, false)
  assert.equal(calls, 0)
})

test('wallet page: loads account and ledger, formats failure as notice', async () => {
  const api = {
    getWalletAccount: async () => ({
      walletId: '1',
      orgId: '151',
      status: 'ACTIVE',
      availablePoints: 19500,
      reservedPoints: 0,
      version: 3
    }),
    getWalletLedger: async () => ({
      list: [
        {
          id: '9',
          type: 'RECHARGE_BASE_CREDIT',
          delta: 20000,
          availableAfter: 20000,
          reservedAfter: 0,
          bizType: 'RECHARGE',
          bizId: 'o1',
          actorId: 'u1',
          reason: '充值',
          createTime: '2026-11-13T02:00:00Z'
        }
      ],
      total: 1
    })
  }
  const page = loadPage(api, identity(true))
  await page.load()
  assert.equal(page.loadError.value, '')
  assert.equal(page.account.value.availablePoints, 19500)
  assert.equal(page.ledger.value.list.length, 1)
  assert.equal(page.ledger.value.total, 1)
})

test('wallet page: load failure surfaces a single safe notice', async () => {
  const api = {
    getWalletAccount: async () => {
      throw new Error('boom')
    },
    getWalletLedger: async () => ({ list: [], total: 0 })
  }
  const page = loadPage(api, identity(true))
  await page.load()
  assert.match(page.loadError.value, /钱包数据加载失败/)
  assert.equal(page.loading.value, false)
})

test('wallet page: uses exactly the two read endpoints and loads on mount', async () => {
  const urls = []
  const api = {
    getWalletAccount: async () => {
      urls.push('/platform/wallet/account')
      return {
        walletId: '1',
        orgId: '151',
        status: 'ACTIVE',
        availablePoints: 0,
        reservedPoints: 0,
        version: 1
      }
    },
    getWalletLedger: async (params) => {
      urls.push(`/platform/wallet/ledger?pageNo=${params.pageNo}&pageSize=${params.pageSize}`)
      return { list: [], total: 0 }
    }
  }
  const page = loadPage(api, identity(true))
  assert.equal(page.__mounted.length, 1)
  page.__mounted[0]()
  // onMounted 内部是 void load()，等两个 GET 的微任务完成
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.deepEqual(urls.sort(), [
    '/platform/wallet/account',
    '/platform/wallet/ledger?pageNo=1&pageSize=20'
  ])
})

test('wallet page: out-of-order pagination responses cannot overwrite the latest page', async () => {
  const deferred = []
  const api = {
    getWalletAccount: async () => ({
      walletId: '1',
      orgId: '151',
      status: 'ACTIVE',
      availablePoints: 0,
      reservedPoints: 0,
      version: 1
    }),
    getWalletLedger: (params) =>
      new Promise((resolve) => {
        deferred.push({ pageNo: params.pageNo, resolve })
      })
  }
  const page = loadPage(api, identity(true))
  page.pageNo.value = 2
  const second = page.load()
  page.pageNo.value = 3
  const third = page.load()
  // 第 3 页先返回、第 2 页后返回：最终必须是第 3 页数据
  deferred.find((d) => d.pageNo === 3).resolve({ list: [{ id: 'p3' }], total: 40 })
  await third
  deferred.find((d) => d.pageNo === 2).resolve({ list: [{ id: 'p2' }], total: 40 })
  await second
  assert.equal(page.ledger.value.list[0].id, 'p3')
  assert.equal(page.pageNo.value, 3)
  assert.equal(page.loading.value, false)
})

test('wallet API passes silentError so the page shows a single notice', () => {
  const calls = []
  const filename = path.join(__dirname, '../src/api/platform/wallet.ts')
  const module = { exports: {} }
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8')), {
    module,
    exports: module.exports,
    console,
    require(name) {
      if (name === '@/config/axios') {
        return {
          get: async (option) => {
            calls.push(option)
            return option
          }
        }
      }
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const api = module.exports
  api.getWalletAccount()
  api.getWalletLedger({ pageNo: 1, pageSize: 20 })
  assert.equal(calls.length, 2)
  // 真实 URL 断言（页面测试用桩，改错 API 封装不会红；这里锁死）
  assert.equal(calls[0].url, '/platform/wallet/account')
  assert.equal(calls[1].url, '/platform/wallet/ledger')
  assert.equal(calls[0].silentError, true)
  assert.equal(calls[1].silentError, true)
})

test('wallet page: stale failing request cannot set error or clobber latest data', async () => {
  const deferred = []
  const api = {
    getWalletAccount: async () => ({
      walletId: '1',
      orgId: '151',
      status: 'ACTIVE',
      availablePoints: 5,
      reservedPoints: 0,
      version: 1
    }),
    getWalletLedger: (params) =>
      new Promise((resolve, reject) => {
        deferred.push({ pageNo: params.pageNo, resolve, reject })
      })
  }
  const page = loadPage(api, identity(true))
  page.pageNo.value = 2
  const older = page.load()
  page.pageNo.value = 3
  const newer = page.load()
  deferred.find((d) => d.pageNo === 3).resolve({ list: [{ id: 'p3' }], total: 40 })
  await newer
  deferred.find((d) => d.pageNo === 2).reject(new Error('old failure'))
  await older
  // 旧请求失败不得写 loadError，也不得把 loading 提前置 false 后再被旧请求改写
  assert.equal(page.loadError.value, '')
  assert.equal(page.ledger.value.list[0].id, 'p3')
  assert.equal(page.loading.value, false)
})

test('wallet page: an older request finishing first must not clear loading for the pending latest', async () => {
  const deferred = []
  const api = {
    getWalletAccount: async () => ({
      walletId: '1',
      orgId: '151',
      status: 'ACTIVE',
      availablePoints: 5,
      reservedPoints: 0,
      version: 1
    }),
    getWalletLedger: (params) =>
      new Promise((resolve) => {
        deferred.push({ pageNo: params.pageNo, resolve })
      })
  }
  const page = loadPage(api, identity(true))
  page.pageNo.value = 2
  const older = page.load()
  page.pageNo.value = 3
  const newer = page.load()
  // 旧请求先返回（成功），新请求仍挂起：loading 必须保持 true（仅最新请求可收尾）
  deferred.find((d) => d.pageNo === 2).resolve({ list: [{ id: 'p2' }], total: 40 })
  await older
  assert.equal(page.loading.value, true)
  assert.equal(page.ledger.value.list.length, 0)
  deferred.find((d) => d.pageNo === 3).resolve({ list: [{ id: 'p3' }], total: 40 })
  await newer
  assert.equal(page.loading.value, false)
  assert.equal(page.ledger.value.list[0].id, 'p3')
})
