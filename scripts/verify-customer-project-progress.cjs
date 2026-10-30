const assert = require('node:assert/strict');

global.wx = { getStorageSync: () => undefined };

const Progress = require('../miniprogram/utils/customer-project-progress');

const customer = {
  id: 'customer-progress',
  projectId: 'project-progress',
  companyId: 'quanzhou',
  owner: '李业务',
  follows: [{ date: '2026-10-01', method: '微信', content: '业主确认设计需求' }],
  events: [
    { title: '客户登记完成', at: '2026-09-30T09:00:00+08:00', by: '李业务' },
    { id: 'generic-follow-event', title: '记录客户跟进', at: '2026-10-01T23:59:59+08:00', by: '李业务' }
  ]
};

const project = {
  id: 'engineering-progress',
  projectId: customer.projectId,
  sourceCustomerId: customer.id,
  companyId: customer.companyId,
  manager: '张工程',
  assignedEngineerName: '张工程',
  actualStart: { date: '2026-10-05', note: '现场放线完成' },
  plan: {
    nodes: [
      { id: 'foundation', name: '基础施工', status: '施工中', start: '2026-10-05', end: '2026-10-15' },
      { id: 'structure', name: '主体施工', status: '待开工', start: '2026-10-16', end: '2026-11-15' }
    ]
  },
  siteRecords: [{
    id: 'site-newest',
    nodeId: 'foundation',
    date: '2026-10-10',
    note: '基础钢筋绑扎完成，等待验收',
    by: '张工程',
    file: { name: '基础现场.jpg', path: '/tmp/foundation.jpg' }
  }],
  materials: [{ id: 'cement', name: '水泥', unit: '吨' }],
  supplies: [{ id: 'supply-cement', materialId: 'cement' }],
  receipts: [{ id: 'receipt-cement', supplyId: 'supply-cement', date: '2026-10-08', actual: 20, by: '张工程', unitPrice: 999999 }],
  payments: [{ id: 'secret-payment', cents: 999999, transaction: 'SECRET' }],
  collections: [{ id: 'secret-collection', cents: 888888, transaction: 'SECRET' }]
};

project.recordFlow = {
  version: 1,
  prepared: { date: '2026-10-03', note: '班组和现场准备完成', by: '张工程' },
  materialPlans: [{ id: 'plan-cement', nodeId: 'foundation', date: '2026-10-06', due: '2026-10-08', materialIds: ['cement'] }]
};
project.inspections = [{ id: 'inspection-foundation', nodeId: 'foundation', date: '2026-10-08', result: '通过', reviewStatus: 'passed', person: '张工程' }];
project.rectifications = [{ id: 'rectification-foundation', inspectionId: 'inspection-foundation', date: '2026-10-09', result: '已整改', reviewStatus: 'passed', by: '张工程' }];
project.settlement = { date: '2026-10-09', note: '项目结算已确认' };
project.events = [
  { id: 'event-prepared', title: '记录开工准备及合同', time: '2026-10-03T10:00:00+08:00' },
  { id: 'event-cancel', title: '调整材料需用数量（取消一项）', time: '2026-10-07T10:00:00+08:00' },
  { id: 'event-settle', title: '确认项目结算', time: '2026-10-09T10:00:00+08:00' },
  { id: 'event-site', title: '记录现场情况', time: '2026-10-10T23:59:59+08:00' },
  { id: 'event-finance', title: '登记工程收款金999999', time: '2026-10-23T10:00:00+08:00' }
];

const actor = { active: true, companyId: customer.companyId, role: 'sales', staffId: 'sales-dual' };
const progress = Progress.build(customer, { projects: [project] }, actor);

assert.equal(progress.title, '基础施工 · 进行中');
assert.equal(progress.owner, '张工程');
assert.equal(progress.latest.source, 'engineering');
assert.equal(progress.latest.text, '基础钢筋绑扎完成，等待验收');
assert.equal(progress.records[0].text, '基础钢筋绑扎完成，等待验收');
assert.equal(progress.records.filter(row => row.text === '记录现场情况').length, 0, '结构化现场原单已覆盖通用工程事件');
assert.equal(progress.records.filter(row => row.text === '记录客户跟进').length, 0, '结构化跟进原单已覆盖通用业务事件');
assert(progress.records.some(row => row.source === 'business' && row.text === '业主确认设计需求'), '业务历史应保留在全流程记录');
assert(progress.records.some(row => row.source === 'engineering' && /水泥到场/.test(row.text)), '材料到场应形成不含价格的施工摘要');
assert(progress.records.some(row => /开工准备/.test(row.text)), '开工准备应纳入全流程');
assert(progress.records.some(row => /验收通过/.test(row.text)), '巡检验收应纳入全流程');
assert(progress.records.some(row => /整改/.test(row.text)), '整改应纳入全流程');
assert(progress.records.some(row => /材料计划/.test(row.text)), '材料计划应纳入全流程');
assert(progress.records.some(row => /取消一项/.test(row.text)), '材料取消应纳入全流程');
assert(progress.records.some(row => /项目结算/.test(row.text)), '结算事实应纳入全流程');
assert(!/999999|888888|SECRET/.test(JSON.stringify(progress)), '客户工作区不得暴露工程收付款、价格或流水');

