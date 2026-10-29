const assert = require('assert/strict');
const { createClient, normalizeBaseUrl } = require('../miniprogram/pages/collaboration/client');

function device() {
  const storage = new Map(), calls = [];
  const held = [];
  let revision = 1, mode = 'ok', commandCount = 0;
  const actor = { name: '模拟行政', role: 'hq-design-admin', personId: 'admin', companyId: 'headquarters', staffId: 'admin' };
  const snapshot = () => ({ mode: 'simulated-cross-device', revision, actor, projects: [], tasks: [], messages: [], wechat: { mode: 'mock', consent: 'none', deliveries: [], jobs: [] } });
  const wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: key => storage.delete(key),
    request(options) {
      calls.push(options);
      queueMicrotask(() => {
        if (mode === 'offline') return options.fail({ errMsg: 'request:fail connection refused' });
        if (options.url.endsWith('/sessions')) return options.success({ statusCode: 200, data: { accessToken: 'mock-session-token', expiresAt: new Date(Date.now() + 3600000).toISOString(), mode: 'simulated-cross-device', actor, revision } });
        if (options.url.includes('/snapshot')) {
          if (mode === 'hold-refresh') return held.push({ options, data: snapshot() });
          return options.success({ statusCode: 200, data: snapshot() });
        }
        if (mode === 'forbidden') return options.success({ statusCode: 403, data: { error: { code: 'FORBIDDEN', message: '没有本项目权限' } } });
        commandCount += 1;
        revision += 1;
        if (mode === 'lost-once') { mode = 'ok'; return options.fail({ errMsg: 'request:fail timeout' }); }
        options.success({ statusCode: 200, data: { revision, duplicate: commandCount > 1, result: 'saved' } });
      });
      return { abort() {} };
    }
  };
  return { wx, calls, storage, actor, setMode: value => { mode = value; }, release: () => { const next = held.shift(); assert(next); next.options.success({ statusCode: 200, data: next.data }); } };
}

(async () => {
  assert.equal(normalizeBaseUrl(' http://127.0.0.1:8787/ '), 'http://127.0.0.1:8787');
  for (const url of ['file:///tmp', 'https://user:pass@example.test', 'http://localhost/a', 'http://localhost?token=x']) assert.throws(() => normalizeBaseUrl(url));
  const first = device(), second = device();
  const a = createClient({ wx: first.wx, pollInterval: 10 }), b = createClient({ wx: second.wx, pollInterval: 10 });
  await a.connect('http://127.0.0.1:8787', 'mock-admin-invite');
  assert(a.connected());
  assert(!b.connected(), '另一手机不能共享本机登录或数据');
  assert.equal(a.snapshot().actor.personId, first.actor.personId);
  assert.equal(first.calls[0].data.inviteCode, 'mock-admin-invite');
  assert(first.calls[1].header.Authorization.startsWith('Bearer '));
  console.log('PASS 服务端会话、独立本地存储与只读作用域快照');

  first.setMode('lost-once');
  await a.command('/commissions/mock-customer/dispatch', { checked: true, recipient: '模拟业务', note: '示例图纸已下发' });
  const requests = first.calls.filter(call => call.url.endsWith('/dispatch'));
  assert.equal(requests.length, 2);
  assert.equal(requests[0].header['Idempotency-Key'], requests[1].header['Idempotency-Key']);
  assert.deepEqual(requests[0].data, requests[1].data);
  console.log('PASS 结果未知时使用相同幂等键和原请求重试');

  first.setMode('forbidden');
  await assert.rejects(() => a.command('/commissions/other/dispatch', { checked: true }), /没有本项目权限/);
  assert.equal(first.calls.filter(call => call.url.endsWith('/other/dispatch')).length, 1, '明确拒绝不得重试');
  first.setMode('offline');
  await assert.rejects(() => a.command('/commissions/mock-customer/complete', { checked: true }), /未连接|连接|网络|同步|timeout|refused/);
  assert.equal(a.snapshot().tasks.length, 0, '网络失败不能本地伪造已办结果');
  assert(a.pending(), '结果未知的操作保留到核实完成');
  const offline = first.calls.filter(call => call.url.endsWith('/complete'));
  const recovered = createClient({ wx: first.wx });
  assert(recovered.pending(), '页面重开须恢复待核实操作');
  first.setMode('ok');
  await recovered.retryPending();
  const retried = first.calls.filter(call => call.url.endsWith('/complete'));
  assert.equal(retried.at(-1).header['Idempotency-Key'], offline[0].header['Idempotency-Key']);
  assert(!recovered.pending());
  console.log('PASS 权限拒绝与断网明确失败，不回落本地保存');

  first.setMode('ok');
  let updates = 0;
  a.start(() => { updates += 1; });
  await new Promise(resolve => setTimeout(resolve, 70));
  a.stop();
  assert(updates > 0, '前台自动轮询必须更新页面');
  const stoppedAt = first.calls.length;
  await new Promise(resolve => setTimeout(resolve, 40));
  assert.equal(first.calls.length, stoppedAt, '退出页面后停止轮询');
  a.disconnect();
  assert(!createClient({ wx: first.wx }).connected(), '退出后不恢复过期会话');
  console.log('PASS 自动轮询、停止与退出清理');

  const overlap = device(), c = createClient({ wx: overlap.wx });
  await c.connect('http://127.0.0.1:8787', 'mock-admin-invite');
  overlap.setMode('hold-refresh');
  const oldPull = c.refresh();
  const saved = c.command('/commissions/mock-customer/dispatch', { checked: true });
  await new Promise(resolve => setImmediate(resolve));
  overlap.setMode('ok');
  overlap.release();
  await oldPull;
  const result = await saved;
  assert(result.synchronized);
  assert(c.snapshot().revision >= result.revision, '提交后缓存必须追平响应版本，不能复用旧轮询误报同步');
  console.log('PASS 提交与旧轮询重叠后重新读取，达到已保存版本才报同步');

  overlap.setMode('hold-refresh');
  let lateUpdates = 0;
  c.start(() => { lateUpdates += 1; });
  await new Promise(resolve => setImmediate(resolve));
  c.stop();
  overlap.setMode('ok');
  await c.connect('http://127.0.0.1:8787', 'mock-admin-invite');
  await c.refresh();
  overlap.release();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(lateUpdates, 0, '退出时在途请求不能通知旧页面，也不能堵住重连的刷新');
  c.disconnect();
  console.log('PASS 在途轮询退出和重连隔离，旧响应不影响新会话');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
