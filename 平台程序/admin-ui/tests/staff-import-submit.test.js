const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')

/**
 * P1 批2：员工批量导入页面级回归（N-P2-4 提交/刷新分离、N-P2-2 文件大小上限、换文件清空）。
 * 编译 index.vue 的 <script setup> 并在 VM 中执行 setup()，以可注入的 api/store 桩驱动。
 */
function transpile(source) {
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
}

function loadTsModule(file, stubs = {}) {
  const filename = path.join(__dirname, '../src', file)
  const source = fs.readFileSync(filename, 'utf8')
  const module = { exports: {} }
  vm.runInNewContext(transpile(source), {
    module,
    exports: module.exports,
    console,
    require(name) {
      if (name in stubs) return stubs[name]
      // 批3：CSV 解析器已提取为共享模块（@/utils/csvImport），测试内按别名解析。
      if (name === '@/utils/csvImport') return loadTsModule('utils/csvImport.ts')
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

function loadPage(apiStub, identityStore, elMessage) {
  const file = path.join(__dirname, '../src/views/fr/org-staff/index.vue')
  const { descriptor } = parse(fs.readFileSync(file, 'utf8'), { filename: file })
  const script = compileScript(descriptor, { id: 'staff-import-page' }).content
  const importModel = loadTsModule('views/fr/org-staff/importModel.ts')
  const delivery = loadTsModule('views/fr/org-staff/staffAccountDelivery.ts')
  const hooks = {}
  const copied = []
  const clipboard = { writeText: async (text) => copied.push(text) }
  const code = transpile(script)
  const routerStub = { replace: async () => {} }
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    console,
    vue,
    navigator: { clipboard },
    localStorage: { setItem() { throw new Error('credentials must not enter localStorage') } },
    sessionStorage: { setItem() { throw new Error('credentials must not enter sessionStorage') } },
    useRouter: () => routerStub,
    useRoute: () => ({ query: {} }),
    Blob: class {},
    URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() {} },
    document: { createElement: () => ({ click() {}, set href(v) {} }) },
    require(name) {
      if (name === 'vue') return {
        ...vue,
        onMounted() {},
        onActivated(callback) { hooks.activated = callback },
        onDeactivated(callback) { hooks.deactivated = callback },
        onBeforeUnmount(callback) { hooks.beforeUnmount = callback }
      }
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
      if (name === 'vue-router')
        return { useRouter: () => routerStub, onBeforeRouteLeave: (callback) => { hooks.leave = callback } }
      if (name === '@/store/modules/platformIdentity') {
        return { usePlatformIdentityStore: () => identityStore }
      }
      if (name === '@/store/modules/permission') {
        return { usePermissionStore: () => ({ generateRoutes: async () => {} }) }
      }
      if (name === '@/utils/platformIdentity') {
        return { isCurrentGeneration: () => true, postGeneration: () => 1 }
      }
      if (name === '@/utils/platformRoles') return { roleLabel: (c) => c }
      if (name === '@/utils') return { generateUUID: () => 'staff-import-test-request' }
      if (name === './importModel') return importModel
      if (name === './staffAccountDelivery') return delivery
      if (name === '@/api/platform/staff') return apiStub
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const setupCtx = { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  const bindings = module.exports.default.setup({}, setupCtx)
  return { bindings, elMessage, api: apiStub, identity: identityStore, hooks, clipboard, copied }
}

function makePage() {
  const elMessage = { warning: [], error: [], success: [] }
  const identityStore = vue.reactive({
    posts: [],
    loading: false,
    error: '',
    currentPost: { postId: 1, orgId: 151, orgName: '门店', orgType: 'store', roleCode: 'boss' },
    can: (p) => p === 'platform:org:query' || p === 'platform:org:manage',
    selectPost: async () => ({}),
    restoreContext: async () => ({}),
    clear() {}
  })
  const api = {
    getTeams: async () => [],
    getPostTemplates: async () => [],
    getStaff: async () => [],
    getHandoverPending: async () => [],
    createTeam: async () => ({}),
    createStaff: async () => ({}),
    assignPost: async () => ({}),
    revokePost: async () => true,
    setStaffStatus: async () => true,
    handoverStaff: async () => ({ items: [] }),
    emergencyRevokeStaff: async () => ({ items: [] }),
    precheckStaffImport: async () => ({
      orgId: 151,
      total: 1,
      created: 0,
      skipped: 0,
      failed: 0,
      rows: []
    }),
    submitStaffImport: async () => ({
      orgId: 151,
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      rows: [
        {
          line: 1,
          name: '张三',
          mobile: '13800000001',
          roleCode: 'sales',
          teamName: '业务一组',
          status: 'CREATED'
        }
      ]
    })
  }
  const page = loadPage(api, identityStore, elMessage)
  return { page, bindings: page.bindings, elMessage, api }
}

async function prepareReady(bindings) {
  bindings.importRows.value = [
    { line: 1, name: '张三', mobile: '13800000001', roleCode: 'sales', teamName: '业务一组' }
  ]
  bindings.importPrecheckResult.value = {
    orgId: 151,
    total: 1,
    created: 0,
    skipped: 0,
    failed: 0,
    rows: []
  }
  await Promise.resolve()
}

test('N-P2-4: submit success with refresh failure shows refresh warning and keeps result', async () => {
  const { page, bindings, elMessage } = makePage()
  page.api.getTeams = () => Promise.reject(new Error('net down'))
  await prepareReady(bindings)
  await bindings.submitImport()

  assert.equal(bindings.importSubmitResult.value.created, 1)
  assert.match(bindings.loadError.value, /加载失败/)
  assert.equal(bindings.importNotice.value, '导入已提交，列表刷新失败，请手动刷新')
  assert.ok(elMessage.warning.some((m) => String(m).includes('刷新失败')))
})

test('N-P2-4: submit failure keeps submit error message and does not set refresh notice', async () => {
  const { page, bindings, elMessage } = makePage()
  page.api.submitStaffImport = () => Promise.reject(new Error('boom'))
  await prepareReady(bindings)
  await bindings.submitImport()

  assert.equal(bindings.importSubmitResult.value, null)
  assert.ok(elMessage.error.some((m) => String(m).includes('提交失败，请稍后重试')))
  assert.doesNotMatch(bindings.importNotice.value, /列表刷新失败/)
  assert.match(bindings.importNotice.value, /不会重新发放初始密码/)
  assert.match(bindings.importNotice.value, /官方微信手机号授权登录/)
})

test('N-P2-2: oversize file rejected without reading; valid file clears old batch', async () => {
  const { page, bindings, elMessage } = makePage()
  const csv = '姓名,手机,角色码,团队名\n张三,13800000001,sales,业务一组\n'
  bindings.importRows.value = [
    { line: 1, name: '旧', mobile: '13800000000', roleCode: 'sales', teamName: '旧组' }
  ]
  bindings.importPrecheckResult.value = {
    orgId: 151,
    total: 9,
    created: 0,
    skipped: 0,
    failed: 0,
    rows: []
  }

  const oversize = { name: 'big.csv', size: 3 * 1024 * 1024, text: async () => csv }
  await bindings.onImportFile({ target: { files: [oversize], value: 'x' } })
  assert.equal(bindings.importRows.value.length, 0)
  assert.equal(bindings.importPrecheckResult.value, null)
  assert.equal(bindings.importSubmitResult.value, null)
  assert.ok(elMessage.error.some((m) => String(m).includes('超过大小上限')))

  const okFile = { name: 'ok.csv', size: 100, text: async () => csv }
  await bindings.onImportFile({ target: { files: [okFile], value: 'y' } })
  assert.equal(bindings.importRows.value.length, 1)
  assert.equal(bindings.importParseErrors.value.length, 0)
})

const accountRow = (line, overrides = {}) => ({
  line, name: `员工${line}`, mobile: `1390000000${line}`, roleCode: 'sales', teamName: '业务一组',
  status: 'CREATED', postId: String(80 + line), userId: String(90 + line), note: null,
  account: {
    accountId: `account-${line}`, name: `员工${line}`, orgName: '本店', loginName: `yuangong${line}`,
    aliases: [`yuangong${line}`, `yg${line}`], initialPassword: `fixture-password-${line}`, reusedUser: false
  }, ...overrides
})
const resultOf = (rows) => ({
  orgId: 151, total: rows.length,
  created: rows.filter((row) => row.status === 'CREATED').length,
  skipped: rows.filter((row) => row.status === 'SKIPPED').length,
  failed: rows.filter((row) => row.status === 'FAILED').length, rows
})
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('AUTH05 real SFC delivers returned aliases and initial password with one-click copy', async () => {
  const { page, bindings } = makePage()
  page.api.submitStaffImport = async () => resultOf([accountRow(1)])
  await prepareReady(bindings)
  await bindings.submitImport()
  assert.equal(bindings.importAccounts.value.length, 1)
  await bindings.copyImportCredential(bindings.importAccounts.value[0])
  assert.equal(page.copied.length, 1)
  assert.match(page.copied[0], /登录账号：yuangong1/)
  assert.match(page.copied[0], /快捷查找：yuangong1、yg1/)
  assert.match(page.copied[0], /初始密码：fixture-password-1/)
  assert.match(page.copied[0], /48小时内登录并改密/)
  assert.match(page.copied[0], /微信登录后初始密码失效/)
})

test('AUTH05 reused accounts show original password instructions without inventing an initial credential', async () => {
  const { page, bindings } = makePage()
  const row = accountRow(1, { status: 'SKIPPED' })
  row.account.initialPassword = null
  row.account.reusedUser = true
  row.account.name = '原姓名'
  page.api.submitStaffImport = async () => resultOf([row])
  await prepareReady(bindings)
  await bindings.submitImport()
  await bindings.copyImportCredential(bindings.importAccounts.value[0])
  assert.match(page.copied[0], /员工独立账号：原姓名/)
  assert.match(page.copied[0], /沿用原账号密码/)
  assert.match(page.copied[0], /官方微信手机号授权登录/)
  assert.doesNotMatch(page.copied[0], /初始密码：/)
})

test('AUTH05 retrying failed rows keeps earlier credentials and only sends failed source rows', async () => {
  const { page, bindings } = makePage()
  const first = accountRow(1)
  const failed = accountRow(2, { status: 'FAILED', code: 400, message: '团队暂不可用', account: null })
  let submitted = 0
  page.api.submitStaffImport = async (payload) => {
    submitted++
    if (submitted === 1) return resultOf([first, failed])
    assert.deepEqual(Array.from(payload.rows, (row) => row.line), [2])
    assert.equal(Object.hasOwn(payload.rows[0], 'account'), false)
    return resultOf([accountRow(2)])
  }
  await prepareReady(bindings)
  await bindings.submitImport()
  const original = bindings.importAccounts.value[0]
  await bindings.retryFailedImport()
  assert.equal(bindings.importAccounts.value[0], original)
  assert.equal(original.initialPassword, 'fixture-password-1')
  await bindings.submitImport()
  assert.equal(bindings.importAccounts.value.length, 2)
  await bindings.copyImportCredential(bindings.importAccounts.value[0])
  assert.match(page.copied[0], /fixture-password-1/)
})

test('AUTH05 multiple roles for one account do not overwrite its only initial credential', async () => {
  const { page, bindings } = makePage()
  const first = accountRow(1)
  const second = accountRow(2, { userId: first.userId, roleCode: 'finance', account: {
    ...first.account, initialPassword: null, reusedUser: true
  } })
  page.api.submitStaffImport = async () => resultOf([first, second])
  await prepareReady(bindings)
  await bindings.submitImport()
  assert.equal(bindings.importAccounts.value.length, 1)
  assert.equal(bindings.importAccounts.value[0].initialPassword, 'fixture-password-1')
})

test('AUTH05 clipboard failure exposes manual copy but a late failure cannot restore closed credentials', async () => {
  const { page, bindings } = makePage()
  page.api.submitStaffImport = async () => resultOf([accountRow(1)])
  await prepareReady(bindings)
  await bindings.submitImport()
  page.clipboard.writeText = async () => { throw new Error('denied') }
  await bindings.copyImportCredential(bindings.importAccounts.value[0])
  assert.equal(bindings.importManualCopyVisible.value, true)
  assert.match(bindings.importCredentialCopyText.value, /fixture-password-1/)
  const late = deferred()
  page.clipboard.writeText = () => late.promise
  const copying = bindings.copyImportCredential(bindings.importAccounts.value[0])
  bindings.resetImport()
  late.reject(new Error('late denied'))
  await copying
  assert.equal(bindings.importManualCopyVisible.value, false)
  assert.equal(bindings.importCredentialCopyText.value, '')
})

for (const boundary of ['close', 'route', 'deactivate', 'unmount', 'identity', 'file']) {
  test(`AUTH05 ${boundary} clears passwords from delivery and response objects`, async () => {
    const { page, bindings } = makePage()
    page.api.submitStaffImport = async () => resultOf([accountRow(1)])
    await prepareReady(bindings)
    await bindings.submitImport()
    const credential = bindings.importAccounts.value[0]
    const response = bindings.importSubmitResult.value.rows[0].account
    await bindings.copyImportCredential(credential)
    if (boundary === 'close') bindings.resetImport()
    if (boundary === 'route') assert.equal(page.hooks.leave(), true)
    if (boundary === 'deactivate') page.hooks.deactivated()
    if (boundary === 'unmount') page.hooks.beforeUnmount()
    if (boundary === 'identity') bindings.resetTransientState()
    if (boundary === 'file') await bindings.onImportFile({ target: { files: [{
      name: 'next.csv', size: 100, text: async () => '姓名,手机,角色码,团队名\n新员工,13900000009,sales,业务一组\n'
    }], value: 'new-file' } })
    assert.equal(credential.initialPassword, null)
    assert.equal(response.initialPassword, null)
    assert.equal(bindings.importAccounts.value.length, 0)
    assert.equal(bindings.importCredentialCopyText.value, '')
  })
}

test('AUTH05 submission is mutually exclusive and normal navigation waits for credential delivery', async () => {
  const { page, bindings } = makePage()
  const waiting = deferred()
  let calls = 0
  page.api.submitStaffImport = () => { calls++; return waiting.promise }
  await prepareReady(bindings)
  const active = bindings.submitImport()
  await bindings.submitImport()
  assert.equal(calls, 1)
  assert.equal(page.hooks.leave(), false)
  waiting.resolve(resultOf([accountRow(1)]))
  await active
  assert.equal(bindings.importAccounts.value.length, 1)
  assert.equal(page.hooks.leave(), true)
  assert.equal(bindings.importAccounts.value.length, 0)
})

test('AUTH05 forced identity change or unmount drops late import credentials and success notification', async () => {
  for (const clear of ['identity', 'unmount']) {
    const { page, bindings, elMessage } = makePage()
    const waiting = deferred()
    page.api.submitStaffImport = () => waiting.promise
    await prepareReady(bindings)
    const active = bindings.submitImport()
    if (clear === 'identity') bindings.resetTransientState()
    else page.hooks.beforeUnmount()
    waiting.resolve(resultOf([accountRow(1)]))
    await active
    assert.equal(bindings.importAccounts.value.length, 0)
    assert.equal(bindings.importSubmitResult.value, null)
    assert.equal(elMessage.success.length, 0)
  }
})

test('AUTH05 store custom manager is blocked but non-store enrollment keeps its original path', async () => {
  const { page, bindings } = makePage()
  let calls = 0
  page.api.submitStaffImport = async () => { calls++; return resultOf([accountRow(1, { account: null })]) }
  page.identity.currentPost.roleCode = 'custom-manager'
  await prepareReady(bindings)
  assert.equal(bindings.canImportStaff.value, false)
  await bindings.submitImport()
  assert.equal(calls, 0)
  page.identity.currentPost.orgType = 'factory'
  assert.equal(bindings.canImportStaff.value, true)
  await bindings.submitImport()
  assert.equal(calls, 1)
  assert.equal(bindings.importAccounts.value.length, 0)
})

test('AUTH05 real import dialog template renders delivered login data, recovery and reused-account guidance', async () => {
  const { page, bindings } = makePage()
  const reused = accountRow(2, { status: 'SKIPPED' })
  reused.account = { ...reused.account, initialPassword: null, reusedUser: true }
  page.api.submitStaffImport = async () => resultOf([accountRow(1), reused])
  await prepareReady(bindings)
  await bindings.submitImport()
  bindings.importDialog.value = true

  const file = path.join(__dirname, '../src/views/fr/org-staff/index.vue')
  const template = parse(fs.readFileSync(file, 'utf8')).descriptor.template.content
  const marker = template.indexOf('v-model="importDialog"')
  const start = template.lastIndexOf('<el-dialog', marker)
  const end = template.indexOf('</el-dialog>', marker) + '</el-dialog>'.length
  const compiled = compileTemplate({ source: template.slice(start, end), id: 'auth05-import-dialog',
    compilerOptions: { mode: 'function', prefixIdentifiers: true }
  })
  assert.deepEqual(compiled.errors, [])
  const render = new Function('Vue', compiled.code)(vue)
  // The extracted template uses _ctx; expose setup values without compileScript's private marker.
  const app = vue.createSSRApp({ setup: () => ({ ...bindings }), render })
  const slot = (name) => ({ setup: (_, { slots }) => () => vue.h(name, [slots.default?.(), slots.footer?.()]) })
  for (const name of ['ElDialog', 'ElButton', 'ElTag']) app.component(name, slot('section'))
  app.component('ElInput', { props: ['modelValue'], setup: (p) => () => vue.h('textarea', p.modelValue) })
  app.component('ElAlert', { props: ['title'], setup: (p) => () => vue.h('p', p.title) })
  app.component('ElTable', { props: ['data'], setup(p, { slots }) {
    vue.provide('import-test-rows', () => p.data)
    return () => vue.h('div', slots.default?.())
  } })
  app.component('ElTableColumn', { props: ['prop', 'label'], setup(p, { slots }) {
    const rows = vue.inject('import-test-rows')
    return () => vue.h('div', [p.label, ...rows().map((row) =>
      vue.h('span', slots.default ? slots.default({ row }) : String(row[p.prop] ?? '')))])
  } })
  const html = await renderToString(app)
  assert.match(html, /员工账号交付（含本批此前成功行）/)
  assert.match(html, /yuangong1/)
  assert.match(html, /fixture-password-1/)
  assert.doesNotMatch(html, /fixture-password-2/)
  assert.match(html, /沿用原密码；也可官方微信手机号授权登录/)
  assert.match(html, /48小时内登录并改密/)
  assert.match(html, /重复导入不会重新发放密码/)
  assert.match(html, /复制账号/)
})
