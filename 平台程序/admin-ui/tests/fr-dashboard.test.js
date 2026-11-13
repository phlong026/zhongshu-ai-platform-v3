const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/**
 * P1 批1：经营看板纯函数口径测试（node:test，零新依赖）。
 * 与 tests/project-navigation.test.js 同型：用仓内 typescript 转译 TS，再在 VM 中加载。
 * 覆盖状态桶零填、金额/比率/完成率格式化、部门指标单位换算、图表序列映射。
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

const model = load('views/fr/dashboard/model.ts')

/** VM 上下文返回的对象/数组原型与测试上下文不同，深比较前归一化为本上下文普通值。 */
const plain = (value) => JSON.parse(JSON.stringify(value))

test('mergeStatusBuckets zero-fills known statuses and appends unknown ones', () => {
  const filled = model.mergeStatusBuckets(['REGISTERED', 'FOLLOWING'], { FOLLOWING: 4, LEGACY: 2 })
  assert.deepEqual(plain(filled), { REGISTERED: 0, FOLLOWING: 4, LEGACY: 2 })
})

test('mergeStatusBuckets tolerates missing input without mutating base', () => {
  const base = ['PENDING_START', 'ARCHIVED']
  const filled = model.mergeStatusBuckets(base, null)
  assert.deepEqual(plain(filled), { PENDING_START: 0, ARCHIVED: 0 })
  assert.deepEqual(base, ['PENDING_START', 'ARCHIVED'])
})

test('centsToYuan formats cents with two decimals and thousands separators', () => {
  assert.equal(model.centsToYuan(123456), '1,234.56')
  assert.equal(model.centsToYuan(0), '0.00')
  assert.equal(model.centsToYuan(null), '-')
  assert.equal(model.centsToYuan(undefined), '-')
})

test('formatMetricValue treats money metrics as cents and counts as x100 storage', () => {
  assert.equal(model.formatMetricValue('auto:collectionsAmount', 123456), '1,234.56')
  assert.equal(model.formatMetricValue('auto:completedNodes', 250), '2.5')
  assert.equal(model.formatMetricValue('auto:completedNodes', 100), '1')
  assert.equal(model.formatMetricValue('unknown:metric', 100), '1')
})

test('percentText and ratioText are null-safe', () => {
  assert.equal(model.percentText(0.523), '52.3%')
  assert.equal(model.percentText(null), '-')
  assert.equal(model.ratioText(1.234), '1.23×')
  assert.equal(model.ratioText(null), '-')
})

test('completionRate handles zero total and clamps bounds', () => {
  assert.equal(model.completionRate(3, 4), 0.75)
  assert.equal(model.completionRate(0, 0), null)
  assert.equal(model.completionRate(5, 4), 1)
  assert.equal(model.completionRate(0, 4), 0)
})

test('statusSummaryText renders labeled buckets in stable order', () => {
  const text = model.statusSummaryText(
    { REGISTERED: 2, FOLLOWING: 1 },
    { REGISTERED: '已登记', FOLLOWING: '跟进中' }
  )
  assert.equal(text, '已登记 2 / 跟进中 1')
  assert.equal(model.statusSummaryText(null, {}), '-')
})

test('buildTrendOption maps cents to yuan series and keeps period axis', () => {
  const option = model.buildTrendOption([
    { period: '2026-10', incomeCents: 10000, paidCents: 4000, netCents: 6000 },
    { period: '2026-11', incomeCents: 20000, paidCents: 5000, netCents: 15000 }
  ])
  assert.deepEqual(plain(option.xAxis.data), ['2026-10', '2026-11'])
  assert.equal(option.series.length, 3)
  assert.deepEqual(plain(option.series[0].data), [100, 200])
  assert.deepEqual(plain(option.series[1].data), [40, 50])
  assert.deepEqual(plain(option.series[2].data), [60, 150])
})

