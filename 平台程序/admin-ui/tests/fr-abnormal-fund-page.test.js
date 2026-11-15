const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/** P1 尾批①：/fr/finance 异常资金写操作页面级（权限显隐/登记/关联/作废/失败刷新）。 */
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

function loadPage(roleCode, apiStub) {
  const file = path.join(__dirname, '../src/views/fr/finance/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'fr-finance-page' }).content
  const model = loadTsModule('views/fr/finance/abnormalModel.ts')
  const bankModel = loadTsModule('views/fr/finance/bankModel.ts')
  const perfModel = loadTsModule('views/fr/finance/perfExportModel.ts')
  const identity = {
    currentPost: { postId: 1, orgId: 151, roleCode },
    can: (p) => p === 'franchise:fin:query'
  }
  const messages = { warning: [], error: [], success: [] }
  const module = { exports: {} }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue,
    require(name) {
      if (name === 'vue') return vue
      if (name === 'element-plus') {
        return {
          ElMessage: {
            warning: (m) => messages.warning.push(String(m)),
            error: (m) => messages.error.push(String(m)),
            success: (m) => messages.success.push(String(m))
          }
        }
      }
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identity }
      }
      if (name === '@/api/platform/franchiseFinance') return apiStub
      if (name === './abnormalModel') return model
      if (name === './bankModel') return bankModel
      if (name === '@/api/platform/franchisePerformance') {
        return { exportPerfCommissionCsv: async () => ({ size: 1 }) }
      }
      if (name === './perfExportModel') return perfModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const bindings = module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
  bindings.__messages = messages
  return bindings
}

function makeApi(overrides = {}) {
  const calls = []
  const base = {
    getReceiptPage: async () => ({ list: [], total: 0 }),
    getPaymentPage: async () => ({ list: [], total: 0 }),
    getRefundPage: async () => ({ list: [], total: 0 }),
    getPayablePage: async () => ({ list: [], total: 0 }),
    getAbnormalFundPage: async () => {
      calls.push(['refresh'])
      return { list: [], total: 0 }
    },
    getMonthReconciliation: async () => ({ month: '2026-11' }),
    getBankFlows: async () => [],
    getPendingBankReceipts: async () => [],
    confirmBankFlow: async () => ({ confirmId: '1' }),
    exportFinanceCsv: async () => new Blob(),
    registerAbnormalFund: async (payload, key) => {
      calls.push(['register', payload, key])
      return { fundId: '1' }
    },
    linkAbnormalFund: async (id, data) => {
      calls.push(['link', id, data])
      return true
    },
    voidAbnormalFund: async (id, reason) => {
      calls.push(['void', id, reason])
      return true
    },
    ...overrides
  }
  base.__calls = calls
  return base
}

test('finance page: abnormal write buttons follow role matrix', () => {
  const sales = loadPage('sales', makeApi())
  assert.equal(sales.canRegisterFund.value, false)
  assert.equal(sales.canVoidFund.value, false)
  const finance = loadPage('finance', makeApi())
  assert.equal(finance.canRegisterFund.value, true)
  assert.equal(finance.canVoidFund.value, false)
  const lead = loadPage('finance-lead', makeApi())
  assert.equal(lead.canRegisterFund.value, true)
  assert.equal(lead.canVoidFund.value, true)
  const boss = loadPage('boss', makeApi())
  assert.equal(boss.canRegisterFund.value, false)
  assert.equal(boss.canVoidFund.value, true)
})

test('finance page: register validates, posts payload+key, refreshes and notices', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.openRegister()
  assert.ok(page.registerKey.value.startsWith('abnormal-register:'))
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1234.5'
  page.registerForm.counterparty = '张三'
  page.registerForm.businessDate = '2026-11-01'
  page.registerForm.method = 'bank'
  await page.submitRegister()
  const call = api.__calls.find((c) => c[0] === 'register')
  assert.ok(call)
  assert.equal(call[1].amountCents, 123450)
  assert.equal(call[1].fundDirection, '客户收款')
  assert.match(call[2], /^abnormal-register:/)
  assert.equal(page.registerDialog.value, false)
  assert.match(page.actionNotice.value, /已登记/)
})

test('finance page: invalid register form does not call API', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.openRegister()
  page.registerForm.fundDirection = ''
  page.registerForm.amountYuan = 'abc'
  await page.submitRegister()
  assert.equal(api.__calls.filter((c) => c[0] === 'register').length, 0)
  assert.equal(page.registerErrors.value.length >= 2, true)
})

test('finance page: link derives linkedType from direction and rejects bad id', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.linkRow.value = {
    id: '9',
    fundDirection: '支出退回',
    amountCents: 100,
    status: '已核待关联'
  }
  page.linkIdText.value = '0'
  await page.submitLink()
  assert.equal(page.linkError.value, '关联单号须为正整数')
  assert.equal(api.__calls.filter((c) => c[0] === 'link').length, 0)

  page.linkIdText.value = '88'
  await page.submitLink()
  const call = api.__calls.find((c) => c[0] === 'link')
  assert.deepEqual(JSON.parse(JSON.stringify(call)), [
    'link',
    '9',
    { linkedType: 'payment', linkedId: '88' }
  ])
  assert.match(page.actionNotice.value, /关联成功/)
})

test('finance page: link failure refreshes and reports conflict', async () => {
  const api = makeApi({
    linkAbnormalFund: async () => {
      throw new Error('conflict')
    }
  })
  const page = loadPage('finance', api)
  page.linkRow.value = {
    id: '9',
    fundDirection: '客户收款',
    amountCents: 100,
    status: '已核待关联'
  }
  page.linkIdText.value = '88'
  await page.submitLink()
  assert.match(page.actionNotice.value, /关联失败/)
  assert.equal(page.actionError.value, true)
})

