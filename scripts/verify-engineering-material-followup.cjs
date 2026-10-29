const assert = require('node:assert/strict');
const fs = require('node:fs');

const store = {};
global.wx = {
  getStorageSync(key) { return store[key]; },
  setStorageSync(key, value) { store[key] = JSON.parse(JSON.stringify(value)); },
  removeStorageSync(key) { delete store[key]; }
};

const Account = require('../miniprogram/utils/account');
const R = require('../miniprogram/utils/engineering-records');
const date = Account.localDate();
const yesterday = new Date(Date.parse(date + 'T12:00:00') - 86400000).toISOString().slice(0, 10);
const past = new Date(Date.parse(date + 'T12:00:00') - 3 * 86400000).toISOString().slice(0, 10);
const future = new Date(Date.parse(date + 'T12:00:00') + 2 * 86400000).toISOString().slice(0, 10);
const file = { name: '材料到货凭据.pdf', sample: true };

store['zhongshu-account-v1'] = Account.load();
Account.mutate(state => Account.loginDemo(state, 'engineering-dual'));
store['zhongshu-engineering-v1'] = { version: 1, projects: [{
  id: 'engineering-material-followup', projectId: 'project-material-followup', companyId: 'quanzhou',
  name: '周建国家', customer: '周建国', city: '武汉', floors: 2,
  manager: '张工程', managerId: 'engineering-dual', assignedEngineerId: 'engineering-dual', accepted: date,
  events: [], planHistory: [], documents: [], materials: [{ id: 'm1', planId: 'plan1', nodeId: 'n1', name: '水泥', quantity: 10, unit: '包', date: past, submitted: true }],
  supplies: [{ id: 's1', planId: 'plan1', materialId: 'm1', quantity: 10, supplier: '本地建材供应商', date: past, unitPrice: 30, freight: 0, payer: '本店', basis: file, status: '待财务办理' }],
  receipts: [], inspections: [], siteRecords: [], collections: [], payments: [], reships: [],
  plan: { version: 1, nodes: [{ id: 'n1', name: '基础工程', start: past, end: date, status: '施工中' }] },
  planSaved: true, actualStart: { date: past }, delivery: null, archived: false, revision: 0,
  recordFlow: { version: 1, materialPlans: [{ id: 'plan1', nodeId: 'n1', date: past, due: past, note: '', materialIds: ['m1'] }], warranties: [] }
}] };
store['zhongshu-management-v1'] = require('../miniprogram/utils/management-seed')();

let project = R.get('engineering-material-followup');
assert(R.owner(project), '测试账号应为项目经办工程人员');
const firstTasks = R.tasks(project);
assert(firstTasks.pending.some(task => task.title.includes('材料已逾期3天') && task.title.includes('催到货登记')), JSON.stringify(firstTasks));

assert.throws(() => R.run(project.id, 'material-followup', { planId: 'plan1' }, project.revision), /催到货联系说明/);
assert.throws(() => R.run(project.id, 'material-followup', { planId: 'plan1', promiseDate: past, note: '供应商承诺日期早于本次联系日' }, project.revision), /今天或之后/);
R.run(project.id, 'material-followup', { planId: 'plan1', date: future, note: '供应商暂未给出明确到货日' }, project.revision);
project = R.get(project.id);
assert.equal(project.recordFlow.materialFollowups.length, 1);
assert.equal(project.recordFlow.materialFollowups[0].date, date, '催办联系日固定为实际登记当天，不读取到货日期字段');
assert(R.tasks(project).waiting.some(task => task.title.includes('等待供应商回复')), '无承诺日的当日联系进入等待区');

const recordedRevision = project.revision;
project = R.run(project.id, 'material-followup', { planId: 'plan1', date: past, note: '供应商暂未给出明确到货日' }, project.revision);
assert.equal(project.revision, recordedRevision, '同日相同催办应返回原结果，不新增项目版本');
assert.equal(project.recordFlow.materialFollowups.length, 1, '同日相同催办不得重复追加');

store['zhongshu-engineering-v1'].projects[0].recordFlow.materialFollowups[0].date = yesterday;
project = R.get(project.id);
assert(R.tasks(project).pending.some(task => task.title.includes('已联系1天') && task.title.includes('再次催办')), '无承诺日到次日应重新提醒');

R.run(project.id, 'material-followup', { planId: 'plan1', promiseDate: future, note: '供应商确认两天后送到现场' }, project.revision);
project = R.get(project.id);
assert.equal(project.recordFlow.materialFollowups.length, 2);
assert(R.tasks(project).waiting.some(task => task.title.includes('承诺' + future + '到货')));

store['zhongshu-engineering-v1'].projects[0].recordFlow.materialFollowups[1].promiseDate = past;
project = R.get(project.id);
assert(R.tasks(project).pending.some(task => task.title.includes('承诺到货日') && task.title.includes('再次催办')));

R.run(project.id, 'arrival', { planId: 'plan1', date, file, lines: [{ id: 'm1', actual: '10' }] }, project.revision);
project = R.get(project.id);
assert(!R.tasks(project).pending.some(task => task.id.includes(':record:material-followup-')));
assert(!R.tasks(project).waiting.some(task => task.id.includes(':record:material-followup-')));

Account.mutate(state => Account.loginDemo(state, 'finance-dual'));
assert.throws(() => R.run(project.id, 'material-followup', { planId: 'plan1', note: '财务代催' }, project.revision), /工程人员/);

const page = fs.readFileSync('miniprogram/pages/build/record/index.js', 'utf8');
const template = fs.readFileSync('miniprogram/pages/build/record/index.wxml', 'utf8');
assert(page.includes("action:'material-followup',label:'登记今日已催到货'"));
assert(page.includes("field('date','本次实际到货日期','date')"));
assert(page.includes("field('note','到货说明或本次催到货联系说明（催办必填）','textarea')"));
assert(page.includes('materialFollowups:'));
assert(template.includes('催到货记录'));

console.log('PASS 材料逾期进入催到货待办');
console.log('PASS 催到货说明必填，联系日固定为登记当天');
console.log('PASS 同日相同催到货操作幂等不追加');
console.log('PASS 无承诺日当日等待，次日自动重新提醒');
console.log('PASS 供应商承诺日登记后进入等待区');
console.log('PASS 承诺过期未到货自动回到再次催办');
console.log('PASS 实际到齐后材料催办自然结束');
console.log('PASS 催到货仅由项目工程人员登记');
console.log('PASS 材料原单提供催办操作并显示历史记录');
