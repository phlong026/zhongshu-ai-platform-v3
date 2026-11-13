const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/** P1 尾批②：银行对账（既有流水展示 + 确认到账）纯函数。 */
function load(file) {
  const filename = path.join(__dirname, '../src', file)
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      console,
      require() {
        throw new Error('unexpected import')
      }
    }
  )
  return module.exports
}

const model = load('views/fr/finance/bankModel.ts')

test('role gate: confirm requires finance or finance-lead (V852 register)', () => {
  assert.equal(model.canConfirmBank('finance'), true)
  assert.equal(model.canConfirmBank('finance-lead'), true)
  assert.equal(model.canConfirmBank('boss'), false)
  assert.equal(model.canConfirmBank('sales'), false)
})

test('source labels map dev/e2e/import and keep unknown raw', () => {
  assert.equal(model.bankSourceLabel('dev'), '联调注入')
  assert.equal(model.bankSourceLabel('e2e'), 'E2E 注入')
  assert.equal(model.bankSourceLabel('import'), '导入')
  assert.equal(model.bankSourceLabel('other'), 'other')
})

test('matchingReceipts keeps only pending receipts with equal amount', () => {
  const receipts = [
    { id: '1', amountCents: 500, status: '待登记' },
    { id: '2', amountCents: 500, status: '已登记' },
    { id: '3', amountCents: 600, status: '待登记' },
    { id: '4', amountCents: '500', status: '待登记' }
  ]
  assert.deepEqual(
    model.matchingReceipts(receipts, 500).map((r) => r.id),
    ['1', '4']
  )
  assert.equal(model.matchingReceipts([], 500).length, 0)
})

test('confirmError covers flow/receipt/amount preconditions', () => {
  const pending = { amountCents: 500, status: '待匹配' }
  assert.equal(model.confirmError(pending, { amountCents: 500, status: '待登记' }, '1'), '')
  assert.match(
    model.confirmError(
      { ...pending, status: '已认领' },
      { amountCents: 500, status: '待登记' },
      '1'
    ),
    /已被认领/
  )
  assert.match(model.confirmError(pending, { amountCents: 500, status: '已登记' }, '1'), /待登记/)
  assert.match(model.confirmError(pending, { amountCents: 501, status: '待登记' }, '1'), /金额/)
  assert.match(model.confirmError(pending, null, '1'), /请选择/)
  assert.match(model.confirmError(pending, { amountCents: 500, status: '待登记' }, ''), /请选择/)
})

test('only 待匹配 flows are confirmable (shared by template and tests)', () => {
  assert.equal(model.isConfirmableBankFlow({ status: '待匹配' }), true)
  assert.equal(model.isConfirmableBankFlow({ status: '已认领' }), false)
  assert.equal(model.isConfirmableBankFlow(null), false)
})

test('confirm key is unique per attempt and prefixed', () => {
  const a = model.newBankConfirmKey()
  const b = model.newBankConfirmKey()
  assert.match(a, /^bank-confirm:[A-Za-z0-9_-]{1,40}$/)
  assert.notEqual(a, b)
})

test('status filter options are the two real flow statuses plus all', () => {
  // 跨 VM realm 原型不同，JSON 归一后比较
  assert.deepEqual(JSON.parse(JSON.stringify(model.BANK_FLOW_STATUS_OPTIONS.map((o) => o.value))), [
    '',
    '待匹配',
    '已认领'
  ])
})
