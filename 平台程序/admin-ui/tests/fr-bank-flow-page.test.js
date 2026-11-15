const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/** P1 尾批②：/fr/finance 银行对账 tab 页面级。 */
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
    require() {
      throw new Error('unexpected import')
    }
  })
  return module.exports
}

function loadPage(roleCode, apiStub) {
  const file = path.join(__dirname, '../src/views/fr/finance/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'fr-finance-bank' }).content
  const bankModel = loadTsModule('views/fr/finance/bankModel.ts')
  const abnormalModel = loadTsModule('views/fr/finance/abnormalModel.ts')
  const perfModel = loadTsModule('views/fr/finance/perfExportModel.ts')
  const identity = {
    currentPost: { postId: 1, orgId: 151, roleCode },
    can: (p) => p === 'franchise:fin:query'
  }
  const module = { exports: {} }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue,
    require(name) {
      if (name === 'vue') return vue
      if (name === 'element-plus') return { ElMessage: { warning() {}, error() {}, success() {} } }
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identity }
      }
      if (name === '@/api/platform/franchiseFinance') return apiStub
      if (name === './bankModel') return bankModel
      if (name === './abnormalModel') return abnormalModel
      if (name === '@/api/platform/franchisePerformance') {
        return { exportPerfCommissionCsv: async () => ({ size: 1 }) }
      }
      if (name === './perfExportModel') return perfModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
}

function makeApi(overrides = {}) {
  const calls = []
  const api = {
    getReceiptPage: async () => ({ list: [], total: 0 }),
    getPaymentPage: async () => ({ list: [], total: 0 }),
    getRefundPage: async () => ({ list: [], total: 0 }),
    getPayablePage: async () => ({ list: [], total: 0 }),
    getAbnormalFundPage: async () => ({ list: [], total: 0 }),
    getMonthReconciliation: async () => ({ month: '2026-11' }),
    exportFinanceCsv: async () => new Blob(),
    registerAbnormalFund: async () => ({ fundId: '1' }),
    linkAbnormalFund: async () => true,
    voidAbnormalFund: async () => true,
    getBankFlows: async (status) => {
      calls.push(['flows', status])
      return [
        {
          id: '11',
          bankRef: 'B-1',
          amountCents: 500,
          bankDate: '2026-11-01',
          status: '待匹配',
          source: 'dev'
        }
      ]
    },
    getPendingBankReceipts: async () => {
      calls.push(['receipts'])
      return [
        { id: '1', amountCents: 500, status: '待登记' },
        { id: '2', amountCents: 600, status: '待登记' }
      ]
    },
    confirmBankFlow: async (flowId, receiptId, key) => {
      calls.push(['confirm', flowId, receiptId, key])
      return { confirmId: '1' }
    },
    ...overrides
  }
  api.__calls = calls
  return api
}

test('bank tab: role gate and status filter passed to API', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  assert.equal(page.canConfirmBankFund.value, true)
  page.bankStatus.value = '待匹配'
  await page.loadBankFlows()
  assert.deepEqual(JSON.parse(JSON.stringify(api.__calls.find((c) => c[0] === 'flows'))), [
    'flows',
    '待匹配'
  ])
  assert.equal(page.bankRows.value.length, 1)

  const boss = loadPage('boss', makeApi())
  assert.equal(boss.canConfirmBankFund.value, false)
})

test('bank tab: candidates filtered to equal amount and pending', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  await page.openBankConfirm({
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    bankDate: '2026-11-01',
    status: '待匹配',
    source: 'dev'
  })
  assert.deepEqual(JSON.parse(JSON.stringify(page.bankReceiptCandidates.value.map((r) => r.id))), [
    '1'
  ])
})

test('bank tab: confirm sends string ids and key; success refreshes flows', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'bank-confirm:test'
  await page.submitBankConfirm()
  const call = api.__calls.find((c) => c[0] === 'confirm')
  assert.equal(call[1], '11')
  assert.equal(call[2], '1')
  assert.match(call[3], /^bank-confirm:[A-Za-z0-9_-]{1,40}$/) // 首次提交按 payload 注册键
  assert.match(page.bankNotice.value, /确认到账成功/)
  assert.equal(api.__calls.filter((c) => c[0] === 'flows').length >= 1, true)
})

