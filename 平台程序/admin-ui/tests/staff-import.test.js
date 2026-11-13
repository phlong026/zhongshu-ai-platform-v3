const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/**
 * P1 批2：员工导入前端纯函数（CSV 解析/模板/失败行重提/上限）。
 * 与 tests/fr-dashboard.test.js 同型：仓内 typescript 转译 + VM 加载，零新依赖。
 */
function load(file) {
  const filename = path.join(__dirname, '../src', file)
  const source = fs.readFileSync(filename, 'utf8')
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    console,
    require(name) {
      throw Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

const model = load('views/fr/org-staff/importModel.ts')
const plain = (value) => JSON.parse(JSON.stringify(value))

test('parses BOM + CRLF CSV and numbers data lines from 1', () => {
  const text =
    '\uFEFF姓名,手机,角色码,团队名\r\n' +
    '张三,13800000001,sales,业务一组\r\n' +
    '李四,13800000002,sales-lead,业务二组\r\n'
  const parsed = model.parseStaffImportCsv(text)
  assert.equal(parsed.errors.length, 0)
  assert.deepEqual(plain(parsed.rows), [
    { line: 1, name: '张三', mobile: '13800000001', roleCode: 'sales', teamName: '业务一组' },
    { line: 2, name: '李四', mobile: '13800000002', roleCode: 'sales-lead', teamName: '业务二组' }
  ])
})

test('parses quoted commas, escaped quotes and embedded newlines', () => {
  const text =
    '姓名,手机,角色码,团队名\n' +
    '"张三, 小张",13800000001,sales,"业务一组"\n' +
    '"李""四",13800000002,sales,"跨行\n团队"\n'
  const parsed = model.parseStaffImportCsv(text)
  assert.equal(parsed.errors.length, 0)
  assert.deepEqual(plain(parsed.rows), [
    { line: 1, name: '张三, 小张', mobile: '13800000001', roleCode: 'sales', teamName: '业务一组' },
    { line: 2, name: '李"四', mobile: '13800000002', roleCode: 'sales', teamName: '跨行\n团队' }
  ])
})

test('rejects wrong header order and missing columns', () => {
  const wrongOrder = model.parseStaffImportCsv(
    '手机,姓名,角色码,团队名\n13800000001,张三,sales,业务一组\n'
  )
  assert.equal(wrongOrder.rows.length, 0)
  assert.equal(wrongOrder.errors.length, 1)
  assert.match(wrongOrder.errors[0], /表头/)

  const missing = model.parseStaffImportCsv('姓名,手机,角色码\n张三,13800000001,sales\n')
  assert.equal(missing.rows.length, 0)
  assert.equal(missing.errors.length, 1)
  assert.match(missing.errors[0], /表头/)
})

test('skips blank lines and numbers remaining data rows sequentially', () => {
  const text =
    '姓名,手机,角色码,团队名\n\n张三,13800000001,sales,业务一组\n,\n李四,13800000002,sales,业务二组\n'
  const parsed = model.parseStaffImportCsv(text)
  assert.equal(parsed.errors.length, 0)
  assert.deepEqual(plain(parsed.rows.map((row) => row.line)), [1, 2])
  assert.deepEqual(plain(parsed.rows.map((row) => row.name)), ['张三', '李四'])
})

test('flags duplicate natural-key rows with the first line', () => {
  const text =
    '姓名,手机,角色码,团队名\n' +
    '张三,13800000001,sales,业务一组\n' +
    '张三(重复),13800000001,sales,业务一组\n'
  const parsed = model.parseStaffImportCsv(text)
  assert.equal(parsed.rows.length, 2)
  assert.equal(parsed.rows[0].error, undefined)
  assert.match(parsed.rows[1].error, /第 1 行/)
})

test('reports column-count errors with data line numbers', () => {
  const text = '姓名,手机,角色码,团队名\n张三,13800000001,sales\n'
  const parsed = model.parseStaffImportCsv(text)
  assert.equal(parsed.rows.length, 0)
  assert.equal(parsed.errors.length, 1)
  assert.match(parsed.errors[0], /第 1 行/)
  assert.match(parsed.errors[0], /列数/)
})

test('template starts with UTF-8 BOM and exact header', () => {
  const template = model.buildStaffImportTemplateCsv()
  assert.equal(template.charCodeAt(0), 0xfeff)
  assert.equal(template.slice(1).split(/\r?\n/)[0], '姓名,手机,角色码,团队名')
})

test('failedRowsForRetry keeps only failed rows and preserves line numbers', () => {
  const rows = [
    {
      line: 1,
      name: '张三',
      mobile: '13800000001',
      roleCode: 'sales',
      teamName: '业务一组',
      status: 'CREATED'
    },
    {
      line: 3,
      name: '王五',
      mobile: '13800000003',
      roleCode: 'sales',
      teamName: '业务一组',
      status: 'FAILED'
    },
    {
      line: 5,
      name: '赵六',
      mobile: '13800000005',
      roleCode: 'sales',
      teamName: '业务二组',
      status: 'FAILED'
    }
  ]
  const retry = model.failedRowsForRetry(rows)
  assert.deepEqual(plain(retry), [
    { line: 3, name: '王五', mobile: '13800000003', roleCode: 'sales', teamName: '业务一组' },
    { line: 5, name: '赵六', mobile: '13800000005', roleCode: 'sales', teamName: '业务二组' }
  ])
})

test('withinImportLimit enforces 1..500', () => {
  assert.equal(model.withinImportLimit([]), false)
  assert.equal(model.withinImportLimit(new Array(500).fill({})), true)
  assert.equal(model.withinImportLimit(new Array(501).fill({})), false)
})

test('strict CSV rejects characters after a closing quote', () => {
  const parsed = model.parseStaffImportCsv(
    '姓名,手机,角色码,团队名\n"张三"oops,13800000001,sales,业务一组\n'
  )
  assert.equal(parsed.rows.length, 0)
  assert.equal(parsed.errors.length, 1)
  assert.match(parsed.errors[0], /第 1 行/)
  assert.match(parsed.errors[0], /引号/)
})

test('strict CSV rejects unclosed quote, quote-after-space and trailing space after quote', () => {
  const unclosed = model.parseStaffImportCsv(
    '姓名,手机,角色码,团队名\n"张三,13800000001,sales,业务一组\n'
  )
  assert.equal(unclosed.rows.length, 0)
  assert.match(unclosed.errors[0], /引号未闭合/)

  const quoteAfterSpace = model.parseStaffImportCsv(
    '姓名,手机,角色码,团队名\n "张三",13800000001,sales,业务一组\n'
  )
  assert.equal(quoteAfterSpace.rows.length, 0)
  assert.match(quoteAfterSpace.errors[0], /引号必须位于字段开头/)

  const trailingSpace = model.parseStaffImportCsv(
    '姓名,手机,角色码,团队名\n"张三" ,13800000001,sales,业务一组\n'
  )
  assert.equal(trailingSpace.rows.length, 0)
  assert.match(trailingSpace.errors[0], /引号闭合后存在非法字符/)
})

test('import generation guard drops stale responses after a newer request', async () => {
  const guard = model.createImportGenerationGuard()
  const olderGeneration = guard.next()
  const newerGeneration = guard.next()

  let applied = null
  const stale = await model.runGuardedImportRequest(
    async () => 'old-batch',
    olderGeneration,
    guard.isCurrent,
    (value) => {
      applied = value
    }
  )
  assert.equal(stale, null)
  assert.equal(applied, null)

  const fresh = await model.runGuardedImportRequest(
    async () => 'new-batch',
    newerGeneration,
    guard.isCurrent,
    (value) => {
      applied = value
    }
  )
  assert.equal(fresh, 'new-batch')
  assert.equal(applied, 'new-batch')
})

test('guarded import request applies before a next-microtask request can supersede it', async () => {
  let generation = 1
  const applied = []
  const isCurrent = (expected) => {
    const current = expected === 1 && generation === 1
    if (current) {
      // 复现 Codex 窗口：isCurrent 通过后的下一微任务启动新请求
      Promise.resolve().then(() => {
        generation = 2
      })
    }
    return current
  }
  const result = await model.runGuardedImportRequest(
    async () => 'old-batch',
    1,
    isCurrent,
    (value) => {
      applied.push({ generation, value })
    }
  )
  await Promise.resolve()
  assert.equal(result, 'old-batch')
  assert.deepEqual(plain(applied), [{ generation: 1, value: 'old-batch' }])
  assert.equal(generation, 2)
})

test('malformed empty-looking record is not swallowed as a blank line', () => {
  const parsed = model.parseStaffImportCsv(
    '姓名,手机,角色码,团队名\n""oops\n张三,13800000001,sales,业务一组\n'
  )
  assert.equal(parsed.errors.length, 1)
  assert.match(parsed.errors[0], /第 1 行/)
  assert.equal(parsed.rows.length, 1)
  assert.equal(parsed.rows[0].line, 2)
  assert.equal(parsed.rows[0].name, '张三')
})

test('withinFileSizeLimit enforces 2MB and non-empty', () => {
  assert.equal(model.withinFileSizeLimit(2 * 1024 * 1024), true)
  assert.equal(model.withinFileSizeLimit(2 * 1024 * 1024 + 1), false)
  assert.equal(model.withinFileSizeLimit(0), false)
})
