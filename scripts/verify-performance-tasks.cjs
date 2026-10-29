const assert = require('node:assert/strict');
const fs = require('node:fs');

const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const store = {};
global.wx = {
  getStorageSync(key) { return clone(store[key]); },
  setStorageSync(key, value) { store[key] = clone(value); }
};

const keys = require('../miniprogram/utils/storage-keys');
const staff = [
  { id: 'boss-a', name: '老板', active: true, roles: ['boss'], personId: 'boss-a' },
  { id: 'sales-a', name: '业务甲', active: true, roles: ['sales'], personId: 'person-sales-a' },
  { id: 'sales-alt', name: '业务甲兼岗', active: true, roles: ['sales-lead'], personId: 'person-sales-a' },
  { id: 'engineer-a', name: '工程甲', active: true, roles: ['engineer'], personId: 'person-engineer-a' },
  { id: 'finance-a', name: '财务甲', active: true, roles: ['finance'], personId: 'person-finance-a' },
  { id: 'left-a', name: '离岗人员', active: false, roles: ['sales'], personId: 'left-a' }
];
const state = {
  companyId: 'acme', period: '2026-10', staff,
  receipts: [{ id: 'receipt-a', projectId: 'project-a', status: '已核算', amount: 300000, bankDate: '2026-10-12', financialRegistrarId: 'finance-a' }],
  payments: [], refunds: [{ id: 'refund-a', receiptId: 'receipt-a', projectId: 'project-a', status: '已核实', amount: 50000, date: '2026-10-20' }], performanceAdjustments: [], operations: { funds: [] }
};
const originalState = JSON.stringify(state);
store[keys.management] = { ...clone(state), version: 1 };
store[keys.account] = {
  version: 1,
  session: { loggedIn: true, companyId: 'acme', staffId: 'boss-a', userId: 'user-boss', role: 'boss' },
  profile: { name: '老板' }, companies: [{ id: 'acme', name: '测试组织', type: 'store', roles: ['boss'] }]
};
store[keys.business] = { customers: [{
  id: 'customer-a', companyId: 'acme', projectId: 'project-a', name: '客户甲',
  businessFlow: { type: 'sales-offline-v1', submittedAt: '2026-10-04' },
  serviceOrders: [{ total: 9999, responsibleId: 'sales-a' }],
  contract: { file: { name: '合同.pdf' }, actorId: 'sales-a', ownerAtSigning: 'sales-a', date: '2026-10-03', total: 1000 }
}] };
store[keys.engineering] = { projects: [{
  id: 'engineering-a', projectId: 'project-a', companyId: 'acme', customer: '客户甲', managerId: 'engineer-a',
  plan: { nodes: [{ id: 'node-a', name: '基础验收', originalEnd: '2026-10-10', end: '2026-10-10', status: '已完成', completedById: 'engineer-a', acceptedAt: '2026-10-09' }] },
  inspections: [{ id: 'inspection-a', nodeId: 'node-a', result: '通过', date: '2026-10-09', submittedById: 'engineer-a' }],
  supplies: [{ id: 'supply-a', name: '水泥', quantity: 10, date: '2026-10-20', applicantId: 'engineer-a' }],
  receipts: [{ id: 'material-receipt-a', supplyId: 'supply-a', actual: 10, quality: '合格', date: '2026-10-18' }], reships: []
}] };
store[keys.growth] = {
  contents: [{ id: 'content-a', companyId: 'acme', status: '演示已发布', authorId: 'sales-a', publishedAt: '2026-10-05', pack: { title: '秋季建房' } }],
  leads: [{ id: 'lead-a', companyId: 'acme', status: '已转客户', ownerId: 'sales-a', name: '线索甲' }],
  events: [{ id: 'lead-a', type: 'lead-convert', at: '2026-10-06' }]
};

const Account = require('../miniprogram/utils/account');
const OwnerPerformance = require('../miniprogram/pages/admin/owner-performance');
assert.equal(typeof OwnerPerformance.facts, 'function', '事实引擎应公开既有 facts 供任务化复用');
const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(OwnerPerformance.facts);