test('buildCompositionOption keeps negative expense-refund visible and signed', () => {
  const option = model.buildCompositionOption(
    [{ kind: 'engineering', cents: 30000 }],
    [
      { kind: 'payment', cents: 20000 },
      { kind: 'refund', cents: 0 },
      { kind: 'expense-refund', cents: -1000 }
    ]
  )
  assert.equal(option.series.length, 1)
  assert.deepEqual(plain(option.yAxis.data), [
    '收入 · engineering',
    '支出 · payment',
    '支出 · refund',
    '支出 · expense-refund'
  ])
  // -10 元必须原样保留（不取绝对值、不丢弃）；标签按符号逐项放置：
  // 负值 left（避让零线），正值/零 right。
  assert.deepEqual(plain(option.series[0].data), [
    { value: 300, label: { position: 'right' } },
    { value: 200, label: { position: 'right' } },
    { value: 0, label: { position: 'right' } },
    { value: -10, label: { position: 'left' } }
  ])
})

test('compositionTotals keeps itemized and signed net paid amounts', () => {
  const totals = model.compositionTotals(
    [{ kind: 'engineering', cents: 30000 }],
    [
      { kind: 'payment', cents: 20000 },
      { kind: 'refund', cents: 500 },
      { kind: 'expense-refund', cents: -1000 }
    ]
  )
  assert.deepEqual(plain(totals), {
    incomeCents: 30000,
    paymentCents: 20000,
    refundCents: 500,
    expenseRefundCents: -1000,
    netPaidCents: 19500
  })
})

const fulfilled = (value) => ({ status: 'fulfilled', value })
const rejected = (reason = new Error('request failed')) => ({ status: 'rejected', reason })

test('resolveDashboardBlocks rejects stale reuse: failed dashboard yields null metrics', () => {
  // 上一月成功
  let state = model.resolveDashboardBlocks({
    dashboard: fulfilled({ period: '2026-10', crm: { newCustomers: 9 } }),
    analytics: fulfilled({ trend: [] }),
    summary: fulfilled({
      period: '2026-10',
      departments: {},
      taskStats: { total: 0, completed: 0 }
    }),
    snapshots: fulfilled({ period: '2026-10', snapshots: [] })
  })
  assert.equal(state.metrics.period, '2026-10')
  // 切月请求发出：页面先清空月份作用域区块
  state = model.emptyDashboardBlocks()
  assert.equal(state.metrics, null)
  // 11 月 dashboard 失败：必须保持 null，不得回退到 10 月值
  state = model.resolveDashboardBlocks({
    dashboard: rejected(),
    analytics: fulfilled({ trend: [] }),
    summary: rejected(),
    snapshots: rejected()
  })
  assert.equal(state.metrics, null)
  assert.equal(state.summary, null)
  assert.deepEqual(plain(state.snapshots), [])
  assert.equal(state.failedBlocks, 3)
})

test('resolveDashboardBlocks writes current-month fulfilled values', () => {
  const metrics = { period: '2026-11', crm: { newCustomers: 3 } }
  const blocks = model.resolveDashboardBlocks({
    dashboard: fulfilled(metrics),
    analytics: fulfilled({ trend: [] }),
    summary: fulfilled({
      period: '2026-11',
      departments: {},
      taskStats: { total: 0, completed: 0 }
    }),
    snapshots: fulfilled({ period: '2026-11', snapshots: [{ id: '1' }] })
  })
  assert.deepEqual(plain(blocks.metrics), metrics)
  assert.equal(blocks.snapshots.length, 1)
  assert.equal(blocks.failedBlocks, 0)
})

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function fetchersWithDashboard(dashboardImpl, overrides = {}) {
  return {
    dashboard: dashboardImpl,
    analytics: async () => ({ trend: [] }),
    summary: async () => ({
      period: '2026-11',
      departments: {},
      taskStats: { total: 0, completed: 0 }
    }),
    snapshots: async () => ({ period: '2026-11', snapshots: [] }),
    reconciliations: async () => [],
    ...overrides
  }
}

