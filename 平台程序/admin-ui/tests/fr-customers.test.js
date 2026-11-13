const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

/** P1 批4：客资表格纯函数与页面关键路径（零新依赖）。 */
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
      if (name === '@/utils/csvImport') return loadTsModule('utils/csvImport.ts')
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

const model = loadTsModule('views/fr/customers/model.ts')

test('customerStatusLabel/Tag map REGISTERED/FOLLOWING and keep unknown raw', () => {
  assert.equal(model.customerStatusLabel('REGISTERED'), '已登记')
  assert.equal(model.customerStatusLabel('FOLLOWING'), '跟进中')
  assert.equal(model.customerStatusLabel('ARCHIVED'), 'ARCHIVED')
  assert.equal(model.customerStatusLabel('toString'), 'toString')
  assert.equal(model.customerStatusTag('REGISTERED'), 'success')
  assert.equal(model.customerStatusTag('FOLLOWING'), 'warning')
  assert.equal(model.customerStatusTag('ARCHIVED'), 'info')
})

test('normalizeCustomerQuery trims, drops empty filters and clamps paging', () => {
  const q = model.normalizeCustomerQuery({
    pageNo: 0,
    pageSize: 500,
    keyword: '  张三  ',
    status: '',
    ownerUserId: ''
  })
  assert.equal(q.pageNo, 1)
  assert.equal(q.pageSize, 100)
  assert.equal(q.keyword, '张三')
  assert.equal('status' in q, false)
  assert.equal('ownerUserId' in q, false)

  const q2 = model.normalizeCustomerQuery({
    pageNo: 3,
    pageSize: 20,
    status: 'FOLLOWING',
    ownerUserId: '42'
  })
  assert.equal(q2.status, 'FOLLOWING')
  assert.equal(q2.ownerUserId, '42')
})

test('transferIdempotencyKey is stable per customer+revision', () => {
  assert.equal(model.transferIdempotencyKey('9', 3), 'customer-transfer:9:3')
  assert.notEqual(model.transferIdempotencyKey('9', 3), model.transferIdempotencyKey('9', 4))
})

// ===== 页面级 =====
function loadPage(identityStore, opts = {}) {
  const file = path.join(__dirname, '../src/views/fr/customers/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'customers-page' }).content
  const leadModel = loadTsModule('views/fr/org-staff/leadImportModel.ts')
  const customerModel = loadTsModule('views/fr/customers/model.ts')
  const mounted = []
  const messages = { warning: [], error: [], success: [] }
  const leadApi = opts.leadApi || {
    getPendingLeads: async () => [],
    precheckLeadImport: async () => ({}),
    submitLeadImport: async () => ({})
  }
  const customerApi = opts.customerApi || {
    getCustomers: async () => ({ list: [], total: 0 }),
    getOwnerOptions: async () => [],
    getTransferCandidates: async () => [],
    transferCustomer: async () => ({})
  }
  const calls = []
  const apiProxies = new Proxy(customerApi, {
    get(target, prop) {
      const value = target[prop]
      if (typeof value === 'function') {
        return (...args) => {
          calls.push([prop, ...args])
          return value(...args)
        }
      }
      return value
    }
  })
  const vueStub = { ...vue, onMounted: (cb) => mounted.push(cb) }
  const module = { exports: {} }
  vm.runInNewContext(transpile(script), {
    module,
    exports: module.exports,
    console,
    vue: vueStub,
    Blob: class {},
    URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() {} },
    document: { createElement: () => ({ click() {}, set href(v) {} }) },
    require(name) {
      if (name === 'vue') return vueStub
      if (name === 'element-plus') {
        return {
          ElMessage: {
            warning: (m) => messages.warning.push(String(m)),
            error: (m) => messages.error.push(String(m)),
            success: (m) => messages.success.push(String(m))
          },
          ElMessageBox: {}
        }
      }
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identityStore }
      }
      if (name === '@/api/platform/leads') return leadApi
      if (name === '@/api/platform/customers') return apiProxies
      if (name === '@/views/fr/org-staff/leadImportModel') return leadModel
      if (name === './model') return customerModel
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const bindings = module.exports.default.setup(
    {},
    { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  )
  bindings.__mounted = mounted
  bindings.__calls = calls
  bindings.__messages = messages
  return bindings
}

function identity(perms) {
  return {
    currentPost: { postId: 1, orgId: 151, roleCode: 'boss' },
    can: (p) => perms.includes(p)
  }
}

test('customers page: loads page with normalized filters and no permission means no request', async () => {
  const rows = {
    list: [
      {
        id: '1',
        name: '张三',
        phone: '13800000001',
        status: 'REGISTERED',
        ownerUserId: '9',
        ownerName: '李四',
        revision: 1
      }
    ],
    total: 1
  }
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:query']), {
    customerApi: {
      getCustomers: async () => rows,
      getOwnerOptions: async () => [],
      getTransferCandidates: async () => [],
      transferCustomer: async () => ({})
    }
  })
  page.customerFilters.value = { keyword: ' 张三 ', status: '', ownerUserId: '' }
  await page.loadCustomers()
  assert.equal(page.customers.value.list.length, 1)
  const calls = page.__calls.filter((c) => c[0] === 'getCustomers')
  assert.equal(calls.length, 1)
  assert.equal(calls[0][1].keyword, '张三')
  assert.equal('status' in calls[0][1], false)

  const denied = loadPage(identity(['franchise:lead:handle']), {
    customerApi: {
      getCustomers: async () => rows,
      getOwnerOptions: async () => [],
      getTransferCandidates: async () => [],
      transferCustomer: async () => ({})
    }
  })
  await denied.loadCustomers()
  assert.equal(denied.__calls.filter((c) => c[0] === 'getCustomers').length, 0)
})

