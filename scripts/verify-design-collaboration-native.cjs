const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const net = require('net');
const { execFileSync, fork } = require('child_process');
const crypto = require('crypto');
const { createSimulationServer } = require('../server/design-collaboration/server.cjs');
const runtime = require('./franchise-native-runtime.cjs');

const root = path.resolve(__dirname, '..');
const out = path.join(root, 'qa/design-collaboration-20261023/native');
const privateRoot = path.join(root, '.tools/design-collaboration-native-20261023');
const cli = process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli';
const KEY = 'zhongshu-collaboration-client-v1';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer();
  server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});

async function phone(name) {
  const project = path.join(privateRoot, name), port = await freePort();
  fs.mkdirSync(project, { recursive: true });
  fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), { recursive: true });
  const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
  config.projectname = 'collaboration-' + name;
  config.setting = { ...config.setting, urlCheck: false };
  fs.writeFileSync(path.join(project, 'project.private.config.json'), JSON.stringify({ setting: { urlCheck: false } }));
  fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
  execFileSync(cli, ['auto', '--project', project, '--auto-port', String(port)], { timeout: 45000, stdio: 'pipe' });
  let result;
  for (let attempt = 0; !result; attempt++) {
    try { result = await runtime.connect(name, { directory: path.join(out, name), wsEndpoint: 'ws://127.0.0.1:' + port, realEvents: true }); }
    catch (error) { if (attempt === 9) throw error; await sleep(600); }
  }
  await result.open('/pages/collaboration/index', '.connect');
  await result.sleep(3000);
  return result;
}

async function input(r, selector, value, ready) {
  r.mp.pageMap?.clear();
  const field = await (await r.mp.currentPage()).$(selector);
  assert(field, selector);
  await field.input(value);
  await r.until(async () => ready(await r.data()), selector + ' input');
}

async function tap(r, selector) {
  r.mp.pageMap?.clear();
  const control = await (await r.mp.currentPage()).$(selector);
  assert(control, selector);
  await control.tap();
  await r.sleep(300);
}

async function capture(r, name) { await r.sleep(2000); await r.capture(name); }

async function connect(r, base, code, role) {
  await input(r, 'input[data-key="endpoint"]', base, data => data.endpoint === base);
  await input(r, 'input[data-key="inviteCode"]', code, data => data.inviteCode === code);
  await tap(r, '.connect');
  await r.until(async () => { const data = await r.data(); if (data.error) throw Error(data.error); return data.connected && data.actor.role === role; }, 'server session ' + role, 12000);
  await r.sleep(1500);
}

async function checked(r) {
  const group = await (await r.mp.currentPage()).$('.check');
  assert(group);
  await group.trigger('change', { value: ['checked'] });
  await r.until(async () => (await r.data()).form.checked, 'checkbox event');
}

function financeProcess(file) {
  const child = fork(path.join(__dirname, 'design-collaboration-client-process.cjs'), [file], { stdio: ['ignore', 'ignore', 'inherit', 'ipc'] });
  const pending = new Map();
  let sequence = 0;
  child.on('message', message => { const task = pending.get(message.id); if (!task) return; clearTimeout(task.timer); pending.delete(message.id); message.error ? task.reject(Error(message.error)) : task.resolve(message.value); });
  return { call(action, args = {}) { return new Promise((resolve, reject) => { const id = ++sequence; const timer = setTimeout(() => { pending.delete(id); reject(Error('Finance client timed out: ' + action)); }, 20000); pending.set(id, { resolve, reject, timer }); child.send({ id, action, ...args }); }); } };
}

async function confirm(finance, stage) {
  const view = await finance.call('refresh');
  const task = view.tasks.find(row => row.stage === stage);
  assert(task);
  const result = await finance.call('command', { path: '/receipts/' + encodeURIComponent(task.id) + '/confirm', input: { taskVersion: task.taskVersion, amount: String(task.amount / 100), date: new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10), transaction: 'NATIVE-CROSS-' + stage.toUpperCase(), proofRef: 'mock-bank-receipt', checked: true } });
  assert(result.synchronized);
}
async function main() {
  fs.mkdirSync(out, { recursive: true });
  fs.mkdirSync(privateRoot, { recursive: true });
  const dataFile = path.join(privateRoot, 'state-' + Date.now() + '.json');
  const server = await createSimulationServer({ dataFile, invites: { 'native-finance-test': 'hq-finance-dual', 'native-admin-test': 'hq-design-admin' } });
  await server.listen();
  const address = server.address(), base = 'http://127.0.0.1:' + address.port;
  const phones = [], checks = [], pass = name => { checks.push(name); console.log('PASS ' + name); };
  let finance, admin;
  try {
    finance = financeProcess(path.join(privateRoot, 'finance-storage-' + Date.now() + '.json'));
    admin = await phone('admin-phone'); phones.push(admin);
    const unchanged = async r => r.mp.evaluate(key => wx.getStorageInfoSync().keys.filter(item => item.startsWith('zhongshu-') && item !== key).sort().map(item => [item, wx.getStorageSync(item)]), KEY);
    const beforeAdmin = await unchanged(admin);
    await finance.call('connect', { base, code: 'native-finance-test' });
    await connect(admin, base, 'native-admin-test', 'hq-design-admin');
    const financialToken = await finance.call('fingerprint');
    const administrativeToken = await admin.mp.evaluate(key => wx.getStorageSync(key).token, KEY);
    assert.notEqual(financialToken, crypto.createHash('sha256').update(administrativeToken).digest('hex'));
    const financeIdentity = (await finance.call('snapshot')).actor;
    assert.equal(financeIdentity.staffId, 'hq-finance-dual');
    assert.equal(financeIdentity.name, '罗财务');
    assert.equal((await admin.data()).actor.staffId, 'hq-design-admin');
    assert.equal((await finance.call('snapshot')).tasks.length, 2);
    assert.equal((await admin.data()).tasks.length, 0);
    const [banner] = await admin.rects(['.simulation-note']);
    const windowInfo = await admin.mp.evaluate(() => getApp().globalData.windowInfo || wx.getSystemInfoSync());
    assert(banner.top <= windowInfo.statusBarHeight + 87 * windowInfo.windowWidth / 750 + 30, '页头占位只计算一次');
    await capture(admin, 'admin-before-receipt');
    pass('独立财务客户端进程与原生行政模拟器分别取得服务端会话');

    await tap(admin, '.workspace-tabs button[data-tab="mine"]');
    await tap(admin, '.subscribe');
    await admin.until(async () => (await admin.data()).consent === 'accepted', 'mock consent');
    await tap(admin, '.workspace-tabs button[data-tab="home"]');
    await confirm(finance, 'first');
    await admin.sleep(2000);
    assert.equal((await admin.data()).tasks.length, 0);
    assert.equal((await admin.data()).deliveries.length, 0);
    pass('仅首款确认时行政继续等待，模拟微信不提前投递');

    await confirm(finance, 'tail');
    await admin.until(async () => { const data = await admin.data(); return data.tasks.some(row => row.action === 'dispatch') && data.messages.length === 1 && data.deliveries.length === 1; }, 'automatic cross-device todo and mock delivery', 15000);
    await capture(admin, 'admin-auto-ready');
    assert.equal((await admin.data()).unread, 1);
    const privateProjection = await admin.mp.evaluate(key => JSON.stringify(wx.getStorageSync(key).snapshot), KEY);
    assert(!/"amount"|bank|transaction|proof|phone|1390000/.test(privateProjection), '行政本机只缓存本角色投影');
    pass('财务足额确认后，行政无手工刷新自动获得待办、未读消息和模拟微信通知');

    await tap(admin, '.messages-entry');
    await capture(admin, 'mock-wechat-inbox');
    await tap(admin, '.mock-delivery');
    await admin.until(async () => (await admin.data()).screen === 'project' && (await admin.data()).activeProject?.action === 'dispatch', 'mock notification opens dispatch');
    const openedCustomer = await admin.mp.evaluate(() => getCurrentPages().at(-1).options.customerId);
    assert.equal(openedCustomer, (await admin.data()).activeProject.id, '使用网关实际深链打开授权项目');
    await admin.sleep(1200);
    await input(admin, 'input[data-key="recipient"]', '李业务', data => data.form.recipient === '李业务');
    await input(admin, 'textarea[data-key="note"]', '示例正式施工图及交付清单已通过模拟渠道下发', data => data.form.note.includes('示例正式施工图'));
    await checked(admin);
    await admin.mp.pageScrollTo(10000);
    const [actions, check] = await admin.rects(['.page-actions', '.check']);
    assert(actions.top >= check.bottom, '下发按钮必须跟随内容，不覆盖确认区域');
    await capture(admin, 'dispatch-form');
    await tap(admin, '.submit');
    await admin.until(async () => { const data = await admin.data(); if(data.error)throw Error(data.error); return data.screen === 'workspace' && data.tasks.length === 0 && data.projects.some(row => row.dispatchedAt); }, 'dispatch saved', 12000);
    const dispatchedId = (await admin.data()).projects.find(row => row.dispatchedAt)?.id;
    const durable = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
    assert(Object.values(durable.storage).some(value => value?.customers?.some(row => row.id === dispatchedId && row.businessFlow?.dispatch?.at)), '下发事实必须保存到服务端原单');
    assert.equal((await finance.call('snapshot')).tasks.length, 0, '财务已确认事项保持已办，财务无客户图纸读取权限');
    await capture(admin, 'admin-dispatched');
    pass('点击模拟微信通知可办理下发并保存服务端原单，双方待办自动更新');

    assert.deepEqual(await unchanged(admin), beforeAdmin, '行政端未修改原本地业务账簿');
    assert.equal(admin.errors.length, 0, JSON.stringify(admin.errors));
    pass('原本地数据保持不变，行政缓存不含财务敏感信息，原生无异常');
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'passed', checks, actors:{finance:'hq-finance-dual',designAdmin:'hq-design-admin',recipient:'sales-dual'}, sessionsIndependent: true, localDomainStorageUnchanged: true, exceptions: [], scope: 'One isolated native administrative simulator plus one separate finance client process using real HTTP and a mock WeChat gateway; no actual handset or WeChat delivery' }, null, 2));
  } catch (error) {
    for (const [index, r] of phones.entries()) {
      try { await capture(r, 'failure'); const data = await r.data(); fs.writeFileSync(path.join(out, 'failure-' + index + '.json'), JSON.stringify({ screen: data.screen, error: data.error, connected: data.connected, role: data.actor?.role, tasks: data.tasks?.length }, null, 2)); } catch (_) {}
    }
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'failed', checks, error: error.message, scope: 'Native simulators and mock WeChat only' }, null, 2));
    throw error;
  } finally {
    const restorations = [];
    for (const r of phones) {
      try { await r.restore(); restorations.push({ directory: path.basename(r.directory), restored: true }); }
      catch (error) { restorations.push({ directory: path.basename(r.directory), restored: false, error: error.message }); }
    }
    if (finance) await finance.call('stop').catch(() => {});
    await server.close();
    fs.writeFileSync(path.join(out, 'restored.json'), JSON.stringify(restorations, null, 2));
    assert(restorations.every(row => row.restored), 'Native test storage must be restored');
  }
}

// DevTools may keep a socket from a failed startup attempt; exit only after awaited restoration.
main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });
