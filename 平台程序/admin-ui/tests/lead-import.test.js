const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/**
 * P1 批3：客资导入前端（纯函数 + 页面级关键路径）。
 * 与 tests/staff-import*.test.js 同型：typescript 转译 + VM 加载，零新依赖。
 */
function transpile(source) {
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
}

function loadTsModule(file) {
  const filename = path.join(__dirname, '../src', file)
  const source = fs.readFileSync(filename, 'utf8')
  const module = { exports: {} }
  vm.runInNewContext(transpile(source), {
    module,
    exports: module.exports,
    console,
    require(name) {
      if (name === '@/utils/csvImport') return loadTsModule('utils/csvImport.ts')
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

const model = loadTsModule('views/fr/org-staff/leadImportModel.ts')
const plain = (value) => JSON.parse(JSON.stringify(value))

test('parses BOM + CRLF lead CSV and numbers data lines from 1', () => {
  const text =
    '\uFEFF姓名,手机,需求,来源\r\n' +
    '张三,13800000001,三层小楼,门店自然流量\r\n' +
    '李四,13800000002,老房翻新,老客户转介绍\r\n'
  const parsed = model.parseLeadImportCsv(text)
  assert.equal(parsed.errors.length, 0)
  assert.deepEqual(plain(parsed.rows), [
    { line: 1, name: '张三', phone: '13800000001', need: '三层小楼', source: '门店自然流量' },
    { line: 2, name: '李四', phone: '13800000002', need: '老房翻新', source: '老客户转介绍' }
  ])
})

test('parses quoted commas and embedded newlines in need', () => {
  const text = '姓名,手机,需求,来源\n' + '张三,13800000001,"带院子，\n两层",抖音\n'
  const parsed = model.parseLeadImportCsv(text)
  assert.equal(parsed.errors.length, 0)
  assert.equal(plain(parsed.rows)[0].need, '带院子，\n两层')
})

test('rejects wrong header order', () => {
  const parsed = model.parseLeadImportCsv('手机,姓名,需求,来源\n13800000001,张三,需求,来源\n')
  assert.equal(parsed.rows.length, 0)
  assert.match(parsed.errors[0], /表头必须为/)
})

test('flags duplicate phone rows with the first line', () => {
  const text =
    '姓名,手机,需求,来源\n' +
    '张三,13800000001,需求A,来源A\n' +
    '张三重复,13800000001,需求B,来源B\n'
  const parsed = model.parseLeadImportCsv(text)
  assert.equal(parsed.rows.length, 2)
  assert.equal(parsed.rows[0].error, undefined)
  assert.match(parsed.rows[1].error, /第 1 行/)
})

test('strict CSV rejects characters after a closing quote', () => {
  const parsed = model.parseLeadImportCsv('姓名,手机,需求,来源\n"张三"oops,13800000001,需求,来源\n')
  assert.equal(parsed.rows.length, 0)
  assert.match(parsed.errors[0], /引号/)
})

test('template starts with UTF-8 BOM and exact header', () => {
  const csv = model.buildLeadImportTemplateCsv()
  assert.ok(csv.startsWith('\uFEFF'))
  assert.equal(csv.replace('\uFEFF', '').trim(), '姓名,手机,需求,来源')
})

test('failedLeadRowsForRetry keeps only failed rows and preserves fields', () => {
  const rows = [
    { line: 1, name: 'A', phone: '13800000001', need: 'n', source: 's', status: 'CREATED' },
    { line: 2, name: 'B', phone: '13800000002', need: 'n', source: 's', status: 'FAILED' },
    { line: 3, name: 'C', phone: '13800000003', need: 'n', source: 's', status: 'SKIPPED' }
  ]
  assert.deepEqual(plain(model.failedLeadRowsForRetry(rows)), [
    { line: 2, name: 'B', phone: '13800000002', need: 'n', source: 's' }
  ])
})

test('newImportBatchId is stable-format and unique per import', () => {
  const a = model.newImportBatchId()
  const b = model.newImportBatchId()
  assert.match(a, /^[A-Za-z0-9_-]{1,64}$/)
  assert.notEqual(a, b)
})

test('withinLeadImportLimit and file size enforce 1..500 / 2MB', () => {
  assert.equal(model.withinLeadImportLimit([]), false)
  assert.equal(model.withinLeadImportLimit([{}]), true)
  assert.equal(model.withinLeadImportLimit(new Array(500).fill({})), true)
  assert.equal(model.withinLeadImportLimit(new Array(501).fill({})), false)
  assert.equal(model.withinFileSizeLimit(0), false)
  assert.equal(model.withinFileSizeLimit(2 * 1024 * 1024), true)
  assert.equal(model.withinFileSizeLimit(2 * 1024 * 1024 + 1), false)
})

// ===== 页面级：提交成功 + 列表刷新失败不得误报 =====
function loadPage(apiStub, identityStore, elMessage) {
  const file = path.join(__dirname, '../src/views/fr/customers/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'customers-page' }).content
  const leadModel = loadTsModule('views/fr/org-staff/leadImportModel.ts')
  const module = { exports: {} }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue,
    Blob: class {},
    URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() {} },
    document: { createElement: () => ({ click() {}, set href(v) {} }) },
    require(name) {
      if (name === 'vue') return vue
      if (name === 'element-plus') {
        return {
          ElMessage: {
            warning: (m) => elMessage.warning.push(String(m)),
            error: (m) => elMessage.error.push(String(m)),
            success: (m) => elMessage.success.push(String(m))
          },
          ElMessageBox: {}
        }
      }
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identityStore }
      }
      if (name === '@/api/platform/leads') return apiStub
      if (name === '@/views/fr/org-staff/leadImportModel') return leadModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const bindings = module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
  return bindings
}

function makeIdentity(roleCode) {
  return {
    currentPost: { postId: 1, orgId: 151, orgName: '门店', roleCode },
    can: (p) => p === 'franchise:lead:handle'
  }
}

test('customers page: submit success is not reported as failure when list refresh fails', async () => {
  const elMessage = { warning: [], error: [], success: [] }
  const api = {
    getPendingLeads: async () => {
      throw new Error('list down')
    },
    linkLead: async () => ({}),
    invalidateLead: async () => ({}),
    precheckLeadImport: async () => ({
      orgId: 151,
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      rows: []
    }),
    submitLeadImport: async () => ({
      orgId: 151,
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      rows: []
    })
  }
  const page = loadPage(api, makeIdentity('boss'), elMessage)
  page.importRows.value = [{ line: 1, name: 'A', phone: '13800000001', need: 'n', source: 's' }]
  await page.runImportPrecheck()
  assert.ok(page.importPrecheckResult.value)
  await page.submitImport()
  assert.ok(page.importSubmitResult.value)
  assert.match(page.importNotice.value, /刷新失败/)
  assert.equal(
    elMessage.error.some((m) => m.includes('提交失败')),
    false
  )
})

test('customers page: import entry only for boss/owner', () => {
  const elMessage = { warning: [], error: [], success: [] }
  const api = {
    getPendingLeads: async () => [],
    precheckLeadImport: async () => ({}),
    submitLeadImport: async () => ({})
  }
  const boss = loadPage(api, makeIdentity('boss'), elMessage)
  const sales = loadPage(api, makeIdentity('sales'), elMessage)
  assert.equal(boss.canImport.value, true)
  assert.equal(sales.canImport.value, false)
})

test('customers page: retry loads only failed rows and clears stale precheck', () => {
  const elMessage = { warning: [], error: [], success: [] }
  const api = { getPendingLeads: async () => [] }
  const page = loadPage(api, makeIdentity('boss'), elMessage)
  page.importSubmitResult.value = {
    orgId: 151,
    total: 2,
    created: 1,
    skipped: 0,
    failed: 1,
    rows: [
      { line: 1, name: 'A', phone: '13800000001', need: 'n', source: 's', status: 'CREATED' },
      { line: 2, name: 'B', phone: '13800000002', need: 'n', source: 's', status: 'FAILED' }
    ]
  }
  page.importPrecheckResult.value = { rows: [] }
  page.importBatchId.value = 'batch-retry-1'
  page.retryFailedRows()
  assert.equal(page.importBatchId.value, 'batch-retry-1')
  assert.deepEqual(plain(page.importRows.value.map(({ line, name }) => ({ line, name }))), [
    { line: 2, name: 'B' }
  ])
  assert.equal(page.importPrecheckResult.value, null)
  assert.equal(page.importSubmitResult.value, null)
})

test('customers page: new file resets batchId, retry keeps it', async () => {
  const elMessage = { warning: [], error: [], success: [] }
  const api = { getPendingLeads: async () => [] }
  const page = loadPage(api, makeIdentity('boss'), elMessage)
  const csv = '姓名,手机,需求,来源\n张三,13800000001,需求,来源\n'
  const file = { name: 'a.csv', size: csv.length, text: async () => csv }
  page.importBatchId.value = 'old-batch'
  await page.onImportFile({ target: { files: [file], value: 'a.csv' } })
  const first = page.importBatchId.value
  assert.ok(first.length > 0)
  assert.notEqual(first, 'old-batch')
  page.importRows.value = [
    { line: 1, name: '张三', phone: '13800000001', need: '需求', source: '来源' }
  ]
  await page.runImportPrecheck()
  page.importSubmitResult.value = {
    rows: [
      {
        line: 1,
        name: '张三',
        phone: '13800000001',
        need: '需求',
        source: '来源',
        status: 'FAILED'
      }
    ]
  }
  page.retryFailedRows()
  assert.equal(page.importBatchId.value, first)
})

test('customers page: stale refresh callback cannot overwrite reopened dialog notice', async () => {
  const elMessage = { warning: [], error: [], success: [] }
  let rejectPending
  const pending = new Promise((_resolve, reject) => {
    rejectPending = reject
  })
  const api = {
    getPendingLeads: async () => pending,
    precheckLeadImport: async () => ({
      orgId: 151,
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      rows: []
    }),
    submitLeadImport: async () => ({
      orgId: 151,
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      rows: []
    })
  }
  const page = loadPage(api, makeIdentity('boss'), elMessage)
  page.importBatchId.value = 'batch-stale'
  page.importRows.value = [{ line: 1, name: 'A', phone: '13800000001', need: 'n', source: 's' }]
  await page.runImportPrecheck()
  await page.submitImport()
  // 提交已成功，刷新仍在途；此时关闭对话框（代次前移）
  page.resetImport()
  page.importNotice.value = ''
  rejectPending(new Error('list down'))
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.equal(page.importNotice.value, '')
})
