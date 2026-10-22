const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MINI_ROOT = path.resolve(__dirname, '../../miniprogram');
const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));

function resolveModule(parentFile, request) {
  if (!request.startsWith('.')) throw Error(`沙箱禁止加载非仓库相对模块：${request}`);
  const base = path.resolve(path.dirname(parentFile), request);
  const candidates = [base, `${base}.js`, `${base}.json`, path.join(base, 'index.js')];
  const found = candidates.find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile());
  if (!found) throw Error(`沙箱模块不存在：${request}`);
  const real = fs.realpathSync(found);
  if (real !== MINI_ROOT && !real.startsWith(MINI_ROOT + path.sep)) throw Error(`沙箱模块越界：${request}`);
  return real;
}

function createRuntime(storage) {
  const cache = new Map();
  const wx = {
    getStorageSync(key) { return copy(storage[key]); },
    setStorageSync(key, value) { storage[key] = copy(value); },
    removeStorageSync(key) { delete storage[key]; },
    getStorageInfoSync() { return { keys: Object.keys(storage) }; }
  };
  const context = vm.createContext({ wx, console: { log() {}, warn() {}, error() {} } });

  function loadFile(filename) {
    if (cache.has(filename)) return cache.get(filename).exports;
    if (filename.endsWith('.json')) {
      const module = { exports: JSON.parse(fs.readFileSync(filename, 'utf8')) };
      cache.set(filename, module);
      return module.exports;
    }
    const module = { exports: {} };
    cache.set(filename, module);
    const source = fs.readFileSync(filename, 'utf8');
    const wrapper = new vm.Script(`(function(exports, require, module, __filename, __dirname) {\n${source}\n})`, { filename });
    const localRequire = request => loadFile(resolveModule(filename, request));
    wrapper.runInContext(context)(module.exports, localRequire, module, filename, path.dirname(filename));
    return module.exports;
  }

  function requireMini(relativePath) {
    const entry = path.resolve(MINI_ROOT, relativePath);
    if (!entry.startsWith(MINI_ROOT + path.sep)) throw Error('沙箱入口越界');
    return loadFile(resolveModule(path.join(MINI_ROOT, '__entry__.js'), `./${relativePath}`));
  }

  function login(userId, preferredRole) {
    const Account = requireMini('utils/account.js');
    Account.mutate(state => Account.loginDemo(state, userId));
    if (preferredRole) {
      const state = Account.load();
      if (state.session.role !== preferredRole) Account.mutate(next => Account.switchIdentity(next, next.session.companyId, preferredRole));
    }
    return requireMini('utils/access.js').context();
  }

  return { requireMini, login, storage };
}

function bootstrapStorage() {
  const storage = {};
  const runtime = createRuntime(storage);
  const A = runtime.requireMini('utils/account.js');
  const B = runtime.requireMini('utils/business.js');
  const SF = runtime.requireMini('utils/sales-flow.js');
  const M = runtime.requireMini('pages/admin/model.js');

  runtime.login('boss');
  SF.savePolicy({ scope: '全案设计', total: '100', first: '40', recipient: '众墅之家总部' });
  runtime.login('sales-dual', 'sales-lead');
  const customer = B.add({
    name: '跨端联调客户', phone: '13900007666', source: '模拟联调', city: '武汉市',
    summary: '模拟两层住宅设计委托，不连接真实收付款或微信服务。'
  });
  B.write(customer.id, row => row.budgets.push({
    version: 1, area: 280, total: 320000,
    parameters: { width: 12, depth: 14, floors: 2, area: 280 }, date: A.localDate()
  }), '保存模拟参考预算', 'budget');
  SF.run(customer.id, 'commission-submit', {
    text: '两层住宅设计联调', scope: '全案设计', total: '100', first: '40',
    recipient: '众墅之家总部', checked: true
  });
  runtime.login('hq-design-admin');
  SF.run(customer.id, 'design-complete', { checked: true, note: '模拟设计及内部审核完成' });

  const file = { name: '模拟门店付款凭据.pdf', sample: true };
  for (const [stage, amount, transaction] of [['first', '40', 'MOCK-STORE-FIRST'], ['tail', '60', 'MOCK-STORE-TAIL']]) {
    runtime.login('finance-wang', 'finance');
    const payable = M.load().payables.find(row => row.source?.id === customer.id && row.source.stage === stage);
    M.mutate(state => M.apply(state, 'pay', payable.id, {
      amount, date: A.localDate(), transaction, file, checked: true
    }));
  }
  return { storage, customerId: customer.id };
}

module.exports = { createRuntime, bootstrapStorage, copy };
