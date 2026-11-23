const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '..')

test('payment mode API uses the protected global payment-mode endpoints', () => {
  const api = fs.readFileSync(path.join(root, 'src/api/platform/payment.ts'), 'utf8')
  assert.match(api, /url: '\/platform\/payment-mode'/)
  assert.match(api, /expectedVersion/)
  assert.match(api, /mode: 'MOCK' \| 'REAL'/)
})

test('payment center clearly identifies mock mode and keeps REAL disabled until channel readiness exists', () => {
  const page = fs.readFileSync(path.join(root, 'src/views/platform/payment/index.vue'), 'utf8')
  assert.match(page, /模拟支付/)
  assert.match(page, /不会发生真实扣款/)
  assert.match(page, /切换为真实支付（尚未开放）/)
  assert.match(page, /expectedVersion/)
  assert.match(page, /变更原因/)
})
