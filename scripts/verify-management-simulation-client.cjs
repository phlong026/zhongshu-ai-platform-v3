const assert = require('node:assert/strict');
const { createClient } = require('../miniprogram/pages/admin/simulation/client');

function device() {
  const storage = new Map(), calls = [];
  let revision = 1, mode = 'ok', commandCalls = 0;
  let actor = { name: '门店老板', role: 'boss', personId: 'boss-person', staffId: 'boss-staff', companyId: 'quanzhou' };
  const snapshot = () => ({ mode: 'simulated-cross-device', revision, actor, tasks: [], management: { period: '2026-10', goals: {}, settings: { version: 0, company: {}, canEdit: true } } });
  const wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: key => storage.delete(key),
    request(options) {
      calls.push(options);
      queueMicrotask(() => {
        if (mode === 'offline') return options.fail({ errMsg: 'request:fail connection refused' });
        if (options.url.endsWith('/sessions')) return options.success({ statusCode: 200, data: { mode: 'simulated-cross-device', accessToken: 'token-' + options.data.inviteCode, actor, expiresAt: new Date(Date.now() + 3600000).toISOString() } });
        if (options.url.includes('/snapshot')) return options.success({ statusCode: 200, data: snapshot() });
        commandCalls += 1;
        if (mode === 'unknown-then-expired') {
          if (commandCalls === 1) return options.fail({ errMsg: 'request:fail response lost after commit' });
          return options.success({ statusCode: 401, data: { error: { code: 'SESSION_EXPIRED', message: '模拟会话已过期' } } });
        }
        revision += 1;
        return options.success({ statusCode: 200, data: { revision, duplicate: commandCalls > 1, result: { id: 'goals:2026-10' } } });
      });
    }
  };
  return { wx, storage, calls, actor: () => actor, setMode: value => { mode = value; }, setActor: value => { actor = value; } };
}

function setData(target, patch) {
  for (const [path, value] of Object.entries(patch)) {
    const parts = path.split('.');
    let cursor = target;
    while (parts.length > 1) {
      const key = parts.shift();
      cursor = cursor[key] || (cursor[key] = {});
    }
    cursor[parts[0]] = value;
  }
}

