const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const ROOT = path.join(__dirname, '../src/views/zs/case')

function loadTsModule(relativePath) {
  const filename = path.join(__dirname, relativePath)
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, Intl, Date })
  return module.exports
}

function build(file, sandbox) {
  const filename = path.join(ROOT, file)
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: file })
  const code = ts.transpileModule(source.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  let carried = null
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    ...vue,
    onMounted() {},
    onBeforeUnmount() {},
    useMessage: () => sandbox.message,
    useRoute: () => sandbox.route,
    useRouter: () => sandbox.router,
    URL: { createObjectURL: () => 'blob:mock', revokeObjectURL() {} },
    require(name) {
      if (name === 'vue') return vue
      if (name === '@/api/zs') return sandbox.api
      if (name === '@/utils/permission')
        return { checkPermi: () => sandbox.hasDeletePermission !== false }
      if (name === '@/utils/zsFormat')
        return { fmtTime: () => '2026-10-14 00:00:00', styleText: (s) => s }
      // create.vue 跨重挂载携带授权勾选的模块级单例
      if (name === './draftCarry') {
        return {
          setCarriedDraftState: (value) => {
            carried = value
          },
          takeCarriedDraftState: () => {
            const value = carried
            carried = null
            return value
          }
        }
      }
      throw new Error(name)
    }
  })
  return {
    setup: () => module.exports.default.setup({}, { expose() {} }),
    template: descriptor.template.content
  }
}

test('保存草稿后就地解锁上传：授权勾选跨重挂载保留并给出可上传提示', async () => {
  const calls = []
  const sandbox = {
    route: { query: {} },
    router: { replace: async (loc) => calls.push(['replace', loc.query]) },
    message: { success() {}, error() {} },
    api: {
      createCase: async () => {
        calls.push(['createCase'])
        return { caseId: '123', version: 1 }
      },
      updateCase: async () => ({ version: 2 }),
      getCase: async () => ({ version: 1 }),
      getCaseAsset: async () => new Blob(),
      uploadCaseImage: async () => ({ version: 2, assetId: 'a1' })
    }
  }
  const app = build('create.vue', sandbox)

  // 新建态：保存草稿触发 replace 补 id，随后整页重挂载
  const creating = app.setup()
  assert.equal(creating.isEdit.value, false)
  creating.form.title = '云栖雅院'
  creating.publicDisplay.value = true
  creating.generationReference.value = true
  await creating.save(false)
  assert.deepEqual(calls.find((c) => c[0] === 'replace')[1].id, '123')

  // 重挂载后的编辑态实例：勾选不丢，提示“草稿已保存、可上传”
  sandbox.route.query = { id: '123' }
  const editMode = app.setup()
  assert.equal(editMode.isEdit.value, true)
  assert.equal(editMode.publicDisplay.value, true)
  assert.equal(editMode.generationReference.value, true)
  assert.match(editMode.draftSavedNotice.value, /草稿已保存（编号 123）/)
  assert.match(editMode.draftSavedNotice.value, /上传图纸$/)

  // 打开其它案例不得带回上一单的勾选状态
  sandbox.route.query = { id: '456' }
  const other = app.setup()
  assert.equal(other.publicDisplay.value, false)
  assert.equal(other.draftSavedNotice.value, '')
})

test('未勾选授权时保存草稿，提示指向勾选动作而不是宣称可上传', async () => {
  const sandbox = {
    route: { query: {} },
    router: { replace: async () => {} },
    message: { success() {}, error() {} },
    api: {
      createCase: async () => ({ caseId: '9', version: 1 }),
      getCase: async () => ({ version: 1 })
    }
  }
  const app = build('create.vue', sandbox)
  const creating = app.setup()
  creating.form.title = '云栖雅院'
  await creating.save(false)
  sandbox.route.query = { id: '9' }
  const editMode = app.setup()
  assert.equal(editMode.publicDisplay.value, false)
  assert.match(editMode.draftSavedNotice.value, /勾选「确认公司拥有图片使用权」后即可上传图纸/)
})

