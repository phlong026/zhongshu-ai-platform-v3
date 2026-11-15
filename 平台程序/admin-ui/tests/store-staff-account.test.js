const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
const source = (file) => fs.readFileSync(path.join(root, 'src', file), 'utf8')

function loadTs(file, imports = {}) {
  const code = ts.transpileModule(source(file), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    require(name) {
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}

function loadView({ orgType = 'store', createAccount, createLegacy }) {
  const filename = path.join(root, 'src/views/fr/org-staff/index.vue')
  const descriptor = parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'store-staff-account' }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const successMessages = []
  const hooks = {}
  const identity = {
    currentPost: {
      postId: 'post-1',
      orgId: 'org-1',
      orgName: '测试门店',
      orgType,
      roleCode: orgType === 'store' ? 'boss' : 'hq-admin'
    },
    can: () => true,
    posts: [],
    loading: false,
    context: {},
    selectPost: async () => null,
    restoreContext: async () => null
  }
  const api = {
    getTeams: async () => [],
    getPostTemplates: async () => [],
    getStaff: async () => [],
    getHandoverPending: async () => [],
    createStaffAccount: createAccount,
    createStaff: createLegacy
  }
  const delivery = loadTs('views/fr/org-staff/staffAccountDelivery.ts', {
    '@/api/platform/staff': {}
  })
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    defineOptions() {},
    computed: vue.computed,
    useRouter: () => ({ replace: async () => {} }),
    require(name) {
      const imports = {
        vue: {
          ...vue,
          onMounted() {},
          onActivated(callback) {
            hooks.activated = callback
          },
          onDeactivated(callback) {
            hooks.deactivated = callback
          },
          onBeforeUnmount(callback) {
            hooks.beforeUnmount = callback
          }
        },
        'vue-router': {
          onBeforeRouteLeave(callback) {
            hooks.beforeRouteLeave = callback
          }
        },
        'element-plus': {
          ElMessage: {
            warning() {},
            error() {},
            success(message) {
              successMessages.push(message)
            }
          },
          ElMessageBox: { confirm: async () => true }
        },
        '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
        '@/store/modules/permission': {
          usePermissionStore: () => ({ generateRoutes: async () => null })
        },
        '@/api/platform/staff': api,
        '@/utils': { generateUUID: () => 'fixture-idempotency-key' },
        '@/utils/platformIdentity': {
          isCurrentGeneration: () => true,
          postGeneration: () => 1
        },
        '@/utils/platformRoles': { roleLabel: (roleCode) => roleCode },
        './staffAccountDelivery': delivery,
        './importModel': {
          STAFF_IMPORT_MAX_FILE_BYTES: 2 * 1024 * 1024,
          STAFF_IMPORT_MAX_ROWS: 500,
          buildStaffImportTemplateCsv: () => '',
          createImportGenerationGuard: () => ({ next: () => 1, isCurrent: () => true }),
          failedRowsForRetry: () => [],
          parseStaffImportCsv: () => ({ rows: [], errors: [] }),
          runGuardedImportRequest: async () => undefined,
          withinFileSizeLimit: () => true,
          withinImportLimit: () => true
        }
      }
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return {
    state: module.exports.default.setup({}, { expose() {} }),
    successMessages,
    hooks
  }
}

const createdAccount = {
  postId: 'post-sales',
  userId: 'user-new',
  name: '王小美',
  mobile: '13800000003',
  active: true,
  roleCode: 'sales',
  teamName: '销售部',
  createTime: '2026-11-15T00:00:00Z',
  accountId: 'account-new',
  orgName: '合家美宅-江西南昌-红谷滩-李忠',
  loginName: 'hjmz-jxnc-honggutan-wangxiaomei',
  aliases: ['jxnc-honggutan-wangxiaomei', 'wangxiaomei'],
  initialPassword: 'FIXTURE-ONLY-********',
  reusedUser: false
}

test('门店录入员工走独立账号端点，总部保留旧员工端点', async () => {
  const calls = []
  const api = loadTs('api/platform/staff.ts', {
    '@/config/axios': {
      __esModule: true,
      default: {
        post: async (config) => {
          calls.push(config)
          return config
        }
      }
    },
    '@/utils/platformIdentity': {}
  })
  const payload = {
    orgId: 'org-1',
    name: '王小美',
    mobile: '13800000003',
    roleCode: 'sales'
  }
  await api.createStaffAccount(payload)
  await api.createStaff(payload)
  assert.equal(calls[0].url, '/platform/org/staff-accounts')
  assert.equal(calls[1].url, '/platform/org/staff')
})

test('门店boss开通后只在内存显示凭据，换店清理且前端过滤boss和owner岗位', async () => {
  let accountCalls = 0
  let legacyCalls = 0
  const { state, hooks } = loadView({
    createAccount: async () => {
      accountCalls += 1
      return { ...createdAccount }
    },
    createLegacy: async () => {
      legacyCalls += 1
      return createdAccount
    }
  })
  state.postTemplates.value = [
    { roleCode: 'boss', line: 'management', lead: true },
    { roleCode: 'owner', line: 'management', lead: true },
    { roleCode: 'sales', line: 'sales', lead: false }
  ]
  assert.deepEqual(
    Array.from(state.roleOptions.value, (item) => item.value),
    ['sales']
  )
  Object.assign(state.staffForm, {
    name: '王小美',
    mobile: '13800000003',
    roleCode: 'sales'
  })
  await state.saveStaff()
  assert.equal(accountCalls, 1)
  assert.equal(legacyCalls, 0)
  assert.equal(state.staffAccountDialog.value, true)
  assert.equal(state.staffAccountResult.value.loginName, createdAccount.loginName)
  const result = state.staffAccountResult.value
  hooks.deactivated()
  assert.equal(result.initialPassword, null)
  assert.equal(state.staffAccountResult.value, null)

  await state.saveStaff()
  const routeResult = state.staffAccountResult.value
  hooks.beforeRouteLeave()
  assert.equal(routeResult.initialPassword, null)
  assert.equal(state.staffAccountResult.value, null)

  await state.saveStaff()
  const switchedResult = state.staffAccountResult.value
  state.resetTransientState()
  assert.equal(switchedResult.initialPassword, null)
  assert.equal(state.staffAccountResult.value, null)
  assert.equal(accountCalls, 3)
})

test('总部仍使用旧staff接口，真实API失败不显示开通完成', async () => {
  let accountCalls = 0
  let legacyCalls = 0
  const headquarters = loadView({
    orgType: 'headquarters',
    createAccount: async () => {
      accountCalls += 1
      return createdAccount
    },
    createLegacy: async () => {
      legacyCalls += 1
      return createdAccount
    }
  })
  Object.assign(headquarters.state.staffForm, {
    name: '总部员工',
    mobile: '13800000004',
    roleCode: 'hq-sales'
  })
  await headquarters.state.saveStaff()
  assert.equal(accountCalls, 0)
  assert.equal(legacyCalls, 1)
  assert.equal(headquarters.state.staffAccountResult.value, null)

  const failed = loadView({
    createAccount: async () => {
      throw new Error('fixture rejected')
    },
    createLegacy: async () => createdAccount
  })
  Object.assign(failed.state.staffForm, {
    name: '失败员工',
    mobile: '13800000005',
    roleCode: 'sales'
  })
  await failed.state.saveStaff()
  assert.equal(failed.state.staffAccountResult.value, null)
  assert.equal(failed.state.staffAccountDialog.value, false)
  assert.equal(failed.successMessages.length, 0)
  assert.match(failed.state.actionError.value, /服务器未确认/)
})

test('真实SFC员工账号提交pending时阻止离页，交付后离页再清密', async () => {
  let resolveCreate
  const deferred = new Promise((resolve) => {
    resolveCreate = resolve
  })
  const { state, hooks } = loadView({
    createAccount: () => deferred,
    createLegacy: async () => createdAccount
  })
  Object.assign(state.staffForm, {
    name: '王小美',
    mobile: '13800000003',
    roleCode: 'sales'
  })

  const saving = state.saveStaff()
  await Promise.resolve()
  assert.equal(state.saving.value, true)
  assert.equal(hooks.beforeRouteLeave(), false)
  assert.equal(state.staffAccountResult.value, null)

  resolveCreate({ ...createdAccount })
  await saving
  assert.equal(state.staffAccountResult.value.initialPassword, 'FIXTURE-ONLY-********')
  const delivered = state.staffAccountResult.value
  assert.equal(hooks.beforeRouteLeave(), true)
  assert.equal(delivered.initialPassword, null)
  assert.equal(state.staffAccountResult.value, null)
})

test('KeepAlive异常停用期间收到成功响应保留交付，激活后展示', async () => {
  let resolveCreate
  const deferred = new Promise((resolve) => {
    resolveCreate = resolve
  })
  const { state, hooks } = loadView({
    createAccount: () => deferred,
    createLegacy: async () => createdAccount
  })
  Object.assign(state.staffForm, {
    name: '王小美',
    mobile: '13800000003',
    roleCode: 'sales'
  })

  const saving = state.saveStaff()
  await Promise.resolve()
  hooks.deactivated()
  resolveCreate({ ...createdAccount })
  await saving
  assert.equal(state.staffAccountResult.value.initialPassword, 'FIXTURE-ONLY-********')
  assert.equal(state.staffAccountDialog.value, false)
  hooks.activated()
  assert.equal(state.staffAccountDialog.value, true)
})

test('员工账号复制文案区分新用户与复用用户', () => {
  const { buildStaffCredentialCopyText } = loadTs('views/fr/org-staff/staffAccountDelivery.ts', {
    '@/api/platform/staff': {}
  })
  const created = buildStaffCredentialCopyText(createdAccount)
  assert.match(created, /员工独立账号/)
  assert.match(created, /FIXTURE-ONLY/)
  const reused = buildStaffCredentialCopyText({
    ...createdAccount,
    reusedUser: true,
    initialPassword: null
  })
  assert.match(reused, /沿用原账号密码/)
  assert.doesNotMatch(reused, /初始密码：/)
})
