const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const acceptance = path.join(root, 'qa/full-flow-acceptance-20261023');
const output = path.join(acceptance, 'data-invariants.json');
const checks = [];

function read(relative) {
  const file = path.join(acceptance, relative);
  if (!fs.existsSync(file)) throw Error(`缺少最终验收产物：${relative}`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function readCross(relative) {
  const file = path.join(root, 'qa/design-collaboration-20261023/native', relative);
  if (!fs.existsSync(file)) throw Error(`缺少最终跨端验收产物：qa/design-collaboration-20261023/native/${relative}`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function storage(relative) {
  const rows = read(relative);
  assert(Array.isArray(rows), `${relative} 必须是微信存储快照`);
  return Object.fromEntries(rows);
}

function mainCustomer(state, label) {
  const customer = state['zhongshu-business-v3']?.customers?.find(row => row.phone === '13900008823');
  assert(customer, `${label} 缺少周建国家主链客户`);
  return customer;
}

function sourceFacts(customer) {
  return {
    source: customer.source,
    originLeadId: customer.originLeadId,
    originContentId: customer.originContentId,
    originPublicationId: customer.originPublicationId,
    originPublicationUrl: customer.originPublicationUrl,
    acquisitionSnapshot: customer.acquisitionSnapshot
  };
}

function designMoney(customer) {
  return {
    serviceTotalYuan: customer.serviceOrders?.at(-1)?.total,
    firstPaidYuan: customer.firstPayment?.amount,
    tailPaidYuan: customer.tailPayment?.amount,
    payments: (customer.designPayments || []).map(row => ({
      id: row.id,
      projectId: row.projectId,
      amountCents: row.amount,
      stage: row.stage,
      serviceVersion: row.serviceVersion
    }))
  };
}

function managementBook(state, companyId) {
  const management = state['zhongshu-management-v1'];
  assert(management, '缺少管理账簿');
  const book = management.companyId === companyId ? management : management.books?.[companyId];
  assert(book, `缺少 ${companyId} 账簿`);
  return book;
}

function mergedStoreLedger(state) {
  const previousWx = global.wx;
  global.wx = { getStorageSync: key => state[key] };
  try {
    return require('../miniprogram/utils/finance-ledger').merge(managementBook(state, 'quanzhou'));
  } finally {
    if (previousWx === undefined) delete global.wx;
    else global.wx = previousWx;
  }
}

function designLedgerFacts(state, customer) {
  const payments = mergedStoreLedger(state).payments
    .filter(row => row.projectId === customer.projectId && row.source?.module === 'business' && row.source?.kind === 'design')
    .map(row => ({ id: row.id, amountCents: row.amount, registrarId: row.registrarId }))
    .sort((left, right) => left.id.localeCompare(right.id));
  const paymentById = new Map((customer.designPayments || []).map(row => [row.id, row]));
  assert.equal(payments.length, 3, '门店应保留三笔设计付款原单');
  assert(payments.every(row => row.registrarId === 'finance-dual'), '门店三笔设计付款均应由王财务登记');
  for (const payment of payments) {
    assert.equal(payment.amountCents, paymentById.get(payment.id)?.amount, `${payment.id} 门店付款金额与客户原单不一致`);
  }

  const headquartersReceipts = managementBook(state, 'headquarters').receipts
    .filter(row => row.source?.customerId === customer.id && row.source?.kind === 'design')
    .map(row => ({
      id: row.id,
      amountCents: row.amount,
      registrarId: row.registrarId,
      originPaymentId: row.source.originPaymentId,
      projectId: row.source.projectId,
      transaction: row.transaction
    }))
    .sort((left, right) => left.originPaymentId.localeCompare(right.originPaymentId));
  assert.equal(headquartersReceipts.length, 3, '总部应保留三笔设计实际到账原单');
  assert.deepEqual(headquartersReceipts.map(row => row.amountCents).sort((a, b) => a - b), [200000, 400000, 600000]);
  assert.equal(headquartersReceipts.reduce((sum, row) => sum + row.amountCents, 0), 1200000, '总部设计实收应为12000.00元');
  for (const receipt of headquartersReceipts) {
    const payment = paymentById.get(receipt.originPaymentId);
    assert(payment, `${receipt.id} 未关联门店原付款`);
    assert.equal(receipt.registrarId, 'hq-finance-dual', `${receipt.id} 应由罗财务登记实际到账`);
    assert.equal(receipt.projectId, customer.projectId, `${receipt.id} 项目号不一致`);
    assert.equal(receipt.amountCents, payment.amount, `${receipt.id} 总部到账金额与门店原付款不一致`);
  }
  return { payments, headquartersReceipts };
}

function assertEngineeringReceipts(state, project) {
  const derived = mergedStoreLedger(state).receipts
    .filter(row => row.source?.module === 'engineering' && row.source?.id === project.id);
  assert.equal(derived.length, project.collections.length, '工程收款与门店派生收款单数量不一致');
  for (const collection of project.collections) {
    assert.equal(collection.registrarId, 'finance-dual', `${collection.id} 应由王财务登记`);
    const receipt = derived.find(row => row.source.recordId === collection.id);
    assert(receipt, `${collection.id} 缺少门店派生收款单`);
    assert.equal(receipt.id, `engineering-receipt:${project.id}:${collection.id}`);
    assert.equal(receipt.projectId, project.projectId);
    assert.equal(receipt.amount, collection.cents, `${collection.id} 派生收款金额不一致`);
    assert.equal(receipt.transaction, collection.transaction, `${collection.id} 派生收款流水不一致`);
    assert.deepEqual(receipt.file, collection.file, `${collection.id} 派生收款凭据不一致`);
    assert.equal(receipt.registrarId, 'finance-dual');
  }
  return derived.map(row => ({
    id: row.id,
    amountCents: row.amount,
    registrarId: row.registrarId,
    transaction: row.transaction
  }));
}

function pass(name, details) {
  checks.push({ name, result: 'passed', details });
}

function assertResult(relative, expectedChecks) {
  const result = read(relative);
  assert.equal(result.result, 'passed', `${relative} 尚未通过`);
  if (expectedChecks !== undefined) {
    const count = result.steps?.length ?? result.checks?.length ?? result.scenarios?.length;
    assert.equal(count, expectedChecks, `${relative} 场景数不完整`);
  }
  return result;
}

function verifyBusiness() {
  assertResult('native-business/results.json', 10);
  const states = Array.from({ length: 10 }, (_, index) => storage(`native-business/fixtures/B${String(index + 1).padStart(2, '0')}.json`));
  const customers = states.slice(1).map((state, index) => mainCustomer(state, `B${String(index + 2).padStart(2, '0')}`));
  const identity = {
    customerId: customers[0].id,
    projectId: customers[0].projectId,
    name: customers[0].name,
    phone: customers[0].phone,
    ownerId: customers[0].ownerId
  };
  assert.equal(identity.name, '周建国家');
  assert.equal(identity.ownerId, 'sales-dual');
  const source = sourceFacts(customers[0]);
  for (const customer of customers) {
    assert.deepEqual({
      customerId: customer.id,
      projectId: customer.projectId,
      name: customer.name,
      phone: customer.phone,
      ownerId: customer.ownerId
    }, identity, 'B02-B10 必须沿用同一客户和项目');
    assert.deepEqual(sourceFacts(customer), source, 'B02-B10 必须保留原作品获客来源');
  }
  const publicationSource = JSON.stringify(states[0]);
  for (const value of [source.originContentId, source.originPublicationId, source.acquisitionSnapshot?.workId, source.originPublicationUrl]) {
    assert(value && publicationSource.includes(value), `B01 发布源缺少 ${value || '必要标识'}`);
  }
  const finalCustomer = customers.at(-1);
  const ledger = designLedgerFacts(states.at(-1), finalCustomer);
  pass('B01作品源与B02-B10客户主链连续', {
    ...identity,
    source,
    storeDesignPaymentsCents: ledger.payments.map(row => row.amountCents),
    headquartersReceiptsCents: ledger.headquartersReceipts.map(row => row.amountCents),
    headquartersReceivedCents: ledger.headquartersReceipts.reduce((sum, row) => sum + row.amountCents, 0)
  });
  return { identity, source, money: designMoney(finalCustomer), ledger, finalCustomer };
}

function verifyEngineering(business) {
  assertResult('native-engineering/continuous-results.json', 16);
  const baselineMoney = business.money;
  let engineeringId = '';
  for (let index = 1; index <= 16; index++) {
    const state = storage(`native-engineering/fixtures/step-${String(index).padStart(2, '0')}.json`);
    const customer = mainCustomer(state, `E${String(index).padStart(2, '0')}`);
    assert.deepEqual({
      customerId: customer.id,
      projectId: customer.projectId,
      name: customer.name,
      phone: customer.phone,
      ownerId: customer.ownerId
    }, business.identity, `E${index} 必须接续业务主链原客户`);
    assert.deepEqual(sourceFacts(customer), business.source, `E${index} 必须保留原作品来源`);
    assert.deepEqual(designMoney(customer), baselineMoney, `E${index} 不得改写设计费用与到账事实`);
    assert.deepEqual(designLedgerFacts(state, customer), business.ledger, `E${index} 不得改写门店付款或总部到账原单`);
    const projects = state['zhongshu-engineering-v1']?.projects || [];
    const project = projects.find(row => row.sourceCustomerId === business.identity.customerId);
    if (index === 1) {
      assert.equal(project, undefined, 'E01 仅提交业务移交，工程项目不应提前生成');
      continue;
    }
    assert(project, `E${index} 缺少业务原单对应工程项目`);
    engineeringId ||= project.id;
    assert.equal(project.id, engineeringId, `E${index} 工程记录ID发生变化`);
    assert.equal(project.projectId, business.identity.projectId, `E${index} 工程项目号发生变化`);
    assert.equal(project.managerId, 'engineering-dual', `E${index} 工程负责人应为张工程`);
    assert.equal(project.drawingVersion, 'V2', `E${index} 应承接业务移交的V2图纸`);
  }

  const finalState = storage('native-engineering/continuous-final-state.json');
  const finalProject = finalState['zhongshu-engineering-v1'].projects.find(row => row.id === engineeringId);
  assert(finalProject, '工程终态缺少同一工程记录');
  assert.equal(finalProject.recordFlow.contract.total, 10000000, '工程合同应为100000.00元');
  assert.equal(finalProject.collections.reduce((sum, row) => sum + row.cents, 0), 10000000, '工程累计实收应为100000.00元');
  const derivedReceipts = assertEngineeringReceipts(finalState, finalProject);
  assert(finalProject.archived && finalProject.delivery, '工程终态必须已交付归档');
  assert(finalProject.plan.nodes.length > 0 && finalProject.plan.nodes.every(row => row.status === '已完成'), '工程全部节点必须完成');
  pass('E01-E16承接同一客户、项目、负责人和V2图纸', {
    engineeringId,
    projectId: finalProject.projectId,
    managerId: finalProject.managerId,
    drawingVersion: finalProject.drawingVersion,
    contractCents: finalProject.recordFlow.contract.total,
    collectedCents: finalProject.collections.reduce((sum, row) => sum + row.cents, 0),
    derivedReceipts,
    archived: finalProject.archived,
    completedNodes: finalProject.plan.nodes.length
  });
}

function verifyDesign(business) {
  assertResult('native-design/results.json', 4);
  const state = storage('native-design/fixtures/D04.json');
  const customer = mainCustomer(state, 'D04');
  assert.equal(customer.id, business.identity.customerId);
  assert.equal(customer.projectId, business.identity.projectId);
  assert.equal(customer.ownerId, 'sales-dual');
  assert.deepEqual(sourceFacts(customer), business.source, 'D分支不得改变原作品来源');
  const plan = customer.plans.at(-1);
  const facade = customer.facades.at(-1);
  const budget = customer.budgets.at(-1);
  assert(plan && facade && budget, 'D04 必须保存平面、立面和预算');
  assert.equal(plan.selected, 2, '应保存第三个平面候选');
  assert.equal(facade.selected, 0, '应保存第一个立面候选');
  assert.equal(String(plan.floors), '3');
  assert.equal(String(facade.floors), '3');
  assert.equal(String(budget.parameters.floors), '3');
  assert.equal(budget.planVersion, plan.version);
  assert.equal(budget.facadeVersion, facade.version);
  assert.equal(budget.bodyRows.reduce((sum, row) => sum + row.value, 0), budget.main, '主体预算分项合计不一致');
  assert.equal(budget.exteriorRows.reduce((sum, row) => sum + row.value, 0), budget.exterior, '外装预算分项合计不一致');
  assert.equal(budget.main + budget.exterior, budget.total, '预算总额不守恒');
  pass('D01-D04独立设计分支回写同一原客户且预算守恒', {
    customerId: customer.id,
    projectId: customer.projectId,
    planVersion: plan.version,
    selectedPlan: plan.selected,
    facadeVersion: facade.version,
    selectedFacade: facade.selected,
    budgetVersion: budget.version,
    mainYuan: budget.main,
    exteriorYuan: budget.exterior,
    totalYuan: budget.total
  });
}

function verifyOwner(business) {
  assertResult('native-owner/results.json', 6);
  for (let index = 1; index <= 6; index++) {
    const state = storage(`native-owner/fixtures/M${String(index).padStart(2, '0')}.json`);
    const customer = mainCustomer(state, `M${String(index).padStart(2, '0')}`);
    assert.deepEqual({
      customerId: customer.id,
      projectId: customer.projectId,
      name: customer.name,
      phone: customer.phone,
      ownerId: customer.ownerId
    }, business.identity, `M${index} 主链客户被分页样本替换`);
    assert.deepEqual(sourceFacts(customer), business.source, `M${index} 主链作品来源被改写`);
    assert.deepEqual(designLedgerFacts(state, customer), business.ledger, `M${index} 不得改写门店付款或总部到账原单`);
    const samples = state['zhongshu-business-v3'].customers.filter(row => row.id.startsWith('owner-p0-page-'));
    assert.equal(samples.length, 21, `M${index} 应包含21条独立分页样本`);
    for (const sample of samples) {
      assert.notEqual(sample.id, business.identity.customerId);
      assert.notEqual(sample.projectId, business.identity.projectId);
      assert(!sample.request && !sample.handover && !sample.businessFlow, `${sample.id} 不得继承业务委托或移交`);
      assert.equal((sample.serviceOrders || []).length, 0, `${sample.id} 不得继承设计服务单`);
      assert.equal((sample.designPayments || []).length, 0, `${sample.id} 不得继承原项目付款`);
      assert(!sample.originLeadId && !sample.originContentId && !sample.originPublicationId, `${sample.id} 不得继承原作品来源`);
    }
  }
  pass('M01-M06保留主客户并隔离21条分页样本', {
    customerId: business.identity.customerId,
    projectId: business.identity.projectId,
    paginationSamples: 21
  });
}

function assertIdsKept(before, after, key, label) {
  const afterIds = new Set((after[key] || []).map(row => row.id));
  for (const row of before[key] || []) assert(afterIds.has(row.id), `${label} 丢失原 ${key} ID：${row.id}`);
}

function verifyCorrections() {
  const result = assertResult('native-corrections/results.json', 7);
  assert.equal(result.inputScenes, 'input-scenes.json', 'C场景必须引用原生实际安装的输入快照');
  const input = read('native-corrections/input-scenes.json');
  const expectedIds = ['C06', 'C01-reduce', 'C01-cancel', 'C02', 'C03', 'C04-C05', 'E13-archive'];
  assert.equal(input.scenes.length, expectedIds.length);
  assert.deepEqual(result.checks.map(row => row.id), expectedIds, 'C场景与原生安装顺序不一致');
  for (const [index, id] of expectedIds.entries()) {
    const scene = input.scenes[index];
    const check = result.checks[index];
    assert.equal(check.fixture, `fixtures/${id}.json`);
    const beforeState = Object.fromEntries(scene.before);
    const afterState = storage(`native-corrections/${check.fixture}`);
    const beforeProjects = beforeState['zhongshu-engineering-v1']?.projects || [];
    assert(beforeProjects.length, `${id} 输入缺少工程原记录`);
    for (const beforeProject of beforeProjects) {
      const afterProject = afterState['zhongshu-engineering-v1']?.projects?.find(row => row.id === beforeProject.id);
      assert(afterProject, `${id} 丢失原工程ID：${beforeProject.id}`);
      assert.equal(afterProject.projectId, beforeProject.projectId, `${id} 改写项目号`);
      assert.equal(afterProject.sourceCustomerId, beforeProject.sourceCustomerId, `${id} 改写来源客户`);
      assert.equal(afterProject.managerId, beforeProject.managerId, `${id} 改写工程负责人`);
      assertIdsKept(beforeProject, afterProject, 'materials', id);
      assertIdsKept(beforeProject, afterProject, 'supplies', id);
      assertIdsKept(beforeProject.plan || {}, afterProject.plan || {}, 'nodes', id);
      assertIdsKept(beforeProject, afterProject, 'receipts', id);
    }
    const session = afterState['zhongshu-account-v1']?.session;
    assert.equal(session?.staffId, scene.actor.loginId, `${id} 执行账号与原生输入场景不一致`);
  }
  pass('C01-C06及E13修正场景保留原工程身份和记录ID', {
    input: 'native-corrections/input-scenes.json',
    scenarios: expectedIds
  });
}

function verifyBoundaries(business) {
  const result = assertResult('native-boundaries/results.json', 3);
  assert.equal(result.scenarios.length, 3);
  for (const scenario of result.scenarios) {
    assert(scenario.beforeFixture && scenario.afterFixture, `${scenario.id} 缺少边界前后快照`);
    for (const fixture of [scenario.beforeFixture, scenario.afterFixture]) {
      const state = storage(`native-boundaries/${fixture}`);
      const customer = mainCustomer(state, `${scenario.id} ${fixture}`);
      assert.deepEqual({
        customerId: customer.id,
        projectId: customer.projectId,
        name: customer.name,
        phone: customer.phone,
        ownerId: customer.ownerId
      }, business.identity, `${scenario.id} 边界办理改写主客户身份`);
    }
    if (scenario.id === 'R02-02') {
      const recovered = storage(`native-boundaries/${scenario.recoveredFixture || scenario.afterFixture}`);
      const customer = mainCustomer(recovered, 'R02-02 补齐后');
      assert.equal(customer.handover?.designVersion, 2, 'R02-02 补齐后应继续承接V2设计成果');
      assert.equal(customer.handover?.drawingVersion, 'V2', 'R02-02 补齐后图纸仍应为V2');
    }
  }
  pass('R02三条异常边界保持同一主客户和项目', {
    customerId: business.identity.customerId,
    projectId: business.identity.projectId,
    ownerId: business.identity.ownerId,
    scenarios: result.scenarios.map(row => row.id)
  });
}

function verifyCollaboration(business) {
  const report = readCross('results.json');
  assert.equal(report.result, 'passed', 'X01 跨端原生验收尚未通过');
  assert(report.customerId && report.projectId && report.fixture, 'X01 报告缺少动态案例标识或快照');
  const rows = readCross(report.fixture);
  assert(Array.isArray(rows), 'X01 场景必须是裸微信存储数组');
  const state = Object.fromEntries(rows);
  const customer = state['zhongshu-business-v3']?.customers?.find(row => row.id === report.customerId);
  assert(customer, 'X01 快照缺少报告指定客户');
  assert.equal(customer.projectId, report.projectId);
  assert.notEqual(customer.id, business.identity.customerId, 'X01 应为独立案例，不得复用周建国客户ID');
  assert.notEqual(customer.projectId, business.identity.projectId, 'X01 应为独立案例，不得复用周建国项目ID');

  const service = customer.serviceOrders?.at(-1);
  assert(service, 'X01 缺少设计费用约定');
  assert.equal(service.total, 100, 'X01 设计费应为100元');
  assert.equal(service.first, 40, 'X01 首款应为40元');
  assert.equal(service.tail, 60, 'X01 尾款应为60元');
  assert.equal(service.first + service.tail, service.total, 'X01 首尾款合计不等于设计费');

  const payments = customer.designPayments || [];
  assert.equal(payments.length, 2, 'X01 应保留首尾两笔门店原付款');
  assert(payments.every(row => row.projectId === customer.projectId && row.registrarId === 'finance-dual'), 'X01 门店付款须由王财务登记在同一项目');
  assert.deepEqual(payments.map(row => row.amount).sort((a, b) => a - b), [4000, 6000]);
  assert.equal(payments.reduce((sum, row) => sum + row.amount, 0), 10000, 'X01 门店付款合计应为10000分');

  const paymentById = new Map(payments.map(row => [row.id, row]));
  const receipts = managementBook(state, 'headquarters').receipts
    .filter(row => row.source?.customerId === customer.id && row.source?.kind === 'design');
  assert.equal(receipts.length, 2, 'X01 总部应保留首尾两笔实际到账');
  for (const receipt of receipts) {
    const payment = paymentById.get(receipt.source.originPaymentId);
    assert(payment, `${receipt.id} 未匹配X01门店原付款`);
    assert.equal(receipt.registrarId, 'hq-finance-dual');
    assert.equal(receipt.source.projectId, customer.projectId);
    assert.equal(receipt.amount, payment.amount);
  }
  assert.equal(receipts.reduce((sum, row) => sum + row.amount, 0), 10000, 'X01 总部实际到账合计应为10000分');

  const dispatch = customer.businessFlow?.dispatch;
  assert.equal(dispatch?.actorId, 'hq-design-admin', 'X01 应由总部行政下发');
  assert.equal(dispatch?.recipient, '李业务', 'X01 应由李业务收图');
  assert.equal(report.actors?.designAdmin, 'hq-design-admin');
  assert.equal(report.actors?.recipient, 'sales-dual');

  const budget = customer.budgets?.at(-1);
  assert(budget, 'X01 缺少真实预算结果');
  assert.equal(String(customer.width), '12');
  assert.equal(String(customer.depth), '14');
  assert.equal(String(customer.floors), '2');
  assert.equal(String(budget.parameters.width), String(customer.width));
  assert.equal(String(budget.parameters.depth), String(customer.depth));
  assert.equal(String(budget.parameters.floors), String(customer.floors));
  assert.equal(budget.area, 336);
  assert.equal(budget.area, Number(customer.width) * Number(customer.depth) * Number(customer.floors));
  assert.equal(budget.bodyRows.reduce((sum, row) => sum + row.value, 0), budget.main, 'X01 主体预算分项不守恒');
  assert.equal(budget.exteriorRows.reduce((sum, row) => sum + row.value, 0), budget.exterior, 'X01 外装预算分项不守恒');
  assert.equal(budget.main + budget.exterior, budget.total, 'X01 预算总额不守恒');

  pass('X01独立跨端案例内部客户、预算、付款、到账和收图一致', {
    customerId: customer.id,
    projectId: customer.projectId,
    independentFromMainFlow: true,
    dimensions: { width: 12, depth: 14, floors: 2, area: 336 },
    designFeeYuan: service.total,
    storePaymentsCents: payments.map(row => row.amount),
    headquartersReceiptsCents: receipts.map(row => row.amount),
    designAdmin: dispatch.actorId,
    recipient: dispatch.recipient
  });
}

try {
  const business = verifyBusiness();
  verifyEngineering(business);
  verifyDesign(business);
  verifyOwner(business);
  verifyCorrections();
  verifyBoundaries(business);
  verifyCollaboration(business);
  const report = {
    result: 'passed',
    scope: '六角色主链跨业务、工程、设计和老板管理场景的模拟数据一致性',
    units: { yuan: '字段后缀Yuan', cents: '字段后缀Cents；1元=100分' },
    identity: business.identity,
    money: {
      designServiceYuan: business.money.serviceTotalYuan,
      designPaidCents: business.money.payments.reduce((sum, row) => sum + row.amountCents, 0),
      headquartersDesignReceivedCents: business.ledger.headquartersReceipts.reduce((sum, row) => sum + row.amountCents, 0),
      engineeringContractCents: 10000000,
      engineeringCollectedCents: 10000000
    },
    checks
  };
  fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`PASS ${checks.length}组跨模块模拟数据一致性校验：${path.relative(root, output)}`);
} catch (error) {
  console.error(`模拟数据一致性校验失败：${error.message}`);
  process.exitCode = 1;
}
