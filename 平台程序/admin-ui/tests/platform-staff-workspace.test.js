const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const pinia = require('pinia')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const source = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')

function loadSession(storage, persistentStorage = storage) {
  const code = ts.transpileModule(source('utils/platformIdentity.ts'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    sessionStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: (key) => storage.delete(key)
    },
    localStorage: {
      getItem: (key) => persistentStorage.get(key) ?? null,
      setItem: (key, value) => persistentStorage.set(key, value),
      removeItem: (key) => persistentStorage.delete(key)
    }
  })
  return module.exports
}

function loadModule(file, imports) {
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

function loadOrgStaffView(overrides = {}) {
  const filename = path.join(__dirname, '../src/views/fr/org-staff/index.vue')
  const descriptor = parse(source('views/fr/org-staff/index.vue'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'platform-org-staff' }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const identity = {
    currentPost: { postId: '1', orgId: '1001', orgName: '测试门店', orgType: 'store' },
    can: () => true,
    posts: [],
    loading: false,
    context: {},
    selectPost: async () => null,
    restoreContext: async () => null,
    ...overrides.identity
  }
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
          onActivated() {},
          onDeactivated() {},
          onBeforeUnmount() {}
        },
        'vue-router': { onBeforeRouteLeave() {} },
        'element-plus': {
          ElMessage: { warning() {}, success() {} },
          ElMessageBox: { confirm: async () => true }
        },
        '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
        '@/store/modules/permission': {
          usePermissionStore: () => ({
            generateRoutes: async () => null,
            ...overrides.permissions
          })
        },
        '@/api/platform/staff': {
          getTeams: async () => [],
          getPostTemplates: async () => [],
          getStaff: async () => [],
          getPendingHandovers: async () => [],
          ...overrides.api
        },
        '@/utils': { generateUUID: () => require('node:crypto').randomUUID() },
        '@/utils/platformIdentity': {
          isCurrentGeneration: () => true,
          postGeneration: () => 1
        },
        '@/utils/platformRoles': { roleLabel: (roleCode) => roleCode },
        './staffAccountDelivery': {
          buildStaffCredentialCopyText: () => '',
          isRestrictedOwnerRole: (roleCode) => ['boss', 'owner'].includes(roleCode)
        },
        // 批2 起 org-staff 页引入导入纯函数；本测试只关心权限/菜单，给出最小桩。
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
  return module.exports.default.setup({}, { expose() {} })
}

function loadPlatformLoginView({ login, loadPosts, token = '', mode = 'admin' }) {
  const filename = path.join(__dirname, '../src/views/fr/platform-login/index.vue')
  const descriptor = parse(source('views/fr/platform-login/index.vue'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'platform-login-recovery' }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  let accessToken = token
  let portal = mode
  const identity = {
    posts: [],
    error: '',
    clear() {
      this.posts = []
      this.error = ''
    },
    async loadPosts() {
      try {
        const rows = await loadPosts()
        this.posts = rows
        this.error = ''
        return rows
      } catch (error) {
        this.error = '工作身份加载失败，请检查网络后重试。'
        throw error
      }
    },
    async selectPost() {
      return null
    }
  }
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    defineOptions() {},
    computed: vue.computed,
    useRouter: () => ({ replace: async () => {} }),
    require(name) {
      const imports = {
        vue: { ...vue, onBeforeUnmount() {} },
        'element-plus': { ElMessage: { warning() {}, success() {} } },
        '@/api/platform/staff': { sendStaffSms: async () => {}, staffSmsLogin: login },
        '@/utils/auth': {
          getAccessToken: () => accessToken,
          setToken: (value) => {
            accessToken = value.accessToken
          }
        },
        '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
        '@/store/modules/permission': {
          usePermissionStore: () => ({ $reset() {}, generateRoutes: async () => {} })
        },
        '@/store/modules/user': { useUserStore: () => ({ resetState() {} }) },
        '@/hooks/web/useCache': { deleteUserCache() {} },
        '@/utils/platformIdentity': {
          portalMode: () => portal,
          setPortalMode: (value) => {
            portal = value
          },
          resolveStaffLandingPath: () => '/fr/identity'
        },
        '@/utils/platformRoles': { roleLabel: (role) => role }
      }
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return {
    state: module.exports.default.setup({}, { expose() {} }),
    identity,
    clearToken: () => (accessToken = '')
  }
}

test('岗位会话只保存当前岗位权限，切岗递增代次并清空旧上下文', () => {
  const storage = new Map()
  const identity = loadSession(storage)
  identity.savePosts([
    { postId: 11, orgId: 1 },
    { postId: 22, orgId: 2 }
  ])
  identity.setPortalMode('staff', 'token-a')
  assert.equal(identity.portalMode('token-a'), 'staff')
  const firstGeneration = identity.beginPostSwitch(11)
  identity.saveCurrentContext(firstGeneration, {
    post: { postId: 11, orgId: 1 },
    permissions: ['platform:org:query']
  })
  assert.equal(identity.currentPostId(), '11')
  assert.equal(identity.hasCurrentPostPermission('platform:org:query'), true)

  const nextGeneration = identity.beginPostSwitch(22)
  assert.ok(nextGeneration > firstGeneration)
  assert.equal(identity.currentContext(), null)
  assert.equal(
    identity.saveCurrentContext(firstGeneration, {
      post: { postId: 11, orgId: 1 },
      permissions: ['platform:org:manage']
    }),
    false
  )
  assert.equal(identity.hasCurrentPostPermission('platform:org:manage'), false)
})

test('员工门户模式跨新标签页保留，但换账号令牌不能继承', () => {
  const persistentStorage = new Map()
  const firstTab = loadSession(new Map(), persistentStorage)
  firstTab.setPortalMode('staff', 'staff-token')

  const newTab = loadSession(new Map(), persistentStorage)
  assert.equal(newTab.portalMode('staff-token'), 'staff')
  assert.equal(newTab.portalMode('another-account-token'), 'admin')
  assert.equal(newTab.portalMode('staff-token'), 'admin')
})

test('新标签页有多个任职时要求重新选岗，不静默选第一个', async () => {
  const identity = loadSession(new Map(), new Map())
  const activePinia = pinia.createPinia()
  pinia.setActivePinia(activePinia)
  const loaded = loadModule('store/modules/platformIdentity.ts', {
    pinia,
    '@/store': { store: activePinia },
    '@/api/platform/staff': {
      getMyPosts: async () => [
        { postId: '9007199254740992', orgId: '1001' },
        { postId: '9007199254740993', orgId: '1002' }
      ],
      getCurrentPostContext: async () => {
        throw new Error('不应在未选岗时请求上下文')
      }
    },
    '@/utils/auth': { getAccessToken: () => 'staff-token' },
    '@/utils/platformIdentity': identity
  })
  const store = loaded.usePlatformIdentityStore()

  assert.equal(await store.restoreContext(), null)
  assert.equal(store.posts.length, 2)
  assert.equal(identity.currentPostId(), '')
})

test('相邻超大岗位ID按字符串精确切换，非本人岗位被拒绝', async () => {
  const storage = new Map()
  const identity = loadSession(storage)
  const first = '9007199254740992'
  const second = '9007199254740993'
  identity.savePosts([
    { postId: first, orgId: '1001' },
    { postId: second, orgId: '1002' }
  ])
  const activePinia = pinia.createPinia()
  pinia.setActivePinia(activePinia)
  const loaded = loadModule('store/modules/platformIdentity.ts', {
    pinia,
    '@/store': { store: activePinia },
    '@/api/platform/staff': {
      getMyPosts: async () => identity.posts(),
      getCurrentPostContext: async () => ({
        post: { postId: identity.currentPostId(), orgId: '1001' },
        permissions: []
      })
    },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/utils/platformIdentity': identity
  })
  const store = loaded.usePlatformIdentityStore()

  await store.selectPost(first, false)
  assert.equal(identity.currentPostId(), first)
  await store.selectPost(second, false)
  assert.equal(identity.currentPostId(), second)
  await assert.rejects(() => store.selectPost('9007199254740994', false), /已失效/)
  assert.equal(identity.currentPostId(), second)
})

test('刷新不把缓存权限当已验证身份，撤岗后清旧上下文并要求重新选岗', async () => {
  const identity = loadSession(new Map())
  const oldPost = { postId: '11', orgId: '1001' }
  identity.savePosts([oldPost])
  const generation = identity.beginPostSwitch('11')
  identity.saveCurrentContext(generation, {
    post: oldPost,
    permissions: ['platform:org:query', 'platform:org:manage']
  })
  let rows = [
    { postId: '22', orgId: '1002' },
    { postId: '33', orgId: '1003' }
  ]
  let contextReads = 0
  const activePinia = pinia.createPinia()
  pinia.setActivePinia(activePinia)
  const loaded = loadModule('store/modules/platformIdentity.ts', {
    pinia,
    '@/store': { store: activePinia },
    '@/api/platform/staff': {
      getMyPosts: async () => rows,
      getCurrentPostContext: async () => {
        contextReads++
        return {
          post: rows.find((row) => row.postId === identity.currentPostId()),
          permissions: ['platform:org:query']
        }
      }
    },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/utils/platformIdentity': identity
  })
  const store = loaded.usePlatformIdentityStore()
  assert.equal(store.context, null, '缓存只能帮助显示候选身份，不能恢复未经服务端复验的权限')
  assert.equal(store.hasCurrentPostPermission('platform:org:manage'), false)
  assert.equal(await store.restoreContext(), null)
  assert.equal(identity.currentPostId(), '')
  assert.equal(identity.currentContext(), null)
  assert.equal(contextReads, 0)

  await store.selectPost('22', false)
  assert.equal(store.currentPost.postId, '22')
  rows = [
    { postId: '33', orgId: '1003' },
    { postId: '44', orgId: '1004' }
  ]
  assert.equal(await store.restoreContext(), null)
  assert.equal(store.context, null, '会话内撤岗也不能保留旧岗位与权限')
  assert.equal(store.hasCurrentPostPermission('platform:org:query'), false)
  assert.equal(identity.currentPostId(), '')
})

test('员工有候选岗位但尚未通过身份复验时，业务路由转到工作身份页', async () => {
  let guard
  const identity = { posts: [{ postId: '22' }, { postId: '33' }], context: null, error: '' }
  loadModule('permission.ts', {
    './router': {
      __esModule: true,
      default: {
        beforeEach: (fn) => {
          guard = fn
        },
        afterEach() {}
      }
    },
    '@/config/axios/service': { isRelogin: { show: false } },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/hooks/web/useTitle': { useTitle() {} },
    '@/hooks/web/useNProgress': { useNProgress: () => ({ start() {}, done() {} }) },
    '@/hooks/web/usePageLoading': { usePageLoading: () => ({ loadStart() {}, loadDone() {} }) },
    '@/store/modules/dict': {},
    '@/store/modules/user': {},
    '@/store/modules/permission': {
      usePermissionStoreWithOut: () => ({ getRouters: [{}], generateRoutes: async () => {} })
    },
    '@/utils/routeParams': {},
    '@/utils/platformIdentity': { portalMode: () => 'staff' },
    '@/store/modules/platformIdentity': { usePlatformIdentityStoreWithOut: () => identity }
  })
  assert.equal((await guard({ path: '/fr/org/staff' }, {})).path, '/fr/identity')
  assert.equal(await guard({ path: '/fr/identity' }, {}), true, '选岗页本身不能循环跳转')
})

test('岗位列表网络失败保留已知身份并暴露可重试错误，不伪装成无任职', async () => {
  const storage = new Map()
  const identity = loadSession(storage)
  identity.savePosts([{ postId: '9007199254740993', orgId: '1001' }])
  const activePinia = pinia.createPinia()
  pinia.setActivePinia(activePinia)
  const loaded = loadModule('store/modules/platformIdentity.ts', {
    pinia,
    '@/store': { store: activePinia },
    '@/api/platform/staff': {
      getMyPosts: async () => {
        throw new Error('network down')
      },
      getCurrentPostContext: async () => null
    },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/utils/platformIdentity': identity
  })
  const store = loaded.usePlatformIdentityStore()

  await assert.rejects(() => store.restoreContext(), /network down/)
  assert.equal(store.posts.length, 1)
  assert.match(store.error, /加载失败/)
})

test('切岗上下文加载失败时恢复旧岗位与权限，不提前清空页面数据', async () => {
  const storage = new Map()
  const identitySession = loadSession(storage)
  const oldPost = { postId: '11', orgId: '1001', orgName: '旧组织', roleCode: 'boss' }
  const nextPost = { postId: '22', orgId: '1002', orgName: '新组织', roleCode: 'boss' }
  identitySession.savePosts([oldPost, nextPost])
  const oldGeneration = identitySession.beginPostSwitch(oldPost.postId)
  const oldContext = { post: oldPost, permissions: ['platform:org:query'] }
  identitySession.saveCurrentContext(oldGeneration, oldContext)
  const activePinia = pinia.createPinia()
  pinia.setActivePinia(activePinia)
  const loaded = loadModule('store/modules/platformIdentity.ts', {
    pinia,
    '@/store': { store: activePinia },
    '@/api/platform/staff': {
      getMyPosts: async () => [oldPost, nextPost],
      getCurrentPostContext: async () => {
        throw new Error('context timeout')
      }
    },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/utils/platformIdentity': identitySession
  })
  const store = loaded.usePlatformIdentityStore()
  store.context = oldContext

  await assert.rejects(() => store.selectPost(nextPost.postId, false), /context timeout/)
  assert.equal(String(store.currentPost.postId), oldPost.postId)
  assert.equal(identitySession.currentPostId(), oldPost.postId)
  assert.equal(identitySession.hasCurrentPostPermission('platform:org:query'), true)
  assert.match(store.error, /切换失败/)
})

test('组织人员页切岗失败保留旧列表并显示可重试错误', async () => {
  const oldPost = { postId: '11', orgId: '1001', orgName: '旧组织', roleCode: 'boss' }
  const state = loadOrgStaffView({
    identity: {
      currentPost: oldPost,
      selectPost: async () => {
        throw new Error('context timeout')
      }
    }
  })
  state.teams.value = [{ deptId: '1', name: '原部门' }]
  state.staff.value = [{ userId: '1', name: '原员工' }]

  await state.switchPost('22')
  assert.equal(state.teams.value.length, 1)
  assert.equal(state.staff.value.length, 1)
  assert.match(state.switchError.value, /切换失败.*重试/)
})

test('短信登录后岗位加载失败保留会话，重试只加载岗位不重复消费验证码', async () => {
  let loginCalls = 0
  let postCalls = 0
  let failPosts = true
  const view = loadPlatformLoginView({
    login: async () => {
      loginCalls++
      return { accessToken: 'staff-token' }
    },
    loadPosts: async () => {
      postCalls++
      if (failPosts) throw new Error('posts unavailable')
      return [
        { postId: '11', orgId: '1001', roleCode: 'boss' },
        { postId: '22', orgId: '1002', roleCode: 'finance' }
      ]
    }
  })
  view.state.form.mobile = '13800138000'
  view.state.form.captcha = '123456'
  view.state.formRef.value = { validate: async () => true }

  await view.state.login()
  assert.equal(view.state.authenticated.value, true)
  assert.match(view.identity.error, /加载失败/)
  assert.equal(loginCalls, 1)
  failPosts = false
  await view.state.retryPosts()
  assert.equal(view.identity.posts.length, 2)
  assert.equal(loginCalls, 1, '岗位重试不得再次提交已消费的验证码')
  assert.equal(postCalls, 2)
})

test('岗位重试收到401清理令牌后回到短信登录态', async () => {
  let view
  view = loadPlatformLoginView({
    token: 'staff-token',
    mode: 'staff',
    login: async () => ({ accessToken: 'staff-token' }),
    loadPosts: async () => {
      view.clearToken()
      throw new Error('认证失败')
    }
  })

  await view.state.retryPosts()
  assert.equal(view.state.authenticated.value, false)
})

test('超大用户ID在岗位指派请求体中原样传递', async () => {
  const calls = []
  const api = loadModule('api/platform/staff.ts', {
    '@/config/axios': {
      __esModule: true,
      default: {
        post: async (config) => {
          calls.push(config)
          return config
        }
      }
    }
  })
  const userId = '9007199254740993'
  await api.assignPost({ orgId: '8000000000000001', userId, roleCode: 'sales' })
  assert.equal(calls[0].data.userId, userId)
  assert.equal(typeof calls[0].data.userId, 'string')
})

test('停职恢复按原手机号复用账号，不要求老板猜用户ID', async () => {
  const calls = []
  const api = loadModule('api/platform/staff.ts', {
    '@/config/axios': {
      __esModule: true,
      default: {
        post: async (config) => {
          calls.push(config)
          return config
        }
      }
    }
  })
  await api.createStaff({
    orgId: '8000000000000001',
    name: '原员工',
    mobile: '13800138000',
    roleCode: 'sales'
  })
  assert.equal(calls[0].url, '/platform/org/staff')
  assert.equal(calls[0].data.mobile, '13800138000')

  const view = source('views/fr/org-staff/index.vue')
  assert.match(view, /原手机号.*复用已有账号/)
  assert.match(view, /const openAssign = \(row: StaffRow\)/)
  assert.doesNotMatch(view, /@click="openAssign\(\)"/)
})

test('岗位模板即使boss排第一，录入和追加也不自动授予老板岗位', () => {
  const state = loadOrgStaffView()
  state.postTemplates.value = [
    { roleCode: 'boss', line: 'management', lead: true },
    { roleCode: 'sales', line: 'sales', lead: false }
  ]

  state.openStaff()
  assert.equal(state.staffForm.roleCode, '')
  state.openAssign({ userId: '9007199254740993', teamId: null })
  assert.equal(state.assignForm.roleCode, '')
})

test('三个员工门户页面复用完整13岗位中文标签', () => {
  const roles = loadModule('utils/platformRoles.ts', {})
  assert.equal(roles.roleLabel('hq-designer'), '设计师')
  assert.equal(roles.roleLabel('hq-design-lead'), '设计负责人')
  assert.equal(roles.roleLabel('hq-operations'), '运营专员')
  assert.equal(roles.roleLabel('hq-operations-lead'), '运营负责人')
  assert.equal(roles.roleLabel('future-role'), 'future-role')

  for (const page of [
    'views/fr/platform-login/index.vue',
    'views/fr/identity/index.vue',
    'views/fr/org-staff/index.vue'
  ]) {
    assert.match(source(page), /import \{ roleLabel \} from '@\/utils\/platformRoles'/)
  }
})

test('请求层携带当前 X-Post-Id 并用岗位代次拒绝迟到响应', () => {
  const transport = source('config/axios/service.ts')
  assert.match(transport, /X-Post-Id/)
  assert.match(transport, /platformGeneration/)
  assert.match(transport, /isCurrentGeneration/)
})

test('平台开通菜单保留，组织人员页只使用当前岗位权限', () => {
  const navigation = source('utils/projectNavigation.ts')
  const permissionStore = source('store/modules/permission.ts')
  assert.match(navigation, /\/platform-org\/onboard/)
  assert.match(permissionStore, /hasCurrentPostPermission/)
  assert.doesNotMatch(permissionStore, /checkPermi\(\['platform:org:/)
})

test('组织人员页面覆盖现有真实接口并明确交接结果', () => {
  const api = source('api/platform/staff.ts')
  const view = source('views/fr/org-staff/index.vue')
  for (const endpoint of [
    '/platform/posts/mine',
    '/platform/posts/current-context',
    '/platform/org',
    '/teams',
    '/post-templates',
    '/staff',
    '/posts',
    '/staff-status',
    '/staff-handover',
    '/staff-emergency-revoke',
    '/handover-pending'
  ])
    assert.match(api, new RegExp(endpoint.replaceAll('/', '\\/')))
  for (const state of [
    'HANDED_OVER',
    'NONE',
    'PENDING_UNASSIGNED',
    'NOT_INTEGRATED',
    'SCAN_FAILED'
  ]) {
    assert.match(view, new RegExp(state))
  }
  assert.match(view, /录入员工 \/ 恢复任职/)
  assert.match(view, /resetTransientState/)
  assert.match(view, /permissions\.generateRoutes/)
  assert.match(view, /isCurrentGeneration/)
  assert.match(view, /getPostTemplates/)
  assert.doesNotMatch(view, /const storeRoles|const headRoles/)
})

test('员工短信登录复用平台接口并在无任职时给出明确落点', () => {
  const api = source('api/platform/staff.ts')
  const view = source('views/fr/platform-login/index.vue')
  assert.match(api, /\/platform\/auth\/sms\/send/)
  assert.match(api, /\/platform\/auth\/sms\/login/)
  assert.match(view, /暂无有效任职/)
  assert.match(view, /if \(!getAccessToken\(\)\) identity\.clear\(\)/)
  assert.match(view, /selectPost/)
})

test('刷新后所有岗位失效时显示无任职原因，未登录和管理员不会误显示', () => {
  const filename = path.join(__dirname, '../src/views/fr/platform-login/index.vue')
  const descriptor = parse(source('views/fr/platform-login/index.vue'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'platform-login' }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const setup = (token, mode, error = '') => {
    const identity = { posts: [], error, clear() {} }
    const module = { exports: {} }
    vm.runInNewContext(code, {
      module,
      exports: module.exports,
      defineOptions() {},
      computed: vue.computed,
      useRouter: () => ({}),
      require(name) {
        const imports = {
          vue: { ...vue, onBeforeUnmount() {} },
          'element-plus': {},
          '@/api/platform/staff': {},
          '@/utils/auth': { getAccessToken: () => token },
          '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
          '@/store/modules/permission': { usePermissionStore: () => ({}) },
          '@/store/modules/user': { useUserStore: () => ({}) },
          '@/hooks/web/useCache': {},
          '@/utils/platformIdentity': { portalMode: () => mode },
          '@/utils/platformRoles': { roleLabel: (role) => role }
        }
        if (Object.hasOwn(imports, name)) return imports[name]
        throw new Error(`Unexpected import: ${name}`)
      }
    })
    return module.exports.default.setup({}, { expose() {} })
  }
  assert.match(setup('staff-token', 'staff').notice.value, /暂无有效任职.*联系组织管理员/)
  assert.equal(setup('', 'staff').notice.value, '')
  assert.equal(setup('admin-token', 'admin').notice.value, '')
  assert.equal(setup('staff-token', 'staff', '加载失败').notice.value, '')
})

test('部门创建超时重试复用键，内容变化或成功后换键', async () => {
  const keys = []
  let fail = true
  const state = loadOrgStaffView({
    api: {
      createTeam: async (data, key) => {
        keys.push(key)
        if (fail) throw new Error('response lost')
        return { deptId: '21' }
      }
    }
  })
  state.teamForm.name = '工程部'
  await state.saveTeam()
  await state.saveTeam()
  assert.ok(keys[0])
  assert.equal(keys[0], keys[1])
  state.teamForm.name = '财务部'
  await state.saveTeam()
  assert.notEqual(keys[2], keys[1])
  fail = false
  await state.saveTeam()
  assert.equal(keys[3], keys[2])
  await state.saveTeam()
  assert.notEqual(keys[4], keys[3])
})

test('门店保留身份及其兼岗用户无法通过普通 Web 四入口撤权', async () => {
  const calls = []
  const state = loadOrgStaffView({ api: {
    revokePost: async () => calls.push('revoke'),
    setStaffStatus: async () => calls.push('stop'),
    handoverStaff: async () => calls.push('handover'),
    emergencyRevokeStaff: async () => calls.push('emergency')
  } })
  for (const roleCode of ['boss', 'owner']) {
    const reserved = { postId: '11', userId: '1', name: '负责人', roleCode, active: true }
    const employee = { postId: '12', userId: '1', name: '负责人', roleCode: 'sales', active: true }
    state.staff.value = [employee, reserved]
    assert.equal(state.isReservedRole(employee), false)
    assert.equal(state.hasReservedRole(employee.userId), true)
    state.openHandover(employee)
    assert.equal(state.handoverDialog.value, false)
    state.handoverForm.fromUserId = '1'
    state.handoverForm.toUserId = '2'
    await state.saveHandover()
    await state.revoke(reserved)
    await state.stopInOrg(employee)
    await state.emergency(employee)
    assert.match(state.actionError.value, /总部/)
  }
  assert.deepEqual(calls, [])
})

test('Web 保留角色保护不限制独立撤员工兼岗或作为接收人，也不套用到总部成员页', async () => {
  const calls = []
  const boss = { postId: '11', userId: '1', name: '老板', roleCode: 'boss', active: true }
  const employee = { postId: '12', userId: '1', name: '老板', roleCode: 'sales', active: true }
  const state = loadOrgStaffView({ api: { revokePost: async (id) => calls.push(id) } })
  state.staff.value = [boss, employee]
  state.handoverForm.fromUserId = '2'
  assert.equal(state.receivers.value.length, 1)
  await state.revoke(employee)
  assert.deepEqual(calls, ['12'])

  const hq = loadOrgStaffView({ identity: { currentPost: { postId: '1', orgId: '1001', orgType: 'headquarters' } } })
  hq.staff.value = [boss]
  assert.equal(hq.isReservedRole(boss), false)
  assert.equal(hq.hasReservedRole('1'), false)
})