test('customers page: transfer sends revision and idempotency key, failure refreshes list', async () => {
  const row = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 3
  }
  let ownerOptionCalls = 0
  let transferCalls = 0
  const page = loadPage(
    identity(['franchise:lead:handle', 'franchise:customer:query', 'franchise:customer:transfer']),
    {
      customerApi: {
        getCustomers: async () => ({ list: [row], total: 1 }),
        getOwnerOptions: async () => {
          ownerOptionCalls += 1
          return [{ userId: '1', name: '王五' }]
        },
        getTransferCandidates: async (customerId) => {
          assert.equal(customerId, '9')
          return [
            { userId: '2', name: '李四', roleCode: 'sales' },
            { userId: '1', name: '当前负责人', roleCode: 'sales' }
          ]
        },
        transferCustomer: async () => {
          transferCalls += 1
          if (transferCalls === 1) return {}
          throw new Error('conflict')
        }
      }
    }
  )
  page.transferRow.value = row
  page.transferTargetUserId.value = '2'
  page.transferReason.value = '  调岗  '
  await page.confirmTransfer()
  const call = page.__calls.filter((c) => c[0] === 'transferCustomer')[0]
  assert.equal(call[1], '9')
  assert.deepEqual(JSON.parse(JSON.stringify(call[2])), {
    targetUserId: '2',
    reason: '调岗',
    revision: 3
  })
  assert.equal(call[3], 'customer-transfer:9:3')
  assert.equal(page.customerNotice.value, '转让成功')
  // 转让成功后强制重载负责人名册（新负责人可能此前没有客资）
  assert.equal(ownerOptionCalls >= 1, true)

  page.transferRow.value = row
  page.transferTargetUserId.value = '2'
  page.transferReason.value = '调岗'
  await page.confirmTransfer()
  assert.match(page.customerNotice.value, /转让失败/)
  assert.equal(page.__calls.filter((c) => c[0] === 'getCustomers').length >= 2, true)
})

test('customers page: transfer requires candidate and reason', async () => {
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:transfer']))
  page.transferRow.value = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 1
  }
  await page.confirmTransfer()
  assert.match(page.__messages.warning[0] || '', /./)
  assert.equal(page.__calls.filter((c) => c[0] === 'transferCustomer').length, 0)
})

test('customers page: owner options load independently of transfer permission', async () => {
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:query']), {
    customerApi: {
      getCustomers: async () => ({ list: [], total: 0 }),
      getOwnerOptions: async () => [
        { userId: '1', name: '王五' },
        { userId: '2', name: '' }
      ],
      getTransferCandidates: async () => [],
      transferCustomer: async () => ({})
    }
  })
  assert.equal(page.__mounted.length, 1)
  page.__mounted[0]()
  await new Promise((resolve) => setTimeout(resolve, 0))
  assert.equal(page.ownerOptions.value.length, 2)
  assert.equal(page.ownerOptions.value[0].name, '王五')
})

test('customers page: reason-only validation does not call transfer', async () => {
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:transfer']))
  page.transferRow.value = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 1
  }
  page.transferTargetUserId.value = '2'
  page.transferReason.value = '   '
  await page.confirmTransfer()
  assert.match(page.__messages.warning[0] || '', /转让原因/)
  assert.equal(page.__calls.filter((c) => c[0] === 'transferCustomer').length, 0)
})

