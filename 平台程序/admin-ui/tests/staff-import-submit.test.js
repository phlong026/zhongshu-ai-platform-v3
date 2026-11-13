const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')

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
  const code = transpile(script)
  const routerStub = { replace: async () => {} }
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    console,
    vue,
    useRouter: () => routerStub,
    useRoute: () => ({ query: {} }),
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
      if (name === 'vue-router') return { useRouter: () => routerStub }
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
      if (name === './importModel') return importModel
      if (name === '@/api/platform/staff') return apiStub
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const setupCtx = { expose: () => {}, slots: {}, attrs: {}, emit: () => {} }
  const bindings = module.exports.default.setup({}, setupCtx)
  console.log('LOADER bindings keys:', Object.keys(bindings).length)
  return { bindings, elMessage, api: apiStub, identity: identityStore }
}

function makePage() {
  const elMessage = { warning: [], error: [], success: [] }
  const identityStore = {
    posts: [],
    loading: false,
    error: '',
    currentPost: { postId: 1, orgId: 151, orgName: '门店', roleCode: 'boss' },
    can: (p) => p === 'platform:org:query' || p === 'platform:org:manage',
    selectPost: async () => ({}),
    restoreContext: async () => ({}),
    clear() {}
  }
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
  console.log(
    'DEBUG keys:',
    Object.keys(bindings || {}).length,
    Object.keys(bindings || {}).slice(0, 5)
  )
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
  assert.equal(bindings.importNotice.value, '')
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