const noProject = Progress.build(customer, { projects: [] }, actor);
assert.equal(noProject.engineering, false);
assert.equal(noProject.latest.source, 'business');
assert.equal(noProject.title, '需求沟通中');
assert.equal(noProject.owner, '李业务');
assert.equal(noProject.next, '出方案与预算');
assert(!noProject.records.some(row => row.source === 'engineering'), '无关联工程时不得编造施工记录');

const missingIds = Progress.build({ ...customer, id: 'customer-no-id', projectId: '' }, { projects: [{ ...project, sourceCustomerId: '', projectId: '' }] }, actor);
assert.equal(missingIds.engineering, false, '空关联键不得把无关工程串到客户');

const conflictingIds = Progress.build(customer, { projects: [{ ...project, projectId: 'another-project' }] }, actor);
assert.equal(conflictingIds.engineering, false, 'sourceCustomerId 命中但 projectId 矛盾时必须拒绝串单');

const foreign = Progress.build(customer, { projects: [{ ...project, companyId: 'other-store' }] }, actor);
assert.equal(foreign.engineering, false);
assert(!foreign.records.some(row => row.source === 'engineering'), '跨公司工程必须隔离');

const delivered = Progress.build(customer, { projects: [{
  ...project,
  delivery: { date: '2026-10-20', by: '张工程', recipient: '业主陈先生' }
}] }, actor);
assert.equal(delivered.title, '工程已交付');
assert.equal(delivered.latest.source, 'engineering');
assert.match(delivered.latest.text, /工程已交付/);
assert.equal(delivered.next, '');

const projectIdOnly = Progress.build(customer, { projects: [{ ...project, sourceCustomerId: '' }] }, actor);
assert.equal(projectIdOnly.engineering, true, '同公司同 projectId 应能关联历史工程');

const timezoneOrder = Progress.build({ ...customer, follows: [
  { at: '2026-10-10T00:30:00Z', content: 'UTC 更新' },
  { at: '2026-10-10T08:15:00+08:00', content: '本地时间较早' }
] }, { projects: [] }, actor);
assert.equal(timezoneOrder.records[0].text, 'UTC 更新', '应按真实时刻而非时区字符串排序');

const sameDayRecords = Progress.build({ ...customer, follows: [
  { id: 'follow-morning', at: '2026-10-10T09:00:00+08:00', content: '同一沟通内容' },
  { id: 'follow-afternoon', at: '2026-10-10T15:00:00+08:00', content: '同一沟通内容' }
] }, { projects: [] }, actor);
assert.equal(sameDayRecords.records.filter(row => row.text === '同一沟通内容').length, 2, '同日同文案但不同事件不得按天误去重');

const sameObjectEvents = Progress.build({ ...customer, events: [
  { objectId: customer.id, at: '2026-10-10T09:00:00+08:00', title: '客户责任转交' },
  { objectId: customer.id, at: '2026-10-10T15:00:00+08:00', title: '客户责任转交' }
] }, { projects: [] }, actor);
assert.equal(sameObjectEvents.records.filter(row => row.text === '客户责任转交').length, 2, 'objectId 是关联对象而非事件身份，不得用于去重');

const undatedEvents = Progress.build({ ...customer, follows: [], events: [
  { title: '历史无时间记录' },
  { title: '历史无时间记录' }
] }, { projects: [] }, actor);
assert.equal(undatedEvents.records.length, 2, '无时间同文案历史事件不得合并');
assert.deepEqual(undatedEvents.records.map(row => row.ordinal), [1, 0], '无时间记录应按源数组后写优先');

const archived = Progress.build(customer, { projects: [{
  ...project,
  archived: true,
  events: [{ id: 'archive-event', title: '归档工程资料', time: '2026-10-25T14:00:00+08:00' }]
}] }, actor);
const archiveRecord = archived.records.find(row => /归档/.test(row.text));
assert.equal(archiveRecord.date, '2026-10-25 14:00', '归档时间必须来自真实归档事件');

