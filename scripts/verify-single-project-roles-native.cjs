const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const sourceFile = path.resolve(process.env.SINGLE_PROJECT_NATIVE_SOURCE
  || 'qa/six-role-project-20261030/native-engineering/continuous-final-state.json');
const out = path.resolve(process.env.SINGLE_PROJECT_ROLES_NATIVE_OUT
  || 'qa/six-role-project-20261030/native-roles');
const wsEndpoint = process.env.SINGLE_PROJECT_NATIVE_WS || 'ws://127.0.0.1:10061';

const sourceRows = JSON.parse(fs.readFileSync(sourceFile, 'utf8'));
const source = Object.fromEntries(sourceRows);
const business = source['zhongshu-business-v3'];
const engineering = source['zhongshu-engineering-v1'];
const managementRoot = source['zhongshu-management-v1'];
assert(business && engineering && managementRoot, '原生连续终态缺少业务、工程或财务账簿');

const projects = engineering.projects.filter(row => row.recordFlow && row.archived && row.delivery && row.settlement
  && row.drawingVersion === 'V2' && row.plan?.nodes?.every(node => node.status === '已完成'));
const linked = projects.map(project => ({ project, customer: business.customers.find(row => row.id === project.sourceCustomerId) }))
  .filter(row => row.customer?.originPublicationId && row.customer.projectId === row.project.projectId);
assert.equal(linked.length, 1, '原生终态必须唯一定位内容获客、V2成果和工程归档的同一项目');
const { customer, project } = linked[0];
const serviceVersion = customer.businessFlow?.authorization?.serviceVersion || customer.serviceOrders?.at(-1)?.version;
assert(serviceVersion, '同一项目缺少设计服务版本');
const payableId = 'business-reimburse:' + customer.id + ':' + serviceVersion;
const storeBook = managementRoot.companyId === customer.companyId ? managementRoot : managementRoot.books?.[customer.companyId];
const reimbursement = storeBook?.payments?.find(row => row.payableId === payableId && row.projectId === project.projectId && row.status === '已核实');
assert(reimbursement, '原生终态必须先完成业务垫付设计费报销实付，再验收六角色成果');

const normalize = rows => Object.fromEntries(rows);
const same = (actual, expected, key) => assert.deepEqual(actual[key], expected[key], '当前原生窗口不是指定连续终态：' + key);

