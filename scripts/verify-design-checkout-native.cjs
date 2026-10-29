const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const out = path.resolve('qa/design-checkout-20261023/native');
const project = path.resolve('.tools/design-checkout-20261023/native-project');
const fixtures = JSON.parse(fs.readFileSync('qa/design-checkout-20261023/initial-state.json'));
const customers = fixtures.find(([key]) => key === 'zhongshu-business-v3')[1].customers.filter(c => /支付闭环|隔离项目/.test(c.name));
assert.equal(customers.length, 2);
for (const c of customers) assert(!c.request && !c.businessFlow, '原生起点不能注入委托或支付结果');
const main = customers.find(c => /微信/.test(c.name)), second = customers.find(c => /支付宝/.test(c.name));
function fingerprint(directory) {
  const walk = rel => fs.readdirSync(path.join(directory, rel), { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.posix.join(rel, e.name)) : [path.posix.join(rel, e.name)]);
  const files = walk('').sort(), hash = crypto.createHash('sha256');
  for (const file of files) hash.update(file + '\0' + crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, file))).digest('hex') + '\n');
  return { files: files.length, sha256: hash.digest('hex') };
}
(async () => {
  const r = await require('./franchise-native-runtime.cjs').connect('design-checkout', { directory: out, wsEndpoint: process.env.FRANCHISE_WS || 'ws://127.0.0.1:10037', realEvents: true });
  const checks = [], screenshots = [], sourceFingerprint = fingerprint(path.join(project, 'miniprogram'));
  const check = name => { checks.push({ name, result: 'passed' }); console.log('PASS ' + name); };
  const event = (selector, method, dataset, detail) => r.event({ selector, dataset }, method, detail);
  const input = async (key, value, tag = 'input') => { await r.until(async () => { r.mp.pageMap?.clear(); const p = await r.mp.currentPage(); for (const e of await p.$$(tag)) if (await e.attribute('data-key') === key) return true; return false; }, 'input '+key, 12000); return event(tag, 'input', { key }, { value }); };
  const checked = key => event('checkbox-group', 'change', { key }, { value: ['yes'] });
  const sample = key => event('button', 'useSampleFile', { sampleKey: key });
  const primary = () => r.event('.commission-action-panel .commission-primary', 'submit');
  const open = (id, view) => r.open('/pages/workflow/commission/index?view=' + view + '&id=' + encodeURIComponent(id), '.commission-page', { relaunch: true });
  const raw = async id => (await r.snapshot()).find(([key]) => key === 'zhongshu-business-v3')[1].customers.find(c => c.id === id);
  const shot = async name => { await r.mp.evaluate(() => { wx.hideToast(); return true; }); await r.sleep(800); screenshots.push(await r.capture(name)); };
  const assertSaved = async (id, predicate, label) => { const c = await raw(id); assert(predicate(c), label + ': ' + (await r.data()).error); };
  const todoClick = async (id, view) => {
    await r.open('/pages/workflow/commission/index?view=admin', '.commission-admin', { relaunch: true });
    const task = (await r.data()).tasks.find(t => t.customerId === id && t.actionUrl.includes('view=' + view));
    assert(task, '总部设计待办 ' + view);
    await event('button', 'openTask', { url: task.actionUrl });
    await r.until(async () => (await r.data()).view === view, view);
    await open(id, view);
  };
  try {
    await r.replace(fixtures);
    await r.login('hq-design-admin');
    await r.open('/pages/profile/index', '.profile-page', { relaunch: true });
    await event('button', 'open', { url: '/pages/workflow/commission/index?view=pricing' });
    await r.until(async () => (await r.data()).view === 'pricing', '计价页面打开', 12000);
    await r.open('/pages/workflow/commission/index?view=pricing', '.commission-page', { relaunch: true });
    await input('rate', '20'); await input('scope', '全套方案设计、立面及施工图', 'textarea'); await input('recipient', '众墅之家总部');
    await primary(); assert.equal((await r.data()).error, '');
    await shot('01-hq-design-pricing'); check('总部设计我的页面配置并保存计价');
    await r.login('sales-dual', 'sales-lead');
    await open(main.id, 'submit');
    assert.equal((await r.data()).pricingQuote.amountCents, 800000);
    await shot('02-area-quote');
    await input('text', '已有宅基地，两层乡墅，老人房与露台按本版预算尺寸设计。', 'textarea');
    await sample('attachment'); await checked('checked'); await primary();
    await assertSaved(main.id, c => c.businessFlow?.paymentOrder?.status === 'pending', '生成待支付订单');
    await r.event('.commission-action-panel .commission-primary', 'submit');
    await r.until(async () => (await r.data()).view === 'pay', '业务继续进入原支付订单');
    await open(main.id, 'pay');
    await event('button', 'simulatePaymentOutcome', { outcome: 'cancelled' });
    await assertSaved(main.id, c => c.businessFlow.paymentOrder.status === 'pending', '取消不推进');
    await event('button', 'selectPaymentChannel', { channel: 'wechat' }); await shot('03-wechat-simulated-checkout'); await primary();
    await assertSaved(main.id, c => c.businessFlow.paymentOrder.status === 'paid' && c.businessFlow.paymentOrder.channel === 'wechat', '微信模拟成功');
    check('400㎡自动计费8000元、取消保留订单、微信一次性模拟支付成功');
    for (const [loginId, label] of [['finance-dual', 'store'], ['hq-finance-dual', 'hq']]) {
      await r.login(loginId, 'finance');
      if (label === 'hq') { const home = (await r.data()).financeDesk; assert(home?.shortcuts?.length); assert(!home.shortcuts.some(row => row.label === '付款登记')); await shot('04-hq-finance-home'); }
      await r.open('/pages/profile/index', '.profile-page', { relaunch: true });
      await event('button', 'open', { action: 'messages' });
      await r.until(async () => (await r.data()).view === 'messages', '财务消息');
      const msg = (await r.data()).messages.find(m => m.objectId === main.id && m.simulated);
      assert(msg && /8,000/.test(msg.description), label + '支付抄送');
      await shot('04-' + label + '-finance-copy');
      await r.event('button.read-all', 'readAll');
      assert((await r.data()).messages.find(m => m.id === msg.id).read);
      const detailView = label === 'store' ? 'project' : 'partner';
      const detailId = label === 'store' ? main.projectId : main.companyId;
      await r.open('/pages/admin/finance-desk/index?view='+detailView+'&id='+encodeURIComponent(detailId), '.fin-page', { relaunch: true });
      const detail = (await r.data())[label === 'store' ? 'detail' : 'partner'];
      const matching = detail.design.filter(row => row.customerId === main.id);
      assert.equal(matching.length, 1); assert(matching[0].simulated && matching[0].readOnly);
      assert.equal(matching[0].label, '一次性设计费（体验模拟）');
      await shot('04-'+label+'-finance-project');

    }
    check('门店和总部财务经消息入口查看同一支付抄送并标记已读');
    await r.login('sales-dual', 'sales-lead'); await open(second.id, 'submit'); await sample('attachment'); await checked('checked'); await primary();
    await open(second.id, 'pay'); await event('button', 'selectPaymentChannel', { channel: 'alipay' });
    await event('button', 'simulatePaymentOutcome', { outcome: 'failed' });
    await assertSaved(second.id, c => c.businessFlow.paymentOrder.status === 'pending', '失败不推进');
    await primary(); await assertSaved(second.id, c => c.businessFlow.paymentOrder.channel === 'alipay' && c.businessFlow.paymentOrder.status === 'paid', '支付宝模拟成功');
    assert.notEqual((await raw(main.id)).businessFlow.paymentOrder.id, (await raw(second.id)).businessFlow.paymentOrder.id);
    check('支付宝失败重试成功，两个客户项目订单相互隔离');
    await r.login('hq-design-admin'); await todoClick(main.id, 'complete'); await input('note', '总部设计与专业审核已完成', 'textarea'); await checked('checked'); await primary();
    await assertSaved(main.id, c => !!c.businessFlow.completed, '设计完成');
    await todoClick(main.id, 'dispatch'); await input('recipient', '李业务'); await input('drawingVersion', 'V1');
    for (const key of ['resultFile', 'drawingFile', 'reviewFile']) await sample(key);
    await event('button.commission-file', 'preview', { key: 'drawingFile' }); assert((await r.data()).preview.text.includes(main.projectId)); await r.event('.commission-mask .commission-primary', 'closePreview');
    await input('note', '正式成果、V1施工图与本版专业审核依据整包下发。', 'textarea'); await checked('checked'); await shot('05-design-files-ready'); await primary();
    await assertSaved(main.id, c => !!c.businessFlow.dispatch?.drawingFile?.meta, '总部上传下发');
    check('总部设计首页连续接续完成与下发待办，三份模拟文件可预览且版本一致');
    await r.login('sales-dual', 'sales-lead'); await r.open('/pages/business/index', '.sales-page', { relaunch: true });
    const businessData = await r.data(), task = businessData.flowPending.find(t => t.customerId === main.id && t.actionable); assert(task);
    if (!businessData.todoRows.some(t => t.id === task.id && t.show)) await r.event('.sales-todo-more', 'toggleTodos');
    await event('button', 'openFlowTask', { id: task.id }); await r.until(async () => (await r.data()).view === 'handover', '业务移交待办'); await open(main.id, 'handover');
    for (const key of ['resultFile', 'drawingFile', 'reviewFile']) assert.equal((await r.data()).form[key].meta.projectId, main.projectId);
    await sample('confirmationFile'); await input('note', '客户确认V1成果，现场边界和道路以本版交底为准。', 'textarea'); await checked('received'); await checked('confirmed'); await shot('06-sales-packet-ready'); await primary();
    await assertSaved(main.id, c => c.handover?.status === 'pending' && c.handover.documents.length === 5, '整包移交');
    check('业务本人待办带入总部三文件，补客户确认及交底后移交五项资料');
    await r.login('engineering-dual', 'project-manager'); await r.open('/pages/build/home/index', '.engineering-home', { relaunch: true });
    const engineeringTask = (await r.data()).eng.tasks.find(t => t.customerId === main.id || t.id === 'handover:' + main.id); assert(engineeringTask);
    await event('button', 'openEngineering', { action: 'todos' });
    await r.until(async () => (await r.current()).route === 'pages/build/projects/index' && (await r.data()).mode === 'tasks', '工程全部待办列表', 12000);
    await r.sleep(500); await event('button', 'open', { url: engineeringTask.url });
    await r.until(async () => (await r.data()).view === 'receive', '工程接收待办'); await open(main.id, 'receive'); await checked('checked'); await primary();
    await assertSaved(main.id, c => !!c.acceptance, '工程接收');
    await r.open('/pages/build/home/index', '.engineering-home', { relaunch: true });
    assert.equal((await r.data()).error || '', '');
    const engineering = (await r.snapshot()).find(([key]) => key === 'zhongshu-engineering-v1')[1].projects.filter(p => p.sourceCustomerId === main.id && p.recordFlow);
    assert.equal(engineering.length, 1); assert.equal(engineering[0].documents.length, 5); assert.equal(engineering[0].projectId, main.projectId);
    assert(!(await raw(second.id)).handover, '不能串入另一个项目'); await shot('07-engineering-created');
    check('工程本人待办接收自动创建唯一同源项目，带入五资料，返回工作台正常');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'passed', scope: '隔离微信开发者工具原生渲染；真实登录、Element.tap/input/trigger(change)；只注入预算起点，无真实扣款', sourceFingerprint, checks, screenshots, interactions: r.interactions, exceptions: r.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'failed', error: error.stack, sourceFingerprint, checks, screenshots, interactions: r.interactions, exceptions: r.errors }, null, 2));
    try { await shot('failure'); } catch (_) {}
    throw error;
  } finally {
    await r.restore();
    const backup = path.join(out, 'design-checkout-before-storage.json'); if (fs.existsSync(backup)) fs.unlinkSync(backup);
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
