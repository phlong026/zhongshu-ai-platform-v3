const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
const source = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')

function loadModel() {
  const filename = path.join(root, 'src/views/zs/platform-org/franchiseAccountForm.ts')
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports }, { filename })
  return module.exports
}

function loadOnboardView({ onboardFranchise }) {
  const filename = path.join(root, 'src/views/zs/platform-org/index.vue')
  const descriptor = parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'franchise-onboard-real-sfc' }).content
  const code = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const hooks = {}
  const messages = { warning: [], success: [], error: [] }
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    defineOptions() {},
    navigator: { clipboard: { writeText: async () => {} } },
    setTimeout,
    clearTimeout,
    require(name) {
      const imports = {
        vue: {
          ...vue,
          onMounted() {},
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
            warning(message) {
              messages.warning.push(message)
            },
            success(message) {
              messages.success.push(message)
            },
            error(message) {
              messages.error.push(message)
            }
          }
        },
        '@/api/system/area': { getAreaTree: async () => [] },
        '@/api/zs/platform-org': {
          onboardFranchise,
          onboardOrg: async () => ({}),
          previewFranchise: async () => ({})
        },
        './franchiseAccountForm': loadModel()
      }
      if (Object.hasOwn(imports, name)) return imports[name]
      throw new Error(`Unexpected import: ${name}`)
    }
  })
  return {
    state: module.exports.default.setup({}, { expose() {} }),
    hooks,
    messages
  }
}

test('加盟商开通 API 区分预览和正式开通并保留旧组织接口', () => {
  const api = source('src/api/zs/platform-org.ts')
  assert.match(api, /\/franchise-preview/)
  assert.match(api, /\/franchise-onboard/)
  assert.match(api, /\/onboard/)
  for (const field of [
    'brand',
    'province',
    'city',
    'area',
    'adminName',
    'adminMobile',
    'areaPinyin',
    'ownerPinyin',
    'initialPassword',
    'requiresPasswordChange',
    'accountId'
  ]) {
    assert.match(api, new RegExp(`\\b${field}\\b`))
  }
})

test('地区树只取省市名称并构建服务端预览载荷', () => {
  const { findAreaNames, buildPreviewPayload, resetOverrideWhenSourceChanges, toProvinceCityTree } =
    loadModel()
  const tree = [
    {
      id: 1,
      name: '江西省',
      children: [{ id: 2, name: '南昌市', children: [{ id: 3, name: '高新区' }] }]
    }
  ]
  assert.deepEqual(JSON.parse(JSON.stringify(findAreaNames(tree, [1, 2]))), {
    province: '江西省',
    city: '南昌市'
  })
  assert.deepEqual(JSON.parse(JSON.stringify(findAreaNames(tree, undefined))), {
    province: '',
    city: ''
  })
  const provinceCityTree = toProvinceCityTree(tree)
  assert.deepEqual(JSON.parse(JSON.stringify(provinceCityTree)), [
    { id: 1, name: '江西省', children: [{ id: 2, name: '南昌市' }] }
  ])
  assert.deepEqual(JSON.parse(JSON.stringify(tree[0].children[0].children)), [
    { id: 3, name: '高新区' }
  ])
  assert.deepEqual(
    JSON.parse(
      JSON.stringify(
        buildPreviewPayload({
          brand: ' 合家美宅 ',
          province: '江西省',
          city: '南昌市',
          area: ' 高新 ',
          adminName: ' 李忠 ',
          areaPinyin: ' GaoXin ',
          ownerPinyin: ' LiZhong '
        })
      )
    ),
    {
      brand: '合家美宅',
      province: '江西省',
      city: '南昌市',
      area: '高新',
      adminName: '李忠',
      areaPinyin: 'gaoxin',
      ownerPinyin: 'lizhong'
    }
  )
  assert.equal(resetOverrideWhenSourceChanges('高新', '红谷滩', 'gaoxin'), '')
  assert.equal(resetOverrideWhenSourceChanges('高新', '高新', 'gao-xin'), 'gao-xin')

  const automatic = buildPreviewPayload({
    brand: '合家美宅',
    province: '江西省',
    city: '南昌市',
    area: '红谷滩',
    adminName: '李忠',
    areaPinyin: '',
    ownerPinyin: ''
  })
  assert.equal(automatic.areaPinyin, undefined)
  assert.equal(automatic.ownerPinyin, undefined)
})

test('预览代次拒绝迟到响应覆盖新表单', () => {
  const { createPreviewGenerationGuard } = loadModel()
  const guard = createPreviewGenerationGuard()
  const oldRequest = guard.next()
  const currentRequest = guard.next()
  assert.equal(guard.isCurrent(oldRequest), false)
  assert.equal(guard.isCurrent(currentRequest), true)
  guard.invalidate()
  assert.equal(guard.isCurrent(currentRequest), false)
})