(async () => {
  const shared = device();
  const first = createClient({ wx: shared.wx, storageKey: 'management-device-one' });
  const second = createClient({ wx: shared.wx, storageKey: 'management-device-two' });
  await first.connect('http://127.0.0.1:8788', 'boss-one');
  assert(first.connected());
  assert(!second.connected(), '自定义存储键必须隔离独立模拟设备');
  await second.connect('http://127.0.0.1:8788', 'boss-two');
  assert.equal(shared.storage.get('management-device-one').token, 'token-boss-one');
  assert.equal(shared.storage.get('management-device-two').token, 'token-boss-two');
  await assert.rejects(() => first.command('/subscriptions', {}), /不支持/);
  console.log('PASS 管理客户端两个实例存储隔离且只允许管理命令入口');

  shared.setMode('offline');
  await assert.rejects(() => first.command('/commands', { kind: 'goals', period: '2026-10', form: {} }), /网络|连接|同步|refused/);
  assert(first.pending(), '断网后必须保留待核实命令');
  const attempts = shared.calls.filter(call => call.url.endsWith('/commands'));
  assert.equal(attempts.length, 2);
  assert.equal(attempts[0].header['Idempotency-Key'], attempts[1].header['Idempotency-Key']);
  shared.setMode('ok');
  const reopened = createClient({ wx: shared.wx, storageKey: 'management-device-one' });
  await reopened.retryPending();
  const retried = shared.calls.filter(call => call.url.endsWith('/commands'));
  assert.equal(retried.at(-1).header['Idempotency-Key'], attempts[0].header['Idempotency-Key']);
  assert(!reopened.pending());
  console.log('PASS 管理命令断网保留原幂等键并可在重开后重试');

  const uncertain = device();
  const originalActor = uncertain.actor();
  const uncertainClient = createClient({ wx: uncertain.wx, storageKey: 'management-uncertain' });
  await uncertainClient.connect('http://127.0.0.1:8788', 'boss-one');
  uncertain.setMode('unknown-then-expired');
  await assert.rejects(() => uncertainClient.command('/commands', { kind: 'goals', period: '2026-10', form: {} }), /过期/);
  const uncertainCalls = uncertain.calls.filter(call => call.url.endsWith('/commands'));
  assert.equal(uncertainCalls.length, 2);
  assert(uncertainClient.pending(), '发生过结果未知后，即使第二次明确过期也必须保留原操作');
  const stored = uncertain.storage.get('management-uncertain');
  stored.expiresAt = new Date(Date.now() - 1000).toISOString();
  uncertain.storage.set('management-uncertain', stored);
  const expiredClient = createClient({ wx: uncertain.wx, storageKey: 'management-uncertain' });
  assert(!expiredClient.connected() && expiredClient.pending(), '页面重开遇到过期 token 仍须保留待核实操作');
  uncertain.setMode('ok');
  uncertain.setActor({ ...originalActor, personId: 'other-person' });
  await assert.rejects(() => expiredClient.connect('http://127.0.0.1:8788', 'other-boss'), /身份|人员/);
  assert(expiredClient.pending(), '不同身份重连不能清除原身份待核实操作');
  uncertain.setActor(originalActor);
  await expiredClient.connect('http://127.0.0.1:8788', 'boss-one');
  assert(expiredClient.pending(), '同一身份重连后须继续持有原幂等键');
  await expiredClient.retryPending();
  const recoveredCalls = uncertain.calls.filter(call => call.url.endsWith('/commands'));
  assert.equal(recoveredCalls.at(-1).header['Idempotency-Key'], uncertainCalls[0].header['Idempotency-Key']);
  assert(!expiredClient.pending());
  console.log('PASS 结果未知跨过期会话保留，且只允许同一身份使用原幂等键重试');

  const pageWx = { getStorageSync: () => null, setStorageSync() {}, removeStorageSync() {}, request() {} };
  global.wx = pageWx;
  let definition;
  global.Page = value => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/pages/admin/simulation/index')];
  require('../miniprogram/pages/admin/simulation/index');
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { setData(this.data, patch); } };
  const pageActor = { name: '门店老板', role: 'boss', personId: 'boss-person', staffId: 'boss-staff', companyId: 'quanzhou' };
  const pageSnapshot = (revision, goalVersion, sales) => ({
    revision, actor: pageActor,
    management: {
      period: '2026-10',
      goals: { sales, collections: 70000, effective: 8, customers: 10, nodes: 6, staff: { one: 1 }, departmentGoals: { business: { commissionAmount: 80000 } }, staffDepartmentGoals: { one: { business: { commissionAmount: 60000 } } }, version: goalVersion },
      settings: { version: 2, canEdit: true, company: { displayName: '泉州店', contact: '赵老板', phone: '13800138000', address: '泉州' } },
      commission: { canEdit: true, projects: [{ id: 'project-one', name: '项目一' }], staff: [{ id: 'sales-one', name: '业务一' }], rows: [] }
    }
  });
  page.accept(pageSnapshot(1, 3, 80000));
  assert.equal(page.data.goalForm.expectedVersion, 3);
  assert.equal(page.data.goalForm.departmentGoals.business.commissionAmount, 80000);
  page.goalInput({ currentTarget: { dataset: { field: 'sales' } }, detail: { value: '99999' } });
  page.companyInput({ currentTarget: { dataset: { field: 'contact' } }, detail: { value: '未保存联系人' } });
  page.commissionInput({ currentTarget: { dataset: { field: 'amount' } }, detail: { value: '1888' } });
  page.accept(pageSnapshot(2, 4, 90000));
  assert.equal(page.data.goalForm.sales, '99999');
  assert.equal(page.data.goalForm.expectedVersion, 3, '轮询不能悄悄把未保存目标草稿升级为新版本');
  assert.equal(page.data.goalForm.staffDepartmentGoals.one.business.commissionAmount, 60000);
  assert.equal(page.data.companyForm.contact, '未保存联系人');
  assert.equal(page.data.commissionForm.amount, '1888');
  delete global.Page;
  delete global.wx;
  console.log('PASS 页面轮询保留未保存草稿及嵌套目标配置');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
