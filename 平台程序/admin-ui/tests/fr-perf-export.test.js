const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/**
 * P1 尾批③：/fr/finance 绩效报表导出（纯函数 + 页面级 + API 封装）。
 * 与 fr-bank-flow-page.test.js 同型：typescript 转译 + free-vm 加载，零新依赖。
 */
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

function loadApiModule(calls) {
  const filename = path.join(__dirname, '../src/api/platform/franchisePerformance.ts')
  const module = { exports: {} }
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8')), {
    module,
    exports: module.exports,
    console,
    require(name) {
      if (name === '@/config/axios/service') {
        return {
          service: (config) => {
            calls.push(config)
            return Promise.resolve({ size: 1 })
          }
        }
      }
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

function loadPage(roleCode, perfApi) {
  const file = path.join(__dirname, '../src/views/fr/finance/index.vue')
  const source = fs.readFileSync(file, 'utf8')
  const { descriptor } = parse(source, { filename: file })
  const script = compileScript(descriptor, { id: 'fr-finance-perf' }).content
  const perfModel = loadTsModule('views/fr/finance/perfExportModel.ts')
  const abnormalModel = loadTsModule('views/fr/finance/abnormalModel.ts')
  const bankModel = loadTsModule('views/fr/finance/bankModel.ts')

  const calls = []
  const downloads = []
  const messages = { warning: [], error: [], success: [] }
  const identity = {
    currentPost: { postId: 1, orgId: 151, roleCode },
    can: () => true
  }
  const financeApi = {
    getReceiptPage: async () => ({ list: [], total: 0 }),
    getPaymentPage: async () => ({ list: [], total: 0 }),
    getRefundPage: async () => ({ list: [], total: 0 }),
    getPayablePage: async () => ({ list: [], total: 0 }),
    getAbnormalFundPage: async () => ({ list: [], total: 0 }),
    getMonthReconciliation: async () => ({ month: '2026-11' }),
    exportFinanceCsv: async () => ({ size: 1 }),
    registerAbnormalFund: async () => ({ fundId: '1' }),
    linkAbnormalFund: async () => true,
    voidAbnormalFund: async () => true,
    getBankFlows: async () => [],
    getPendingBankReceipts: async () => [],
    confirmBankFlow: async () => ({ confirmId: '1' })
  }
  const module = { exports: {} }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue,
    window: { URL: { createObjectURL: () => 'blob:perf', revokeObjectURL() {} } },
    document: {
      createElement: () => ({
        href: '',
        download: '',
        click() {
          downloads.push(this.download)
        }
      }),
      body: { appendChild() {}, removeChild() {} }
    },
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
      if (name === '@/api/platform/franchiseFinance') return financeApi
      if (name === '@/api/platform/franchisePerformance') return perfApi
      if (name === './abnormalModel') return abnormalModel
      if (name === './bankModel') return bankModel
      if (name === './perfExportModel') return perfModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const bindings = module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
  bindings.__messages = messages
  bindings.__downloads = downloads
  bindings.__calls = calls
  return bindings
}

function makePerfApi(overrides = {}) {
  const calls = []
  const impl = overrides.exportPerfCommissionCsv || (async () => ({ size: 1 }))
  return {
    calls,
    exportPerfCommissionCsv: async (month) => {
      calls.push(month)
      return impl(month)
    }
  }
}

// ===================== 纯函数 =====================

test('perf export: only boss/owner/finance/finance-lead can export', () => {
  const model = loadTsModule('views/fr/finance/perfExportModel.ts')
  for (const role of ['boss', 'owner', 'finance', 'finance-lead']) {
    assert.equal(model.canExportPerfCommission(role), true, role)
  }
  for (const role of [
    'sales',
    'sales-lead',
    'engineer',
    'project-manager',
    'hq-design-admin',
    null,
    undefined,
    ''
  ]) {
    assert.equal(model.canExportPerfCommission(role), false, String(role))
  }
})

test('perf export: month must be strict YYYY-MM', () => {
  const model = loadTsModule('views/fr/finance/perfExportModel.ts')
  assert.equal(model.isValidMonth('2026-11'), true)
  assert.equal(model.isValidMonth('2026-01'), true)
  assert.equal(model.isValidMonth('2026-13'), false)
  assert.equal(model.isValidMonth('2026-1'), false)
  assert.equal(model.isValidMonth(''), false)
  assert.equal(model.isValidMonth(null), false)
})

test('perf export: filename and error message normalization', () => {
  const model = loadTsModule('views/fr/finance/perfExportModel.ts')
  assert.equal(model.perfExportFilename('2026-11'), 'performance-commission-2026-11.csv')
  assert.equal(model.perfExportErrorMessage('月份格式须为 YYYY-MM'), '月份格式须为 YYYY-MM')
  assert.equal(model.perfExportErrorMessage(new Error('boom')), 'boom')
  assert.equal(model.perfExportErrorMessage('error'), '绩效报表导出失败，请确认月份与权限后重试')
  assert.equal(model.perfExportErrorMessage({}), '绩效报表导出失败，请确认月份与权限后重试')
  assert.equal(model.perfExportErrorMessage(null), '绩效报表导出失败，请确认月份与权限后重试')
})

// ===================== API 封装 =====================

test('perf export api: GET month as blob endpoint', async () => {
  const calls = []
  const api = loadApiModule(calls)
  await api.exportPerfCommissionCsv('2026-11')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'GET')
  assert.equal(calls[0].url, '/franchise/performance/commission/export')
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].params)), { month: '2026-11' })
  assert.equal(calls[0].responseType, 'blob')
  assert.equal(calls[0].silentError, true) // 页面单条提示，关全局 notification
})

