const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { webcrypto } = require('node:crypto')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const source = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
function load(file, imports = {}, globals = {}) {
  let text = source(file).replaceAll('import.meta.env.', '__testEnv.')
  if (file.endsWith('.vue'))
    text = compileScript(parse(text, { filename: file }).descriptor, {
      id: 'announcements'
    }).content
  const compiled = ts.transpileModule(text, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    Error,
    Date,
    Intl,
    crypto: webcrypto,
    __testEnv: {},
    require(name) {
      if (name in imports) return imports[name]
      throw new Error(`Unexpected import ${name}`)
    },
    ...globals
  })
  return module.exports
}
const model = load('views/platform/announcements/model.ts')
const helpers = load('utils/projectNavigation.ts')
const platform = load('utils/platformIdentity.ts')
const id = 'e6321485-1bd9-45f7-98be-50ab627ec5a9'
const bigBrand = '9007199254740993'
const record = (overrides = {}) => ({
  id,
  title: '公司活动通知',
  summary: '活动安排',
  content: '第一段\n第二段',
  visibility: 'BRANDS',
  brandIds: [bigBrand],
  pinned: true,
  status: 'DRAFT',
  revision: 2,
  publishedAt: null,
  createdAt: '2026-11-25T08:30:00Z',
  updatedAt: '2026-11-25T08:30:00Z',
  ...overrides
})
const choices = (canWrite = true) => ({ canWrite, brands: [{ id: bigBrand, name: '合家美宅' }] })
const deferred = () => {
  let resolve, reject
  const promise = new Promise((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
const plain = (value) => JSON.parse(JSON.stringify(value))

const tabStorage = () => {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  }
}
function view(overrides = {}) {
  const state = { tenant: '1', actor: '101', post: '201', generation: 1, ...overrides.scope }
  const identity = vue.reactive({
    context: { post: { postId: '201' } },
    currentPost: overrides.currentPost,
    ...overrides.identity
  })
  const calls = []
  const confirmations = []
  const notices = []
  const hooks = {}
  const api = {
    announcementOptions: async () => choices(),
    listAnnouncements: async () => ({ list: [record()], total: 1 }),
    getAnnouncement: async () => record(),
    saveAnnouncement: async (input, existing, createRequestId) => {
      calls.push(['save', plain(input), plain(existing || null), createRequestId])
      return record({ ...input, revision: 3 })
    },
    changeAnnouncementStatus: async (key, action, expectedRevision) => {
      calls.push([action, key, expectedRevision])
      return record({
        status: action === 'publish' ? 'PUBLISHED' : 'WITHDRAWN',
        revision: expectedRevision + 1
      })
    },
    ...overrides.api
  }
  const component = load('views/platform/announcements/index.vue', {
    vue: {
      ...vue,
      onMounted(fn) {
        hooks.mount = fn
      },
      onBeforeUnmount(fn) {
        hooks.unmount = fn
      }
    },
    'element-plus': {
      ElMessage: { success: (message) => notices.push(message) },
      ElMessageBox: {
        confirm: async (...args) => {
          confirmations.push(args)
          return overrides.confirm ? overrides.confirm(...args) : true
        }
      }
    },
    '@/api/platform/announcements': api,
    '@/utils/auth': {
      getAccessToken: () => 'access-token',
      getTenantId: () => state.tenant,
      getVisitTenantId: () => ''
    },
    '@/utils/platformIdentity': {
      currentPostId: () => state.post,
      isHeadquartersAnnouncementPost: platform.isHeadquartersAnnouncementPost,
      PENDING_ANNOUNCEMENT_CREATE_KEY: platform.PENDING_ANNOUNCEMENT_CREATE_KEY,
      postGeneration: () => state.generation
    },
    '@/utils/platformRoles': load('utils/platformRoles.ts'),
    '@/store/modules/platformIdentity': { usePlatformIdentityStore: () => identity },
    '@/store/modules/user': {
      useUserStore: () => ({
        get getUser() {
          return { id: state.actor }
        }
      })
    },
    './model': model
  }, { sessionStorage: overrides.storage || tabStorage() }).default
  return {
    page: component.setup({}, { expose() {} }),
    state,
    identity,
    calls,
    confirmations,
    notices,
    hooks
  }
}

test('announcement API uses B endpoints, preserves UUID and large brand IDs, and sends expectedRevision', async () => {
  const calls = []
  const request = Object.fromEntries(
    ['get', 'post', 'put'].map((method) => [
      method,
      async (value) => {
        calls.push([method, plain(value)])
        return record()
      }
    ])
  )
  const api = load('api/platform/announcements.ts', {
    '@/config/axios': { __esModule: true, default: request }
  })
  await api.announcementOptions()
  await api.listAnnouncements({
    pageNo: 2,
    pageSize: 20,
    keyword: '通知',
    status: '',
    brandId: bigBrand
  })
  await api.getAnnouncement(id)
  await api.saveAnnouncement(model.announcementInput(record()), { id, revision: 2 })
  await api.changeAnnouncementStatus(id, 'publish', 3)
  assert.equal(calls[0][1].url, '/platform/announcements/admin/options')
  assert.equal(calls[1][1].params.brandId, bigBrand)
  assert.equal(calls[2][1].url, `/platform/announcements/admin/${id}`)
  assert.equal(calls[3][1].data.expectedRevision, 2)
  assert.deepEqual(calls[3][1].data.brandIds, [bigBrand])
  assert.deepEqual(calls[4][1].data, { expectedRevision: 3 })
  await api.saveAnnouncement(model.announcementInput(record()), undefined, id)
  assert.equal(calls[5][0], 'post')
  assert.equal(calls[5][1].data.createRequestId, id)
  assert.equal(calls[3][1].data.createRequestId, undefined)
  assert.ok(calls.every(([, request]) => request.preserveBusinessError && request.silentError))
})

test('an admin session without a current post can explicitly choose an eligible headquarters post', async () => {
  let selected = null
  const headquarters = {
    postId: '2126143515310000001',
    orgType: 'headquarters',
    roleCode: 'hq-admin',
    orgName: '总部'
  }
  const store = { postId: '203', orgType: 'store', roleCode: 'finance', orgName: '门店' }
  const fixture = view({
    scope: { post: '' },
    identity: {
      context: null,
      loadPosts: async () => [store, headquarters],
      selectPost: async function (postId) {
        selected = postId
        fixture.state.post = String(postId)
        this.context = { post: headquarters }
        return this.context
      }
    },
    api: {
      announcementOptions: async () => {
        if (!selected) throw Object.assign(new Error('请选择工作身份'), { code: 1103000001 })
        return choices()
      }
    }
  })
  await fixture.hooks.mount()
  assert.equal(fixture.page.needsPost.value, true)
  assert.equal(fixture.page.canWrite.value, false)
  assert.equal(fixture.page.postPickerOpen.value, true)
  assert.deepEqual(plain(fixture.page.postChoices.value), [headquarters])
  await fixture.page.choosePost(store)
  assert.equal(selected, null)
  await fixture.page.choosePost(headquarters)
  await vue.nextTick()
  assert.equal(selected, headquarters.postId)
  assert.equal(fixture.page.postPickerOpen.value, false)
  assert.equal(fixture.page.canWrite.value, true)
})

test('headquarters announcement entry requires headquarters current post and query permission', () => {
  const can = (permission) => permission === 'platform:announcement:query'
  for (const roleCode of ['hq-admin', 'finance', 'finance-lead']) {
    assert.equal(
      platform.canReadHeadquartersAnnouncements({ orgType: 'headquarters', roleCode }, can),
      true
    )
    assert.equal(
      platform.canReadHeadquartersAnnouncements({ orgType: 'store', roleCode }, can),
      false
    )
  }
  assert.equal(
    platform.canReadHeadquartersAnnouncements(
      { orgType: 'headquarters', roleCode: 'hq-design-admin' },
      can
    ),
    false
  )
  assert.equal(
    platform.canReadHeadquartersAnnouncements(
      { orgType: 'headquarters', roleCode: 'hq-admin' },
      () => false
    ),
    false
  )
  assert.equal(platform.canReadHeadquartersAnnouncements(null, can), false)
  assert.equal(platform.isPostScopedUrl('/platform/announcements/admin'), true)
  const base = [{ path: '/', children: [{ path: 'index', meta: { title: '工作台' } }] }]
  const entries = helpers.buildPlatformMenus(base, [], (permissions) =>
    permissions.includes('platform:announcement:query')
  )
  assert.ok(
    helpers
      .searchProjectMenus(helpers.groupPlatformNavigation(entries), '公告')
      .some((row) => row.value === '/platform-announcements')
  )
  const grouped = helpers.groupPlatformNavigation(entries)
  assert.equal(grouped[1].path, '/platform-announcements')
  assert.equal(grouped[1].meta.title, '公告管理')
  assert.equal(helpers.buildStaffMenus([], can, false).length, 0)
  assert.equal(helpers.buildStaffMenus([], can, true)[0].path, '/platform-announcements')
})

test('staff router permits the same announcement path only for an authorized headquarters post', async () => {
  let guard
  const identity = {
    context: {},
    posts: [{ postId: '1' }],
    currentPost: { roleCode: 'finance', orgType: 'headquarters' },
    can: () => true
  }
  load('permission.ts', {
    './router': {
      __esModule: true,
      default: {
        beforeEach(fn) {
          guard = fn
        },
        afterEach() {}
      }
    },
    '@/config/axios/service': { isRelogin: {} },
    '@/utils/auth': { getAccessToken: () => 'token' },
    '@/hooks/web/useTitle': { useTitle() {} },
    '@/hooks/web/useNProgress': { useNProgress: () => ({ start() {}, done() {} }) },
    '@/hooks/web/usePageLoading': { usePageLoading: () => ({ loadStart() {}, loadDone() {} }) },
    '@/store/modules/dict': {},
    '@/store/modules/user': {},
    '@/store/modules/permission': {
      usePermissionStoreWithOut: () => ({ getRouters: [1], generateRoutes: async () => {} })
    },
    '@/utils/routeParams': {},
    '@/utils/platformIdentity': {
      portalMode: () => 'staff',
      ANNOUNCEMENTS_PATH: platform.ANNOUNCEMENTS_PATH,
      canReadHeadquartersAnnouncements: platform.canReadHeadquartersAnnouncements
    },
    '@/store/modules/platformIdentity': { usePlatformIdentityStoreWithOut: () => identity }
  })
  assert.equal(await guard({ path: '/platform-announcements', query: {} }, {}), true)
  identity.currentPost.orgType = 'store'
  assert.deepEqual(plain(await guard({ path: '/platform-announcements', query: {} }, {})), {
    path: '/fr/identity',
    query: { redirect: '/platform-announcements' },
    replace: true
  })
  assert.deepEqual(plain(await guard({ path: '/platform-pricing', query: {} }, {})), {
    path: '/fr/identity',
    replace: true
  })
  identity.context = null
  assert.deepEqual(plain(await guard({ path: '/platform-announcements', query: {} }, {})), {
    path: '/fr/identity',
    query: { redirect: '/platform-announcements' },
    replace: true
  })
})

test('draft validation retains precise IDs and renders UTC in Beijing time', () => {
  assert.match(model.inputError(model.newAnnouncement()), /标题/)
  assert.match(model.inputError({ ...record(), brandIds: [] }), /品牌/)
  assert.match(model.inputError({ ...record(), content: '  \n  ' }), /正文/)
  assert.equal(model.inputError(record()), '')
  const input = model.announcementInput({ ...record(), visibility: 'ALL' })
  assert.deepEqual(plain(input.brandIds), [])
  assert.deepEqual(record().brandIds, [bigBrand])
  assert.match(model.publicationTime('2026-11-25T08:30:00Z'), /2026.*11.*25.*16:30/)
  assert.equal(model.scopeLabel(record(), choices().brands), '合家美宅')
})

test('creating a draft does not publish until explicit confirmation and uses the saved revision', async () => {
  const f = view()
  await f.page.load()
  f.page.createDraft()
  Object.assign(f.page.form, model.announcementInput(record()))
  await f.page.save()
  assert.equal(f.calls.length, 1)
  assert.equal(f.calls[0][0], 'save')
  assert.equal(f.page.mode.value, 'view')
  await f.page.changeStatus(f.page.selected.value, 'publish')
  assert.deepEqual(f.calls[1], ['publish', id, 3])
  assert.match(f.confirmations[0][0], /合家美宅/)
})

test('empty announcement content is rejected before saving a draft', async () => {
  const f = view()
  await f.page.load()
  f.page.createDraft()
  Object.assign(f.page.form, { ...model.announcementInput(record()), content: '  \n  ' })
  await f.page.save()
  assert.equal(f.calls.length, 0)
  assert.match(f.page.editorError.value, /正文/)
})

test('read-only finance cannot create, edit or publish even through page handlers', async () => {
  const f = view({ api: { announcementOptions: async () => choices(false) } })
  await f.page.load()
  f.page.createDraft()
  assert.equal(f.page.editorOpen.value, false)
  await f.page.openRecord(id, true)
  assert.equal(f.page.mode.value, 'view')
  f.page.editSelected()
  await f.page.save()
  await f.page.changeStatus(record(), 'publish')
  assert.equal(f.calls.length, 0)
})

test('announcement roles can switch from read-only and see how to edit a published record', () => {
  const template = parse(source('views/platform/announcements/index.vue')).descriptor.template.content
  assert.match(template, /v-if="ready"[^>]+@click="openPostPicker"[^>]*>\s*切换工作身份/)
  assert.match(template, /撤回后可编辑/)
})

test('published records remain read-only until withdrawn', async () => {
  const f = view({ api: { getAnnouncement: async () => record({ status: 'PUBLISHED' }) } })
  await f.page.load()
  await f.page.openRecord(id, true)
  f.page.editSelected()
  assert.equal(f.page.mode.value, 'view')
  await f.page.changeStatus(f.page.selected.value, 'withdraw')
  assert.deepEqual(f.calls[0], ['withdraw', id, 2])
  f.page.editSelected()
  assert.equal(f.page.mode.value, 'edit')
})

test('repeated publication clicks share one pending confirmation and one mutation', async () => {
  const confirm = deferred()
  const f = view({ confirm: () => confirm.promise })
  await f.page.load()
  const first = f.page.changeStatus(record(), 'publish')
  await f.page.changeStatus(record(), 'publish')
  assert.equal(f.confirmations.length, 1)
  confirm.resolve(true)
  await first
  assert.equal(f.calls.filter((call) => call[0] === 'publish').length, 1)
})

test('older search responses cannot replace a newer page', async () => {
  const first = deferred(),
    second = deferred()
  const f = view({
    api: { listAnnouncements: (query) => (query.pageNo === 1 ? first.promise : second.promise) }
  })
  const one = f.page.load()
  f.page.query.pageNo = 2
  const two = f.page.load()
  second.resolve({ list: [record({ title: '第二页' })], total: 21 })
  await two
  first.resolve({ list: [record({ title: '旧页面' })], total: 21 })
  await one
  assert.equal(f.page.rows.value[0].title, '第二页')
})

test('changing current post clears records and discards late old-post data', async () => {
  const late = deferred()
  let call = 0
  const f = view({
    api: { listAnnouncements: async () => (++call === 1 ? late.promise : { list: [], total: 0 }) }
  })
  const loading = f.page.load()
  f.state.post = '202'
  f.state.generation++
  f.identity.context = { post: { postId: '202' } }
  await vue.nextTick()
  late.resolve({ list: [record({ title: '前一品牌公告' })], total: 1 })
  await loading
  await vue.nextTick()
  assert.equal(f.page.rows.value.length, 0)
  assert.equal(f.page.selected.value, null)
})

test('409 blocks repeating an old revision and keeps unsaved edits until refresh', async () => {
  let attempts = 0
  const f = view({
    api: {
      saveAnnouncement: async () => {
        attempts++
        throw Object.assign(new Error('conflict'), { code: 409 })
      }
    }
  })
  await f.page.load()
  await f.page.openRecord(id, true)
  f.page.form.title = '未保存的修改'
  await f.page.save()
  assert.equal(f.page.stale.value, true)
  assert.match(f.page.editorError.value, /刷新最新/)
  assert.equal(f.page.form.title, '未保存的修改')
  await f.page.save()
  assert.equal(attempts, 1)
  await f.page.refreshRecord()
  assert.equal(f.page.form.title, '公司活动通知')
  assert.equal(f.page.stale.value, false)
  assert.match(f.confirmations[0][0], /未保存/)
})

test('forbidden mutations clear private records and disable writing', async () => {
  const f = view({
    api: {
      changeAnnouncementStatus: async () => {
        throw Object.assign(new Error('forbidden'), { code: 403 })
      }
    }
  })
  await f.page.load()
  await f.page.openRecord(id)
  await f.page.changeStatus(record(), 'publish')
  assert.equal(f.page.rows.value.length, 0)
  assert.equal(f.page.selected.value, null)
  assert.equal(f.page.canWrite.value, false)
  assert.match(f.page.listError.value, /工作身份/)
})

test('unmounted detail cannot paint late content and the template treats content as plain text', async () => {
  const late = deferred()
  const f = view({ api: { getAnnouncement: () => late.promise } })
  await f.page.load()
  const opening = f.page.openRecord(id)
  f.hooks.unmount()
  late.resolve(record({ content: '<script>unsafe()</script>' }))
  await opening
  assert.equal(f.page.selected.value, null)
  assert.doesNotMatch(
    source('views/platform/announcements/index.vue'),
    /v-html|innerHTML|dangerouslyUseHTMLString/
  )
})

test('axios preserves opted-in business errors after post-generation rejection and leaves defaults unchanged', async () => {
  let response,
    sameGeneration = true
  const service = {
    interceptors: {
      request: { use() {} },
      response: {
        use(fn) {
          response = fn
        }
      }
    }
  }
  load(
    'config/axios/service.ts',
    {
      axios: { __esModule: true, default: { create: () => service } },
      'element-plus': { ElMessage: { error() {} }, ElMessageBox: {}, ElNotification() {} },
      qs: { __esModule: true, default: { stringify: () => '' } },
      '@/config/axios/config': { config: { result_code: 0 } },
      '@/utils/auth': {},
      './errorCode': { __esModule: true, default: {} },
      '@/router': {},
      '@/hooks/web/useCache': {},
      '@/utils/encrypt': { ApiEncrypt: { getEncryptHeader: () => 'encrypt' } },
      '@/utils/platformIdentity': { isCurrentGeneration: () => sameGeneration }
    },
    { useI18n: () => ({ t: (key) => key }), console }
  )
  const envelope = (config) => ({
    config: { silentError: true, ...config },
    data: { code: 409, msg: '版本已更新' },
    headers: {},
    request: { responseType: 'json' }
  })
  await assert.rejects(
    response(envelope({ preserveBusinessError: true })),
    (error) => error.code === 409 && error.message === '版本已更新'
  )
  await assert.rejects(response(envelope({})), (error) => error === 'error')
  sameGeneration = false
  await assert.rejects(
    response(envelope({ preserveBusinessError: true, platformGeneration: 1 })),
    /STALE_POST_RESPONSE/
  )
})

test('lost create response reopens the same frozen command and resolves to the original record', async () => {
  const attempts = []
  const drafts = new Map()
  const f = view({
    api: {
      saveAnnouncement: async (input, existing, requestId) => {
        attempts.push({ input: plain(input), existing, requestId })
        assert.match(requestId, /^[0-9a-f-]{36}$/)
        if (!drafts.has(requestId)) {
          drafts.set(requestId, record(input))
          throw Error('模拟服务器已保存，网页回执丢失')
        }
        return { ...drafts.get(requestId), status: 'WITHDRAWN', revision: 3 }
      }
    }
  })
  await f.page.load()
  f.page.createDraft()
  Object.assign(f.page.form, { title: '原草稿', content: '原正文' })
  await f.page.save()
  assert.match(f.page.editorError.value, /确认保存结果/)
  f.page.closeEditor()
  f.page.createDraft()
  assert.equal(f.page.form.title, '原草稿')
  f.page.form.title = '即使模型误写也不替换原请求'
  await f.page.save()
  assert.equal(drafts.size, 1)
  assert.deepEqual(attempts[1], attempts[0])
  assert.equal(f.page.selected.value.id, id)
  assert.equal(f.page.selected.value.status, 'WITHDRAWN')
  assert.match(f.notices.at(-1), /原公告/)
})

test('manual role switch waits for a lost save response to be resolved by the original command', async () => {
  const storage = tabStorage()
  const finance = { postId: '202', orgType: 'headquarters', roleCode: 'finance' }
  const saved = new Map()
  const requestIds = []
  const switched = []
  const f = view({
    storage,
    identity: {
      loadPosts: async () => [finance],
      selectPost: async (postId) => {
        switched.push(postId)
        return true
      }
    },
    api: {
      saveAnnouncement: async (input, existing, requestId) => {
        requestIds.push(requestId)
        if (!saved.has(requestId)) {
          saved.set(requestId, record(input))
          throw Error('模拟保存回执丢失')
        }
        return saved.get(requestId)
      }
    }
  })
  await f.page.load()
  f.page.createDraft()
  Object.assign(f.page.form, { title: '待确认草稿', content: '原正文' })
  await f.page.save()
  f.page.editorOpen.value = false
  f.page.closeEditor()
  assert.equal(f.page.pendingCreate.value.id, requestIds[0])
  const stored = storage.getItem(platform.PENDING_ANNOUNCEMENT_CREATE_KEY)

  await f.page.openPostPicker()
  assert.equal(f.page.postPickerOpen.value, false)
  f.page.postChoices.value = [finance]
  await f.page.choosePost(finance)
  assert.deepEqual(switched, [])
  assert.equal(storage.getItem(platform.PENDING_ANNOUNCEMENT_CREATE_KEY), stored)

  f.page.createDraft()
  assert.equal(f.page.form.title, '待确认草稿')
  await f.page.save()
  assert.deepEqual(requestIds, [requestIds[0], requestIds[0]])
  assert.equal(saved.size, 1)
  assert.equal(f.page.pendingCreate.value, null)

  await f.page.openPostPicker()
  assert.equal(f.page.postPickerOpen.value, true)
  await f.page.choosePost(finance)
  assert.deepEqual(switched, [finance.postId])
})

test('definite create rejection allows editing and a new command; switching identity clears pending state', async () => {
  const attempts = []
  const f = view({
    api: {
      saveAnnouncement: async (input, existing, requestId) => {
        attempts.push({ title: input.title, requestId })
        if (attempts.length === 1) throw Object.assign(Error('请求无效'), { code: 400 })
        if (attempts.length === 2) throw Error('回执丢失')
        return record(input)
      }
    }
  })
  await f.page.load()
  f.page.createDraft()
  Object.assign(f.page.form, { title: '首个提交', content: '正文' })
  await f.page.save()
  f.page.form.title = '纠正后的标题'
  await f.page.save()
  assert.equal(attempts[1].title, '纠正后的标题')
  assert.notEqual(attempts[1].requestId, attempts[0].requestId)
  f.state.post = '202'
  f.state.generation++
  f.identity.context = { post: { postId: '202' } }
  await vue.nextTick()
  await f.page.load()
  f.page.createDraft()
  assert.equal(f.page.form.title, '')
  Object.assign(f.page.form, { title: '另一工作身份', content: '正文' })
  await f.page.save()
  assert.notEqual(attempts[2].requestId, attempts[1].requestId)
})

test('request IDs use secure random bytes if randomUUID is unavailable (intranet HTTP)', () => {
  const intranetCrypto = { getRandomValues: (bytes) => bytes.fill(0xab) }
  const intranetModel = load(
    'views/platform/announcements/model.ts',
    {},
    { crypto: intranetCrypto }
  )
  assert.equal(intranetModel.createRequestId(), 'abababab-abab-4bab-abab-abababababab')
  const unsupported = load('views/platform/announcements/model.ts', {}, { crypto: {} })
  assert.throws(() => unsupported.createRequestId(), /安全生成/)
})

test('总部员工仅靠已确认岗位身份也能在刷新后恢复同一草稿请求', async () => {
  const storage = tabStorage()
  const attempts = []
  const api = {
    saveAnnouncement: async (input, existing, requestId) => {
      attempts.push({ input: plain(input), requestId })
      if (attempts.length === 1) throw Error('服务端已落草稿，回执丢失')
      return record(input)
    }
  }
  const currentPost = { userId: '101', postId: '201' }
  const staff = { storage, api, currentPost }
  const before = view({ ...staff, scope: { actor: 0 } })
  await before.page.load()
  before.page.createDraft()
  Object.assign(before.page.form, { title: '待确认草稿', content: '只保存一次的测试正文' })
  await before.page.save()
  before.hooks.unmount()
  const after = view({ ...staff, scope: { actor: 0, generation: 2 } })
  await after.page.load()
  assert.equal(attempts.length, 1, '重新打开页面不会自动提交')
  after.page.createDraft()
  assert.equal(after.page.form.title, '待确认草稿')
  await after.page.save()
  assert.deepEqual(attempts[1], attempts[0])
  const again = view({ ...staff, scope: { actor: 0, generation: 3 } })
  await again.page.load()
  again.page.createDraft()
  assert.equal(again.page.form.title, '', '确认后清理，不恢复历史正文')
})

test('a new actor, tenant or read-only post cannot restore old announcement content', async () => {
  for (const change of [{ scope: { actor: '102' } }, { scope: { tenant: '2' } },
    { scope: { post: '202' } }, { api: { announcementOptions: async () => choices(false) } }]) {
    const storage = tabStorage()
    const before = view({ storage, api: { saveAnnouncement: async () => { throw Error('回执丢失') } } })
    await before.page.load()
    before.page.createDraft()
    Object.assign(before.page.form, { title: '旧身份草稿', content: '原内容' })
    await before.page.save()
    before.hooks.unmount()
    const after = view({ storage, ...change })
    await after.page.load()
    after.page.createDraft()
    assert.equal(after.page.form.title, '')
    assert.equal(storage.getItem(platform.PENDING_ANNOUNCEMENT_CREATE_KEY), null)
    assert.equal(after.calls.length, 0)
  }
})

test('platform identity removes temporary announcement state on real post change and sign-out', () => {
  const storage = tabStorage()
  const session = load('utils/platformIdentity.ts', {}, { sessionStorage: storage, localStorage: tabStorage() })
  session.beginPostSwitch('201')
  storage.setItem(session.PENDING_ANNOUNCEMENT_CREATE_KEY, 'same authorized tab')
  session.beginPostSwitch('201')
  assert.equal(storage.getItem(session.PENDING_ANNOUNCEMENT_CREATE_KEY), 'same authorized tab')
  session.beginPostSwitch('202')
  assert.equal(storage.getItem(session.PENDING_ANNOUNCEMENT_CREATE_KEY), null)
  storage.setItem(session.PENDING_ANNOUNCEMENT_CREATE_KEY, 'cleared on logout')
  session.clearPlatformIdentity()
  assert.equal(storage.getItem(session.PENDING_ANNOUNCEMENT_CREATE_KEY), null)
})
