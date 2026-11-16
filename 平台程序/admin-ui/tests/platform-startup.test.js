const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function execute(file, environment, imports = {}) {
  const source = fs
    .readFileSync(path.join(__dirname, '..', file), 'utf8')
    .replaceAll('import.meta.env', 'testEnv')
  const module = { exports: {} }
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
    }).outputText,
    {
      module,
      exports: module.exports,
      testEnv: environment,
      process,
      require: (name) => {
        if (Object.hasOwn(imports, name)) return imports[name]
        throw new Error(`Unexpected import: ${name}`)
      }
    }
  )
  return module.exports
}

function devConfig(environment) {
  return execute('vite.config.ts', environment, {
    path,
    vite: { loadEnv: () => environment, normalizePath: (value) => value },
    './build/vite': { createVitePlugins: () => [] },
    './build/vite/editor-supply-chain': { editorSupplyChainGate: () => ({}) },
    './build/vite/training-pdf-assets': { trainingPdfAssets: () => ({}) },
    './build/vite/optimize': { include: [], exclude: [] }
  }).default({ command: 'serve', mode: 'env.local' })
}

test('干净B工作树默认代理赋能后端48081，显式代理配置仍生效', () => {
  assert.equal(devConfig({}).server.proxy['/admin-api'].target, 'http://127.0.0.1:48081')
  assert.equal(
    devConfig({ VITE_PROXY_TARGET: 'http://127.0.0.1:49081' }).server.proxy['/admin-api'].target,
    'http://127.0.0.1:49081'
  )
})

test('无私有env文件时API仍走同源admin-api，部署覆盖保持有效', () => {
  assert.equal(execute('src/config/axios/config.ts', {}).config.base_url, '/admin-api')
  assert.equal(
    execute('src/config/axios/config.ts', {
      VITE_BASE_URL: 'https://platform.example.test',
      VITE_API_URL: '/admin-api'
    }).config.base_url,
    'https://platform.example.test/admin-api'
  )
})

test('启动HTML只标识用户选定的赋能管理后台', () => {
  const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8')
  assert.ok(html.includes('<title>管理后台</title>'))
  assert.ok(html.includes('总部运营'))
  assert.equal(html.includes('设计管理后台'), false)
  assert.equal(html.includes('%VITE_APP_TITLE%'), false)
})
