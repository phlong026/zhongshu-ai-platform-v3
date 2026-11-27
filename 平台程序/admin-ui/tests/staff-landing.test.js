const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/**
 * P2-1：员工 Web 落地页共用解析（identity 切换 + 短信登录）。
 * 与 tests/fr-dashboard.test.js 同型：仓内 typescript 转译 + VM 加载，零新依赖。
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

const identity = load('utils/platformIdentity.ts')
const post = (roleCode, orgType = 'store') => ({ roleCode, orgType })

const withPermissions =
  (...permissions) =>
  (permission) =>
    permissions.includes(permission)

test('boss and owner land on dashboard by role', () => {
  assert.equal(identity.resolveStaffLandingPath(post('boss'), withPermissions()), '/fr/dashboard')
  assert.equal(identity.resolveStaffLandingPath(post('owner'), withPermissions()), '/fr/dashboard')
})

test('finance roles land on finance even when they also hold operate', () => {
  assert.equal(
    identity.resolveStaffLandingPath(
      post('finance'),
      withPermissions('franchise:performance:operate', 'franchise:fin:query')
    ),
    '/fr/finance'
  )
  assert.equal(
    identity.resolveStaffLandingPath(
      post('finance-lead'),
      withPermissions('franchise:performance:operate', 'franchise:fin:query')
    ),
    '/fr/finance'
  )
})

test('总部管理员进入公告管理，只有获准总部岗位能恢复公告直达链接', () => {
  const can = withPermissions('platform:announcement:query')
  const path = identity.ANNOUNCEMENTS_PATH
  assert.equal(identity.resolveStaffLandingPath(post('hq-admin', 'headquarters'), can), path)
  assert.equal(identity.resolveStaffLandingPath(post('finance', 'headquarters'), can, path), path)
  assert.equal(identity.resolveStaffLandingPath(post('finance', 'headquarters'), can), '/fr/finance')
  assert.equal(identity.resolveStaffLandingPath(post('finance'), can, path), '/fr/finance')
  assert.equal(
    identity.resolveStaffLandingPath(post('hq-admin', 'headquarters'), withPermissions(), path),
    '/fr/identity'
  )
})

test('unmapped role with org query lands on staff page', () => {
  assert.equal(
    identity.resolveStaffLandingPath(post('sales'), withPermissions('platform:org:query')),
    '/fr/org/staff'
  )
  assert.equal(
    identity.resolveStaffLandingPath(null, withPermissions('platform:org:query')),
    '/fr/org/staff'
  )
})

test('unmapped role falls back in order: operate -> fin -> org -> identity', () => {
  assert.equal(
    identity.resolveStaffLandingPath(
      post('sales'),
      withPermissions('franchise:performance:operate', 'franchise:fin:query', 'platform:org:query')
    ),
    '/fr/dashboard'
  )
  assert.equal(
    identity.resolveStaffLandingPath(
      post('sales'),
      withPermissions('franchise:fin:query', 'platform:org:query')
    ),
    '/fr/finance'
  )
  assert.equal(identity.resolveStaffLandingPath(post('sales'), withPermissions()), '/fr/identity')
})

test('unmapped role without permissions does not dead-end on a blank page', () => {
  assert.equal(identity.resolveStaffLandingPath(post('engineer'), withPermissions()), '/fr/identity')
})

test('公告直达遇到门店身份，选岗后仍回到原公告，但无权身份仍不能查看', async () => {
  let guard
  const staff = { context: {}, posts: [post('finance'), post('finance', 'headquarters')],
    currentPost: post('finance'), can: withPermissions('platform:announcement:query') }
  const router = { beforeEach: (fn) => { guard = fn }, afterEach() {} }
  const noop = () => {}
  const deps = {
    './router': { __esModule: true, default: router },
    '@/config/axios/service': {},
    '@/utils/auth': { getAccessToken: () => 'synthetic-session' },
    '@/hooks/web/useTitle': { useTitle: noop },
    '@/hooks/web/useNProgress': { useNProgress: () => ({ start: noop, done: noop }) },
    '@/hooks/web/usePageLoading': { usePageLoading: () => ({ loadStart: noop, loadDone: noop }) },
    '@/store/modules/dict': {},
    '@/store/modules/user': {},
    '@/store/modules/permission': { usePermissionStoreWithOut: () => ({ getRouters: [true] }) },
    '@/utils/routeParams': {},
    '@/utils/platformIdentity': { ...identity, portalMode: () => 'staff' },
    '@/store/modules/platformIdentity': { usePlatformIdentityStoreWithOut: () => staff }
  }
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/permission.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  vm.runInNewContext(code, { exports: {}, require: (id) => deps[id] })
  const destination = { path: identity.ANNOUNCEMENTS_PATH, query: {} }
  const redirect = await guard(destination, {})
  assert.equal(redirect.path, '/fr/identity')
  assert.equal(redirect.query?.redirect, identity.ANNOUNCEMENTS_PATH)
  assert.equal(identity.resolveStaffLandingPath(post('finance', 'headquarters'), staff.can,
    redirect.query.redirect), identity.ANNOUNCEMENTS_PATH)
  assert.equal(identity.resolveStaffLandingPath(post('finance'), staff.can,
    redirect.query.redirect), '/fr/finance')
  staff.currentPost = post('finance', 'headquarters')
  assert.equal(await guard(destination, {}), true)
  staff.can = withPermissions()
  assert.equal((await guard(destination, {})).path, '/fr/identity')
})