test('案例列表：单一上传人列兼容新旧字段，首页精选与软删除同时保留', async () => {
  const rows = [
    {
      caseId: '1',
      title: '公司下架',
      sourceType: 'COMPANY',
      publicationStatus: 'OFFLINE',
      uploaderCompany: '众墅总部',
      uploaderName: '运营甲',
      uploader: '旧字段不应优先'
    },
    {
      caseId: '2',
      title: 'AI下架',
      sourceType: 'AI',
      publicationStatus: 'OFFLINE',
      uploader: '投稿用户乙'
    },
    {
      caseId: '3',
      title: '已上架',
      sourceType: 'COMPANY',
      publicationStatus: 'PUBLISHED',
      uploader: '运营甲'
    }
  ]
  const calls = []
  let deleteResult = true
  let confirmError = null
  let deleteError = null
  const messages = []
  const confirmations = []
  const sandbox = {
    route: { query: {} },
    router: { push: async () => {} },
    message: {
      success: (m) => messages.push(['success', m]),
      error: (m) => messages.push(['error', m]),
      info: (m) => messages.push(['info', m]),
      delConfirm: async (text) => {
        confirmations.push(text)
        if (confirmError) throw confirmError
        return true
      }
    },
    api: {
      getCasePage: async () => ({ list: rows, total: rows.length }),
      deleteCase: async (caseId) => {
        calls.push(['deleteCase', caseId])
        if (deleteError) throw deleteError
        return deleteResult
      },
      publishCase: async () => true,
      offlineCase: async () => true,
      setCaseHomeFeatured: async (caseId, featured) => {
        calls.push(['setCaseHomeFeatured', caseId, featured])
      },
      bulkCaseAction: async () => ({ items: [] }),
      getCase: async () => ({})
    }
  }
  const app = build('index.vue', sandbox)

  // 仅保留一列上传人；删除受权限点控制，首页精选入口不丢失
  assert.equal((app.template.match(/label="上传(?:人|者)"/g) || []).length, 1)
  assert.match(app.template, /command="homeFeatured"/)
  assert.match(app.template, /command="delete"/)
  assert.match(app.template, /canDelete/)
  assert.equal(app.setup, app.setup)

  const state = app.setup()
  assert.equal(state.canDelete, true)
  await state.load()
  assert.equal(state.list.value.length, 3)
  assert.equal(state.uploaderText(state.list.value[0]), '众墅总部 · 运营甲')
  assert.equal(state.uploaderText(state.list.value[1]), '投稿用户乙')
  assert.equal(state.uploaderText({}), '-')

  // AI 下架案例此前无任何操作入口，现在因可删除而出现“更多”
  assert.equal(state.canMore(state.list.value[1]), true)
  assert.equal(state.canMore(state.list.value[2]), true)

  // rowAction 返回实际异步动作，调用方可观测完成与错误处理
  await state.rowAction('delete', state.list.value[0])
  assert.match(confirmations.at(-1), /确认删除案例「公司下架」/)
  assert.deepEqual(calls.at(-1), ['deleteCase', '1'])
  assert.equal(messages.at(-1)[0], 'success')

  // 已上架案例仍可切换首页精选，不被删除分支覆盖
  await state.rowAction('homeFeatured', { ...state.list.value[2], homeFeatured: false })
  assert.deepEqual(calls.at(-1), ['setCaseHomeFeatured', '3', true])

  // 删除失败（状态已变化）：明确报错并刷新列表
  deleteResult = false
  await state.rowAction('delete', state.list.value[0])
  assert.equal(messages.at(-1)[0], 'error')
  assert.match(messages.at(-1)[1], /仅已下架的案例可删除/)

  // 用户取消确认是正常分支：不请求删除、不报成功、不留未处理 Promise
  const deleteCallsBeforeCancel = calls.filter(([name]) => name === 'deleteCase').length
  const successBeforeCancel = messages.filter(([type]) => type === 'success').length
  confirmError = 'cancel'
  await assert.doesNotReject(() => state.rowAction('delete', state.list.value[0]))
  assert.equal(calls.filter(([name]) => name === 'deleteCase').length, deleteCallsBeforeCancel)
  assert.equal(messages.filter(([type]) => type === 'success').length, successBeforeCancel)

  // 真实请求失败不可按取消吞掉，展示后端错误信息
  confirmError = null
  deleteResult = true
  deleteError = { msg: '删除服务暂不可用' }
  await assert.doesNotReject(() => state.rowAction('delete', state.list.value[0]))
  assert.deepEqual(messages.at(-1), ['error', '删除服务暂不可用'])
})

