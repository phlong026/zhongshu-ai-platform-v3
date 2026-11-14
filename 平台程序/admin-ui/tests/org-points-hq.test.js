const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const root = path.resolve(__dirname, '..')

function tsModule(relativePath) {
  const filename = path.join(root, relativePath)
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports }, { filename })
  return module.exports
}

function memoryStorage() {
  const values = new Map()
  return {
    values,
    storage: {
      get length() {
        return values.size
      },
      key(index) {
        return [...values.keys()][index] || null
      },
      getItem(key) {
        return values.get(key) || null
      },
      setItem(key, value) {
        values.set(key, value)
      },
      removeItem(key) {
        values.delete(key)
      }
    }
  }
}

test('总部公司积分 API 使用独立公司池合同，不回退旧钱包', () => {
  const source = fs.readFileSync(path.join(root, 'src/api/platform/orgPoints.ts'), 'utf8')
  for (const endpoint of [
    '/platform/org-points/companies',
    '/platform/org-points/${encodeURIComponent(orgId)}/summary',
    '/platform/org-points/${encodeURIComponent(orgId)}/ledger',
    '/platform/org-points/${encodeURIComponent(orgId)}/adjustments',
    '/platform/org-points/adjustments/${encodeURIComponent(id)}/review',
    '/platform/org-points/prices'
  ]) {
    assert.match(source, new RegExp(endpoint.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  assert.doesNotMatch(source, /platform\/ops\/wallets|platform\/ops\/ledger|design-prices/)
})

test('公司积分页按权限展示双人复核和价格发布，并保留失败重试状态', () => {
  const source = fs.readFileSync(path.join(root, 'src/views/platform/org-points/index.vue'), 'utf8')
  for (const permission of [
    'platform:org-point:query',
    'platform:org-point:adjustment:submit',
    'platform:org-point:adjustment:review',
    'platform:org-point:price:query',
    'platform:org-point:price:update'
  ]) {
    assert.match(source, new RegExp(permission))
  }
  assert.match(source, /公司积分池/)
  assert.match(source, /提交人与复核人必须不同/)
  assert.match(source, /需其他人员复核/)
  assert.match(source, /重新加载/)
  assert.match(source, /v-loading="loading"/)
  assert.match(source, /暂无公司积分数据/)
  assert.doesNotMatch(source, /充值套餐|充值订单/)
})

test('调点失败重试冻结原载荷并复用同一幂等键，取消或成功后才重置', () => {
  const { createDraft, beginAttempt, resetDraft } = tsModule(
    'src/views/platform/org-points/adjustmentDraft.ts'
  )
  const draft = createDraft('fixed-key')
  draft.delta = 800
  draft.reason = '补录公司积分'

  const first = beginAttempt(draft)
  assert.deepEqual(JSON.parse(JSON.stringify(first)), {
    delta: 800,
    reason: '补录公司积分',
    requestKey: 'fixed-key'
  })
  assert.equal(draft.attempted, true)

  draft.delta = 900
  draft.reason = '被界面误改'
  const retry = beginAttempt(draft)
  assert.deepEqual(JSON.parse(JSON.stringify(retry)), JSON.parse(JSON.stringify(first)))

  resetDraft(draft, 'next-key')
  assert.equal(draft.requestKey, 'next-key')
  assert.equal(draft.attempted, false)
  assert.equal(draft.delta, 0)
  assert.equal(draft.reason, '')
})

test('调点只接受安全整数，避免 BIGINT 经 Number 静默丢失精度', () => {
  const { createDraft, beginAttempt } = tsModule('src/views/platform/org-points/adjustmentDraft.ts')
  const draft = createDraft('key')
  draft.reason = '测试'
  draft.delta = Number.MAX_SAFE_INTEGER + 1
  assert.throws(() => beginAttempt(draft), /安全整数/)
})

test('调点丢 ACK 后可跨刷新恢复，且按租户账号公司隔离', () => {
  const { savePending, loadPending, findPending, clearPending } = tsModule(
    'src/views/platform/org-points/adjustmentPending.ts'
  )
  const values = new Map()
  const storage = {
    get length() {
      return values.size
    },
    key(index) {
      return [...values.keys()][index] || null
    },
    getItem(key) {
      return values.get(key) || null
    },
    setItem(key, value) {
      values.set(key, value)
    },
    removeItem(key) {
      values.delete(key)
    }
  }
  const scope = { tenantId: 'tenant-a', actorId: 'user-a', orgId: 'org-a' }
  const frozen = { delta: 800, reason: '补录积分', requestKey: 'stable-key' }
  savePending(storage, scope, frozen)

  assert.deepEqual(JSON.parse(JSON.stringify(loadPending(storage, scope).frozen)), frozen)
  assert.equal(loadPending(storage, { ...scope, actorId: 'user-b' }), null)
  assert.equal(loadPending(storage, { ...scope, tenantId: 'tenant-b' }), null)
  assert.equal(loadPending(storage, { ...scope, orgId: 'org-b' }), null)
  assert.equal(findPending(storage, 'tenant-a', 'user-a').scope.orgId, 'org-a')
  assert.equal(findPending(storage, 'tenant-a', 'user-b'), null)

  clearPending(storage, scope)
  assert.equal(loadPending(storage, scope), null)
})

test('首发前存储写入或回读故障时绝不发起请求', async () => {
  const { savePending } = tsModule('src/views/platform/org-points/adjustmentPending.ts')
  const { runPersistedRequest } = tsModule('src/views/platform/org-points/persistedRequest.ts')
  const scope = { tenantId: 'tenant-a', actorId: 'user-a', orgId: 'org-a' }
  const frozen = { delta: 800, reason: '补录积分', requestKey: 'stable-key' }

  for (const storage of [
    {
      setItem() {
        throw new Error('quota')
      },
      getItem() {
        return null
      }
    },
    {
      setItem() {},
      getItem() {
        return 'wrong-value'
      }
    },
    {
      setItem() {},
      getItem() {
        throw new Error('read failed')
      }
    }
  ]) {
    let requestCount = 0
    await assert.rejects(
      runPersistedRequest(
        () => savePending(storage, scope, frozen),
        async () => {
          requestCount += 1
        },
        () => {}
      )
    )
    assert.equal(requestCount, 0)
  }
})

test('请求成功但持久记录清理失败时保留原记录可重试', async () => {
  const { savePricePending, findPricePending, clearPricePending } = tsModule(
    'src/views/platform/org-points/pricePending.ts'
  )
  const { runPersistedRequest } = tsModule('src/views/platform/org-points/persistedRequest.ts')
  const { values, storage } = memoryStorage()
  const scope = { tenantId: 'tenant-a', actorId: 'user-a' }
  const frozen = {
    subject: 'AI_IMAGE',
    priceMicros: 2_500_000,
    minCharge: 1,
    reason: '成本调整',
    requestKey: 'price-key'
  }
  let requestCount = 0
  const brokenClearStorage = {
    ...storage,
    get length() {
      return values.size
    },
    removeItem() {
      throw new Error('remove failed')
    }
  }

  await assert.rejects(
    runPersistedRequest(
      () => savePricePending(brokenClearStorage, scope, frozen),
      async () => {
        requestCount += 1
        return 'price-id'
      },
      () => clearPricePending(brokenClearStorage, scope, frozen.subject)
    ),
    /remove failed/
  )
  assert.equal(requestCount, 1)
  assert.equal(
    findPricePending(brokenClearStorage, 'tenant-a', 'user-a').frozen.requestKey,
    'price-key'
  )

  const ignoredRemoveStorage = { ...storage, removeItem() {} }
  assert.throws(() => clearPricePending(ignoredRemoveStorage, scope, frozen.subject), /清理失败/)
  assert.equal(findPricePending(storage, 'tenant-a', 'user-a').frozen.requestKey, 'price-key')
})

test('损坏的未确认记录必须失败关闭，不当作无待处理记录', () => {
  const { findPending } = tsModule('src/views/platform/org-points/adjustmentPending.ts')
  const { values, storage } = memoryStorage()
  values.set('zs:hq-org-point-adjustment:v1:tenant-a:user-a:org-a', '{broken-json')
  assert.throws(() => findPending(storage, 'tenant-a', 'user-a'), /记录损坏/)
})

test('调点请求拒绝或清理失败后均保留完整原申请与未确认状态', async () => {
  const { createDraft, beginAttempt } = tsModule('src/views/platform/org-points/adjustmentDraft.ts')
  const { savePending, findPending, clearPending } = tsModule(
    'src/views/platform/org-points/adjustmentPending.ts'
  )
  const { runPersistedRequest } = tsModule('src/views/platform/org-points/persistedRequest.ts')
  const scope = { tenantId: 'tenant-a', actorId: 'user-a', orgId: 'org-a' }
  const expected = { delta: -650, reason: '纠正误入积分', requestKey: 'adjustment-original-key' }

  for (const failure of ['request', 'clear']) {
    const { storage } = memoryStorage()
    const draft = createDraft(expected.requestKey)
    draft.delta = expected.delta
    draft.reason = expected.reason
    const frozen = beginAttempt(draft)
    const clearStorage =
      failure === 'clear'
        ? {
            ...storage,
            removeItem() {}
          }
        : storage

    await assert.rejects(
      runPersistedRequest(
        () => savePending(storage, scope, frozen),
        async () => {
          if (failure === 'request') throw new Error('network rejected')
          return 'adjustment-id'
        },
        () => clearPending(clearStorage, scope)
      ),
      failure === 'request' ? /network rejected/ : /清理失败/
    )
    assert.deepEqual(
      JSON.parse(JSON.stringify(findPending(storage, scope.tenantId, scope.actorId).frozen)),
      expected
    )
    assert.equal(draft.requestKey, expected.requestKey)
    assert.equal(draft.attempted, true)
  }
})

test('价格请求拒绝或清理失败后均保留完整原请求与未确认状态', async () => {
  const { createPriceDraft, beginPriceAttempt } = tsModule(
    'src/views/platform/org-points/priceDraft.ts'
  )
  const { savePricePending, findPricePending, clearPricePending } = tsModule(
    'src/views/platform/org-points/pricePending.ts'
  )
  const { runPersistedRequest } = tsModule('src/views/platform/org-points/persistedRequest.ts')
  const scope = { tenantId: 'tenant-a', actorId: 'user-a' }
  const expected = {
    subject: 'AI_VIDEO',
    priceMicros: 12_345_678,
    minCharge: 4,
    reason: '供应商价格调整',
    requestKey: 'price-original-key'
  }

  for (const failure of ['request', 'clear']) {
    const { storage } = memoryStorage()
    const draft = createPriceDraft(expected.requestKey)
    draft.points = '12.345678'
    draft.minCharge = '4'
    draft.reason = expected.reason
    const frozen = beginPriceAttempt(draft, expected.subject)
    const clearStorage =
      failure === 'clear'
        ? {
            ...storage,
            removeItem() {}
          }
        : storage

    await assert.rejects(
      runPersistedRequest(
        () => savePricePending(storage, scope, frozen),
        async () => {
          if (failure === 'request') throw new Error('network rejected')
          return 'price-id'
        },
        () => clearPricePending(clearStorage, scope, frozen.subject)
      ),
      failure === 'request' ? /network rejected/ : /清理失败/
    )
    assert.deepEqual(
      JSON.parse(JSON.stringify(findPricePending(storage, scope.tenantId, scope.actorId).frozen)),
      expected
    )
    assert.equal(draft.requestKey, expected.requestKey)
    assert.equal(draft.attempted, true)
  }
})

test('组合故障在页面上统一映射为结果未确认，不进入成功重置路径', () => {
  const source = fs.readFileSync(path.join(root, 'src/views/platform/org-points/index.vue'), 'utf8')
  assert.match(source, /v-if="adjustmentForm\.attempted"/)
  assert.match(source, /v-if="priceForm\.attempted"/)
  assert.match(source, /提交结果未确认/)
  assert.match(source, /发布结果未确认/)
  assert.match(source, /if \(!requestStarted && !wasAttempted\)/)
})

test('价格发布丢 ACK 后冻结完整请求，刷新后只向原租户账号恢复', () => {
  const { createPriceDraft, beginPriceAttempt, restorePriceDraft } = tsModule(
    'src/views/platform/org-points/priceDraft.ts'
  )
  const { savePricePending, findPricePending, clearPricePending } = tsModule(
    'src/views/platform/org-points/pricePending.ts'
  )
  const values = new Map()
  const storage = {
    get length() {
      return values.size
    },
    key(index) {
      return [...values.keys()][index] || null
    },
    getItem(key) {
      return values.get(key) || null
    },
    setItem(key, value) {
      values.set(key, value)
    },
    removeItem(key) {
      values.delete(key)
    }
  }
  const draft = createPriceDraft('price-key')
  draft.points = '12.345678'
  draft.minCharge = '3'
  draft.reason = '供应商成本调整'
  const frozen = beginPriceAttempt(draft, 'AI_IMAGE')
  savePricePending(storage, { tenantId: 'tenant-a', actorId: 'user-a' }, frozen)

  draft.points = '99'
  draft.reason = '被界面误改'
  assert.deepEqual(
    JSON.parse(JSON.stringify(beginPriceAttempt(draft, 'AI_IMAGE'))),
    JSON.parse(JSON.stringify(frozen))
  )
  assert.equal(findPricePending(storage, 'tenant-a', 'user-b'), null)
  assert.equal(findPricePending(storage, 'tenant-b', 'user-a'), null)

  const restored = createPriceDraft('other-key')
  restorePriceDraft(restored, findPricePending(storage, 'tenant-a', 'user-a').frozen)
  assert.equal(restored.requestKey, 'price-key')
  assert.equal(restored.points, '12.345678')
  assert.equal(restored.minCharge, '3')
  assert.equal(restored.attempted, true)

  clearPricePending(storage, { tenantId: 'tenant-a', actorId: 'user-a' }, 'AI_IMAGE')
  assert.equal(findPricePending(storage, 'tenant-a', 'user-a'), null)
})

test('调点与价格未确认请求在页面上不能被取消或关闭清除', () => {
  const source = fs.readFileSync(path.join(root, 'src/views/platform/org-points/index.vue'), 'utf8')
  const api = fs.readFileSync(path.join(root, 'src/api/platform/orgPoints.ts'), 'utf8')
  assert.match(source, /:before-close="closeAdjustment"/)
  assert.match(source, /:before-close="closePrice"/)
  assert.match(source, /结果未确认的申请不能取消/)
  assert.match(source, /结果未确认的价格发布不能取消/)
  assert.match(api, /requestKey: string/)
})

test('BIGINT 余额和微积分使用字符串精确格式化', () => {
  const { formatInteger, formatMicros } = tsModule('src/views/platform/org-points/int64.ts')
  assert.equal(formatInteger('9007199254740993'), '9,007,199,254,740,993')
  assert.equal(formatInteger(9007199254740992), '数据精度异常')
  assert.equal(formatMicros('9007199254740993'), '9007199254.740993')
})

test('总部工作台明确区分旧钱包并提供公司积分入口', () => {
  const source = fs.readFileSync(path.join(root, 'src/views/platform/home/index.vue'), 'utf8')
  const routes = fs.readFileSync(path.join(root, 'src/router/modules/remaining.ts'), 'utf8')
  assert.match(source, /公司积分池/)
  assert.match(source, /历史钱包/)
  assert.match(source, /\/platform-org-points/)
  assert.match(routes, /path: 'platform-org-points'/)
  assert.match(routes, /permission: 'platform:org-point:query'/)
  assert.match(routes, /hidden: true/)
})