test('可控异步开通期间阻止离页，结果交付后才允许离页清理', async () => {
  const { createPreviewGenerationGuard, canLeaveFranchiseOnboard } = loadModel()
  const guard = createPreviewGenerationGuard()
  let resolveRequest
  const request = new Promise((resolve) => {
    resolveRequest = resolve
  })
  let displayed = null
  let submitting = true
  const generation = guard.next()
  const completion = request.then((result) => {
    if (guard.isCurrent(generation)) displayed = result
    submitting = false
  })

  assert.equal(canLeaveFranchiseOnboard(submitting), false)
  resolveRequest({
    orgName: '合家美宅-江西南昌-高新-李忠',
    initialPassword: 'FIXTURE-ONLY-********'
  })
  await completion
  assert.equal(displayed.initialPassword, 'FIXTURE-ONLY-********')
  assert.equal(canLeaveFranchiseOnboard(submitting), true)
  displayed.initialPassword = null
  guard.invalidate()
  assert.equal(displayed.initialPassword, null)
})

test('真实总部SFC路由守卫在pending时阻止离页，完成交付后才清密', async () => {
  let resolveOnboard
  const deferred = new Promise((resolve) => {
    resolveOnboard = resolve
  })
  const { state, hooks, messages } = loadOnboardView({ onboardFranchise: () => deferred })
  Object.assign(state.franchiseForm, {
    province: '江西省',
    city: '南昌市',
    area: '红谷滩',
    adminName: '李忠',
    adminMobile: '13800000001'
  })
  await vue.nextTick()
  state.franchiseFormRef.value = { validate: async () => true }
  state.preview.value = {
    orgName: '合家美宅-江西南昌-红谷滩-李忠',
    loginName: 'hjmz-jxnc-honggutan-lizhong',
    aliases: ['jxnc-honggutan-lizhong', 'lizhong'],
    areaPinyin: 'honggutan',
    ownerPinyin: 'lizhong'
  }

  const submitting = state.submitFranchise()
  await Promise.resolve()
  assert.equal(state.submitting.value, true)
  assert.equal(hooks.beforeRouteLeave(), false)
  assert.equal(state.franchiseResult.value, null)
  assert.match(messages.warning[0], /开通处理中/)

  resolveOnboard({
    orgId: 'fixture-org',
    adminUserId: 'fixture-user',
    postId: 'fixture-post',
    reusedOrg: false,
    reusedUser: false,
    orgName: '合家美宅-江西南昌-红谷滩-李忠',
    loginName: 'hjmz-jxnc-honggutan-lizhong',
    aliases: ['jxnc-honggutan-lizhong', 'lizhong'],
    areaPinyin: 'honggutan',
    ownerPinyin: 'lizhong',
    initialPassword: 'FIXTURE-ONLY-********',
    requiresPasswordChange: true,
    accountId: 'fixture-account'
  })
  await submitting
  assert.equal(state.franchiseResult.value.initialPassword, 'FIXTURE-ONLY-********')
  const delivered = state.franchiseResult.value
  assert.equal(hooks.beforeRouteLeave(), true)
  assert.equal(delivered.initialPassword, null)
  assert.equal(state.franchiseResult.value, null)
})

test('复制内容仅使用正式开通响应，新账号和复用账号文案清晰区分', () => {
  const { buildCredentialCopyText } = loadModel()
  const common = {
    orgName: '合家美宅-江西南昌-高新-李忠',
    loginName: 'hjmz-jxnc-gaoxin-lizhong',
    aliases: ['jxnc-gaoxin-lizhong', 'lizhong']
  }
  const created = buildCredentialCopyText({
    ...common,
    initialPassword: 'A7mP9q2K',
    reusedUser: false
  })
  assert.match(created, /初始密码：A7mP9q2K/)
  assert.match(created, /首次登录后请修改初始密码/)
  assert.match(created, /门店老板账号/)
  assert.doesNotMatch(created, /undefined|null/)

  const reused = buildCredentialCopyText({ ...common, initialPassword: null, reusedUser: true })
  assert.match(reused, /密码：沿用原账号密码/)
  assert.doesNotMatch(reused, /初始密码：/)
})

test('页面使用地区树、防抖预览、拼音校正、一键复制与敏感结果离页清理', () => {
  const page = source('src/views/zs/platform-org/index.vue')
  assert.match(page, /加盟门店开通/)
  assert.match(page, /其他组织开通/)
  assert.match(page, /getAreaTree/)
  assert.match(page, /previewFranchise/)
  assert.match(page, /setTimeout/)
  assert.match(page, /areaPinyin/)
  assert.match(page, /ownerPinyin/)
  assert.match(page, /navigator\.clipboard\.writeText/)
  assert.match(page, /onBeforeUnmount/)
  assert.match(page, /onDeactivated/)
  assert.match(page, /onBeforeRouteLeave/)
  assert.match(page, /initialPassword/)
  assert.match(page, /请选择省、市/)
  assert.match(page, /v-model="franchiseForm\.area"[\s\S]*?maxlength="20"/)
  assert.doesNotMatch(page, /加盟门店（旧方式）/)
})