test('finance page: void requires reason, trims on submit', async () => {
  const api = makeApi()
  const page = loadPage('finance-lead', api)
  page.voidRow.value = {
    id: '9',
    fundDirection: '客户退款',
    amountCents: 100,
    status: '已核待关联'
  }
  page.voidReason.value = '   '
  await page.submitVoid()
  assert.equal(page.voidError.value, '请填写作废原因')
  assert.equal(api.__calls.filter((c) => c[0] === 'void').length, 0)

  page.voidReason.value = '  误录冲正  '
  await page.submitVoid()
  const call = api.__calls.find((c) => c[0] === 'void')
  assert.deepEqual(JSON.parse(JSON.stringify(call)), ['void', '9', '误录冲正'])
  assert.match(page.actionNotice.value, /已作废/)
})

test('finance page: 19-digit IDs stay strings end to end', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.openRegister()
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1'
  page.registerForm.relatedProjectId = '1987654321098765432'
  await page.submitRegister()
  const reg = api.__calls.find((c) => c[0] === 'register')
  assert.equal(reg[1].relatedProjectId, '1987654321098765432')

  page.linkRow.value = {
    id: '9',
    fundDirection: '客户收款',
    amountCents: 100,
    status: '已核待关联'
  }
  page.linkIdText.value = '1987654321098765432'
  await page.submitLink()
  const link = api.__calls.find((c) => c[0] === 'link')
  assert.equal(link[2].linkedId, '1987654321098765432')
})

test('finance page: write success with refresh failure keeps success fact', async () => {
  let refreshCalls = 0
  const api = makeApi({
    getAbnormalFundPage: async () => {
      refreshCalls += 1
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance', api)
  page.openRegister()
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1'
  await page.submitRegister()
  assert.match(page.actionNotice.value, /已登记，列表刷新失败/)
  assert.equal(page.actionError.value, false)
})

test('finance page: linked row cannot be linked again (concurrency guard)', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.linkRow.value = { id: '9', fundDirection: '客户收款', amountCents: 100, status: '已关联' }
  page.linkIdText.value = '88'
  await page.submitLink()
  assert.match(page.linkError.value, /已被处理/)
  assert.equal(api.__calls.filter((c) => c[0] === 'link').length, 0)
})

test('finance page: stale abnormal response cannot overwrite newer page', async () => {
  const deferred = []
  const api = makeApi({
    getAbnormalFundPage: (params) =>
      new Promise((resolve) => {
        deferred.push({ pageNo: params.pageNo, resolve })
      })
  })
  const page = loadPage('finance', api)
  page.abnormalQuery.pageNo = 2
  const older = page.loadAbnormal(2)
  page.abnormalQuery.pageNo = 3
  const newer = page.loadAbnormal(3)
  deferred.find((d) => d.pageNo === 3).resolve({ list: [{ id: 'p3' }], total: 30 })
  await newer
  deferred.find((d) => d.pageNo === 2).resolve({ list: [{ id: 'p2' }], total: 30 })
  await older
  assert.equal(page.abnormal.list[0].id, 'p3')
})

test('finance page: register retry keeps key until success, reopen rotates it', async () => {
  let fail = true
  const api = makeApi({
    registerAbnormalFund: async (payload, key) => {
      api.__calls.push(['register', payload, key])
      if (fail) throw new Error('failed')
      return { fundId: '1' }
    }
  })
  const page = loadPage('finance', api)
  page.openRegister()
  const firstKey = page.registerKey.value
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1'
  await page.submitRegister()
  assert.equal(page.registerKey.value, firstKey) // 失败重试复用同键
  fail = false
  await page.submitRegister()
  assert.equal(api.__calls.filter((c) => c[0] === 'register')[1][2], firstKey)
  page.openRegister()
  assert.notEqual(page.registerKey.value, firstKey) // 新开窗换键
})

test('finance page: write happens before refresh (order asserted)', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.openRegister()
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1'
  await page.submitRegister()
  const writeIndex = api.__calls.findIndex((c) => c[0] === 'register')
  const refreshIndex = api.__calls.findIndex((c) => c[0] === 'refresh')
  assert.equal(writeIndex >= 0 && refreshIndex > writeIndex, true)
})

test('finance page: register failure with refresh failure shows combined failure', async () => {
  const api = makeApi({
    registerAbnormalFund: async () => {
      throw new Error('failed')
    },
    getAbnormalFundPage: async () => {
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance', api)
  page.openRegister()
  page.registerForm.fundDirection = '客户收款'
  page.registerForm.amountYuan = '1'
  await page.submitRegister()
  assert.match(page.actionNotice.value, /登记失败，且列表刷新失败/)
  assert.equal(page.actionError.value, true)
})

test('finance page: link success with refresh failure keeps success and no error flag', async () => {
  const api = makeApi({
    getAbnormalFundPage: async () => {
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance', api)
  page.linkRow.value = {
    id: '9',
    fundDirection: '客户收款',
    amountCents: 100,
    status: '已核待关联'
  }
  page.linkIdText.value = '88'
  await page.submitLink()
  assert.match(page.actionNotice.value, /关联已成功，列表刷新失败/)
  assert.equal(page.actionError.value, false)
})

test('finance page: void success with refresh failure keeps success and no error flag', async () => {
  const api = makeApi({
    getAbnormalFundPage: async () => {
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance-lead', api)
  page.voidRow.value = {
    id: '9',
    fundDirection: '客户退款',
    amountCents: 100,
    status: '已核待关联'
  }
  page.voidReason.value = '误录'
  await page.submitVoid()
  assert.match(page.actionNotice.value, /已作废，列表刷新失败/)
  assert.equal(page.actionError.value, false)
})
