const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/** P1 尾批①：异常资金写操作纯函数（权限/金额/关联类型/校验/幂等键）。 */
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
      require(name) {
        throw new Error(`Unexpected import: ${name}`)
      }
    }
  )
  return module.exports
}

const model = load('views/fr/finance/abnormalModel.ts')

test('role gates: register=finance/finance-lead; void=finance-lead/boss/owner', () => {
  assert.equal(model.canRegisterAbnormal('finance'), true)
  assert.equal(model.canRegisterAbnormal('finance-lead'), true)
  assert.equal(model.canRegisterAbnormal('boss'), false)
  assert.equal(model.canRegisterAbnormal('sales'), false)
  assert.equal(model.canVoidAbnormal('finance-lead'), true)
  assert.equal(model.canVoidAbnormal('boss'), true)
  assert.equal(model.canVoidAbnormal('owner'), true)
  assert.equal(model.canVoidAbnormal('finance'), false)
})

test('direction maps to the only legal linked type', () => {
  assert.equal(model.linkTypeFor('客户收款'), 'receipt')
  assert.equal(model.linkTypeFor('客户退款'), 'refund')
  assert.equal(model.linkTypeFor('支出退回'), 'payment')
  assert.equal(model.linkTypeFor('未知'), '')
})

test('parseYuanToCents accepts <=2 decimals and rejects malformed/zero/overflow', () => {
  assert.equal(model.parseYuanToCents('12.34'), 1234)
  assert.equal(model.parseYuanToCents('0.5'), 50)
  assert.equal(model.parseYuanToCents('100'), 10000)
  assert.equal(model.parseYuanToCents('1,234'), null)
  assert.equal(model.parseYuanToCents('12.345'), null)
  assert.equal(model.parseYuanToCents('0'), null)
  assert.equal(model.parseYuanToCents('-1'), null)
  assert.equal(model.parseYuanToCents('abc'), null)
  assert.equal(model.parseYuanToCents(''), null)
  assert.equal(model.parseYuanToCents('9999999999999'), null)
})

test('buildRegisterCommand produces backend payload and column-length errors', () => {
  const ok = model.buildRegisterCommand({
    fundDirection: '客户收款',
    amountYuan: '12.34',
    counterparty: '张三',
    relatedProjectId: '88',
    businessDate: '2026-11-01',
    method: 'bank',
    transactionNo: 'T-1',
    stageOrCategory: '首期',
    reason: '原单缺失'
  })
  assert.equal(ok.ok, true)
  assert.deepEqual(JSON.parse(JSON.stringify(ok.payload)), {
    fundDirection: '客户收款',
    amountCents: 1234,
    counterparty: '张三',
    relatedProjectId: '88',
    stageOrCategory: '首期',
    businessDate: '2026-11-01',
    method: 'bank',
    transactionNo: 'T-1',
    reason: '原单缺失'
  })

  const bad = model.buildRegisterCommand({
    fundDirection: '',
    amountYuan: 'x',
    counterparty: 'x'.repeat(65),
    stageOrCategory: 'x'.repeat(33),
    transactionNo: 'x'.repeat(65),
    reason: 'x'.repeat(256)
  })
  assert.equal(bad.ok, false)
  assert.match(bad.errors.join('|'), /资金方向/)
  assert.match(bad.errors.join('|'), /金额/)
  assert.match(bad.errors.join('|'), /对方最长 64/)
  assert.match(bad.errors.join('|'), /期次\/类别最长 32/)
  assert.match(bad.errors.join('|'), /交易号最长 64/)
  assert.match(bad.errors.join('|'), /原因最长 255/)
})

test('link only for pending; void for pending and linked (existing backend semantics)', () => {
  assert.equal(model.isPendingAbnormal({ status: '已核待关联' }), true)
  assert.equal(model.isPendingAbnormal({ status: '已关联' }), false)
  assert.equal(model.isPendingAbnormal({ status: '已作废' }), false)
  assert.equal(model.isVoidableAbnormal({ status: '已核待关联' }), true)
  assert.equal(model.isVoidableAbnormal({ status: '已关联' }), true)
  assert.equal(model.isVoidableAbnormal({ status: '已作废' }), false)
})

test('link target requires positive integer and matches derived type', () => {
  assert.equal(model.linkTargetError(''), '关联单号须为正整数')
  assert.equal(model.linkTargetError('0'), '关联单号须为正整数')
  assert.equal(model.linkTargetError('abc'), '关联单号须为正整数')
  assert.equal(model.linkTargetError('123'), '')
  // 字符串规则与 Number() 的差异：前导零/超 Long 长度必须拒绝（Number 会放行）
  assert.equal(model.linkTargetError('009'), '关联单号须为正整数')
  assert.equal(model.linkTargetError('1'.repeat(20)), '关联单号须为正整数')
  assert.equal(model.linkTargetError('1987654321098765432'), '')
})

test('void reason is required and bounded', () => {
  assert.equal(model.voidReasonError('  '), '请填写作废原因')
  assert.equal(model.voidReasonError('x'.repeat(256)), '作废原因最长 255 字')
  assert.equal(model.voidReasonError('误录'), '')
})

test('register idempotency key is unique per dialog attempt and prefixed', () => {
  const a = model.newAbnormalRegisterKey()
  const b = model.newAbnormalRegisterKey()
  assert.match(a, /^abnormal-register:[A-Za-z0-9_-]{1,40}$/)
  assert.notEqual(a, b)
})