test('loadDashboardBlocks marks the older request stale when it resolves after a newer one', async () => {
  const oldDashboard = deferred()
  const newDashboard = deferred()
  const fetchers = fetchersWithDashboard((period) =>
    period === '2026-10' ? oldDashboard.promise : newDashboard.promise
  )
  let current = 1
  const applied = []
  const oldLoad = model.loadDashboardBlocks(
    fetchers,
    '2026-10',
    () => current === 1,
    (result) => applied.push(result.month)
  )
  current = 2
  const newLoad = model.loadDashboardBlocks(
    fetchers,
    '2026-11',
    () => current === 2,
    (result) => applied.push(result.month)
  )

  newDashboard.resolve({ period: '2026-11', crm: { newCustomers: 2 } })
  const newResult = await newLoad
  oldDashboard.resolve({ period: '2026-10', crm: { newCustomers: 1 } })
  const oldResult = await oldLoad

  assert.equal(newResult.stale, false)
  assert.equal(newResult.blocks.metrics.period, '2026-11')
  // 旧月份绝不能在“选中新月份”之后被 apply 写入。
  assert.deepEqual(applied, ['2026-11'])
  // 删除 loadDashboardBlocks 内的 isCurrent 判断，本断言必红。
  assert.equal(oldResult.stale, true)
  assert.equal(oldResult.blocks.metrics, null)
})

test('loadDashboardBlocks keeps only B data in A->B->A out-of-order resolution', async () => {
  const aDashboard = deferred()
  const bDashboard = deferred()
  const fetchers = fetchersWithDashboard((period) =>
    period === '2026-10' ? aDashboard.promise : bDashboard.promise
  )
  let generation = 0
  let applied = null
  const run = async (period) => {
    const gen = ++generation
    return model.loadDashboardBlocks(
      fetchers,
      period,
      () => gen === generation,
      (result) => {
        applied = result
      }
    )
  }

  const runA = run('2026-10')
  const runB = run('2026-11')
  // B 先返回并写回；A（最早发起）最后返回，必须被代次守卫丢弃。
  bDashboard.resolve({ period: '2026-11', crm: { newCustomers: 2 } })
  await runB
  aDashboard.resolve({ period: '2026-10', crm: { newCustomers: 1 } })
  const resultA = await runA

  assert.equal(resultA.stale, true)
  assert.equal(applied.month, '2026-11')
  assert.equal(applied.blocks.metrics.period, '2026-11')
})

test('loadDashboardBlocks keeps existing reconciliations on failure and counts the failed block', async () => {
  const fetchers = fetchersWithDashboard(
    async () => ({ period: '2026-11', crm: { newCustomers: 1 } }),
    {
      reconciliations: async () => {
        throw new Error('reconciliation failed')
      }
    }
  )
  const result = await model.loadDashboardBlocks(
    fetchers,
    '2026-11',
    () => true,
    () => {}
  )
  assert.equal(result.stale, false)
  assert.equal(result.month, '2026-11')
  assert.equal(result.reconciliations, null)
  assert.equal(result.failedBlocks, 1)
})

test('loadDashboardBlocks applies synchronously before a next-microtask request can supersede it', async () => {
  const dashboard = deferred()
  const fetchers = fetchersWithDashboard(() => dashboard.promise)
  let generation = 1
  const applied = []
  const isCurrent = () => {
    const current = generation === 1
    if (current) {
      // 复现 Codex 窗口：isCurrent 通过后的下一微任务里启动新请求（watch/nextTick 等价）
      Promise.resolve().then(() => {
        generation = 2
      })
    }
    return current
  }
  const load = model.loadDashboardBlocks(fetchers, '2026-10', isCurrent, (result) => {
    // 结构性约束：apply 必须与 isCurrent 同一同步回合；若中间插入微任务，这里会记录 2。
    applied.push({ generation, month: result.month })
  })
  dashboard.resolve({ period: '2026-10', crm: { newCustomers: 1 } })
  const result = await load
  await Promise.resolve() // 让模拟的新请求微任务执行

  assert.equal(result.stale, false)
  assert.deepEqual(applied, [{ generation: 1, month: '2026-10' }])
  assert.equal(generation, 2)
})