(async () => {
  const runtime = await require('./franchise-native-runtime.cjs').connect('single-project-roles', {
    directory: out, wsEndpoint, realEvents: true
  });
  const checks = [], screenshots = [];
  const pass = name => { checks.push({ name, result: 'passed' }); console.log('PASS ' + name); };
  const shot = async name => {
    await runtime.mp.evaluate(() => { wx.hideToast(); wx.pageScrollTo({ scrollTop: 0, duration: 0 }); return true; });
    await runtime.sleep(500);
    const file = await runtime.capture(name);
    screenshots.push(file);
    return file;
  };
  const assertRole = async role => assert.equal(
    await runtime.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session.role), role,
    '必须显式处于验收角色 ' + role
  );
  const settleRoleHome = async role => {
    if (!['finance', 'finance-lead'].includes(role)) return;
    await runtime.until(async () => {
      const current = await runtime.current();
      return current.route === 'pages/admin/finance-desk/index' && current.data.ready;
    }, '财务登录后共享管理容器完成转入财务工作台', 12000);
  };
  const loginAs = async (id, role) => {
    await runtime.login(id);
    const currentRole = await runtime.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session.role);
    await settleRoleHome(currentRole);
    if (currentRole !== role) {
      await runtime.open('/pages/account/index?view=identity', '.account', { relaunch: true });
      await runtime.event({ selector: '.identity-option', dataset: { role } }, 'selectRole');
      await runtime.event('.page-actions .solid', 'switchRole');
      await settleRoleHome(role);
    }
    await assertRole(role);
  };
  try {
    const live = normalize(await runtime.snapshot());
    for (const key of ['zhongshu-business-v3', 'zhongshu-engineering-v1', 'zhongshu-management-v1']) same(live, source, key);
    pass('连接窗口与指定原生连续终态完全一致，未注入或改写业务事实');

    await loginAs('sales-dual', 'sales-lead');
    await runtime.open('/pages/business/index', '.sales-page', { relaunch: true });
    let data = await runtime.data();
    const salesCustomer = data.customers.find(row => row.id === customer.id);
    assert(salesCustomer && data.recentCustomer?.id === customer.id, '业务负责人必须在客户列表和最近办理看到同一客户');
    assert.equal(customer.projectId, project.projectId);
    assert(customer.originPublicationId && customer.acquisitionSnapshot?.workId, '客户必须保留原发布登记和内容作品来源');
    await shot('01-sales-same-customer');
    pass('李业务看到同一内容来源客户及业务移交完成成果');

    await loginAs('engineering-dual', 'project-manager');
    await runtime.open('/pages/build/record/index?id=' + encodeURIComponent(project.id) + '&view=overview', '.record-page', { relaunch: true });
    data = await runtime.data();
    assert.equal(data.p.id, project.id);
    assert.equal(data.p.sourceCustomerId, customer.id);
    assert.equal(data.p.projectId, project.projectId);
    assert(data.p.archived && data.p.delivery && data.p.settlement, '工程端必须看到交付、结算、归档终态');
    assert(data.p.documents.some(row => row.file?.meta?.customerId === customer.id && row.file.meta.projectId === project.projectId && row.file.meta.version === 'V2'), '工程端必须看到同客户同项目V2正式成果');
    await shot('02-engineering-v2-archive');
    pass('张工程看到同一项目V2成果、交付、结算和归档');

    await loginAs('finance-dual', 'finance');
    await runtime.open('/pages/admin/index?view=pay&id=' + encodeURIComponent(payableId), '.view-pay', { relaunch: true });
    data = await runtime.data();
    const payable = data.state.payables.find(row => row.id === payableId);
    assert(payable && payable.status === '已付清' && data.primary === '', '门店财务必须看到原报销应付已付清且不可重复登记');
    await shot('03-store-finance-reimbursement-closed');
    await runtime.open('/pages/admin/index?view=partial-payment&id=' + encodeURIComponent(reimbursement.id), '.view-partial-payment', { relaunch: true });
    data = await runtime.data();
    const payment = data.state.payments.find(row => row.id === reimbursement.id);
    assert(payment && payment.status === '已核实' && payment.projectId === project.projectId, '门店财务付款回执必须呈现同项目报销实付');
    await shot('03-store-finance-payment-ledger');
    await runtime.open('/pages/admin/index?view=todos', '.view-todos', { relaunch: true });
    data = await runtime.data();
    assert(!data.sections.flatMap(section => section.rows).some(row => row.id === payableId || row.url?.includes(encodeURIComponent(payableId))), '已付清报销不得仍在财务待办');
    pass('王财务看到同项目报销原单已付清、实付已核实且待办消失');

    await loginAs('boss', 'boss');
    await runtime.open('/pages/admin/index?view=finance&id=' + encodeURIComponent(project.projectId), '.view-finance', { relaunch: true });
    data = await runtime.data();
    const ownerPayment = data.sections.flatMap(section => section.rows).find(row => row.id === reimbursement.id);
    assert(ownerPayment && ownerPayment.view === 'partial-payment' && ownerPayment.subtitle.includes('业务垫付设计费报销'), '老板必须在同项目账看到报销实付原单');
    await shot('04-owner-project-ledger');
    pass('张老板只读看到同项目完整收支及已核实报销付款');

    await loginAs('hq-design-admin', 'hq-design-admin');
    await runtime.open('/pages/workflow/commission/index?view=admin', '.commission-admin', { relaunch: true });
    data = await runtime.data();
    const design = data.projectRows.find(row => row.id === customer.id);
    assert(design && design.projectId === project.projectId && design.status === '已下发' && !design.actionable, '总部设计必须看到同项目成果已下发且不可重复办理');
    await shot('05-hq-design-dispatched');
    pass('林设计看到同一客户同一项目成果已下发');

    await loginAs('hq-finance-dual', 'finance');
    const hqReceiptId = 'design-demo-receipt:' + customer.businessFlow.paymentOrder.id + ':headquarters';
    await runtime.open('/pages/admin/index?view=receipt&id=' + encodeURIComponent(hqReceiptId), '.view-receipt', { relaunch: true });
    data = await runtime.data();
    const hqFinance = data.state.receipts.find(row => row.id === hqReceiptId);
    assert(hqFinance && hqFinance.source?.customerId === customer.id && hqFinance.projectId === project.projectId, '总部财务原收据必须保持同customerId和projectId');
    assert(hqFinance.simulated && hqFinance.readOnly && hqFinance.status === '体验模拟', '总部财务必须如实显示体验模拟支付，不得伪称真实到账');
    await shot('06-hq-finance-design-copy');
    pass('罗财务看到同项目设计费体验模拟抄送及只读边界');

    assert.equal(runtime.errors.length, 0, JSON.stringify(runtime.errors));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({
      result: 'passed', source: sourceFile, wsEndpoint, customerId: customer.id,
      projectId: project.projectId, engineeringId: project.id, reimbursementId: reimbursement.id,
      checks, screenshots, interactions: runtime.interactions, exceptions: runtime.errors,
      note: '仅原生登录、身份切换、页面导航、读取和截图；未通过业务模型写入事实。总部设计费仍为体验模拟，不代表真实银行到账。'
    }, null, 2));
  } catch (error) {
    fs.mkdirSync(out, { recursive: true });
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'failed', error: error.stack, checks, screenshots, exceptions: runtime.errors }, null, 2));
    try { await shot('failure'); } catch (_) {}
    throw error;
  } finally {
    await runtime.restore();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
