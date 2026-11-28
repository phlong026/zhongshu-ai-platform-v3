// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
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
  receipts: [{ id: 'receipt-a', projectId: 'project-a', status: '已核算', amount: 300000, bankDate: '2026-10-12', financialRegistrarId: 'finance-a', financialRegistrarPersonId: 'person-finance-a' }],
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
  serviceOrders: [{ total: 9999, responsibleId: 'sales-a', responsiblePersonId: 'person-sales-a' }],
  contract: { file: { name: '合同.pdf' }, actorId: 'sales-a', personId: 'person-sales-a', ownerAtSigning: 'sales-a', date: '2026-10-03', total: 1000 }
}] };
store[keys.engineering] = { projects: [{
  id: 'engineering-a', projectId: 'project-a', companyId: 'acme', customer: '客户甲', managerId: 'engineer-a',
  plan: { nodes: [{ id: 'node-a', name: '基础验收', originalEnd: '2026-10-10', end: '2026-10-10', status: '已完成', completedById: 'engineer-a', completedByPersonId: 'person-engineer-a', acceptedAt: '2026-10-09' }] },
  inspections: [{ id: 'inspection-a', nodeId: 'node-a', result: '通过', date: '2026-10-09', submittedById: 'engineer-a', submittedByPersonId: 'person-engineer-a' }],
  supplies: [{ id: 'supply-a', name: '水泥', quantity: 10, date: '2026-10-20', applicantId: 'engineer-a', applicantPersonId: 'person-engineer-a' }],
  receipts: [{ id: 'material-receipt-a', supplyId: 'supply-a', actual: 10, quality: '合格', date: '2026-10-18' }], reships: []
}] };
const initialGrowth = {
  contents: [{ id: 'content-a', companyId: 'acme', status: '演示已发布', authorId: 'sales-a', authorPersonId: 'person-sales-a', publishedAt: '2026-10-05', pack: { title: '秋季建房' } }],
  leads: [{ id: 'lead-a', companyId: 'acme', status: '已转客户', ownerId: 'sales-a', ownerPersonId: 'person-sales-a', name: '线索甲' }],
  events: [{ id: 'lead-a', type: 'lead-convert', at: '2026-10-06' }]
};
store[keys.growth] = clone(initialGrowth);

const Account = require('../miniprogram/utils/account');
const OwnerPerformance = require('./fixtures/legacy-admin/owner-performance');
assert.equal(typeof OwnerPerformance.facts, 'function', '事实引擎应公开既有 facts 供任务化复用');
const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(OwnerPerformance.facts);

