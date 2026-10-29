const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { createSimulationServer } = require('../server/design-collaboration/server.cjs');
const TODAY = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10);

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const request = async (base, route, { token, method = 'GET', body, key } = {}) => {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (key) headers['idempotency-key'] = key;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(base + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const json = await response.json();
  return { status: response.status, body: json };
};

async function start(options = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'design-collaboration-'));
  const dataFile = path.join(dir, 'state.json');
  const gatewayCalls = [];
  const gateway = options.gateway || {
    async send(message) {
      gatewayCalls.push(message);
      return { id: `mock-delivery-${gatewayCalls.length}`, status: 'delivered' };
    }
  };
  const server = await createSimulationServer({
    dataFile,
    invites: { finance: 'hq-finance', finance2: 'hq-finance-2', admin: 'hq-design-admin', sales: 'sales-dual', visibleFinance: 'hq-finance-dual' },
    gateway,
    persist: options.persist
  });
  await server.listen();
  const address = server.address();
  return { server, dataFile, dir, gatewayCalls, base: `http://${address.address}:${address.port}` };
}

async function session(env, inviteCode) {
  const result = await request(env.base, '/sim/v1/sessions', { method: 'POST', body: { inviteCode } });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body;
}

async function command(env, token, route, revision, body, key) {
  return request(env.base, route, { token, method: 'POST', key, body: { expectedRevision: revision, ...body } });
}

async function snapshot(env, token) {
  const result = await request(env.base, '/sim/v1/snapshot', { token });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body;
}

async function waitFor(check, label) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    const value = await check();
    if (value) return value;
    await sleep(25);
  }
  throw Error(`等待超时：${label}`);
}

async function confirmAll(env, token, prefix) {
  let view = await snapshot(env, token);
  for (const task of [...view.tasks]) {
    view = await snapshot(env, token);
    const current = view.tasks.find(row => row.stage === task.stage);
    const result = await command(env, token, `/sim/v1/receipts/${encodeURIComponent(current.id)}/confirm`, view.revision, {
      taskVersion: current.taskVersion, amount: String(current.amount / 100), date: TODAY,
      transaction: `${prefix}-${task.stage}`, proofRef: 'mock-bank-receipt', checked: true
    }, `${prefix}-${task.stage}`);
    assert.equal(result.status, 200, JSON.stringify(result.body));
  }
}