test('案例列表：无删除权限时不出现删除入口', () => {
  const sandbox = {
    route: { query: {} },
    router: { push: async () => {} },
    message: { success() {}, error() {}, info() {}, delConfirm: async () => true },
    hasDeletePermission: false,
    api: { getCasePage: async () => ({ list: [], total: 0 }) }
  }
  const app = build('index.vue', sandbox)
  const state = app.setup()
  assert.equal(state.canDelete, false)
  assert.equal(state.canMore({ sourceType: 'AI', publicationStatus: 'OFFLINE' }), false)
})

test('案例详情：上传人优先展示公司与姓名，同时兼容旧 uploader 字段', () => {
  const sandbox = {
    route: { params: { caseId: '1' }, query: {} },
    router: { push: async () => {} },
    message: { success() {}, error() {} },
    api: { getCase: async () => ({}) }
  }
  const app = build('detail.vue', sandbox)
  const state = app.setup()
  assert.equal(
    state.uploaderText({ uploaderCompany: '众墅总部', uploaderName: '运营甲' }),
    '众墅总部 · 运营甲'
  )
  assert.equal(state.uploaderText({ uploader: '历史上传者' }), '历史上传者')
  assert.equal((app.template.match(/label="上传(?:人|者)"/g) || []).length, 1)
})

test('风格预设改为七个指定风格，含显式自定义入口', () => {
  const sandbox = {
    route: { query: {} },
    router: { replace: async () => {} },
    message: { success() {}, error() {} },
    api: { getCase: async () => ({ version: 1 }) }
  }
  const app = build('create.vue', sandbox)
  const state = app.setup()
  assert.deepEqual(
    Array.from(state.STYLE_PRESETS, (s) => `${s.label}:${s.value}`),
    [
      '新中式:NEW_CHINESE',
      '中式:CHINESE',
      '欧式:EUROPEAN',
      '新亚洲:NEW_ASIAN',
      '宋式:SONG',
      '田园:COUNTRYSIDE',
      '现代:MODERN'
    ]
  )
  assert.match(app.template, /自定义风格/)
  assert.match(app.template, /confirmCustomStyle/)
})

test('风格展示保留历史编码兼容，未映射的自定义风格直接展示原文', () => {
  const { styleText } = loadTsModule('../src/utils/zsFormat.ts')
  assert.equal(styleText('EURO'), '欧式')
  assert.equal(styleText('AMERICAN'), '美式')
  assert.equal(styleText('JAPANESE'), '日式')
  assert.equal(styleText('FRENCH'), '法式')
  assert.equal(styleText('现代轻奢'), '现代轻奢')
})

test('自定义风格：确认后写入表单；哨兵值、空白与超长风格在保存时被拦截', async () => {
  const calls = []
  const messages = []
  const sandbox = {
    route: { query: {} },
    router: { replace: async () => {} },
    message: { success() {}, error: (m) => messages.push(m) },
    api: {
      createCase: async (data) => {
        calls.push(['createCase', data])
        return { caseId: '1', version: 1 }
      },
      getCase: async () => ({ version: 1 })
    }
  }
  const app = build('create.vue', sandbox)
  const state = app.setup()
  state.form.title = '云栖雅院'

  // 进入自定义模式可回填原名，确认后写入表单
  state.form.styleCode = '现代轻奢'
  state.onStyleChange('__custom__')
  assert.equal(state.customStyleMode.value, true)
  state.customStyleText.value = '  新亚洲混搭  '
  state.confirmCustomStyle()
  assert.equal(state.form.styleCode, '新亚洲混搭')
  assert.equal(state.customStyleMode.value, false)

  // 未完成自定义输入（仍是哨兵值）：拦截并提示，不发起创建
  state.onStyleChange('__custom__')
  state.customStyleText.value = ''
  state.form.styleCode = '__custom__'
  await state.save(false)
  assert.equal(messages.at(-1), '请选择风格；选「自定义风格…」后需输入名称并确定')
  assert.equal(calls.length, 0)

  // 超过 32 字符：拦截
  state.form.styleCode = '风'.repeat(33)
  await state.save(false)
  assert.equal(messages.at(-1), '自定义风格不能超过32字符')
  assert.equal(calls.length, 0)

  // 合法自定义风格通过且按原文保存（后端 VARCHAR(32) 无枚举约束）
  state.form.styleCode = '宋式新中式'
  await state.save(false)
  assert.equal(calls.length, 1)
  assert.equal(calls[0][1].styleCode, '宋式新中式')
})