const login = (staffId, role) => Account.mutate(account => {
  account.session = { loggedIn: true, companyId: 'acme', staffId, userId: 'user-' + staffId, role };
  account.profile = { name: staff.find(person => person.id === staffId).name };
  account.companies = [{ id: 'acme', name: '测试组织', type: 'store', roles: staff.find(person => person.id === staffId).roles }];
});
const create = (type, assigneeId, target) => Tasks.create(state, {
  period: '2026-10', title: '测试' + type, assigneeId, due: '2026-10-31', type, target: String(target),
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
assert.equal(initialized.created.length, 8, '仅按上月八类正数真实业绩生成建议任务');
assert(initialized.created.every(task => task.type.startsWith('auto:') && task.target > 0 && task.due === '2026-11-30'));
assert(initialized.created.every(task => !Object.hasOwn(task, 'bonus')), '建议任务不应生成奖金字段');
assert(initialized.created.every(task => task.suggestion?.period === '2026-10' && task.suggestion.sources.length > 0 && task.suggestion.sources.every(source => source.url.startsWith('/pages/'))), '建议任务须保留上月原单依据');
assert.deepEqual(Object.fromEntries(initialized.created.map(task => [task.type, task.target])), {
  'auto:collectionsAmount':2500, 'auto:contract':1000, 'auto:completedNodes':1, 'auto:onTimeNodes':1,
  'auto:publications':1, 'auto:effectiveLeads':1, 'auto:qualifiedDeliveries':1, 'auto:financeRegistrations':1
}, '建议目标应与上月八类实际完成值一致');
assert.equal(new Set(initialized.created.map(task => task.assigneePersonId + ':' + task.type)).size, initialized.created.length, '同一真实 person/type 仅生成一项');
assert.deepEqual(Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:2026-11' }).created.map(task => task.id), initialized.created.map(task => task.id), '同一命令键重放返回原批次');
assert.equal(Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:retry' }).created.length, 0, '不同命令键重试也不重复生成');
assert.equal(Tasks.list(state, { scope:'company', period:'2026-11' }).length, 8);
Tasks.archive(state, '2026-11', { note:'十一月归档', idempotencyKey:'archive:2026-11' });
assert.throws(() => Tasks.initializePrevious(state, { period:'2026-11', idempotencyKey:'initialize:archived' }), /已归档/);
const emptyInitialization = Tasks.initializePrevious(state, { period:'2026-12', idempotencyKey:'initialize:2026-12' });
assert.equal(emptyInitialization.created.length, 0, '上月无真实事实时不造默认业绩');
assert.equal(Tasks.list(state, { scope:'company', period:'2026-12' }).length, 0, '0 业绩不得写成已完成任务');

const contract = create('auto:contract', 'sales-a', 1000);
const collections = create('auto:collectionsAmount', 'sales-a', 2500);
const nodes = create('auto:completedNodes', 'engineer-a', 1);
const onTime = create('auto:onTimeNodes', 'engineer-a', 1);
const publications = create('auto:publications', 'sales-a', 1);
const leads = create('auto:effectiveLeads', 'sales-a', 1);
const materials = create('auto:qualifiedDeliveries', 'engineer-a', 1);
const manual = create('manual', 'sales-a', '');
const contractAlias = Tasks.create(state, { period: '2026-10', title: '兼岗签约事实', assigneeId: 'sales-alt', due: '2026-10-31', type: 'auto:contract', target: '1000', idempotencyKey: 'create:contract-alias' });
assert.equal(contract.assigneePersonId, 'person-sales-a');
assert.equal(contractAlias.assigneePersonId, contract.assigneePersonId);
assert.equal(create('auto:contract', 'sales-a', 1000).id, contract.id, '同一幂等键重试不重复创建');
assert.throws(() => Tasks.create(state, {
  period:'2026-10', title:'测试auto:publications', assigneeId:'sales-a', due:'2026-10-31', type:'auto:publications', target:'1', idempotencyKey:'create:publications:duplicate'
}), /相同自动任务/, '不同幂等键也不能重复创建字段完全相同的自动任务');
assert.throws(() => Tasks.create(state, { period: '2026-02', title: '无效日期', assigneeId: 'sales-a', due: '2026-02-30', type: 'manual', target: '' }), /截止日期/);
const tierOne = Tasks.create(state, { period:'2026-12', title:'阶梯发布', assigneeId:'sales-a', due:'2026-12-31', type:'auto:publications', target:'1', bonus:'历史客户端无效值', idempotencyKey:'create:tier-one' });
const tierTwo = Tasks.create(state, { period:'2026-12', title:'阶梯发布', assigneeId:'sales-a', due:'2026-12-31', type:'auto:publications', target:'2', bonus:'999999999', idempotencyKey:'create:tier-two' });
assert.notEqual(tierOne.id, tierTwo.id, '不同目标的阶梯任务应保留');
assert(!Object.hasOwn(tierOne, 'bonus') && !Object.hasOwn(tierTwo, 'bonus'), '历史客户端传入的奖金字段应被忽略');

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
assert(automaticRows.every(task => !Object.hasOwn(task, 'bonusAwarded')), '任务进度不应派生奖金结果');

Tasks.reassign(state, manual.id, { assigneeId: 'engineer-a', note: '调整执行人', idempotencyKey: 'create:manual' });
const reassigned = Tasks.list(state, { scope: 'company', period: '2026-10' }).find(task => task.id === manual.id);
assert.equal(reassigned.assigneeId, 'engineer-a');
assert(reassigned.history.some(row => row.action === 'reassign' && row.from === 'sales-a' && row.to === 'engineer-a'));
assert(Object.keys(store[Tasks.KEY].companies.acme.periods['2026-10'].commands).every(key => key.split(':').length >= 3), '幂等键必须包含命令与真实人身份');
assert.throws(() => create('manual', 'left-a', ''), /在岗/);

login('sales-a', 'sales');
assert.deepEqual(Tasks.list(state, { scope: 'mine', period: '2026-10' }).map(task => task.assigneePersonId), Array(5).fill('person-sales-a'), '同一真实 person 切换兼岗身份后仍能读本人任务');
assert.throws(() => Tasks.list(state, { scope: 'company', period: '2026-10' }), /老板/);
assert.throws(() => Tasks.archiveSummary(state, '2026-10'), /只有本组织老板/, '员工不得读取组织任务归档');
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
assert.equal(Tasks.previousActual(replacementState, { period:'2026-11', assigneeId:'sales-a', type:'auto:collectionsAmount' }).value, 0, '新任人不得按当前staff映射继承原责任人的历史回款');
const historyBoundBusiness = clone(store[keys.business]);
delete historyBoundBusiness.customers[0].serviceOrders[0].responsiblePersonId;
historyBoundBusiness.customers[0].responsibilityHistory = [{ from:'sales-a', fromPersonId:'person-sales-a', to:'sales-alt', toPersonId:'person-sales-a', effectiveAt:'2026-10-05' }];
store[keys.business] = historyBoundBusiness;
assert.equal(Tasks.list(replacementState, { scope:'company', period:'2026-10' }).find(task => task.id === collections.id).collected, 2500, '委托后的责任历史fromPersonId须稳定保留原责任人净回款');
const legacyGrowth = clone(store[keys.growth]);
legacyGrowth.contents.push({ id:'legacy-no-person', companyId:'acme', status:'演示已发布', authorId:'sales-a', publishedAt:'2026-10-08', pack:{ title:'待核对旧发布' } });
store[keys.growth] = legacyGrowth;
const replacementPublication = Tasks.create(replacementState, { period:'2026-10', title:'新任发布任务', assigneeId:'sales-a', due:'2026-10-31', type:'auto:publications', target:'1', idempotencyKey:'create:replacement-publication' });
const replacementRow = Tasks.list(replacementState, { scope:'company', period:'2026-10' }).find(task => task.id === replacementPublication.id);
assert.equal(replacementRow.collected, 0, '旧无person事实不能按当前staffId回猜给新岗位持有人');
assert.equal(replacementRow.status, '归属待核对', '无法稳定绑定的旧事实必须停止计入并提示待核对');
const legacyBusiness = clone(store[keys.business]);
delete legacyBusiness.customers[0].responsibilityHistory[0].fromPersonId;
store[keys.business] = legacyBusiness;
const replacementCollections = Tasks.create(replacementState, { period:'2026-10', title:'新任回款任务', assigneeId:'sales-a', due:'2026-10-31', type:'auto:collectionsAmount', target:'2500', idempotencyKey:'create:replacement-collections' });
const replacementCollectionRow = Tasks.list(replacementState, { scope:'company', period:'2026-10' }).find(task => task.id === replacementCollections.id);
assert.equal(replacementCollectionRow.collected, 0, '旧无person的委托回款不能通过当前员工表映射给新岗位持有人');
assert.equal(replacementCollectionRow.status, '归属待核对', '回款责任缺少person证据时必须暂停计入并提示待核对');
store[keys.growth] = clone(initialGrowth);
store[keys.business].customers[0].serviceOrders[0].responsiblePersonId = 'person-sales-a';
store[keys.management] = { ...clone(state), version: 1 };

const archive = Tasks.archive(state, '2026-10', { note: '月度任务冻结', idempotencyKey: 'archive:2026-10' });
assert.equal(Tasks.archive(state, '2026-10', { note: '月度任务冻结', idempotencyKey: 'archive:2026-10' }).id, archive.id);
assert.equal(create('auto:contract', 'sales-a', 1000).id, contract.id, '归档后可安全重放原创建命令，不新增任务');
assert.equal(archive.tasks.length, 11, '归档应冻结当月全部任务及事实');
assert(!Object.hasOwn(archive, 'bonusTotal'), '任务归档不生成奖金汇总');
assert.throws(() => Tasks.reassign(state, manual.id, { assigneeId: 'sales-a', note: '归档后改派' }), /已归档/);
const futureFinanceState = clone(state);
futureFinanceState.receipts.push({ id:'future-receipt', projectId:'future-project', status:'已核算', amount:10000, bankDate:'2026-11-01' });
futureFinanceState.refunds.push({ id:'future-refund', receiptId:'future-receipt', projectId:'future-project', status:'已核实', amount:1000, date:'2026-11-02' });
assert.equal(Tasks.archiveSummary(futureFinanceState, '2026-10').needsRevision, false, '未来月份无关收退款不能污染已冻结旧月签名');
const correctedFinanceState = clone(state);
correctedFinanceState.receipts[0].amount += 100;
assert.equal(Tasks.archiveSummary(correctedFinanceState, '2026-10').needsRevision, true, '归档月份内原收款修订仍须触发重新冻结');
const correctedPersonState = clone(state);
correctedPersonState.receipts[0].financialRegistrarPersonId = 'corrected-finance-person';
assert.equal(Tasks.archiveSummary(correctedPersonState, '2026-10').needsRevision, true, '原单person归属更正即使数值不变也须触发重新冻结');

const engineering = clone(store[keys.engineering]);
engineering.projects[0].plan.nodes.push({ id: 'node-b', name: '主体验收', originalEnd: '2026-10-22', end: '2026-10-22', status: '已完成', completedById: 'engineer-a', acceptedAt: '2026-10-21' });
engineering.projects[0].inspections.push({ id: 'inspection-b', nodeId: 'node-b', result: '通过', date: '2026-10-21', submittedById: 'engineer-a' });
store[keys.engineering] = engineering;
assert.equal(Tasks.archiveSummary(state, '2026-10').needsRevision, true, '归档后原业务变化应提示修订');
const revision = Tasks.reviseArchive(state, '2026-10', { note: '新增验收事实', idempotencyKey: 'archive:2026-10:r2' });
assert.equal(revision.revision, 2);
assert.equal(revision.supersedes, archive.id);
assert.equal(Tasks.archiveSummary(state, '2026-10').needsRevision, false);

login('finance-a', 'finance');
assert.throws(() => Tasks.archiveSummary(state, '2026-10'), /只有本组织老板/, '财务无组织任务归档查看权');
login('boss-a', 'boss');
const summary = Tasks.archiveSummary(state, '2026-10');
assert.equal(summary.latest.id, revision.id);
const legacyStore = clone(store[Tasks.KEY]);
const legacyCycle = legacyStore.companies.acme.periods['2026-10'];
legacyCycle.archives.at(-1).bonusTotal = 12345;
legacyCycle.archives.at(-1).tasks[0].bonus = 100;
legacyCycle.payoutLinks = [{ archiveId: revision.id, referenceId: 'legacy-payable' }];
store[Tasks.KEY] = legacyStore;
assert.equal(Tasks.archiveSummary(state, '2026-10').latest.id, revision.id, '历史存储中的金额与应付字段不得破坏任务归档读取');
assert.equal(store[Tasks.KEY].companies.acme.periods['2026-10'].archives.at(-1).bonusTotal, 12345, '不做破坏性历史数据迁移');
const modelSource = fs.readFileSync('miniprogram/utils/performance-tasks.js', 'utf8');
assert(!modelSource.includes("require('../pages/admin"), '根包领域模型不得反向依赖 admin 分包');
assert(!['parseBonus', 'bonusSummary', 'payoutDraft', 'linkPayout', 'bonusAwarded', 'bonusTotal', 'payoutLinks'].some(name => modelSource.includes(name)), '任务模型不再暴露奖金或应付能力');
assert.equal(JSON.stringify(state), originalState, '任务化不改写绩效事实、薪酬或历史账簿对象');

console.log('PASS 历史夹具（非当前线上验收）：绩效任务权限、自动事实、手动凭证、任务归档修订与幂等隔离');
