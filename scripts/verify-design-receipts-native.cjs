const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/design-receipts-20261022/native');
const project = path.join(root, '.tools/page-actions-flow-20261022/native-project');

async function fixture() {
  execFileSync(process.execPath, ['scripts/verify-sales-offline-flow.cjs'], { cwd: root, stdio: 'pipe' });
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const SF = require('../miniprogram/utils/sales-flow');
  const M = require('../miniprogram/pages/admin/model');
  const scene = JSON.parse(fs.readFileSync('qa/business-module-simplification-20261021/page-scenes.json')).find(row => row.action === 'design-complete');
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  for (const [key, value] of scene.before) wx.setStorageSync(key, value);
  const login = (id, role) => {
    A.mutate(s => A.loginDemo(s, id));
    if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role));
  };
  login('hq-design-admin');
  SF.run(scene.id, 'design-complete', { checked: true, note: '本地自动化设计完成场景' });
  login('finance-wang', 'finance');
  const payable = M.load().payables.find(row => row.source?.id === scene.id && row.source.stage === 'tail');
  M.mutate(s => M.apply(s, 'pay', payable.id, { amount: String(payable.total / 100), date: A.localDate(), transaction: 'NATIVE-STORE-TAIL', file: { name: '示例付款凭证.pdf', sample: true }, checked: true }));
  const snapshot = () => wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]);
  login('hq-design-admin');
  const awaiting = snapshot();
  login('hq-finance', 'finance');
  const row = M.load().receipts.find(row => row.source?.customerId === scene.id && row.source.stage === 'tail' && row.status === '待登记');
  assert(row);
  return { awaiting, finance: snapshot(), id: scene.id, receiptId: row.id, amount: row.amount };
}

