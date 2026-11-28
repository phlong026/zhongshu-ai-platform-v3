// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/six-role-project-20261030');
const read = file => JSON.parse(fs.readFileSync(path.join(directory, file), 'utf8'));
const renderedText = value => Array.isArray(value)
  ? value.map(renderedText).join(' ')
  : value && typeof value === 'object'
    ? [value.text || '', renderedText(value.children || [])].join(' ')
    : '';
function run(script, environment = {}) {
  const result = spawnSync(process.execPath, [path.join(__dirname, script)], {
    cwd: root, env: { ...process.env, ...environment }, stdio: 'inherit', timeout: 180000
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, script + ' 必须通过才可接续');
}

async function main() {
  run('verify-six-role-flow.cjs', { FULL_FLOW_OUT: directory });
  run('verify-engineering-records.cjs', {
    ENGINEERING_CONTINUATION_SOURCE: path.join(directory, 'page-business/engineering-continuation.json'),
    ENGINEERING_PAGE_OUT: path.join(directory, 'page-engineering')
  });
  const business = read('page-business/results.json');
  const engineering = read('page-engineering/results.json');
  const initial = Object.fromEntries(read('page-business/engineering-continuation.json').before);
  let rows = read('page-engineering/continuous-final-state.json');
  const final = Object.fromEntries(rows);
  const customer = final['zhongshu-business-v3'].customers.find(row => row.id === business.customerId);
  const project = final['zhongshu-engineering-v1'].projects.find(row => row.id === engineering.engineeringId);
  const original = initial['zhongshu-business-v3'].customers.find(row => row.id === customer.id);
  assert.equal(engineering.customerId, customer.id);
  assert.equal(project.sourceCustomerId, customer.id);
  assert.equal(project.projectId, business.projectId);
  assert.equal(customer.projectId, business.projectId);
  assert.deepEqual(customer.acquisitionSnapshot, original.acquisitionSnapshot);
  assert.equal(customer.originPublicationId, original.originPublicationId);
  assert.deepEqual(customer.designPayments, original.designPayments);
  assert.equal(project.managerId, 'engineering-dual');
  assert.equal(customer.ownerId, 'sales-dual');
  assert.equal(project.drawingVersion, 'V2');
  assert(project.archived && project.delivery && project.settlement);
  assert(project.plan.nodes.every(node => node.status === '已完成'));
  assert.equal(project.collections.reduce((sum, item) => sum + item.cents, 0), project.recordFlow.contract.total);

  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Access = require('../miniprogram/utils/access');
  const Checkout = require('../miniprogram/utils/design-checkout');
  const Finance = require('../miniprogram/utils/finance-source');
  const Management = require('./fixtures/legacy-admin/model');
  const Organization = require('../miniprogram/utils/organization');
  const { render } = require('./role-page-runtime.cjs');
  const designOrder = Checkout.order(customer);
  assert(designOrder, '同一项目必须保留一次性设计费原订单');
  const reimbursement = await require('./business-reimbursement-journey.cjs').settle({ runtime, rows, customerId: customer.id, projectId: project.projectId });
  rows = reimbursement.rows;
  fs.writeFileSync(path.join(directory, 'single-project-final-state.json'), JSON.stringify(rows, null, 2));
  const roles = [
    ['sales-dual', 'sales-lead', 'sales', '/pages/business/index'],
    ['engineering-dual', 'project-manager', 'engineer', '/pages/build/record/index?id=' + encodeURIComponent(project.id) + '&view=overview'],
    ['finance-dual', 'finance-lead', 'finance', '/pages/admin/finance-desk/index?view=flows&tab=ledger&scope=all'],
    ['boss', 'boss', '', '/pages/admin/index?view=finance&id=' + encodeURIComponent(project.projectId)],
    ['hq-design-admin', 'hq-design-admin', '', '/pages/workflow/commission/index?view=admin'],
    ['hq-finance-dual', 'finance-lead', 'finance', '/pages/management/index']
  ];
  const views = [];
  for (const [person, expectedRole, inheritedRole, url] of roles) {
    wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
    rows.forEach(([key, value]) => wx.setStorageSync(key, value));
    Account.mutate(state => Account.loginDemo(state, person));
    const actor = Access.context();
    assert.equal(actor.role, expectedRole, person + ' 必须以明确的当前身份进入终态验收');
    if (inheritedRole) assert(Organization.has(actor.role, inheritedRole), person + ' 负责人身份应只继承本部门经办能力');
    assert(!Organization.has(actor.role, inheritedRole === 'sales' ? 'engineer' : 'sales'), person + ' 兼岗不得跨部门继承权限');
    if (person !== 'boss') assert(!Organization.has(actor.role, inheritedRole === 'finance' ? 'engineer' : 'finance'), person + ' 兼岗不得跨部门继承财务或工程权限');
    const started = performance.now();
    await runtime.callWxMethod('reLaunch', { url });
    const page = getCurrentPages().at(-1);
    assert(!page.data.error && !page.data.missing && page.data.view !== 'access', person + ' 终态入口可达');
    const html = renderedText(render(page.file, page.data));
    assert(html.includes(customer.name), person + ' 页面必须呈现同一客户成果');
    let evidence;
    if (person === 'sales-dual') {
      const visible = page.data.customers.find(row => row.id === customer.id);
      assert(visible && page.data.recentCustomer?.id === customer.id, '业务必须在客户列表和最近客户看到同一客户');
      assert.equal(customer.projectId, project.projectId);
      assert(customer.originPublicationId, '客户必须保留原发布登记来源');
      evidence = { type: 'customer', customerId: visible.id, projectId: customer.projectId, publicationId: customer.originPublicationId, status: visible.desc };
    } else if (person === 'engineering-dual') {
      const visible = page.data.p;
      assert(visible && visible.archived && visible.delivery && visible.settlement, '工程负责人必须看到同一项目已交付、结算及归档成果');
      assert.equal(visible.sourceCustomerId, customer.id);
      assert.equal(visible.projectId, project.projectId);
      assert.equal(visible.drawingVersion, 'V2');
      assert(visible.documents.some(doc => doc.file?.meta?.customerId === customer.id && doc.file.meta.projectId === project.projectId && doc.file.meta.version === 'V2'), '工程端必须看到属于同客户同项目的V2正式成果');
      evidence = { type: 'engineering-project', customerId: visible.sourceCustomerId, projectId: visible.projectId, engineeringId: visible.id, drawingVersion: visible.drawingVersion, status: '已归档' };
    } else if (person === 'finance-dual') {
      const paymentRow = page.data.rows.find(row => row.id === reimbursement.evidence.paymentId);
      const payable = Management.load().payables.find(row => row.id === reimbursement.evidence.payableId);
      assert(paymentRow && payable, '门店财务流水必须呈现同客户的设计费报销实付原单');
      assert.equal(payable.projectId, project.projectId);
      assert.equal(payable.source?.id, customer.id);
      assert.equal(payable.status, '已付清');
      assert.equal(paymentRow.status, '已核实');
      assert(!page.data.allTasks.some(row => row.id === payable.id), '已付清报销不得继续出现在门店财务待办');
      evidence = { type: 'store-reimbursement-payment', customerId: payable.source.id, projectId: payable.projectId, payableId: payable.id, paymentId: paymentRow.id, amount: reimbursement.evidence.amount, status: payable.status };
    } else if (person === 'boss') {
      const ledger = page.data.sections.flatMap(section => section.rows).find(row => row.id === reimbursement.evidence.paymentId);
      assert(ledger && ledger.view === 'partial-payment' && ledger.subtitle.includes('业务垫付设计费报销'), '老板必须在同一项目账看到已核实报销实付原单');
      assert(!Organization.has(actor.role, 'engineer') && !Organization.has(actor.role, 'finance'), '老板查看不得伪装成工程或财务经办身份');
      evidence = { type: 'owner-ledger', customerId: customer.id, projectId: project.projectId, recordId: ledger.id, status: '已核实只读查看' };
    } else if (person === 'hq-design-admin') {
      const visible = page.data.projectRows.find(row => row.id === customer.id);
      assert(visible && !visible.actionable, '总部设计必须看到同项目已下发且不能重复办理');
      assert.equal(visible.projectId, project.projectId);
      assert.equal(visible.status, '已下发');
      evidence = { type: 'design-project', customerId: visible.id, projectId: visible.projectId, status: visible.status, result: visible.recentText };
    } else {
      const focus = page.data.deskHome.focus.find(row => row.id.startsWith(customer.id + ':'));
      const headquarters = Finance.merge(Access.rawBook('headquarters'));
      const receipt = headquarters.receipts.find(row => row.source?.module === 'design-checkout' && row.source.customerId === customer.id);
      assert(focus && receipt, '总部财务页面焦点必须可追到同客户设计费原收据');
      assert.equal(receipt.projectId, project.projectId);
      assert.equal(receipt.amount, designOrder.amountCents);
      assert.equal(receipt.status, '体验模拟');
      evidence = { type: 'hq-design-receipt', customerId: receipt.source.customerId, projectId: receipt.projectId, recordId: receipt.id, status: receipt.status };
    }
    views.push({ person, activeRole: actor.role, url, route: page.route, evidence, nodePageAndRenderMs: Math.round(performance.now() - started) });
  }
  const result = { result: 'passed', evidence: '同一客户从创作到归档及业务垫付报销付清的Node Page/WXML事件；附件/生成/设计支付为模拟。耗时不代表手机性能。', customerId: customer.id, projectId: project.projectId, engineeringId: project.id, publicationId: customer.originPublicationId, workId: customer.acquisitionSnapshot.workId, drawingVersion: project.drawingVersion, contractCents: project.recordFlow.contract.total, collectedCents: project.collections.reduce((sum, row) => sum + row.cents, 0), reimbursement: reimbursement.evidence, archived: true, settled: true, businessSteps: business.steps.length, engineeringChecks: engineering.checks.length, views };
  fs.writeFileSync(path.join(directory, 'single-project-results.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