const login = (staffId, role) => Account.mutate(account => {
  account.session = { loggedIn: true, companyId: 'acme', staffId, userId: 'user-' + staffId, role };
  account.profile = { name: staff.find(person => person.id === staffId).name };
  account.companies = [{ id: 'acme', name: '测试组织', type: 'store', roles: staff.find(person => person.id === staffId).roles }];
});
const create = (type, assigneeId, target, bonus = '10') => Tasks.create(state, {
  period: '2026-10', title: '测试' + type, assigneeId, due: '2026-10-31', type, target: String(target), bonus,
  idempotencyKey: 'create:' + type
});

const beforeFailedBatch = clone(store[keys.performanceTasks]);
let factCalls = 0;
const BrokenTasks = require('../miniprogram/utils/performance-tasks').withFacts(snapshot => {
  factCalls += 1;
  if (factCalls === 3) throw Error('模拟批量中断');
  return OwnerPerformance.facts(snapshot);
});
assert.throws(() => BrokenTasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:broken' }), /模拟批量中断/);
assert.deepEqual(store[keys.performanceTasks], beforeFailedBatch, '批量初始化失败不得留下半批任务');

login('sales-a', 'sales');
assert.throws(() => Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:staff' }), /老板/, '员工不得批量生成组织任务');
login('boss-a', 'boss');
const initialized = Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:2026-11' });
assert.equal(initialized.created.length, 7, '仅按上月七类正数真实业绩生成建议任务');
assert(initialized.created.every(task => task.type.startsWith('auto:') && task.target > 0 && task.bonus === 0 && task.due === '2026-11-30'));
assert(initialized.created.every(task => task.suggestion?.period === '2026-10' && task.suggestion.sources.length > 0 && task.suggestion.sources.every(source => source.url.startsWith('/pages/'))), '建议任务须保留上月原单依据');
assert.deepEqual(Object.fromEntries(initialized.created.map(task => [task.type, task.target])), {
  'auto:collectionsAmount':2500, 'auto:contract':1000, 'auto:completedNodes':1, 'auto:onTimeNodes':1,
  'auto:publications':1, 'auto:effectiveLeads':1, 'auto:qualifiedDeliveries':1
}, '建议目标应与上月七类实际完成值一致');
assert.equal(new Set(initialized.created.map(task => task.assigneePersonId + ':' + task.type)).size, initialized.created.length, '同一真实 person/type 仅生成一项');
assert.deepEqual(Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:2026-11' }).created.map(task => task.id), initialized.created.map(task => task.id), '同一命令键重放返回原批次');
assert.equal(Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:retry' }).created.length, 0, '不同命令键重试也不重复生成');
assert.equal(Tasks.list(state, { scope:'company', period:'2026-11' }).length, 7);
Tasks.archive(state, '2026-11', { note:'十一月归档', idempotencyKey:'archive:2026-11' });
assert.throws(() => Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:archived' }), /已归档/);
const emptyInitialization = Tasks.initializePrevious(state, { period:'2026-12', idempotencyKey:'initialize:2026-12' });
assert.equal(emptyInitialization.created.length, 0, '上月无真实事实时不造默认业绩');
assert.equal(Tasks.list(state, { scope:'company', period:'2026-12' }).length, 0, '0 业绩不得写成已完成任务');

const contract = create('auto:contract', 'sales-a', 1000);
const collections = create('auto:collectionsAmount', 'sales-a', 2500);
const nodes = create('auto:completedNodes', 'engineer-a', 1);
const onTime = create('auto:onTimeNodes', 'engineer-a', 1);
const publications = create('auto:publications', 'sales-a', 1, '');
const leads = create('auto:effectiveLeads', 'sales-a', 1);
const materials = create('auto:qualifiedDeliveries', 'engineer-a', 1);
const manual = create('manual', 'sales-a', '', '20');
const contractAlias = Tasks.create(state, { period: '2026-10', title: '兼岗签约事实', assigneeId: 'sales-alt', due: '2026-10-31', type: 'auto:contract', target: '1000', bonus: '', idempotencyKey: 'create:contract-alias' });
assert.equal(contract.assigneePersonId, 'person-sales-a');
assert.equal(contractAlias.assigneePersonId, contract.assigneePersonId);
assert.equal(create('auto:contract', 'sales-a', 1000).id, contract.id, '同一幂等键重试不重复创建');
assert.throws(() => Tasks.create(state, { period: '2026-02', title: '无效日期', assigneeId: 'sales-a', due: '2026-02-30', type: 'manual', target: '', bonus: '' }), /截止日期/);

const actuals = Object.fromEntries(Tasks.list(state, { scope: 'company', period: '2026-10' }).map(task => [task.type, task.collected]));
assert.deepEqual(actuals, {
  'auto:contract': 1000,
  'auto:collectionsAmount': 2500,
  'auto:completedNodes': 1,
  'auto:onTimeNodes': 1,
  'auto:publications': 1,
  'auto:effectiveLeads': 1,
  'auto:qualifiedDeliveries': 1,
  manual: 0
});
const automaticRows = Tasks.list(state, { scope: 'company', period: '2026-10' }).filter(task => task.type !== 'manual');
assert(automaticRows.every(task => task.sourceIds.length > 0 && task.sources.every(source => source.url.startsWith('/pages/'))), '自动任务必须保留原单 ID 与下钻 URL');
assert.equal(automaticRows.find(task => task.type === 'auto:collectionsAmount').sources.reduce((sum, source) => sum + source.amount, 0), 2500, '回款金额按元汇总并减已核实退款');
assert.equal(automaticRows.find(task => task.type === 'auto:publications').bonus, 0, '奖金空值按 0 处理');

Tasks.reassign(state, manual.id, { assigneeId: 'engineer-a', note: '调整执行人', idempotencyKey: 'create:manual' });
const reassigned = Tasks.list(state, { scope: 'company', period: '2026-10' }).find(task => task.id === manual.id);
assert.equal(reassigned.assigneeId, 'engineer-a');
assert(reassigned.history.some(row => row.action === 'reassign' && row.from === 'sales-a' && row.to === 'engineer-a'));
assert(Object.keys(store[Tasks.KEY].companies.acme.periods['2026-10'].commands).every(key => key.split(':').length >= 3), '幂等键必须包含命令与真实人身份');
assert.throws(() => create('manual', 'left-a', '', '1'), /在岗/);

login('sales-a', 'sales');
assert.deepEqual(Tasks.list(state, { scope: 'mine', period: '2026-10' }).map(task => task.assigneePersonId), Array(5).fill('person-sales-a'), '同一真实 person 切换兼岗身份后仍能读本人任务');
assert.throws(() => Tasks.list(state, { scope: 'company', period: '2026-10' }), /老板/);
assert.throws(() => create('auto:contract', 'sales-a', 1000), /老板/, '创建命令幂等命中前仍校验老板权限');
assert.throws(() => Tasks.completeManual(state, manual.id, { note: '越权', photos: ['saved://wrong.jpg'] }), /本人/);

login('engineer-a', 'engineer');
assert.throws(() => Tasks.completeManual(state, manual.id, { note: '照片超限', photos: Array(7).fill('saved://evidence.jpg') }), /最多 6 张/);
Tasks.completeManual(state, manual.id, { note: '现场协调完成', photos: [], idempotencyKey: 'manual:done' });
const ownManual = Tasks.list(state, { scope: 'mine', period: '2026-10' }).find(task => task.id === manual.id);
assert.equal(ownManual.status, '已完成');
assert.deepEqual(ownManual.manualDone.photos, [], '手动完成照片按规格可选填');
login('sales-a', 'sales');
assert.throws(() => Tasks.completeManual(state, manual.id, { note: '现场协调完成', photos: [], idempotencyKey: 'manual:done' }), /本人/, '幂等命中前仍必须校验当前操作人');
assert.throws(() => Tasks.previousActual(state, { period:'2026-11', assigneeId:'engineer-a', type:'auto:completedNodes' }), /本人|老板/, '员工不能读取其他人员的上月业绩参考');
const managementWithReplacement = clone(store[keys.management]);
managementWithReplacement.staff.find(person => person.id === 'sales-a').personId = 'replacement-person';
store[keys.management] = managementWithReplacement;
assert.equal(Tasks.list(state, { scope: 'mine', period: '2026-10' }).length, 0, '岗位账号换人后不能读到原 person 的任务');
const replacementState = { ...clone(state), staff:managementWithReplacement.staff };
login('boss-a', 'boss');
const originalPublication = Tasks.list(replacementState, { scope:'company', period:'2026-10' }).find(task => task.id === publications.id);
assert.equal(originalPublication.collected, 1, '旧任务应按派发时 person 保留原始来源归属');
assert.equal(Tasks.previousActual(replacementState, { period:'2026-11', assigneeId:'sales-a', type:'auto:publications' }).value, 0, '新任人不得继承同一 staffId 下缺少 person 证据的历史业绩');
store[keys.management] = { ...clone(state), version: 1 };

const archive = Tasks.archive(state, '2026-10', { note: '月度结算冻结', idempotencyKey: 'archive:2026-10' });
assert.equal(Tasks.archive(state, '2026-10', { note: '月度结算冻结', idempotencyKey: 'archive:2026-10' }).id, archive.id);
assert.equal(create('auto:contract', 'sales-a', 1000).id, contract.id, '归档后可安全重放原创建命令，不新增任务');
assert.equal(archive.bonusTotal, 8000, '已完成任务奖金按分汇总，空奖金不发放');
assert.throws(() => Tasks.reassign(state, manual.id, { assigneeId: 'sales-a', note: '归档后改派' }), /已归档/);
const draft = Tasks.payoutDraft(state, archive.id);
assert.equal(draft.idempotencyKey, 'performance-task-bonus:' + archive.id);
assert.equal(draft.total, 8000);
assert.equal(Tasks.payoutDraft(state, archive.id).idempotencyKey, draft.idempotencyKey);

const engineering = clone(store[keys.engineering]);
engineering.projects[0].plan.nodes.push({ id: 'node-b', name: '主体验收', originalEnd: '2026-10-22', end: '2026-10-22', status: '已完成', completedById: 'engineer-a', acceptedAt: '2026-10-21' });
engineering.projects[0].inspections.push({ id: 'inspection-b', nodeId: 'node-b', result: '通过', date: '2026-10-21', submittedById: 'engineer-a' });
store[keys.engineering] = engineering;
assert.equal(Tasks.bonusSummary(state, '2026-10').needsRevision, true, '归档后原业务变化应提示修订');
assert.throws(() => Tasks.payoutDraft(state, archive.id), /先修订归档/);
const revision = Tasks.reviseArchive(state, '2026-10', { note: '新增验收事实', idempotencyKey: 'archive:2026-10:r2' });
assert.equal(revision.revision, 2);
assert.equal(revision.supersedes, archive.id);
assert.equal(Tasks.bonusSummary(state, '2026-10').needsRevision, false);

login('finance-a', 'finance');
const summary = Tasks.bonusSummary(state, '2026-10');
assert.equal(summary.latest.id, revision.id);
const revisedDraft = Tasks.payoutDraft(state, revision.id);
const link = Tasks.linkPayout(state, revision.id, { referenceId: 'payable-external-1', idempotencyKey: revisedDraft.idempotencyKey });
assert.equal(Tasks.linkPayout(state, revision.id, { referenceId: 'payable-external-1', idempotencyKey: revisedDraft.idempotencyKey }).referenceId, link.referenceId);
assert.throws(() => Tasks.linkPayout(state, revision.id, { referenceId: 'payable-external-2', idempotencyKey: revisedDraft.idempotencyKey }), /已关联/);

const pageSource = fs.readFileSync('miniprogram/pages/admin/tasks/index.js', 'utf8');
const template = fs.readFileSync('miniprogram/pages/admin/tasks/index.wxml', 'utf8');
const modelSource = fs.readFileSync('miniprogram/utils/performance-tasks.js', 'utf8');
assert(pageSource.includes("scope: 'mine'") && pageSource.includes('chooseMedia') && pageSource.includes('saveFile'));
assert(pageSource.includes("fail: () => this.fail('未选择照片，可重试')"), 'chooseMedia 取消或失败必须明确提示');
assert(pageSource.includes('bonus?.latest && !bonus.needsRevision'), '原业务变化时页面应先允许归档修订，不生成过期应付草案');
assert(['团队任务', '我的任务', '奖金归档'].every(label => pageSource.includes(label)));
assert(template.includes('wx:for="{{tabs}}"'));
assert(template.includes('bindsubmit="submitTask"') && template.includes('bindsubmit="completeManual"'));
assert(!modelSource.includes("require('../pages/admin"), '根包领域模型不得反向依赖 admin 分包');
assert.equal(JSON.stringify(state), originalState, '任务化不改写绩效事实、薪酬或历史账簿对象');

console.log('PASS 绩效任务权限、自动事实、手动凭证、归档修订与幂等奖金衔接');