test('bank tab: confirm failure with refresh failure keeps failure fact', async () => {
  const api = makeApi({
    confirmBankFlow: async () => {
      throw new Error('conflict')
    },
    getBankFlows: async () => {
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'bank-confirm:test'
  await page.submitBankConfirm()
  assert.match(page.bankNotice.value, /确认到账失败，且列表刷新失败/)
  assert.equal(page.bankError.value, true)
})

test('bank tab: confirm success with refresh failure keeps success fact', async () => {
  const api = makeApi({
    getBankFlows: async () => {
      throw new Error('refresh down')
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'bank-confirm:test'
  await page.submitBankConfirm()
  assert.match(page.bankNotice.value, /确认到账已成功，列表刷新失败/)
  assert.equal(page.bankError.value, false)
})

test('bank tab: preconditions block request (claimed flow / mismatched amount)', async () => {
  const api = makeApi()
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '已认领',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  await page.submitBankConfirm()
  assert.match(page.bankConfirmError.value, /已被认领/)
  assert.equal(api.__calls.filter((c) => c[0] === 'confirm').length, 0)
})

test('bank tab: stale candidate response cannot overwrite newer dialog', async () => {
  const deferred = []
  const api = makeApi({
    getPendingBankReceipts: () =>
      new Promise((resolve) => {
        deferred.push(resolve)
      })
  })
  const page = loadPage('finance', api)
  const flowA = { id: '11', bankRef: 'A', amountCents: 500, status: '待匹配', source: 'dev' }
  const flowB = { id: '12', bankRef: 'B', amountCents: 600, status: '待匹配', source: 'dev' }
  const first = page.openBankConfirm(flowA)
  const second = page.openBankConfirm(flowB)
  deferred[1]([{ id: '2', amountCents: 600, status: '待登记' }])
  await second
  deferred[0]([{ id: '1', amountCents: 500, status: '待登记' }])
  await first
  assert.equal(page.bankConfirmFlow.value.id, '12')
  assert.deepEqual(JSON.parse(JSON.stringify(page.bankReceiptCandidates.value.map((r) => r.id))), [
    '2'
  ])
})

test('bank tab: out-of-order flow loads keep the latest filter', async () => {
  const deferred = []
  const api = makeApi({
    getBankFlows: (status) =>
      new Promise((resolve) => {
        deferred.push({ status, resolve })
      })
  })
  const page = loadPage('finance', api)
  page.bankStatus.value = '待匹配'
  const older = page.loadBankFlows()
  page.bankStatus.value = '已认领'
  const newer = page.loadBankFlows()
  deferred[1].resolve([{ id: 'n' }])
  await newer
  deferred[0].resolve([{ id: 'o' }])
  await older
  assert.equal(page.bankRows.value[0].id, 'n')
})

test('bank tab: confirming refreshes receipts tab even when it is empty', async () => {
  let receiptRefreshes = 0
  const api = makeApi({
    getReceiptPage: async () => {
      receiptRefreshes += 1
      return { list: [], total: 0 }
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'bank-confirm:test'
  await page.submitBankConfirm()
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.equal(receiptRefreshes >= 1, true)
})

test('bank tab: stale submit cannot close a newer dialog or write its notice', async () => {
  let releaseConfirm
  let confirmCalls = 0
  const api = makeApi({
    confirmBankFlow: () => {
      confirmCalls += 1
      return new Promise((resolve) => {
        releaseConfirm = resolve
      })
    },
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  const flowA = { id: '11', bankRef: 'A', amountCents: 500, status: '待匹配', source: 'dev' }
  page.bankConfirmFlow.value = flowA
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'k-a'
  const submitA = page.submitBankConfirm()
  // A 在途时打开 B 的弹窗
  const flowB = { id: '12', bankRef: 'B', amountCents: 600, status: '待匹配', source: 'dev' }
  await page.openBankConfirm(flowB)
  const noticeBefore = page.bankNotice.value
  releaseConfirm({ confirmId: '1' })
  await submitA
  assert.equal(confirmCalls, 1)
  assert.equal(page.bankConfirmFlow.value.id, '12')
  assert.equal(page.bankConfirmDialog.value, true)
  assert.equal(page.bankNotice.value, noticeBefore)
})

test('bank tab: older failing load cannot set error after newer success', async () => {
  const deferred = []
  const api = makeApi({
    getBankFlows: () =>
      new Promise((resolve, reject) => {
        deferred.push({ resolve, reject })
      })
  })
  const page = loadPage('finance', api)
  page.bankStatus.value = '待匹配'
  const older = page.loadBankFlows()
  page.bankStatus.value = '已认领'
  const newer = page.loadBankFlows()
  deferred[1].resolve([{ id: 'n' }])
  await newer
  deferred[0].reject(new Error('old failure'))
  await older
  assert.equal(page.bankError.value, false)
  assert.equal(page.bankNotice.value, '')
  assert.equal(page.bankRows.value[0].id, 'n')
  assert.equal(page.bankLoading.value, false)
})

test('bank tab: receipts refresh failure after success does not swallow success notice', async () => {
  const api = makeApi({
    getReceiptPage: async () => {
      throw new Error('receipts down')
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'B-1',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'bank-confirm:test'
  await page.submitBankConfirm()
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.match(page.bankNotice.value, /确认到账成功/)
  assert.equal(page.bankError.value, false)
})

test('bank tab: dialog opened during the refresh wait still blocks stale notice writes', async () => {
  let resolveRefresh
  let confirmed = 0
  let firstRefresh = true
  const api = makeApi({
    confirmBankFlow: async () => {
      confirmed += 1
      return { confirmId: '1' }
    },
    getBankFlows: () =>
      new Promise((resolve) => {
        if (firstRefresh) {
          firstRefresh = false
          resolveRefresh = resolve
        } else {
          resolve([{ id: 'x' }])
        }
      }),
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'k-a'
  const submitA = page.submitBankConfirm()
  // 等 A 的写入完成、进入刷新等待
  await new Promise((resolve) => setTimeout(resolve, 0))
  const dialogAfterWrite = page.bankConfirmDialog.value
  // 刷新在途时打开 B
  await page.openBankConfirm({
    id: '12',
    bankRef: 'B',
    amountCents: 600,
    status: '待匹配',
    source: 'dev'
  })
  const noticeBefore = page.bankNotice.value
  resolveRefresh([{ id: 'a' }])
  await submitA
  assert.equal(confirmed, 1)
  assert.equal(dialogAfterWrite, false) // A 写入成功立即关闭自己的弹窗；随后才进入刷新等待
  assert.equal(page.bankConfirmFlow.value.id, '12')
  assert.equal(page.bankConfirmDialog.value, true)
  assert.equal(page.bankNotice.value, noticeBefore)
})

test('bank tab: stale submit cannot clear the busy flag of a newer submit', async () => {
  const pending = []
  const api = makeApi({
    confirmBankFlow: () =>
      new Promise((resolve) => {
        pending.push(resolve)
      }),
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitA = page.submitBankConfirm()
  // A 在途时打开 B 并提交 B
  await page.openBankConfirm({
    id: '12',
    bankRef: 'B',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  })
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmKey.value = 'k-b'
  const submitB = page.submitBankConfirm()
  assert.equal(page.bankConfirmBusy.value, true)
  // A 迟到返回：不得清除 B 的 busy
  pending[0]({ confirmId: '1' })
  await submitA
  assert.equal(page.bankConfirmBusy.value, true)
  pending[1]({ confirmId: '2' })
  await submitB
  assert.equal(page.bankConfirmBusy.value, false)
})

test('bank tab: opening a new dialog releases the superseded submit busy (no permanent loading)', async () => {
  const pendingConfirms = []
  const api = makeApi({
    confirmBankFlow: () =>
      new Promise((resolve) => {
        pendingConfirms.push(resolve)
      }),
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitA = page.submitBankConfirm()
  assert.equal(page.bankConfirmBusy.value, true)
  await page.openBankConfirm({
    id: '12',
    bankRef: 'B',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  })
  assert.equal(page.bankConfirmBusy.value, false)
  pendingConfirms[0]({ confirmId: '1' })
  await submitA
  assert.equal(page.bankConfirmBusy.value, false)

  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitB = page.submitBankConfirm()
  assert.equal(page.bankConfirmBusy.value, true)
  pendingConfirms[1]({ confirmId: '2' })
  await submitB
  assert.equal(page.bankConfirmBusy.value, false)
})

test('bank tab: stale refresh failure cannot overwrite a newer dialog notice', async () => {
  let rejectRefresh
  const api = makeApi({
    confirmBankFlow: async () => ({ confirmId: '1' }),
    getBankFlows: () =>
      new Promise((_resolve, reject) => {
        rejectRefresh = reject
      }),
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitA = page.submitBankConfirm()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await page.openBankConfirm({
    id: '12',
    bankRef: 'B',
    amountCents: 600,
    status: '待匹配',
    source: 'dev'
  })
  const noticeBefore = page.bankNotice.value
  rejectRefresh(new Error('quiet refresh down'))
  await submitA
  assert.equal(page.bankNotice.value, noticeBefore)
  assert.equal(page.bankError.value, false)
})

test('bank tab: stale submit finishing during a newer submit cannot clear its busy', async () => {
  let resolveRefreshA
  let refreshCalls = 0
  const pendingConfirms = []
  const api = makeApi({
    confirmBankFlow: () =>
      new Promise((resolve) => {
        pendingConfirms.push(resolve)
      }),
    getBankFlows: () => {
      refreshCalls += 1
      if (refreshCalls === 1) {
        return new Promise((resolve) => {
          resolveRefreshA = resolve
        })
      }
      return Promise.resolve([{ id: 'x' }])
    },
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitA = page.submitBankConfirm()
  pendingConfirms[0]({ confirmId: '1' })
  await new Promise((resolve) => setTimeout(resolve, 0))
  await page.openBankConfirm({
    id: '12',
    bankRef: 'B',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  })
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  const submitB = page.submitBankConfirm()
  assert.equal(page.bankConfirmBusy.value, true)
  resolveRefreshA([{ id: 'a' }])
  await submitA
  assert.equal(page.bankConfirmBusy.value, true)
  pendingConfirms[1]({ confirmId: '2' })
  await submitB
  assert.equal(page.bankConfirmBusy.value, false)
})

test('bank tab: quiet refresh during a pending query still finishes loading', async () => {
  let resolveQuery
  let calls = 0
  const api = makeApi({
    confirmBankFlow: async () => ({ confirmId: '1' }),
    getBankFlows: () => {
      calls += 1
      if (calls === 1) {
        // 用户查询在途
        return new Promise((resolve) => {
          resolveQuery = resolve
        })
      }
      return Promise.resolve([{ id: 'q' }])
    },
    getPendingBankReceipts: async () => [{ id: '1', amountCents: 500, status: '待登记' }]
  })
  const page = loadPage('finance', api)
  const query = page.loadBankFlows()
  assert.equal(page.bankLoading.value, true)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  await page.submitBankConfirm()
  resolveQuery([{ id: 'old' }])
  await query
  assert.equal(page.bankLoading.value, false)
})

test('bank tab: confirm success refreshes reconciliation card', async () => {
  let reconRefreshes = 0
  const api = makeApi({
    getMonthReconciliation: async () => {
      reconRefreshes += 1
      return { month: '2026-11' }
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  await page.submitBankConfirm()
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.equal(reconRefreshes >= 1, true)
})

test('bank tab: backend failure shows an inline dialog error', async () => {
  const api = makeApi({
    confirmBankFlow: async () => {
      throw new Error('conflict')
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [{ id: '1', amountCents: 500, status: '待登记' }]
  page.bankConfirmReceiptId.value = '1'
  page.bankConfirmDialog.value = true
  await page.submitBankConfirm()
  assert.match(page.bankConfirmError.value, /确认失败/)
  assert.equal(page.bankConfirmDialog.value, true)
})

test('bank tab: changing receipt after failure rotates the idempotency key', async () => {
  let fail = true
  const keys = []
  const api = makeApi({
    confirmBankFlow: async (_flowId, _receiptId, key) => {
      keys.push(key)
      if (fail) throw new Error('conflict')
      return { confirmId: '1' }
    }
  })
  const page = loadPage('finance', api)
  page.bankConfirmFlow.value = {
    id: '11',
    bankRef: 'A',
    amountCents: 500,
    status: '待匹配',
    source: 'dev'
  }
  page.bankReceiptCandidates.value = [
    { id: '1', amountCents: 500, status: '待登记' },
    { id: '2', amountCents: 500, status: '待登记' }
  ]
  page.bankConfirmReceiptId.value = '1'
  await page.submitBankConfirm()
  const firstKey = keys[0]
  // 同选择重试：复用同键
  fail = false
  await page.submitBankConfirm()
  assert.equal(keys[1], firstKey)
  // 更换选择：轮换键（否则后端 IDEMPOTENCY_KEY_REUSED）
  fail = true
  page.bankConfirmReceiptId.value = '2'
  await page.submitBankConfirm()
  assert.notEqual(keys[2], firstKey)
})

test('bank tab: failed load clears rows so stale filter data is not shown', async () => {
  const api = makeApi({
    getBankFlows: async () => {
      throw new Error('down')
    }
  })
  const page = loadPage('finance', api)
  page.bankRows.value = [
    { id: 'old', bankRef: 'old', amountCents: 1, bankDate: '', status: '待匹配', source: 'dev' }
  ]
  page.bankStatus.value = '已认领'
  await page.loadBankFlows()
  assert.equal(page.bankRows.value.length, 0)
  assert.equal(page.bankError.value, true)
})
