const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');

const businessDirectory = path.resolve(
  process.env.FULL_FLOW_NATIVE_BUSINESS_OUT ||
  'qa/full-flow-acceptance-20261023/native-business'
);
const directory = path.resolve(
  process.env.FLOW_BOUNDARIES_NATIVE_OUT ||
  'qa/full-flow-acceptance-20261023/native-boundaries'
);
const fixtureDirectory = path.join(directory, 'fixtures');
const clone = value => JSON.parse(JSON.stringify(value));
const fixturePath = step => path.join(businessDirectory, 'fixtures', step + '.json');
const storageValue = (rows, key) => rows.find(row => row[0] === key)?.[1];

function loadFixture(step) {
  const file = fixturePath(step);
  assert(fs.existsSync(file), '缺少连续主链快照：' + file);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function fixtureCustomer(rows) {
  const customer = storageValue(rows, 'zhongshu-business-v3')?.customers
    ?.find(row => row.name === '周建国家');
  assert(customer, '快照中缺少周建国家');
  return customer;
}

async function main() {
  fs.mkdirSync(fixtureDirectory, { recursive: true });
  const source = {
    B04: loadFixture('B04'),
    B07: loadFixture('B07'),
    B09: loadFixture('B09')
  };
  const identities = Object.fromEntries(
    Object.entries(source).map(([step, rows]) => {
      const customer = fixtureCustomer(rows);
      return [step, {
        customerId: customer.id,
        projectId: customer.projectId,
        customer: customer.name,
        ownerId: customer.ownerId,
        owner: customer.owner
      }];
    })
  );
  assert.deepEqual(identities.B07, identities.B04, 'B04 与 B07 不是同一客户、项目和经办人');
  assert.deepEqual(identities.B09, identities.B04, 'B04 与 B09 不是同一客户、项目和经办人');

  const runtime = await require('./franchise-native-runtime.cjs').connect(
    'flow-boundaries',
    { directory, realEvents: true }
  );
  const report = {
    result: 'running',
    scope: 'R02 原生异常边界：六个可见账号登录沿用已通过的 R01 证据；本脚本对实际办理的张老板、李业务、林设计再次真实登录。老板越权、尾款不足和移交缺件均不能改写原业务单；缺件补齐后沿同一项目恢复。',
    roleCoverage: {
      reference: 'qa/full-flow-acceptance-20261023/native-login/results.json',
      referenceScenario: 'R01',
      retestedActors: ['张老板', '李业务', '林设计']
    },
    source: Object.fromEntries(Object.keys(source).map(step => [step, fixturePath(step)])),
    identity: identities.B04,
    roles: [],
    scenarios: [],
    interactions: []
  };
  const save = (name, value) => fs.writeFileSync(
    path.join(directory, name),
    JSON.stringify(value, null, 2)
  );
  const persist = () => save('results.json', report);
  const snapshot = () => runtime.snapshot();
  const rawCustomer = id => runtime.mp.evaluate(
    customerId => wx.getStorageSync('zhongshu-business-v3').customers
      .find(customer => customer.id === customerId),
    id
  );
  const pageData = async () => (await runtime.current()).data;
  const rendered = async selector => !!(await runtime.rects([selector]))[0];
  const commission = (view, id) => runtime.open(
    '/pages/workflow/commission/index?view=' + view + '&id=' + encodeURIComponent(id),
    '.commission-page'
  );
  async function capture(name) {
    await runtime.sleep(180);
    return runtime.capture(name);
  }
  async function writeFixture(name, rows) {
    const relative = 'fixtures/' + name + '.json';
    save(relative, rows);
    return relative;
  }
  async function install(step) {
    await runtime.open('/pages/account/index?view=policy', '.account', { relaunch: true });
    await runtime.replace(clone(source[step]));
  }
  async function scenario(id, name, step, actor, test) {
    console.log('START ' + id + ' ' + name);
    await install(step);
    const interactionStart = runtime.interactions.length;
    await runtime.login(actor.loginId, actor.role);
    const account = await runtime.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1'));
    assert.equal(account.session.staffId, actor.staffId);
    assert.equal(account.session.role, actor.role);
    assert.equal(account.profile.name, actor.name);
    if (!report.roles.some(row => row.staffId === actor.staffId)) {
      report.roles.push({ loginId: actor.loginId, role: actor.role, staffId: actor.staffId, name: actor.name, result: 'passed' });
    }
    const before = await snapshot();
    const beforeFixture = await writeFixture(id + '-before', before);
    const evidence = await test(fixtureCustomer(source[step]));
    const after = await snapshot();
    const afterFixture = await writeFixture(id + '-after', after);
    const interactions = runtime.interactions.slice(interactionStart);
    const result = {
      id,
      name,
      sourceStep: step,
      actor,
      result: 'passed',
      beforeFixture,
      afterFixture,
      interactions,
      ...evidence
    };
    report.scenarios.push(result);
    report.interactions.push(...interactions.map(row => ({ scenario: id, ...row })));
    persist();
    console.log('PASS ' + id + ' ' + name);
  }

  try {
    await scenario(
      'R02-01',
      '张老板不能代替李业务提交设计委托',
      'B04',
      { loginId: 'boss', staffId: 'zhao-boss', role: 'boss', name: '张老板' },
      async sourceCustomer => {
        const before = await rawCustomer(sourceCustomer.id);
        await commission('submit', sourceCustomer.id);
        const data = await pageData();
        assert.equal(data.customer.id, sourceCustomer.id);
        assert.equal(data.primary, '');
        assert.equal(data.fields.length, 0);
        assert.equal(await rendered('.commission-actions .commission-primary'), false);
        const screenshot = await capture('R02-01-boss-readonly');
        assert.deepEqual(await rawCustomer(sourceCustomer.id), before, '老板查看时改写了原委托');
        return { screenshot, assertion: '无提交按钮，完整客户原单未改写' };
      }
    );

    await scenario(
      'R02-02',
      '李业务缺少V2移交附件时被阻断，补齐后沿原项目恢复',
      'B09',
      { loginId: 'sales-dual', staffId: 'sales-dual', role: 'sales', name: '李业务' },
      async sourceCustomer => {
        const before = await rawCustomer(sourceCustomer.id);
        assert.equal(before.projectId, identities.B04.projectId);
        assert.equal(before.businessFlow.dispatch.version, 2);
        await commission('handover', sourceCustomer.id);
        assert.equal((await pageData()).primary, '提交资料移交');
        await runtime.event({ selector: 'input', dataset: { key: 'drawingVersion' } }, 'input', { value: 'V2' });
        await runtime.event({ selector: 'textarea', dataset: { key: 'note' } }, 'input', { value: '周建国家V2道路、水电与老人房尺寸已核对' });
        const data = await pageData();
        const managerIndex = data.managerOptions.findIndex(person => person.id === 'engineering-dual');
        assert(managerIndex >= 0, '同组织工程负责人张工程不在选择列表');
        await runtime.event({ selector: 'picker', dataset: { key: 'managerId' } }, 'change', { value: managerIndex });
        await runtime.event({ selector: 'checkbox-group', dataset: { key: 'received' } }, 'change', { value: ['yes'] });
        await runtime.event({ selector: 'checkbox-group', dataset: { key: 'confirmed' } }, 'change', { value: ['yes'] });
        await runtime.event('.commission-actions .commission-primary', 'submit');
        await runtime.until(async () => (await pageData()).error.includes('请补齐正式成果'), '缺少移交附件的业务校验');
        const blockedScreenshot = await capture('R02-02-missing-files-blocked');
        assert.deepEqual(await rawCustomer(sourceCustomer.id), before, '缺件提交改写了原项目');

        const files = {
          resultFile: { name: '周建国家-V2-正式设计成果.pdf', sample: true },
          drawingFile: { name: '周建国家-V2-施工图.pdf', sample: true },
          reviewFile: { name: '周建国家-V2-专业审核依据.pdf', sample: true },
          confirmationFile: { name: '周建国家-V2-客户确认凭据.pdf', sample: true }
        };
        await runtime.mp.evaluate(files => {
          const page = getCurrentPages().at(-1);
          for (const [key, file] of Object.entries(files)) page.setData({ ['form.' + key]: file });
        }, files);
        runtime.interactions.push({
          route: (await runtime.current()).route,
          keys: Object.keys(files),
          via: 'local sample attachment',
          note: '仅附件选择使用本地示例值'
        });
        await runtime.event('.commission-actions .commission-primary', 'submit');
        await runtime.until(async () => {
          const customer = await rawCustomer(sourceCustomer.id);
          return customer.handover?.status === 'pending';
        }, '补齐附件后恢复提交');
        const recovered = await rawCustomer(sourceCustomer.id);
        assert.equal(recovered.projectId, before.projectId);
        assert.equal(recovered.ownerId, 'sales-dual');
        assert.equal(recovered.handover.designVersion, 2);
        assert.equal(recovered.handover.managerId, 'engineering-dual');
        assert.deepEqual(recovered.serviceOrders, before.serviceOrders);
        assert.deepEqual(recovered.designPayments, before.designPayments);
        const recoveredFixture = await writeFixture('R02-02-recovered', await snapshot());
        const recoveredScreenshot = await capture('R02-02-files-completed');
        return {
          blockedScreenshot,
          recoveredScreenshot,
          recoveredFixture,
          assertion: '缺件时原单不变；补齐后仍为同一 customerId/projectId、V2 和经办人'
        };
      }
    );

    await scenario(
      'R02-03',
      '尾款不足时林设计没有成果下发动作',
      'B07',
      { loginId: 'hq-design-admin', staffId: 'hq-design-admin', role: 'hq-design-admin', name: '林设计' },
      async sourceCustomer => {
        const before = await rawCustomer(sourceCustomer.id);
        assert(before.businessFlow.completed, 'B07 应已登记设计完成');
        assert.equal(before.businessFlow.dispatch, undefined);
        const admin = await pageData();
        assert.equal(admin.view, 'admin');
        assert(!admin.tasks.some(task => task.customerId === sourceCustomer.id && task.actionUrl?.includes('view=dispatch')));
        const adminScreenshot = await capture('R02-03-admin-no-dispatch-task');
        await commission('dispatch', sourceCustomer.id);
        const data = await pageData();
        assert.equal(data.rows[0].value, '设计尾款待门店付款');
        assert.equal(data.primary, '');
        assert.equal(await rendered('.commission-actions .commission-primary'), false);
        const detailScreenshot = await capture('R02-03-tail-shortage-no-button');
        assert.deepEqual(await rawCustomer(sourceCustomer.id), before, '查看尾款不足边界时改写了原项目');
        return { adminScreenshot, detailScreenshot, assertion: '行政待办和原单详情均无成果下发按钮，原项目未改写' };
      }
    );

    assert.equal(runtime.errors.length, 0, JSON.stringify(runtime.errors));
    report.result = 'passed';
    report.exceptions = runtime.errors;
    persist();
  } catch (error) {
    report.result = 'failed';
    report.error = error.stack;
    report.exceptions = runtime.errors;
    try {
      report.failedPage = await runtime.current();
      save('failure-state.json', await snapshot());
      report.failureScreenshot = await capture('failure');
    } catch (captureError) {
      report.captureError = captureError.stack;
    }
    persist();
    throw error;
  } finally {
    await runtime.restore();
  }
}

main().then(
  () => process.exit(0),
  error => {
    console.error(error.stack);
    process.exit(1);
  }
);
