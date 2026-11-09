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

function loadOrgStaffView() {
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
    restoreContext: async () => null
  }
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    defineOptions() {},
    useRouter: () => ({ replace: async () => {} }),
    require(name) {
      const imports = {
        vue: { ...vue, onMounted() {} },
        'element-plus': {
          ElMessage: { warning() {}, success() {} },
          ElMessageBox: { confirm: async () => true }
        },
        '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
        '@/store/modules/permission': {
          usePermissionStore: () => ({ generateRoutes: async () => null })
        },
        '@/api/platform/staff': {},
        '@/utils/platformIdentity': {
          isCurrentGeneration: () => true,
          postGeneration: () => 1
        },
        '@/utils/platformRoles': { roleLabel: (roleCode) => roleCode }
      }
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports.default.setup({}, { expose() {} })
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