const acceptedOnly = Progress.build(customer, { projects: [{
  ...project,
  actualStart: null,
  siteRecords: [],
  inspections: [],
  rectifications: [],
  receipts: [],
  settlement: null,
  events: [],
  accepted: '2026-10-02',
  recordFlow: { prepared: { date: '2026-10-03', note: '开工现场准备完成' }, materialPlans: [] }
}] }, actor);
assert.equal(acceptedOnly.latest.source, 'engineering');
assert.match(acceptedOnly.latest.text, /开工准备|工程资料已接收/);

async function verifyPage() {
  const path = require('node:path');
  const runtime = await require('./role-page-runtime.cjs').connect();
  const { render } = require('./role-page-runtime.cjs');
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const Engineering = require('../miniprogram/utils/engineering-records');
  const Keys = require('../miniprogram/utils/storage-keys');
  Account.mutate(state => Account.loginDemo(state, 'sales-dual'));
  const created = Business.add({ name: '项目进展投影', phone: '13900007826', source: '门店到访', city: '武汉市', summary: '施工阶段查看全流程' });
  Business.write(created.id, row => {
    row.follows.push({ date: '2026-10-01', method: '微信', content: '业务历史沟通' });
  }, '记录客户跟进', 'follow', created.revision || 0);
  wx.setStorageSync(Keys.engineering, { projects: [{
    ...project,
    id: 'engineering-page-progress',
    projectId: created.projectId,
    sourceCustomerId: created.id,
    companyId: created.companyId,
    managerId: 'engineering-dual',
    assignedEngineerId: 'engineering-dual',
    siteRecords: Array.from({ length: 25 }, (_, index) => ({
      ...project.siteRecords[0],
      id: 'site-page-' + index,
      date: `2026-10-${String(29 - index).padStart(2, '0')}`,
      note: index ? `施工现场记录${index + 1}` : '基础施工原生投影记录'
    }))
  }] });
  Account.mutate(state => { Account.loginDemo(state, 'engineering-dual'); state.session.role = 'engineer'; });
  Engineering.run('engineering-page-progress', 'site', { date: '2026-10-30', nodeId: 'foundation', note: '工程run丰富现场记录' });
  Account.mutate(state => Account.loginDemo(state, 'sales-dual'));
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/index?view=progress&id=' + encodeURIComponent(created.id) });
  const page = getCurrentPages().at(-1);
  assert.equal(page.data.progressInfo.title, '基础施工 · 进行中');
  assert.equal(page.data.stageIndex, page.data.stages.indexOf('施工阶段'));
  assert.equal(page.data.progressInfo.latest.text, '工程run丰富现场记录');
  assert.equal(page.data.progressInfo.records.filter(row => row.text === '工程run丰富现场记录').length, 1);
  assert.equal(page.data.progressInfo.records.filter(row => row.text === '记录现场情况').length, 0, '工程run通用事件不得抢占最新进展');
  assert.equal(page.data.progressInfo.records.filter(row => row.text === '业务历史沟通').length, 1);
  assert.equal(page.data.progressInfo.records.filter(row => row.text === '记录客户跟进').length, 0, 'B.write通用事件不得与跟进原单重复');
  const current = await runtime.currentPage();
  const all = await current.$('[bindtap="toggleProgressRecords"]');
  assert(all, '项目进度页应提供全流程记录展开入口');
  await all.tap();
  assert.equal(page.data.showAllProgressRecords, true);
  assert.equal(page.data.visibleProgressRecords.length, 20, '全流程首次最多渲染20条');
  page.loadMoreProgressRecords();
  assert(page.data.visibleProgressRecords.length > 20, '应可增量加载后续记录');
  const loadedCount = page.data.visibleProgressRecords.length;
  page.onShow();
  assert.equal(page.data.visibleProgressRecords.length, loadedCount, 'onShow 刷新后应保留已加载的分页数');
  const nodes = render(path.resolve('miniprogram/pages/workflow/index.wxml'), page.data);
  const text = node => (node.text || '') + (node.children || []).map(text).join('');
  const rendered = nodes.map(text).join('');
  assert.match(rendered, /全流程记录/);
  assert.match(rendered, /工程run丰富现场记录/);
  assert.match(rendered, /业务历史沟通/);
  await runtime.disconnect();
}

verifyPage().then(() => {
  console.log('PASS 客户工作区按同项目投影业务、施工、材料和交付记录，并隔离跨公司与工程财务隐私');
}).catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