// ===================== 页面级 =====================

test('perf export page: button visibility follows roleCode', () => {
  const boss = loadPage('boss', makePerfApi())
  const owner = loadPage('owner', makePerfApi())
  const finance = loadPage('finance', makePerfApi())
  const lead = loadPage('finance-lead', makePerfApi())
  const sales = loadPage('sales', makePerfApi())
  assert.equal(boss.canExportPerf.value, true)
  assert.equal(owner.canExportPerf.value, true)
  assert.equal(finance.canExportPerf.value, true)
  assert.equal(lead.canExportPerf.value, true)
  assert.equal(sales.canExportPerf.value, false)
})

test('perf export page: button is rendered next to month picker', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/views/fr/finance/index.vue'), 'utf8')
  assert.match(source, /v-if="canExportPerf"/)
  assert.match(source, /绩效报表导出/)
  assert.match(source, /exportPerfCommission/)
})

test('perf export page: success downloads selected month and reports no error', async () => {
  const api = makePerfApi()
  const page = loadPage('boss', api)
  page.month.value = '2026-11'
  await page.exportPerfCommission()
  assert.deepEqual(api.calls, ['2026-11'])
  assert.deepEqual(page.__downloads, ['performance-commission-2026-11.csv'])
  assert.deepEqual(page.__messages.error, [])
  assert.deepEqual(page.__messages.success, []) // 成功不弹额外提示，也不误报
  assert.equal(page.exporting.value, '')
})

test('perf export page: failure shows exactly one message and does not download', async () => {
  const api = makePerfApi({
    exportPerfCommissionCsv: async () => {
      throw '月份格式须为 YYYY-MM'
    }
  })
  const page = loadPage('finance', api)
  await page.exportPerfCommission()
  assert.deepEqual(page.__messages.error, ['月份格式须为 YYYY-MM'])
  assert.deepEqual(page.__messages.success, [])
  assert.deepEqual(page.__downloads, [])
  assert.equal(page.exporting.value, '')
})

test('perf export page: generic failure falls back to readable message', async () => {
  const api = makePerfApi({
    exportPerfCommissionCsv: async () => {
      throw new Error('network down')
    }
  })
  const page = loadPage('finance-lead', api)
  await page.exportPerfCommission()
  assert.deepEqual(page.__messages.error, ['network down'])
})

test('perf export page: invalid month blocks request before calling api', async () => {
  const api = makePerfApi()
  const page = loadPage('boss', api)
  page.month.value = '2026-13'
  await page.exportPerfCommission()
  assert.deepEqual(api.calls, [])
  assert.deepEqual(page.__messages.error, ['月份格式须为 YYYY-MM'])
})

test('perf export page: double click sends only one request', async () => {
  let resolvePending
  const api = makePerfApi({
    exportPerfCommissionCsv: () =>
      new Promise((resolve) => {
        resolvePending = resolve
      })
  })
  const page = loadPage('boss', api)
  const first = page.exportPerfCommission()
  await page.exportPerfCommission() // 第二次在 exporting 期间应直接返回
  assert.equal(api.calls.length, 1)
  resolvePending({ size: 1 })
  await first
  assert.equal(page.exporting.value, '')
})

test('perf export page: switching month during request keeps filename and request month consistent', async () => {
  let resolvePending
  const api = makePerfApi({
    exportPerfCommissionCsv: () =>
      new Promise((resolve) => {
        resolvePending = resolve
      })
  })
  const page = loadPage('boss', api)
  page.month.value = '2026-11'
  const pending = page.exportPerfCommission()
  page.month.value = '2026-12' // 请求在途时切月
  resolvePending({ size: 1 })
  await pending
  assert.deepEqual(api.calls, ['2026-11']) // 请求参数=发出时月份
  assert.deepEqual(page.__downloads, ['performance-commission-2026-11.csv']) // 文件名=内容月份
})

test('perf export page: hidden roles cannot trigger export', async () => {
  const api = makePerfApi()
  const page = loadPage('sales', api)
  await page.exportPerfCommission()
  assert.deepEqual(api.calls, [])
  assert.deepEqual(page.__messages.error, [])
})
