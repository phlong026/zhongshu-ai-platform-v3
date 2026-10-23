const assert = require('assert/strict');
const fs = require('fs');

(async () => {
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const SF = require('../miniprogram/utils/sales-flow');
  const M = require('../miniprogram/pages/admin/model');
  const F = require('../miniprogram/utils/finance-source');
  const T = require('../miniprogram/utils/intercompany');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const login = (id, role) => {
    A.mutate(s => A.loginDemo(s, id));
    if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role));
  };
  const date = A.localDate();
  const file = { name: '银行到账凭据.pdf', sample: true };
  login('boss');
  SF.savePolicy({ scope: '全案设计', total: '100', first: '40', recipient: '众墅之家总部' });
  login('sales-dual', 'sales-lead');
  const customer = B.add({ name: '到账闭环客户', phone: '13900007721', source: '到店', city: '武汉市', summary: '两层住宅' });
  const id = customer.id;
  const raw = () => wx.getStorageSync(B.KEY).customers.find(row => row.id === id);
  B.write(id, row => row.budgets.push({ version: 1, area: 360, total: 380000, parameters: { width: 12, depth: 15, floors: 2, area: 360 }, date }), '保存参考预算', 'budget');
  SF.run(id, 'commission-submit', { text: '两层住宅', scope: '全案设计', total: '100', first: '40', recipient: '众墅之家总部', checked: true });
  login('hq-design-admin');
  assert(!SF.tasks().some(task => task.customerId === id && task.action === '设计完成'));
  assert.equal(SF.next(raw()).waitingFirst, true);
  assert.throws(() => SF.run(id, 'design-complete', { checked: true }), /首款|付款/);
  pass('费用批准后首款未登记时不生成设计待办且不能越级完成');

  const pay = (stage, amount, transaction) => {
    login('finance-wang', 'finance');
    const q = M.load().payables.find(row => row.source?.id === id && row.source.stage === stage);
    return M.mutate(s => M.apply(s, 'pay', q.id, { amount, date, transaction, file, checked: true }));
  };
  pay('first', '40', 'STORE-FIRST');
  assert.equal(T.condition(raw(), 'first').paid, 4000);
  assert.equal(T.condition(raw(), 'first').ready, false, '总部尚未确认到账');
  login('hq-design-admin');
  assert.equal(SF.next(raw()).view, 'complete');
  assert(SF.tasks().some(task => task.customerId === id && task.action === '设计完成'));
  SF.run(id, 'design-complete', { checked: true });
  assert.throws(() => SF.run(id, 'design-dispatch', { checked: true, recipient: '业务', note: '不能提前下发' }), /到账|财务/);
  pass('门店实际登记首款后即可启动设计，无需等待总部确认到账');

  pay('tail', '60', 'STORE-TAIL');
  assert(!T.condition(raw(), 'tail').ready);
  login('hq-finance', 'finance');
  const incoming = stage => M.load().receipts.find(row => row.source?.customerId === id && row.source.stage === stage && row.status === '待登记');
  assert(incoming('first') && incoming('tail'));
  assert.equal(F.tasks(M.load()).filter(row => row.view === 'incoming-register' && row.id.includes(id)).length, 2);
  pass('门店登记付款只生成总部到账确认，不替代实际到账');

  const form = (row, amount, transaction) => ({ amount, date, transaction, file, checked: true, expectedSource: JSON.stringify(row.source), expectedAmount: String(row.amount) });
  const snapshot = () => JSON.stringify(wx.getStorageInfoSync().keys.sort().map(key => [key, wx.getStorageSync(key)]));
  const reject = (row, input, pattern) => {
    const before = snapshot();
    assert.throws(() => M.mutate(s => M.apply(s, 'incoming-register', row.id, input)), pattern);
    assert.equal(snapshot(), before, '拒绝操作不能写入任何存储');
  };
  let row = incoming('first');
  for (const input of [
    { ...form(row, '40', 'BAD-AMOUNT'), amount: '40.01' },
    { ...form(row, '40', 'BAD-PRECISION'), amount: '1.001' },
    { ...form(row, '40', 'BAD-ZERO'), amount: '0' },
    { ...form(row, '40', 'BAD-DATE'), date: '2099-01-01' },
    { ...form(row, '40', 'BAD-FILE'), file: null },
    { ...form(row, '40', 'BAD-CHECK'), checked: false },
    { ...form(row, '40', 'STALE-SOURCE'), expectedSource: '{}' },
    { ...form(row, '40', 'STALE-AMOUNT'), expectedAmount: '1' }
  ]) reject(row, input, /金额|到账|日期|凭|核对|变化|刷新|确认/);
  pass('超额、无效金额、未来日期、缺凭证与过期表单拒绝且无副作用');

  const confirm = (stage, amount, transaction) => {
    login('hq-finance', 'finance');
    const candidate = incoming(stage);
    const data = form(candidate, amount, transaction);
    M.mutate(s => M.apply(s, 'incoming-register', candidate.id, data));
    return { candidate, data };
  };
  const part = confirm('first', '10', 'HQ-FIRST-1');
  assert.equal(T.condition(raw(), 'first').received, 1000);
  assert(!T.condition(raw(), 'first').ready);
  reject(part.candidate, part.data, /重复|办理|登记|变化|刷新/);
  reject(incoming('tail'), form(incoming('tail'), '10', 'HQ-FIRST-1'), /流水|重复/);
  pass('分笔实收按分累计，重复点击和跨期重复银行流水不能再次入账');

  row = incoming('first');
  const rootBefore = wx.getStorageSync(M.KEY);
  const savedSet = wx.setStorageSync;
  const beforeFailure = snapshot();
  let failed = false;
  let saving = false;
  wx.setStorageSync = (key, value) => {
    if (key === M.KEY && saving && !failed) { failed = true; throw Error('模拟保存失败'); }
    return savedSet(key, value);
  };
  try {
    assert.throws(() => M.mutate(s => {
      const result = M.apply(s, 'incoming-register', row.id, form(row, '30', 'ROLLBACK'));
      saving = true;
      return result;
    }), /模拟保存失败/);
  } finally { wx.setStorageSync = savedSet; }
  assert.deepEqual(wx.getStorageSync(M.KEY), rootBefore);
  assert.equal(snapshot(), beforeFailure);
  pass('到账保存失败回滚账簿，不提前触发行政放行');

  row = incoming('first');
  for (const who of ['finance-wang', 'hq-boss', 'hq-design-admin']) {
    login(who);
    reject(row, form(row, '30', 'UNAUTHORIZED-' + who), /授权|财务|权限|组织|原收款/);
  }
  login('hq-finance');
  const current = wx.getStorageSync(B.KEY);
  const stale = B.clone(current);
  stale.customers.find(c => c.id === id).request.version += 1;
  wx.setStorageSync(B.KEY, stale);
  reject(row, form(row, '30', 'OLD-VERSION'), /变化|来源|原收款|办理|版本|刷新/);
  wx.setStorageSync(B.KEY, current);
  pass('门店、老板、行政与旧委托版本不能代总部确认到款');

  confirm('first', '30', 'HQ-FIRST-2');
  confirm('tail', '59.99', 'HQ-TAIL-1');
  login('hq-design-admin');
  assert.equal(SF.next(raw()).actionable, false);
  assert.equal(require('../miniprogram/utils/design-admin-workbench').load().counts['待到账确认'], 1);
  assert.throws(() => SF.run(id, 'design-dispatch', { checked: true, recipient: '业务', note: '还差一分' }), /到账|财务/);
  pass('首尾款独立核对，尾款仍差一分时持续等待且不能下发');

  confirm('tail', '0.01', 'HQ-TAIL-2');
  assert(T.condition(raw(), 'first').ready && T.condition(raw(), 'tail').ready);
  assert.equal(T.condition(raw(), 'tail').received, 6000);
  assert.equal(F.summary(M.load()).income, 10000, '总部实际收款只计一次');
  assert.equal(raw().designPayments.reduce((sum, p) => sum + p.amount, 0), 10000, '总部确认不重复写门店付款');
  assert(!raw().receipts?.length, '总部实收不伪造客户合同收款');
  login('hq-design-admin');
  assert.equal(SF.next(raw()).view, 'dispatch');
  const projected = B.customer(id);
  assert.deepEqual(projected.serviceOrders, []);
  assert.deepEqual(projected.designPayments, []);
  assert(projected.paymentConditions.tail.ready);
  pass('足额确认自动转为行政待下发，保持门店账与总部账及隐私边界');

  const confirmedBook = wx.getStorageSync(M.KEY);
  const changeReceipt = mutate => {
    const root = B.clone(confirmedBook);
    const book = root.companyId === 'headquarters' ? root : root.books.headquarters;
    const receipt = book.receipts.find(r => r.source?.customerId === id && r.source.stage === 'tail' && r.amount === 5999);
    mutate(book, receipt);
    wx.setStorageSync(M.KEY, root);
    assert(!T.condition(raw(), 'tail').ready);
    assert.equal(SF.next(raw()).actionable, false);
    login('hq-finance');
    assert(incoming('tail'), '净实收不足时自动保留差额到款入口');
    wx.setStorageSync(M.KEY, B.clone(confirmedBook));
    login('hq-design-admin');
  };
  changeReceipt((book, receipt) => { receipt.status = '已撤销'; });
  changeReceipt((book, receipt) => { receipt.revisions = [{ amount: 5900 }]; });
  changeReceipt((book, receipt) => { book.refunds.push({ receiptId: receipt.id, status: '已核实', amount: 100 }); });
  pass('撤销、更正和既有退款会重新阻止下发，并生成差额确认事项');

  for (const [key, value] of Object.entries({ fromCompanyId: 'other-store', toCompanyId: 'other-headquarters', serviceVersion: 99, requestVersion: 99, stage: 'first', customerId: id + '-other' })) changeReceipt((book, receipt) => { receipt.source[key] = value; });
  pass('其他门店、收款组织、首尾阶段与费用及需求版本均不能借用到账事实');

  const confirmationVersion = require('../miniprogram/utils/design-receipts').release(raw()).confirmationVersion;
  SF.run(id, 'design-dispatch', { checked: true, recipient: '经办业务', note: '正式版图纸已实际发送' });
  assert.equal(raw().businessFlow.dispatch.financeConfirmation.confirmationVersion, confirmationVersion);
  assert(!/HQ-TAIL|银行到账凭据/.test(confirmationVersion), '下发回执不包含银行流水与凭证');
  assert.throws(() => SF.run(id, 'design-dispatch', { checked: true, recipient: '业务', note: '重复' }), /重复|已下发/);
  login('sales-dual', 'sales-lead');
  assert(SF.tasks().some(t => t.customerId === id && t.action === '收图并移交'));
  pass('正式下发关闭行政动作，自动接续业务收图移交且不能重复下发');

  fs.mkdirSync('qa/design-receipts-20261022', { recursive: true });
  fs.writeFileSync('qa/design-receipts-20261022/results.json', JSON.stringify({ result: 'passed', checks, scope: 'Local isolated state and actual business/finance commands; no external payment' }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