async function main() {
  const source = await fixture();
  fs.mkdirSync(directory, { recursive: true });
  if (!process.env.FRANCHISE_WS) {
    fs.mkdirSync(project, { recursive: true });
    fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), {
      recursive: true,
      filter: (from, to) => fs.statSync(from).isDirectory() || !fs.existsSync(to) || !fs.readFileSync(from).equals(fs.readFileSync(to))
    });
    const config = JSON.parse(fs.readFileSync('project.config.json'));
    config.projectname = 'design-receipts-qa';
    fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
    execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', ['auto', '--project', project, '--auto-port', '9898'], { timeout: 45000, stdio: 'pipe' });
  }
  const runtime = require('./franchise-native-runtime.cjs');
  let r;
  for (let attempt = 0; !r; attempt++) {
    try { r = await runtime.connect('design-receipts', { directory, wsEndpoint: process.env.FRANCHISE_WS || 'ws://127.0.0.1:9898', realEvents: true }); }
    catch (error) { if (attempt === 9) throw error; await runtime.sleep(500); }
  }
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const settle = async () => { await r.sleep(3000); r.mp.pageMap?.clear(); };
  const capture = async name => { await settle(); await r.capture(name); };
  try {
    await r.sleep(2500);
    await r.open('/pages/account/index?view=policy');
    await r.replace(source.awaiting);
    await r.open('/pages/workflow/commission/index?view=admin', '.admin-metrics');
    let data = await r.data();
    assert.equal(data.projectCounts['待到账确认'], 1);
    assert(!data.tasks.some(row => row.customerId === source.id));
    await capture('admin-awaiting-receipt');
    pass('门店已付款但总部未确认时，行政等待且没有下发按钮');

    await r.open('/pages/account/index?view=policy');
    await r.replace(source.finance);
    await r.open('/pages/management/index');
    data = await r.data();
    const task = data.financeDesk.tasks.find(task => task.view === 'incoming-register' && task.id === source.receiptId);
    assert(task);
    await capture('finance-home');
    await (await (await r.mp.currentPage()).$('button[data-url="' + task.url + '"]')).tap();
    await r.until(async () => (await r.data()).view === 'incoming-register', 'finance receipt form');
    await settle();
    data = await r.data();
    assert.equal(data.form.amount, String(source.amount / 100));
    assert(data.form.expectedSource && data.form.expectedAmount);
    const page = await r.mp.currentPage();
    await (await page.$('input[data-key="transaction"]')).input('NATIVE-HQ-TAIL');
    await r.until(async () => (await r.data()).form.transaction === 'NATIVE-HQ-TAIL', 'bank reference input saved');
    await page.setData({ 'form.file': { name: '示例银行到款凭证.pdf', sample: true }, 'form.checked': true });
    await capture('finance-confirm-form');
    const actions = await page.$('.page-actions');
    assert(actions && !['fixed', 'sticky', 'absolute'].includes(await actions.style('position')), '到账确认操作随表单内容滚动');
    await r.mp.pageScrollTo(10000);
    await capture('finance-confirm-actions');
    await (await page.$('.page-actions .solid')).tap();
    await r.until(async () => { const data = await r.data(); if(data.error)throw Error(data.error); return data.view === 'receipt'; }, 'saved receipt');
    await settle();
    const saved = await r.mp.evaluate(id => {
      const root = wx.getStorageSync('zhongshu-management-v1');
      const book = root.companyId === 'headquarters' ? root : root.books.headquarters;
      const row = book.receipts.find(row => row.id === id);
      return { status: row?.status, amount: row?.amount, registrar: row?.financialRegistrarId };
    }, source.receiptId);
    assert.equal(saved.status, '已核算');
    assert.equal(saved.amount, source.amount);
    assert(saved.registrar);
    await capture('finance-confirmed');
    pass('总部从自动待办确认到账，一次保存即记实收，无通知或二次复核按钮');

    await r.open('/pages/management/index');
    assert(!(await r.data()).financeDesk.tasks.some(task => task.id === source.receiptId));
    pass('原到账确认待办自动关闭');

    await r.login('hq-design-admin');
    await r.open('/pages/workflow/commission/index?view=admin', '.admin-metrics');
    data = await r.data();
    assert.equal(data.projectCounts['待到账确认'], 0);
    assert(data.tasks.some(task => task.customerId === source.id && task.actionUrl.includes('view=dispatch')));
    await capture('admin-ready');
    pass('切换行政后自动出现待下发事项，无人工通知步骤');

    await r.open('/pages/account/index?view=messages', '.message');
    const message = (await r.data()).messages.find(message => message.route === 'design-dispatch' && message.objectId === source.id);
    assert(message && !message.read);
    await capture('admin-notification');
    let control;
    for (const item of await (await r.mp.currentPage()).$$('.message')) if (await item.attribute('data-id') === message.id) control = item;
    assert(control);
    await control.tap();
    await r.until(async () => (await r.data()).view === 'dispatch' && (await r.data()).customer?.id === source.id, 'notification opens dispatch');
    await settle();
    await capture('dispatch');
    pass('站内通知自动产生，点击直接进入本项目合法下发页面');

    const dispatch = await r.mp.currentPage();
    await dispatch.setData({ form: { ...(await r.data()).form, recipient: '本地演示经办业务', note: '自动化示例正式图纸下发记录', checked: true } });
    await (await dispatch.$('.commission-actions .commission-primary')).tap();
    await r.until(async () => (await r.data()).view === 'progress' && !(await r.data()).error, 'dispatch registered');
    await r.login('sales-dual', 'sales-lead');
    await r.open('/pages/business/index');
    assert((await r.data()).flowPending.some(task => task.customerId === source.id && task.action === '收图并移交'));
    await capture('sales-handover');
    pass('行政实际下发后，业务收图移交待办自动接续');

    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', checks, exceptions: r.errors, scope: 'Local native simulator and sample bank evidence; no real payment, remote notification or upload' }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'failed', checks, error: error.message, exceptions: r.errors }, null, 2));
    throw error;
  } finally { await r.restore(); }
}
// A failed DevTools startup attempt can leave an SDK socket alive; exit only after main has restored storage and disconnected.
main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });
