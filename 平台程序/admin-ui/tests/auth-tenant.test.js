const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function evaluate(file, env, mocks, globals = {}) {
  const source = fs
    .readFileSync(path.join(__dirname, '../src', file), 'utf8')
    .replaceAll('import.meta.env', 'testEnv')
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    testEnv: env,
    console,
    ...globals,
    require(name) {
      if (Object.hasOwn(mocks, name)) return mocks[name]
      throw new Error('Unmocked: ' + name)
    }
  })
  return module.exports
}
function auth(env, cachedTenant = 99) {
  return evaluate('utils/auth.ts', env, {
    '@/hooks/web/useCache': {
      useCache: () => ({ wsCache: { get: () => cachedTenant } }),
      CACHE_KEY: { TenantId: 'tenant' }
    }
  })
}
function transport(
  env,
  platform = { postId: '', generation: 0 },
  refreshToken = 'test-refresh',
  pageHref = 'https://app.example/fr/identity'
) {
  let request, response, responseError
  let identityClears = 0
  let tokenRemovals = 0
  const redirects = []
  const messages = []
  const posts = []
  const client = async (config) => config
  client.interceptors = {
    request: {
      use: (fn) => {
        request = fn
      }
    },
    response: {
      use: (fn, onError) => {
        response = fn
        responseError = onError
      }
    }
  }
  const axios = {
    create: () => client,
    defaults: { headers: { common: {} } },
    post: async (...args) => {
      posts.push(args)
      return { data: { data: { accessToken: 'refreshed', refreshToken: 'refresh' } } }
    }
  }
  evaluate(
    'config/axios/service.ts',
    env,
    {
      axios,
      'element-plus': {
        ElMessage: { error: (message) => messages.push(message) },
        ElNotification: { error() {} },
        ElMessageBox: { confirm: () => Promise.resolve() }
      },
      qs: {},
      '@/config/axios/config': { config: { base_url: '/admin-api', result_code: 0 } },
      '@/utils/auth': {
        ...auth(env),
        getAccessToken: () => 'test-token',
        getRefreshToken: () => refreshToken,
        getVisitTenantId: () => 88,
        removeToken: () => tokenRemovals++,
        setToken() {}
      },
      './errorCode': {},
      '@/router': { resetRouter() {} },
      '@/hooks/web/useCache': { deleteUserCache() {} },
      '@/utils/encrypt': { ApiEncrypt: { getEncryptHeader: () => 'x-encrypted' } },
      '@/utils/platformIdentity': {
        currentPostId: () => platform.postId,
        postGeneration: () => platform.generation,
        isCurrentGeneration: (value) => value === platform.generation,
        isPostScopedUrl: (url) => String(url).includes('/platform/org/'),
        clearPlatformIdentity: () => identityClears++,
        portalMode: () => 'admin',
        setPortalMode() {}
      }
    },
    {
      useI18n: () => ({ t: (value) => value }),
      window: { location: { href: pageHref, replace: (path) => redirects.push(path) } }
    }
  )
  return {
    request,
    response,
    responseError,
    messages,
    posts,
    axios,
    redirects,
    identityClears: () => identityClears,
    tokenRemovals: () => tokenRemovals
  }
}

test('单租户使用部署配置，不继承旧的其他租户缓存', () => {
  assert.equal(
    auth({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' }).getTenantId(),
    '1'
  )
  assert.equal(
    auth({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '42' }).getTenantId(),
    '42'
  )
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'false' }).getTenantId(), undefined)
})
test('多租户保持选择的租户，不被单租户配置覆盖', () => {
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'true', VITE_APP_TENANT_ID: '1' }).getTenantId(), 99)
})
test('真实请求拦截器：单租户登录带租户，不带旧令牌或跨租户访问头', () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' })
  const request = app.request({ url: '/system/auth/login', method: 'post', headers: {} })
  assert.equal(request.headers['tenant-id'], '1')
  assert.equal(request.headers.Authorization, undefined)
  assert.equal(request.headers['visit-tenant-id'], undefined)
})
test('真实请求拦截器：多租户受保护请求保留授权与访问租户', () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'true', VITE_APP_TENANT_ID: '1' })
  const request = app.request({ url: '/design/v1/budget/regions', method: 'get', headers: {} })
  assert.equal(request.headers['tenant-id'], 99)
  assert.equal(request.headers['visit-tenant-id'], 88)
  assert.equal(request.headers.Authorization, 'Bearer test-token')
})
test('401刷新与原请求使用同一租户，且不污染全局 axios 默认头', async () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '42' })
  await app.response({ data: { code: 401 }, config: { headers: {} }, headers: {}, request: {} })
  assert.equal(app.posts.length, 1)
  assert.equal(app.posts[0][2].headers['tenant-id'], '42')
  assert.equal(app.axios.defaults.headers.common['tenant-id'], undefined)
})
test('令牌失效重新登录时同步清除岗位上下文', async () => {
  const app = transport(
    { VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' },
    { postId: '11', generation: 3 },
    ''
  )
  await assert.rejects(
    () => app.response({ data: { code: 401 }, config: { headers: {} }, headers: {}, request: {} }),
    /timeoutMessage/
  )
  await Promise.resolve()
  assert.equal(app.identityClears(), 1)
  assert.equal(app.tokenRemovals(), 1)
})
test('员工登录页加载岗位遇到401时清会话并保持拒绝，不把失败当成功', async () => {
  const app = transport(
    { VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' },
    { postId: '11', generation: 3 },
    '',
    'https://app.example/platform-login'
  )
  await assert.rejects(
    () => app.response({ data: { code: 401 }, config: { headers: {} }, headers: {}, request: {} }),
    /timeoutMessage/
  )
  assert.equal(app.identityClears(), 1)
  assert.equal(app.tokenRemovals(), 1)
  assert.deepEqual(app.redirects, ['/platform-login'])
})
test('岗位请求固定原 X-Post-Id 和代次，切岗后的成功、失败与重放均被丢弃', async () => {
  const platform = { postId: '11', generation: 3 }
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' }, platform)
  const request = app.request({ url: '/platform/org/staff', method: 'get', headers: {} })
  assert.equal(request.headers['X-Post-Id'], '11')
  assert.equal(request.platformGeneration, 3)
  platform.postId = '22'
  platform.generation = 4
  await assert.rejects(
    () => app.response({ data: { code: 0, data: [] }, config: request, headers: {}, request: {} }),
    /STALE_POST_RESPONSE/
  )
  assert.throws(() => app.request(request), /STALE_POST_RESPONSE/)
})
test('切岗后旧请求在重放前被拒绝时不向用户展示内部错误标识', async () => {
  const platform = { postId: '11', generation: 3 }
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' }, platform)
  const request = app.request({ url: '/platform/org/staff', method: 'get', headers: {} })
  platform.postId = '22'
  platform.generation = 4
  let stale
  try {
    app.request(request)
  } catch (error) {
    stale = error
  }
  assert.ok(stale)
  await assert.rejects(() => app.responseError(stale), /STALE_POST_RESPONSE/)
  assert.deepEqual(app.messages, [])
})

test('页面权限与后端超级管理员规则一致，普通账号无预算权限仍拒绝', () => {
  const user = { roles: [], permissions: new Set() }
  const permission = evaluate(
    'directives/permission/hasPermi.ts',
    {},
    {
      '@/store/modules/user': { useUserStore: () => user }
    },
    { useI18n: () => ({ t: (value) => value }) }
  )
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.roles = ['admin']
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.permissions.add('design:budget:query')
  assert.equal(permission.hasPermission(['design:budget:query']), true)
  assert.equal(permission.hasPermission(['design:budget:configure']), false)
  user.permissions.clear()
  user.roles = ['super_admin']
  assert.equal(permission.hasPermission(['design:budget:query']), true)
  user.roles = []
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.permissions.add('*:*:*')
  assert.equal(permission.hasPermission(['design:budget:query']), true)
})