test('customers page: out-of-order customer loads keep the latest page', async () => {
  const deferred = []
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:query']), {
    customerApi: {
      getCustomers: (params) =>
        new Promise((resolve) => {
          deferred.push({ pageNo: params.pageNo, resolve })
        }),
      getOwnerOptions: async () => [],
      getTransferCandidates: async () => [],
      transferCustomer: async () => ({})
    }
  })
  page.customerPageNo.value = 2
  const older = page.loadCustomers()
  page.customerPageNo.value = 3
  const newer = page.loadCustomers()
  deferred.find((d) => d.pageNo === 3).resolve({ list: [{ id: 'p3' }], total: 40 })
  await newer
  deferred.find((d) => d.pageNo === 2).resolve({ list: [{ id: 'p2' }], total: 40 })
  await older
  assert.equal(page.customers.value.list[0].id, 'p3')
})

test('customers page: transfer success with refresh failure keeps the success fact', async () => {
  let calls = 0
  const page = loadPage(
    identity(['franchise:lead:handle', 'franchise:customer:query', 'franchise:customer:transfer']),
    {
      customerApi: {
        getCustomers: async () => {
          calls += 1
          if (calls >= 1) throw new Error('refresh down')
          return {
            list: [
              {
                id: '9',
                name: '张三',
                phone: '13800000001',
                status: 'REGISTERED',
                ownerUserId: '1',
                revision: 3
              }
            ],
            total: 1
          }
        },
        getOwnerOptions: async () => [],
        getTransferCandidates: async () => [],
        transferCustomer: async () => ({})
      }
    }
  )
  page.transferRow.value = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 3
  }
  page.transferTargetUserId.value = '2'
  page.transferReason.value = '调岗'
  await page.confirmTransfer()
  assert.match(page.customerNotice.value, /转让已成功，列表刷新失败/)
  assert.equal(page.customerError.value, false)
})

test('customers page: transfer candidates exclude the current owner and pass customerId', async () => {
  const calls = []
  const row = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 3
  }
  const page = loadPage(
    identity(['franchise:lead:handle', 'franchise:customer:query', 'franchise:customer:transfer']),
    {
      customerApi: {
        getCustomers: async () => ({ list: [row], total: 1 }),
        getOwnerOptions: async () => [],
        getTransferCandidates: async (customerId) => {
          calls.push(customerId)
          return [
            { userId: '2', name: '李四', roleCode: 'sales' },
            { userId: '1', name: '当前负责人', roleCode: 'sales' }
          ]
        },
        transferCustomer: async () => ({})
      }
    }
  )
  await page.openTransfer(row)
  assert.equal(calls[0], '9')
  assert.deepEqual(
    page.transferCandidates.value.map((c) => c.userId),
    ['2']
  )
})

test('customers page: failed page change keeps previous page number and data', async () => {
  let failNext = false
  const page = loadPage(identity(['franchise:lead:handle', 'franchise:customer:query']), {
    customerApi: {
      getCustomers: async (params) => {
        if (failNext) throw new Error('page 3 down')
        return { list: [{ id: `p${params.pageNo}` }], total: 40 }
      },
      getOwnerOptions: async () => [],
      getTransferCandidates: async () => [],
      transferCustomer: async () => ({})
    }
  })
  await page.loadCustomers(2)
  assert.equal(page.customerPageNo.value, 2)
  failNext = true
  await page.onCustomerPageChange(3)
  assert.equal(page.customerPageNo.value, 2)
  assert.equal(page.customers.value.list[0].id, 'p2')
  assert.match(page.customerNotice.value, /客资列表加载失败/)
})

test('customers page: transfer failure with refresh failure keeps both facts', async () => {
  const page = loadPage(
    identity(['franchise:lead:handle', 'franchise:customer:query', 'franchise:customer:transfer']),
    {
      customerApi: {
        getCustomers: async () => {
          throw new Error('refresh down')
        },
        getOwnerOptions: async () => [],
        getTransferCandidates: async () => [],
        transferCustomer: async () => {
          throw new Error('conflict')
        }
      }
    }
  )
  page.transferRow.value = {
    id: '9',
    name: '张三',
    phone: '13800000001',
    status: 'REGISTERED',
    ownerUserId: '1',
    revision: 3
  }
  page.transferTargetUserId.value = '2'
  page.transferReason.value = '调岗'
  await page.confirmTransfer()
  assert.match(page.customerNotice.value, /转让失败，且列表刷新失败/)
  assert.equal(page.customerError.value, true)
})