(async () => {
  const env = await start();
  try {
    const initial = JSON.parse(fs.readFileSync(env.dataFile, 'utf8'));
    const mainCustomer = initial.storage['zhongshu-business-v3'].customers.find(row => row.id === initial.fixtureCustomerId);
    const budget = mainCustomer.budgets.at(-1);
    assert.equal(budget.area, Number(budget.parameters.width) * Number(budget.parameters.depth) * Number(budget.parameters.floors), '跨会话案例面积与尺寸层数一致');
    assert.equal(String(mainCustomer.width), String(budget.parameters.width));
    assert.equal(String(mainCustomer.depth), String(budget.parameters.depth));
    assert.equal(budget.bodyRows.reduce((sum,row)=>sum+row.value,0), budget.main);
    assert.equal(budget.exteriorRows.reduce((sum,row)=>sum+row.value,0), budget.exterior);
    assert.equal(mainCustomer.designPayments.length,2);
    assert(mainCustomer.designPayments.every(row => row.registrarId === 'finance-dual'), '跨会话案例门店付款仍由同一王财务登记');
    const finance = await session(env, 'finance');
    const finance2 = await session(env, 'finance2');
    const admin = await session(env, 'admin');
    const visibleFinance = await session(env, 'visibleFinance');
    assert.equal(visibleFinance.actor.staffId, 'hq-finance-dual');
    assert.equal(visibleFinance.actor.name, '罗财务');
    assert.equal(visibleFinance.actor.role, 'finance');
    assert.equal(finance.mode, 'simulated-cross-device');
    assert.equal(finance.actor.role, 'finance');
    assert.equal(admin.actor.role, 'hq-design-admin');
    assert.notEqual(finance.accessToken, finance2.accessToken);

    let financeView = await snapshot(env, finance.accessToken);
    assert.equal(financeView.tasks.length, 2, '初始首尾款都应待总部财务确认');
    assert(financeView.tasks.every(task => task.kind === 'receipt' && Number.isInteger(task.amount)));
    let adminView = await snapshot(env, admin.accessToken);
    assert.equal(adminView.tasks.length, 0, '到账前行政没有下发待办');
    assert.equal(adminView.wechat.consent, 'none');
    assert(adminView.recent.some(row => row.action === '已登记设计完成'), '行政最近办理只投影本人真实事件');
    assert.deepEqual(financeView.recent, [], '财务端不推断行政最近办理');

    const first = financeView.tasks.find(task => task.stage === 'first');
    const short = await command(env, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(first.id)}/confirm`, financeView.revision, {
      taskVersion: first.taskVersion, amount: String((first.amount - 1) / 100), date: TODAY, transaction: 'SIM-FIRST-SHORT', proofRef: 'mock-bank-receipt', checked: true
    }, 'short-one-cent');
    assert.equal(short.status, 200);
    financeView = await snapshot(env, finance.accessToken);
    assert.equal(financeView.tasks.find(task => task.stage === 'first').amount, 1, '少一分后应只留下差额待办');

    const remaining = financeView.tasks.find(task => task.stage === 'first');
    const sameCommand = {
      taskVersion: remaining.taskVersion, amount: '0.01', date: TODAY, transaction: 'SIM-FIRST-LAST', proofRef: 'mock-bank-receipt', checked: true
    };
    const sameKeyConcurrent = await Promise.all([
      command(env, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(remaining.id)}/confirm`, financeView.revision, sameCommand, 'first-last-cent'),
      command(env, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(remaining.id)}/confirm`, financeView.revision, sameCommand, 'first-last-cent')
    ]);
    assert(sameKeyConcurrent.every(row => row.status === 200), '同幂等键并发应复用同一结果');
    assert.equal(sameKeyConcurrent.filter(row => row.body.duplicate).length, 1);
    const reused = await command(env, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(remaining.id)}/confirm`, financeView.revision, { ...sameCommand, transaction: 'DIFFERENT' }, 'first-last-cent');
    assert.equal(reused.status, 409, '同一幂等键不能复用不同请求');

    financeView = await snapshot(env, finance.accessToken);
    const tail = financeView.tasks.find(task => task.stage === 'tail');
    const concurrentBody = { taskVersion: tail.taskVersion, amount: String(tail.amount / 100), date: TODAY, transaction: 'SIM-TAIL', proofRef: 'mock-bank-receipt', checked: true };
    const concurrent = await Promise.all([
      command(env, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(tail.id)}/confirm`, financeView.revision, concurrentBody, 'tail-a'),
      command(env, finance2.accessToken, `/sim/v1/receipts/${encodeURIComponent(tail.id)}/confirm`, financeView.revision, concurrentBody, 'tail-b')
    ]);
    assert.deepEqual(concurrent.map(row => row.status).sort(), [200, 409], '同版并发只能有一个事务成功');
    const winner = concurrent.findIndex(row => row.status === 200);

    adminView = await snapshot(env, admin.accessToken);
    assert.equal(adminView.tasks.length, 1);
    assert.equal(adminView.tasks[0].action, 'dispatch');
    assert.equal(adminView.messages.length, 1);
    assert.equal(adminView.wechat.deliveries.length, 0, '未授权时不能标记模拟微信送达');
    assert.equal(adminView.wechat.jobs.length, 1);
    const adminText = JSON.stringify(adminView);
    assert(!/amount|bank|transaction|proof|phone|139\d|SIM-/.test(adminText), '行政快照不得泄露金额、银行、电话微信或流水');

    const forbidden = await command(env, admin.accessToken, `/sim/v1/receipts/${encodeURIComponent(tail.id)}/confirm`, adminView.revision, concurrentBody, 'admin-forbidden');
    assert.equal(forbidden.status, 403);

    const subscribed = await command(env, admin.accessToken, '/sim/v1/subscriptions', adminView.revision, { accepted: true }, 'subscribe-once');
    assert.equal(subscribed.status, 200);
    adminView = await waitFor(async () => {
      const view = await snapshot(env, admin.accessToken);
      return view.wechat.deliveries.some(row => row.status === 'mock-delivered') ? view : null;
    }, '模拟微信投递');
    assert.equal(env.gatewayCalls.length, 1);
    assert.equal(adminView.wechat.mode, 'mock');
    const deliveryPage = adminView.wechat.deliveries[0].page;
    assert.equal(deliveryPage, `/pages/collaboration/index?customerId=${encodeURIComponent(adminView.wechat.deliveries[0].customerId)}`);
    assert(!/token|invite|amount|transaction|proof|bank/i.test(deliveryPage), '投递深链只能携带customerId');

    const message = adminView.messages[0];
    const read = await command(env, admin.accessToken, `/sim/v1/messages/${encodeURIComponent(message.id)}/read`, adminView.revision, {}, 'read-message');
    assert.equal(read.status, 200);
    adminView = await snapshot(env, admin.accessToken);
    assert(adminView.messages[0].read);

    const project = adminView.projects[0];
    const dispatched = await command(env, admin.accessToken, `/sim/v1/commissions/${encodeURIComponent(project.id)}/dispatch`, adminView.revision, { recipient: '经办业务', note: '模拟跨端正式图纸已下发', checked: true }, 'dispatch');
    assert.equal(dispatched.status, 200, JSON.stringify(dispatched.body));
    adminView = await snapshot(env, admin.accessToken);
    assert.equal(adminView.tasks.length, 0);
    assert(adminView.projects[0].dispatchedAt);

    await env.server.close();
    const restarted = await createSimulationServer({ dataFile: env.dataFile, invites: { finance: 'hq-finance', admin: 'hq-design-admin' }, gateway: { async send() { throw Error('不应重复投递'); } } });
    await restarted.listen();
    env.server = restarted;
    const addr = restarted.address();
    env.base = `http://${addr.address}:${addr.port}`;
    const adminAgain = await session(env, 'admin');
    const restored = await snapshot(env, adminAgain.accessToken);
    assert(restored.projects[0].dispatchedAt, '重启应保留业务状态');
    assert(restored.messages[0].read, '重启应保留人员已读状态');
    assert.equal(restored.wechat.deliveries.length, 1, '重启应保留模拟投递记录');
    const persistedDuplicate = await command(env, winner === 0 ? finance.accessToken : finance2.accessToken, `/sim/v1/receipts/${encodeURIComponent(tail.id)}/confirm`, financeView.revision, concurrentBody, `tail-${winner === 0 ? 'a' : 'b'}`);
    assert.equal(persistedDuplicate.status, 200);
    assert.equal(persistedDuplicate.body.duplicate, true, '重启应保留幂等结果');
  } finally {
    await env.server.close().catch(() => {});
  }

  const locked = await start();
  try {
    await assert.rejects(() => createSimulationServer({
      dataFile: locked.dataFile,
      invites: { finance: 'hq-finance' }
    }), /锁|占用|实例/);
  } finally {
    await locked.server.close();
  }

  const staleDir = fs.mkdtempSync(path.join(os.tmpdir(), 'design-collaboration-stale-'));
  const staleFile = path.join(staleDir, 'state.json');
  fs.writeFileSync(`${staleFile}.lock`, JSON.stringify({ pid: 2147483647, nonce: 'a'.repeat(32), createdAt: new Date().toISOString() }), { mode: 0o600 });
  const stale = await createSimulationServer({ dataFile: staleFile, invites: { finance: 'hq-finance' } });
  await stale.close();
  assert(!fs.existsSync(`${staleFile}.lock`), '已确认PID不存在的僵尸锁应安全回收');

  const initDir = fs.mkdtempSync(path.join(os.tmpdir(), 'design-collaboration-init-fail-'));
  const initFile = path.join(initDir, 'state.json');
  await assert.rejects(() => createSimulationServer({
    dataFile: initFile, invites: { finance: 'hq-finance' },
    persist: async () => { throw Error('初始化失败'); }
  }), /初始化|保存/);
  assert(!fs.existsSync(`${initFile}.lock`), '初始化失败必须释放生命周期锁');

  let failAfterWrite = false;
  const uncertain = await start({
    persist: async (state, write) => {
      await write(state);
      if (failAfterWrite) { failAfterWrite = false; throw Error('模拟rename后失败'); }
    }
  });
  try {
    const finance = await session(uncertain, 'finance');
    const view = await snapshot(uncertain, finance.accessToken);
    const task = view.tasks[0];
    const input = { taskVersion: task.taskVersion, amount: String(task.amount / 100), date: TODAY, transaction: 'COMMIT-UNKNOWN', proofRef: 'mock-bank-receipt', checked: true };
    failAfterWrite = true;
    const failed = await command(uncertain, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(task.id)}/confirm`, view.revision, input, 'commit-unknown');
    assert.equal(failed.status, 503);
    assert.equal(failed.body.error.code, 'commit_unknown');
    const after = await snapshot(uncertain, finance.accessToken);
    assert(after.revision > view.revision, '磁盘已提交时内存必须重读并对齐新revision');
    assert.equal(after.tasks.length, view.tasks.length - 1);
    const verified = await command(uncertain, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(task.id)}/confirm`, view.revision, input, 'commit-unknown');
    assert.equal(verified.status, 200);
    assert.equal(verified.body.duplicate, true, 'commit-unknown后同key可核实已提交结果');
  } finally {
    await uncertain.server.close();
  }

  let failNext = false;
  const failedPersist = await start({
    persist: async (state, write) => {
      if (failNext) { failNext = false; throw Error('模拟磁盘失败'); }
      return write(state);
    }
  });
  try {
    const finance = await session(failedPersist, 'finance');
    const view = await snapshot(failedPersist, finance.accessToken);
    const task = view.tasks[0];
    failNext = true;
    const failed = await command(failedPersist, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(task.id)}/confirm`, view.revision, {
      taskVersion: task.taskVersion, amount: String(task.amount / 100), date: TODAY, transaction: 'ROLLBACK', proofRef: 'mock-bank-receipt', checked: true
    }, 'persist-failure');
    assert.equal(failed.status, 500, JSON.stringify(failed.body));
    const after = await snapshot(failedPersist, finance.accessToken);
    assert.equal(after.revision, view.revision, '持久化失败不能发布revision');
    assert.equal(after.tasks.length, view.tasks.length, '持久化失败不能关闭待办');
  } finally {
    await failedPersist.server.close();
  }

  const brokenMessages = await start();
  try {
    const finance = await session(brokenMessages, 'finance');
    await brokenMessages.server.service.mutateFixture(state => {
      state.storage['zhongshu-account-v1'].messages = null;
    }, false);
    const view = await snapshot(brokenMessages, finance.accessToken);
    const task = view.tasks[0];
    const before = brokenMessages.server.service.snapshot();
    const failed = await command(brokenMessages, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(task.id)}/confirm`, view.revision, {
      taskVersion: task.taskVersion, amount: String(task.amount / 100), date: TODAY,
      transaction: 'BROKEN-MESSAGES', proofRef: 'mock-bank-receipt', checked: true
    }, 'broken-messages');
    assert.equal(failed.status, 500, '消息投影异常必须作为服务故障让财务到账事务失败');
    assert.equal(failed.body.error.code, 'notification_projection_failed');
    const after = brokenMessages.server.service.snapshot();
    assert.equal(after.revision, before.revision, '消息投影异常不能发布revision');
    assert.deepEqual(after.storage, before.storage, '消息投影异常不能保存财务原单变更');
    assert.deepEqual(after.idempotency, before.idempotency, '消息投影异常不能保存幂等结果');
    assert.equal((await snapshot(brokenMessages, finance.accessToken)).tasks.length, view.tasks.length, '消息投影异常不能关闭财务待办');
  } finally {
    await brokenMessages.server.close();
  }

  let inactiveCalls = 0;
  const inactive = await start({ gateway: { async send() { inactiveCalls += 1; return { status: 'delivered' }; } } });
  try {
    const finance = await session(inactive, 'finance');
    let admin = await session(inactive, 'admin');
    let adminView = await snapshot(inactive, admin.accessToken);
    assert.equal((await command(inactive, admin.accessToken, '/sim/v1/subscriptions', adminView.revision, { accepted: true }, 'inactive-subscribe')).status, 200);
    await inactive.server.service.mutateFixture(state => {
      const management = state.storage['zhongshu-management-v1'];
      const headquarters = management.companyId === 'headquarters' ? management : management.books.headquarters;
      headquarters.staff.find(person => person.id === 'hq-design-admin').active = false;
    });
    await confirmAll(inactive, finance.accessToken, 'INACTIVE');
    const financeAfter = await snapshot(inactive, finance.accessToken);
    assert.equal(financeAfter.tasks.length, 0, '行政停用不能阻断财务到账原单');
    assert.equal(inactiveCalls, 0, '行政停用时不得向幽灵授权发送');
    assert.equal(inactive.server.service.snapshot().outboxDiagnostics.code, 'recipient_unavailable');
    await inactive.server.service.mutateFixture(state => {
      const management = state.storage['zhongshu-management-v1'];
      const headquarters = management.companyId === 'headquarters' ? management : management.books.headquarters;
      headquarters.staff.find(person => person.id === 'hq-design-admin').active = true;
    });
    admin = await session(inactive, 'admin');
    adminView = await snapshot(inactive, admin.accessToken);
    assert.equal(adminView.tasks.length, 1, '行政恢复后按当前release重新派生待办');
    assert.equal(adminView.wechat.consent, 'none', '恢复后不能沿用停用前授权');
    assert.equal(inactiveCalls, 0);
  } finally {
    await inactive.server.close();
  }

  let rejectedCalls = 0;
  const rejected = await start({ gateway: { async send() { rejectedCalls += 1; return { status: 'delivered' }; } } });
  try {
    const finance = await session(rejected, 'finance');
    const admin = await session(rejected, 'admin');
    let view = await snapshot(rejected, admin.accessToken);
    const accepted = await command(rejected, admin.accessToken, '/sim/v1/subscriptions', view.revision, { accepted: true }, 'accept-then-reject');
    view = await snapshot(rejected, admin.accessToken);
    assert.equal(accepted.status, 200);
    assert.equal((await command(rejected, admin.accessToken, '/sim/v1/subscriptions', view.revision, { accepted: false }, 'reject-before-job')).status, 200);
    await confirmAll(rejected, finance.accessToken, 'REJECTED');
    await sleep(80);
    view = await snapshot(rejected, admin.accessToken);
    assert.equal(view.wechat.consent, 'rejected');
    assert.equal(rejectedCalls, 0, '撤销模拟授权后不得调用网关');
    assert.equal(view.wechat.deliveries.length, 0);
  } finally {
    await rejected.server.close();
  }

  const permanent = await start({ gateway: { async send() { return { status: 'failed', code: 43101, message: '用户拒绝订阅' }; } } });
  try {
    const finance = await session(permanent, 'finance');
    const admin = await session(permanent, 'admin');
    await confirmAll(permanent, finance.accessToken, 'PERMANENT');
    let view = await snapshot(permanent, admin.accessToken);
    assert.equal((await command(permanent, admin.accessToken, '/sim/v1/subscriptions', view.revision, { accepted: true }, 'permanent-subscribe')).status, 200);
    view = await waitFor(async () => {
      const current = await snapshot(permanent, admin.accessToken);
      return current.wechat.jobs.some(job => job.status === 'failed') ? current : null;
    }, '永久错误终止');
    assert.equal(view.wechat.jobs[0].attempts, 1, 'fulfilled永久错误不得当成功或重试');
    assert.equal(view.wechat.deliveries.length, 0);
  } finally {
    await permanent.server.close();
  }

  let failDeliverySave = true;
  const gatewayEvents = new Set();
  let gatewaySideEffects = 0;
  const durableGateway = {
    async send(message) {
      if (gatewayEvents.has(message.eventId)) return { status: 'duplicate' };
      gatewayEvents.add(message.eventId);
      gatewaySideEffects += 1;
      return { status: 'delivered' };
    }
  };
  const interrupted = await start({
    gateway: durableGateway,
    persist: async (state, write) => {
      if (state.deliveries.length && failDeliverySave) { failDeliverySave = false; throw Error('模拟发送后进程中断'); }
      return write(state);
    }
  });
  try {
    const finance = await session(interrupted, 'finance');
    const admin = await session(interrupted, 'admin');
    await confirmAll(interrupted, finance.accessToken, 'INTERRUPTED');
    let view = await snapshot(interrupted, admin.accessToken);
    assert.equal((await command(interrupted, admin.accessToken, '/sim/v1/subscriptions', view.revision, { accepted: true }, 'interrupted-subscribe')).status, 200);
    await waitFor(async () => interrupted.server.service.snapshot().outbox.some(job => job.status === 'sending'), '发送成功但交付状态未保存');
    await interrupted.server.close();
    const restarted = await createSimulationServer({ dataFile: interrupted.dataFile, invites: { admin: 'hq-design-admin' }, gateway: durableGateway });
    interrupted.server = restarted;
    await restarted.listen();
    const address = restarted.address();
    interrupted.base = `http://${address.address}:${address.port}`;
    const adminAgain = await session(interrupted, 'admin');
    view = await waitFor(async () => {
      const current = await snapshot(interrupted, adminAgain.accessToken);
      return current.wechat.deliveries.length ? current : null;
    }, '重启恢复发送状态');
    assert.equal(gatewaySideEffects, 1, 'sending重启只能产生一次网关副作用');
    assert.equal(view.wechat.deliveries[0].status, 'mock-delivered');
  } finally {
    await interrupted.server.close().catch(() => {});
  }

  let gatewayAttempts = 0;
  const flaky = await start({ gateway: { async send() { gatewayAttempts += 1; const error = Error('模拟限流'); error.code = 429; throw error; } } });
  try {
    const finance = await session(flaky, 'finance');
    const admin = await session(flaky, 'admin');
    let view = await snapshot(flaky, finance.accessToken);
    for (const task of [...view.tasks]) {
      view = await snapshot(flaky, finance.accessToken);
      const current = view.tasks.find(row => row.stage === task.stage);
      const result = await command(flaky, finance.accessToken, `/sim/v1/receipts/${encodeURIComponent(current.id)}/confirm`, view.revision, {
        taskVersion: current.taskVersion, amount: String(current.amount / 100), date: TODAY, transaction: `FLAKY-${task.stage}`, proofRef: 'mock-bank-receipt', checked: true
      }, `flaky-${task.stage}`);
      assert.equal(result.status, 200);
    }
    let adminView = await snapshot(flaky, admin.accessToken);
    const subscribed = await command(flaky, admin.accessToken, '/sim/v1/subscriptions', adminView.revision, { accepted: true }, 'flaky-subscribe');
    assert.equal(subscribed.status, 200);
    adminView = await waitFor(async () => {
      const current = await snapshot(flaky, admin.accessToken);
      return current.wechat.jobs.some(job => job.status === 'failed' && job.attempts === 3) ? current : null;
    }, '临时失败有限重试');
    assert.equal(adminView.tasks.length, 1, '通知投递失败不能丢业务待办');
    assert.equal(adminView.wechat.deliveries.length, 0);
    assert(adminView.wechat.jobs.some(job => job.status === 'failed' && job.attempts === 3));
  } finally {
    await flaky.server.close();
  }

  console.log('PASS 模拟跨手机：权威事务、真实域命令、角色隐私、幂等并发、持久化、mock微信授权与失败隔离');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
