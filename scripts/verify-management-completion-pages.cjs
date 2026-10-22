const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const { A, runtime } = await require('./verify-owner-workbench.cjs').fixture();
  const Settings = require('../miniprogram/pages/admin/owner-settings');
  const M = require('../miniprogram/pages/admin/model');
  const Business = require('../miniprogram/pages/admin/owner-business');
  const W = require('../miniprogram/pages/admin/owner-model');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const page = () => getCurrentPages().at(-1);
  const open = url => { wx.reLaunch({ url }); assert(!page().data.error, page().data.error); return page(); };
  const control = async selector => { const result = await (await runtime.currentPage()).$(selector); assert(result, selector); return result; };
  open('/pages/admin/home/index');
  await (await control('.home-six-item')).tap();
  assert.equal(page().data.view, 'metric');
  assert(page().data.rows.length);
  assert(page().data.rows.every(row => row.url));
  pass('首页统计实际点击进入精确指标原单列表');

  open('/pages/admin/performance/index');
  await (await control('[data-department="finance"][bindtap="departmentFilter"]')).tap();
  assert.equal(page().data.desk.performance.selectedDepartment, 'finance');
  assert(page().data.desk.performance.groups.every(group => group.key === 'finance'));
  const person = page().data.desk.performance.groups[0].rows[0];
  await (await control('[data-person="' + person.id + '"][bindtap="togglePerson"]')).tap();
  assert(page().data.desk.performance.groups[0].rows[0].expanded);
  pass('部门筛选与成员原记录展开通过真实控件接通');

  open('/pages/admin/detail/index?view=targets');
  const input = await control('[data-scope="department"][data-department="business"][data-metric="commissionAmount"]');
  await input.input('56000');
  await (await control('[bindtap="savePerformanceGoals"]')).tap();
  assert.equal(page().data.error, '');
  assert.equal(W.snapshot().goals.departmentGoals.business.commissionAmount, 56000);
  pass('部门目标表单保存后读取同月原目标版本');

  open('/pages/admin/detail/index?view=company');
  for (const [field, value] of Object.entries({ displayName: '管理验证门店', contact: '测试联系人', phone: '13900007802', address: '武汉市管理验证地址' })) {
    await (await control('.management-company [data-field="' + field + '"]')).input(value);
  }
  await (await control('[bindtap="saveCompany"]')).tap();
  assert.equal(page().data.error, '');
  assert.equal(Settings.load(M.load()).company.displayName, '管理验证门店');
  open('/pages/admin/home/index');
  assert.equal(page().data.desk.company, '管理验证门店');
  pass('门店资料实际编辑保存且管理页统一显示新名称');

  const receivable = Business.rows('receivables', W.snapshot())[0];
  assert(receivable);
  open(receivable.url);
  let clipboard = '';
  wx.setClipboardData = options => { clipboard = options.data; options.success?.({}); };
  await (await control('[bindtap="copyReminder"]')).tap();
  assert(clipboard.includes('40000') || clipboard.includes('40,000'));
  const due = page().data.record.due;
  assert(clipboard.includes(due.slice(0,4)+'年'+Number(due.slice(5,7))+'月'+Number(due.slice(8,10))+'日'));
  pass('催款复制使用当前原单金额和约定日期');

  A.mutate(state => A.switchIdentity(state, state.session.companyId, 'owner'));
  open('/pages/admin/detail/index?view=company');
  assert.equal(await (await runtime.currentPage()).$('[bindtap="saveCompany"]'), null);
  page().saveCompany();
  assert(page().data.error.includes('老板'));
  pass('门店负责人只读，不能通过调用保存绕过老板权限');
  const out = 'qa/management-completion-20261023';
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(out + '/page-results.json', JSON.stringify({ result: 'passed', checks }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
