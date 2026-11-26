const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
const plain = (value) => JSON.parse(JSON.stringify(value))
const deferred = () => {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
function moduleFrom(source, imports = {}) {
  const module = { exports: {} }
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    File,
    FormData,
    require: (name) => {
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return module.exports
}
function loadView(name, api = {}, props = {}, options = {}) {
  const filename = path.join(root, 'src/views/zs/platform-org', name)
  const descriptor = parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor
  const source = compileScript(descriptor, { id: name }).content
  const hooks = {},
    messages = [],
    events = [],
    timers = new Map()
  let timerId = 0
  const imports = {
    vue: {
      ...vue,
      onMounted: (fn) => {
        hooks.mounted = fn
      },
      onActivated: (fn) => {
        hooks.activated = fn
      },
      onBeforeUnmount: (fn) => {
        hooks.unmount = fn
      },
      onDeactivated: (fn) => {
        hooks.deactivated = fn
      }
    },
    'element-plus': {
      ElMessage: {
        success: (text) => messages.push(['success', text]),
        error: (text) => messages.push(['error', text])
      }
    },
    '@/api/zs/franchise-directory': {
      getFranchiseDirectory: async () => ({ list: [], total: 0 }),
      getDirectoryRegions: async () => [],
      getDirectoryCandidates: async () => [],
      ...api
    },
    '@/api/zs/platform-org': { previewFranchise: async () => ({}), ...api },
    './franchiseAccountForm': moduleFrom(
      fs.readFileSync(path.join(root, 'src/views/zs/platform-org/franchiseAccountForm.ts'), 'utf8')
    )
  }
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    File,
    FormData,
    navigator: { clipboard: { writeText: options.writeClipboard || (async () => {}) } },
    setTimeout: (fn) => {
      const id = ++timerId
      timers.set(id, fn)
      return id
    },
    clearTimeout: (id) => timers.delete(id),
    require: (name) => {
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  const state = module.exports.default.setup(props, {
    expose() {},
    emit: (...event) => events.push(event)
  })
  return {
    state,
    hooks,
    messages,
    events,
    async flushTimers() {
      const jobs = [...timers.values()]
      timers.clear()
      await Promise.all(jobs.map((fn) => fn()))
      await vue.nextTick()
    }
  }
}

const result = { total: 2, created: 0, updated: 0, skipped: 0, failed: 0, unresolved: 1, rows: [] }
const file = (name) =>
  new File(['test workbook bytes'], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
const eventFile = (value) => ({ target: { files: value ? [value] : [] } })
const region = {
  code: '420116',
  level: 'district',
  name: '黄陂区',
  provinceCode: '420000',
  provinceName: '湖北省',
  cityCode: '420100',
  cityName: '武汉市'
}
const entry = {
  id: 'record-test',
  name: '测试登记资料',
  brandName: '测试品牌',
  brand: '测试品牌',
  recordType: 'COVERAGE',
  originalRegionText: '湖北省武汉市黄陂区',
  matchStatus: 'RESOLVED',
  regionCodes: ['420116'],
  linkedOrgId: null,
  version: 7
}

test('public map display saves an independently authorized address without sending internal account fields', async () => {
  const writes = []
  const item = { ...entry, recordType: 'FRANCHISE', linkedOrgId: '998', publicName: '原公开店名',
    publicAddress: '武汉市黄陂区旧地址', publicAddressAuthorized: true }
  const env = loadView('FranchiseDirectoryPanel.vue', {
    updateDirectoryMapDisplay: async (id, payload) => { writes.push({ id, payload }); return item }
  })
  env.state.openMapEditor(item)
  assert.equal(env.state.mapEditVisible.value, true)
  assert.equal(env.state.mapPublicAddress.value, item.publicAddress)
  assert.equal(env.state.mapPublicAddressAuthorized.value, true)
  env.state.mapPublicName.value = ' 测试品牌 · 武汉门店 '
  env.state.editMapPublicAddress(' 武汉市黄陂区前川街道公开门店路88号 ')
  assert.equal(env.state.mapPublicAddressAuthorized.value, false)
  await env.state.saveMapDisplay()
  assert.equal(writes.length, 0)
  assert.match(env.state.mapEditError.value, /授权/)
  env.state.togglePublicAddressAuthorization(true)
  env.state.editMapPublicAddress('武汉市黄陂区前川街道公开门店路89号')
  await env.state.saveMapDisplay()
  assert.equal(writes.length, 0, 'further edits invalidate a fresh confirmation too')
  env.state.togglePublicAddressAuthorization(true)
  await env.state.saveMapDisplay()
  assert.deepEqual(plain(writes), [{
    id: item.id,
    payload: {
      publicName: '测试品牌 · 武汉门店',
      publicAddress: '武汉市黄陂区前川街道公开门店路89号',
      publicAddressAuthorized: true,
      publicAddressConfirmed: true,
      expectedVersion: 7
    }
  }])
  assert.equal(item.name, entry.name)
  assert.equal(env.state.mapEditVisible.value, false)
})

test('editing only the display name does not send or restamp the existing address consent', async () => {
  const writes = []
  const item = { ...entry, recordType: 'FRANCHISE', publicName: '旧名称',
    publicAddress: '合成已授权营业地址', publicAddressAuthorized: true }
  const env = loadView('FranchiseDirectoryPanel.vue', {
    updateDirectoryMapDisplay: async (id, payload) => { writes.push(plain(payload)); return item }
  })
  env.state.openMapEditor(item)
  env.state.editMapPublicAddress('  合成已授权营业地址  ')
  env.state.mapPublicName.value = '新地图名称'
  await env.state.saveMapDisplay()
  assert.deepEqual(writes, [{ publicName: '新地图名称', expectedVersion: 7 }])
})

test('revoking public address authorization clears the address from the next map projection', async () => {
  const writes = []
  const item = { ...entry, recordType: 'FRANCHISE', publicAddress: '原公开地址', publicAddressAuthorized: true }
  const env = loadView('FranchiseDirectoryPanel.vue', {
    updateDirectoryMapDisplay: async (id, payload) => { writes.push({ id, payload }); return item }
  })
  env.state.openMapEditor(item)
  env.state.togglePublicAddressAuthorization(false)
  assert.equal(env.state.mapPublicAddress.value, '')
  assert.equal(env.state.mapPublicAddressAuthorized.value, false)
  await env.state.saveMapDisplay()
  assert.deepEqual(plain(writes[0].payload), {
    publicName: '',
    publicAddress: '',
    publicAddressAuthorized: false,
    expectedVersion: 7
  })
})

test('public map name save cannot repopulate a deactivated directory or display a late success', async () => {
  const pending = deferred()
  const env = loadView('FranchiseDirectoryPanel.vue', { updateDirectoryMapDisplay: () => pending.promise })
  env.state.openMapEditor(entry)
  env.state.mapPublicName.value = '公开名称'
  const saving = env.state.saveMapDisplay()
  env.hooks.deactivated()
  pending.resolve(entry)
  await saving
  assert.equal(env.state.mapEditVisible.value, false)
  assert.equal(env.state.mapEditing.value, null)
  assert.equal(env.state.mapPublicName.value, '')
  assert.deepEqual(env.messages, [])
})

test('public map name conflict keeps the typed name and does not retry a stale revision', async () => {
  let writes = 0
  const env = loadView('FranchiseDirectoryPanel.vue', { updateDirectoryMapDisplay: async () => { writes++; throw { code: 409, msg: '名录资料已变化，请重新载入' } } })
  env.state.openMapEditor(entry)
  env.state.mapPublicName.value = '我的草稿'
  await env.state.saveMapDisplay()
  assert.equal(writes, 1)
  assert.equal(env.state.mapEditVisible.value, true)
  assert.equal(env.state.mapPublicName.value, '我的草稿')
  assert.match(env.state.mapEditError.value, /已变化/)
})

test('XLSX precheck and commit send the original file as multipart and use the unwrapped result', async () => {
  const calls = []
  const api = moduleFrom(
    fs.readFileSync(path.join(root, 'src/api/zs/franchise-directory.ts'), 'utf8'),
    {
      '@/config/axios': {
        post: async (request) => {
          calls.push(request)
          return result
        }
      }
    }
  )
  const workbook = file('directory.xlsx')
  assert.equal(await api.precheckDirectoryImport(workbook), result)
  assert.equal(await api.submitDirectoryImport(workbook), result)
  assert.deepEqual(
    calls.map((c) => c.url),
    [
      '/platform/org/franchise-directory/import/precheck',
      '/platform/org/franchise-directory/import'
    ]
  )
  for (const call of calls) {
    assert.equal(call.data.get('file'), workbook)
    assert.equal(call.headersType, 'multipart/form-data')
  }
})

test('a different workbook invalidates the previous precheck before any commit', async () => {
  let commits = 0
  const { state } = loadView('FranchiseDirectoryPanel.vue', {
    precheckDirectoryImport: async () => result,
    submitDirectoryImport: async () => {
      commits++
      return result
    }
  })
  state.selectFile(eventFile(file('first.xlsx')))
  await state.precheck()
  assert.equal(state.canImport.value, true)
  state.selectFile(eventFile(file('second.xlsx')))
  assert.equal(state.importResult.value, null)
  assert.equal(state.canImport.value, false)
  await state.submitImport()
  assert.equal(commits, 0)
})

test('precheck describes planned changes and only a successful import describes saved changes', async () => {
  const { state } = loadView('FranchiseDirectoryPanel.vue', {
    precheckDirectoryImport: async () => ({ ...result, dryRun: true, created: 1, updated: 1 }),
    submitDirectoryImport: async () => ({ ...result, dryRun: false, created: 1, updated: 1 })
  })
  state.selectFile(eventFile(file('directory.xlsx')))
  await state.precheck()
  assert.equal(state.importStatusLabel('CREATED'), '将新增')
  assert.equal(state.importStatusLabel('UPDATED'), '将更新')
  assert.equal(state.importStatusLabel('FAILED'), '检查未通过')
  await state.submitImport()
  assert.equal(state.importStatusLabel('CREATED'), '已新增')
  assert.equal(state.importStatusLabel('UPDATED'), '已更新')
  assert.equal(state.importStatusLabel('FAILED'), '导入失败')
})

test('precheck is read-only and commit is single-flight; errors require a fresh precheck', async () => {
  const pending = deferred()
  let commits = 0
  const { state } = loadView('FranchiseDirectoryPanel.vue', {
    precheckDirectoryImport: async () => result,
    submitDirectoryImport: () => {
      commits++
      return pending.promise
    }
  })
  state.selectFile(eventFile(file('directory.xlsx')))
  await state.precheck()
  assert.equal(commits, 0)
  const first = state.submitImport()
  await state.submitImport()
  assert.equal(commits, 1)
  assert.equal(state.busy.value, true)
  pending.reject(new Error('network interrupted'))
  await first
  assert.equal(state.canImport.value, false)
  assert.equal(state.importResult.value, null)
  assert.match(state.importError.value, /network interrupted/)
})

test('an all-invalid or empty workbook cannot be committed', async () => {
  const { state } = loadView('FranchiseDirectoryPanel.vue', {
    precheckDirectoryImport: async () => ({ ...result, total: 2, failed: 2 })
  })
  state.selectFile(eventFile(file('directory.xlsx')))
  await state.precheck()
  assert.equal(state.canImport.value, false)
  state.selectFile(eventFile(new File([], 'empty.xlsx')))
  assert.equal(state.importFile.value, null)
  assert.ok(state.importError.value)
})

test('late directory search responses cannot replace a newer filter result', async () => {
  const old = deferred(),
    next = deferred()
  const { state } = loadView('FranchiseDirectoryPanel.vue', {
    getFranchiseDirectory: (query) => (query.keyword === 'old' ? old.promise : next.promise)
  })
  state.keyword.value = 'old'
  const first = state.loadList()
  state.keyword.value = 'new'
  const second = state.loadList()
  next.resolve({ list: [{ ...entry, id: 'new' }], total: 1 })
  await second
  old.resolve({ list: [{ ...entry, id: 'old' }], total: 10 })
  await first
  assert.equal(state.rows.value[0].id, 'new')
  assert.equal(state.total.value, 1)
})

test('unmount discards a pending import preview and the source file', async () => {
  const pending = deferred()
  const { state, hooks } = loadView('FranchiseDirectoryPanel.vue', {
    precheckDirectoryImport: () => pending.promise
  })
  state.selectFile(eventFile(file('directory.xlsx')))
  const checking = state.precheck()
  hooks.unmount()
  pending.resolve(result)
  await checking
  assert.equal(state.importFile.value, null)
  assert.equal(state.importResult.value, null)
})

test('ambiguous city data cannot open an account by choosing an unrelated district', async () => {
  const { state, hooks } = loadView(
    'DirectoryAccountForm.vue',
    {
      getDirectoryRegions: async () => [region]
    },
    { initialRecord: { ...entry, regionCodes: ['420100'], matchStatus: 'PARTIAL' } }
  )
  await hooks.mounted()
  state.districtCode.value = '420116'
  state.preview.value = { orgName: 'invalid', loginName: 'invalid' }
  state.adminMobile.value = '13800000000'
  assert.equal(state.eligible.value, false)
  assert.equal(state.canSubmit.value, false)
})

test('opening from a resolved record carries its stable ID/version and does not create a second registry row', async () => {
  const pending = deferred(),
    calls = []
  const { state, hooks, flushTimers } = loadView(
    'DirectoryAccountForm.vue',
    {
      getDirectoryRegions: async () => [region],
      getDirectoryCandidates: async () => [entry],
      previewFranchise: async () => ({
        orgName: '测试开户名称',
        loginName: 'test-login',
        aliases: []
      }),
      onboardFromDirectory: (payload) => {
        calls.push(payload)
        return pending.promise
      }
    },
    { initialRecord: entry }
  )
  await hooks.mounted()
  state.adminName.value = '测试人'
  state.adminMobile.value = '13800000000'
  await vue.nextTick()
  await flushTimers()
  assert.equal(state.canSubmit.value, true)
  const first = state.submit()
  await state.submit()
  assert.equal(calls.length, 1)
  assert.deepEqual(plain(calls[0]), {
    recordId: 'record-test',
    expectedVersion: 7,
    districtCode: '420116',
    adminName: '测试人',
    adminMobile: '13800000000'
  })
  pending.resolve({
    orgId: '90071992547409931',
    loginName: 'test-login',
    initialPassword: 'test-only-password',
    orgName: '测试开户名称',
    aliases: []
  })
  await first
  assert.equal(state.record.value.linkedOrgId, '90071992547409931')
  assert.equal(state.canSubmit.value, false)
  hooks.unmount()
  assert.equal(state.result.value, null)
  assert.equal(state.copyFallback.value, '')
})

test('a delayed clipboard failure cannot restore credentials after leaving the page', async () => {
  const pending = deferred()
  const { state, hooks } = loadView(
    'DirectoryAccountForm.vue',
    {},
    {},
    { writeClipboard: () => pending.promise }
  )
  state.result.value = {
    orgName: '测试名称',
    loginName: 'test-login',
    initialPassword: 'test-secret',
    aliases: []
  }
  const copying = state.copyCredentials()
  hooks.deactivated()
  hooks.activated()
  pending.reject(new Error('clipboard denied'))
  await copying
  assert.equal(state.result.value, null)
  assert.equal(state.copyFallback.value, '')
})

test('reactivating a cached page reloads candidates without retaining a prior selection or phone', async () => {
  let calls = 0
  const { state, hooks } = loadView(
    'DirectoryAccountForm.vue',
    {
      getDirectoryCandidates: async () => {
        calls++
        return [entry]
      },
      getDirectoryRegions: async () => [region]
    },
    { initialRecord: entry }
  )
  await hooks.mounted()
  state.adminMobile.value = '13800000000'
  hooks.deactivated()
  assert.equal(state.record.value, null)
  assert.equal(state.adminMobile.value, '')
  hooks.activated()
  await new Promise(setImmediate)
  await vue.nextTick()
  assert.equal(calls, 2)
  assert.equal(state.candidates.value.length, 1)
  assert.equal(state.selectedId.value, '')
})
