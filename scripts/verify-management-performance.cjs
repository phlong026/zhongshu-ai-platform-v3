const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));

async function main() {
  const { A, M, W, period } = await require('./verify-owner-workbench.cjs').fixture();
  const Performance = require('../miniprogram/pages/admin/owner-performance');
  const Settings = require('../miniprogram/pages/admin/owner-settings');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };

  let snapshot = W.snapshot();
  const dual = { id: 'performance-dual', name: '跨部门兼岗', active: true, roleId: 'sales', roles: ['sales', 'engineer'] };
  snapshot = {
    ...snapshot,
    staff: [...snapshot.staff, dual],
    goals: {
      ...snapshot.goals,
      version: 3,
      staff: { ...(snapshot.goals.staff || {}), [dual.id]: 12 },
      departmentGoals: {
        sales: { commissionAmount: 200000 },
        operations: { activityTotal: 20 },
        design: { approvedDeliverables: 10 },
        engineering: { completedNodes: 20 }
      },
      staffDepartmentGoals: {
        [dual.id]: {
          sales: { commissionAmount: 100000 },
          operations: { activityTotal: 8 },
          design: { approvedDeliverables: 4 },
          engineering: { completedNodes: 8 }
        }
      }
    },
    state: {
      ...snapshot.state,
      sources: [
        ...snapshot.state.sources,
        { id: 'perf-commission:dual:1', kind: 'contract', person: dual.id, name: '设计委托', customer: '甲客户', phone: '13900000000', date: period + '-03', amount: 3000000, url: '/pages/workflow/commission/index?id=dual' },
        { id: 'perf-node:dual:1', kind: 'node', person: dual.id, name: '主体节点', customer: '乙客户', date: period + '-04', completed: true, onTime: true, first: true, url: '/pages/build/index?id=dual' }
      ]
    }
  };

  const model = Performance.build(snapshot, 'all', dual.id);
  assert.deepEqual(model.filterOptions.map(row => row.id), ['all', 'business', 'engineering', 'finance']);
  assert.equal(model.departmentCards.find(card => card.key === 'business').metrics.length, 9);
  assert(model.departmentCards.filter(card => card.key !== 'business').every(card => card.metrics.length === 3));
  const dualRows = model.groups.flatMap(group => group.rows).filter(row => row.id === dual.id);
  assert.deepEqual(dualRows.map(row => row.department).sort(), ['business', 'engineering']);
  assert.equal(dualRows.find(row => row.department === 'business').metrics.find(row => row.id === 'commissionAmount').target, 100000);
  assert.equal(dualRows.find(row => row.department === 'business').metrics.find(row => row.id === 'activityTotal').target, 8);
  assert.equal(dualRows.find(row => row.department === 'business').metrics.find(row => row.id === 'approvedDeliverables').target, 4);
  assert.equal(dualRows.find(row => row.department === 'engineering').metrics.find(row => row.id === 'completedNodes').target, 8);
  assert(dualRows.every(row => row.recentRecords.every(record => !Object.hasOwn(record, 'phone'))));
  assert(dualRows.flatMap(row => row.recentRecords).every(record => record.url));
  pass('业务九指标统一展示、专业兼岗隔离且原记录不泄露客户对象');

  const legacySnapshot = clone(snapshot);
  legacySnapshot.goals.staffDepartmentGoals = {};
  const legacy = Performance.build(legacySnapshot, 'all', dual.id).groups.flatMap(group => group.rows).filter(row => row.id === dual.id);
  const legacySales = legacy.find(row => row.department === 'business');
  const legacyEngineering = legacy.find(row => row.department === 'engineering');
  assert.equal(legacySales.metrics.find(row => row.id === 'commissionAmount').target, 12);
  assert.equal(legacyEngineering.metrics.find(row => row.id === 'completedNodes').target, null);
  pass('旧个人标量映射统一业务主指标且不串到工程兼岗');

  const filtered = Performance.build(snapshot, 'engineering', dual.id);
  assert.deepEqual(filtered.groups.map(group => group.key), ['engineering']);
  assert(filtered.groups[0].rows.find(row => row.id === dual.id).recentRecords.some(row => row.sourceId === 'perf-node:dual:1'));
  assert(!JSON.stringify(filtered).includes('13900000000'));
  pass('部门筛选和展开记录只返回当前部门可追溯来源');

  const form = Performance.formFor(snapshot);
  const settingsView = Performance.settingsFor(snapshot);
  assert.equal(settingsView.departments.length, 3);
  assert(settingsView.staff.find(person => person.id === dual.id).departments.length === 2);
  form.expectedVersion = snapshot.goals.version;
  form.departmentGoals.business.commissionAmount = '250000.50';
  form.staffDepartmentGoals[dual.id].engineering.completedNodes = '9';
  const goals = Performance.validateGoals(snapshot, form);
  assert.equal(goals.departmentGoals.business.commissionAmount, 250000.5);
  assert.equal(goals.staffDepartmentGoals[dual.id].engineering.completedNodes, 9);
  assert.throws(() => Performance.validateGoals(snapshot, { ...form, expectedVersion: 2 }), /目标已更新/);
  const archived = { ...snapshot, state: { ...snapshot.state, archives: [...snapshot.state.archives, { id: 'closed', period, status: '已归档', dynamic: true }] } };
  const archivedChange = clone(form);
  archivedChange.departmentGoals.business.commissionAmount = '250001';
  assert.throws(() => Performance.validateGoals(archived, archivedChange), /已归档/);
  assert.throws(() => Performance.validateGoals(snapshot, { ...form, departmentGoals: { business: { imaginaryRate: '90' } } }), /不支持的绩效指标/);
  const performancePage = fs.readFileSync('miniprogram/pages/admin/performance/index.wxml', 'utf8');
  const settingsTemplate = fs.readFileSync('miniprogram/pages/admin/performance/settings.wxml', 'utf8');
  assert(performancePage.includes('bindtap="departmentFilter"') && performancePage.includes('bindtap="togglePerson"') && performancePage.includes('catchtap="openSource"'));
  assert(settingsTemplate.includes('bindinput="goalFieldInput"') && settingsTemplate.includes('bindtap="savePerformanceGoals"'));
  pass('目标校验保留版本、归档等价迁移和固定白名单边界');

  const ownership = Performance.build({ ...snapshot, staff: snapshot.staff.map(row => row.id === dual.id ? { ...row, name: '新责任人姓名' } : row) }, 'business', dual.id);
  const commission = ownership.groups[0].rows.find(row => row.id === dual.id).recentRecords.find(row => row.sourceId === 'perf-commission:dual:1');
  assert(commission, '发生时责任来源仍按 source.person 归属');
  pass('历史绩效按来源责任人读取，不用当前展示负责人改写');

  let state = clone(M.load());
  A.mutate(account => A.loginDemo(account, 'store-owner'));
  const readable = Settings.load({ ...snapshot, actor: require('../miniprogram/utils/access').context(), state });
  assert.equal(readable.company.companyId, state.companyId);
  assert.equal(readable.canEdit, false);
  assert.throws(() => Settings.apply(state, 'management-company', state.companyId, { expectedVersion: 0, displayName: '新店名', contact: '赵老板', phone: '13800000000', address: '武汉市测试路1号' }), /老板/);
  pass('门店负责人可读配置但不可写');

  A.mutate(account => A.loginDemo(account, 'boss'));
  state = clone(M.load());
  const initial = Settings.load(state);
  const companyResult = Settings.apply(state, 'management-company', state.companyId, { expectedVersion: initial.version, displayName: '湖北乡墅体验店', contact: '赵老板', phone: '13800000000', address: '武汉市测试路1号' });
  assert.equal(companyResult.id, state.companyId);
  assert.equal(state.managementSettings.version, initial.version + 1);
  assert.equal(state.managementSettings.history.length, initial.version ? 1 : 0);
  assert.equal(state.managementSettings.events.at(-1).action, 'management-company');
  if (state.managementSettings.history.length) assert(state.managementSettings.history.at(-1).actorId);
  assert.throws(() => Settings.apply(state, 'management-company', state.companyId, { expectedVersion: initial.version, displayName: '过期提交', contact: '赵老板', phone: '13800000000', address: '武汉市测试路1号' }), /配置已更新/);
  pass('门店资料保存形成版本、历史与操作事件且拒绝陈旧版本');

  const afterCompany = Settings.load(state);
  const assignments = Object.fromEntries(afterCompany.assignmentFields.filter(field => field.options.length).map(field => [field.id, field.options[0].id]));
  Settings.apply(state, 'management-assignments', state.companyId, { expectedVersion: afterCompany.version, assignments });
  assert.deepEqual(state.managementSettings.assignments, assignments);
  const inactive = state.staff.find(person => person.active === false);
  assert(inactive);
  assert.throws(() => Settings.apply(state, 'management-assignments', state.companyId, { expectedVersion: state.managementSettings.version, assignments: { sales: inactive.id } }), /在岗/);
  const engineer = state.staff.find(person => person.active !== false && (person.roles || []).includes('engineer'));
  assert(engineer);
  assert.throws(() => Settings.apply(state, 'management-assignments', state.companyId, { expectedVersion: state.managementSettings.version, assignments: { sales: engineer.id } }), /岗位/);
  assert.throws(() => Settings.apply({ ...state, companyId: 'other-company' }, 'management-company', 'other-company', { expectedVersion: 0, displayName: '越权', contact: '甲', phone: '13800000000', address: '地址' }), /组织/);
  pass('职责分工限制本组织在岗对应岗位并阻断跨组织写入');

  assert.deepEqual(Settings.FIXED_RULES.map(rule => rule.id), ['moneyApproval', 'designReview', 'financeRegister', 'headquartersReceipt']);
  assert.equal(Settings.FIXED_RULES.find(rule => rule.id === 'financeRegister').value, '财务登记实际收付，总部独立核实设计款到账');
  pass('固定审批与收付规则不开放切换');

  const output = path.resolve('qa/management-completion-20261023/performance-results.json');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({ result: 'passed', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
