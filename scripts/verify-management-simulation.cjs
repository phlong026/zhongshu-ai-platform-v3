const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

(async () => {
  await require('./verify-owner-workbench.cjs').fixture();
  const { createManagementSimulation } = require('../server/management/server.cjs');
  const storage = Object.fromEntries(wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'management-simulation-'));
  const dataFile = path.join(directory, 'state.json');
  const invites = { 'test-boss-one': 'boss', 'test-boss-two': 'boss', 'test-sales': 'sales-dual' };
  let failSave = false;
  const persist = async (state, write) => { if (failSave) throw Error('模拟保存故障'); return write(state); };
  let server = await createManagementSimulation({ dataFile, invites, initialStorage: storage, persist });
  await server.listen(0);
  let baseUrl = 'http://127.0.0.1:' + server.address().port;
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const request = async (route, token, body, key) => {
    const response = await fetch(baseUrl + '/sim/v1' + route, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, body: body ? JSON.stringify(body) : undefined });
    return { status: response.status, data: await response.json() };
  };
  try {
    const first = (await request('/sessions', '', { inviteCode: 'test-boss-one' })).data;
    const second = (await request('/sessions', '', { inviteCode: 'test-boss-two' })).data;
    assert(first.accessToken && second.accessToken && first.accessToken !== second.accessToken);
    const persistedSessions = fs.readFileSync(dataFile, 'utf8');
    assert(!persistedSessions.includes(first.accessToken) && !persistedSessions.includes(second.accessToken), '磁盘只能保存会话哈希');
    const before = (await request('/snapshot', first.accessToken)).data;
    assert.equal(before.mode, 'simulated-cross-device');
    assert.equal(before.management.home.six.length, 6);
    assert.deepEqual(Object.keys(before.management.goals.departmentGoals), ['business','engineering','finance']);
    const rawCustomerPhone = storage['zhongshu-business-v3']?.customers?.find(row => row.phone)?.phone;
    if (rawCustomerPhone) assert(!JSON.stringify(before).includes(rawCustomerPhone), '管理快照不能回传客户手机号');
    const period = before.management.period;
    const command = { kind: 'goals', period, form: { sales: '80000', collections: '70000', effective: '8', customers: '10', nodes: '6', staff: {}, departmentGoals: {}, staffDepartmentGoals: {}, expectedVersion: before.management.goals.version || 0 }, expectedRevision: before.revision };
    const saved = await request('/commands', first.accessToken, command, 'test-goals-0001');
    assert.equal(saved.status, 200);
    const observed = (await request('/snapshot', second.accessToken)).data;
    assert.equal(observed.management.goals.collections, 70000);
    assert.equal(observed.management.goals.version, 1);
    assert.equal(observed.revision, saved.data.revision);
    pass('两个独立会话读取同一管理数据并可见目标保存');
    assert.deepEqual((await request('/commands', first.accessToken, command, 'test-goals-0001')).data, saved.data);
    assert.equal((await request('/commands', 'invalid-session', command, 'test-goals-0001')).status, 401);
    assert.equal((await request('/commands', first.accessToken, { ...command, form: { ...command.form, collections: '1' } }, 'test-goals-0001')).status, 409);
    assert.equal((await request('/commands', second.accessToken, command, 'test-goals-stale')).status, 409);
    assert(!fs.readFileSync(dataFile, 'utf8').includes('test-goals-0001'), '幂等键应按服务端身份作用域哈希保存，不能跨组织直接复用');
    pass('幂等重试不重复保存，陈旧版本及复用异参键被拒绝');

    const secondGoal = {
      ...command,
      form: { ...command.form, effective: '9', expectedVersion: observed.management.goals.version },
      expectedRevision: observed.revision
    };
    const secondSaved = await request('/commands', second.accessToken, secondGoal, 'test-goals-0002');
    assert.equal(secondSaved.status, 200);
    const afterSecondGoal = (await request('/snapshot', first.accessToken)).data;
    assert.equal(afterSecondGoal.management.goals.effective, 9);
    assert.equal(afterSecondGoal.management.goals.version, 2);
    pass('目标版本随快照共享，两个会话可以连续保存');
    const sales = (await request('/sessions', '', { inviteCode: 'test-sales' })).data;
    const limited = (await request('/snapshot', sales.accessToken)).data;
    assert.equal(limited.management, null);
    assert.equal((await request('/commands', sales.accessToken, { ...command, expectedRevision: afterSecondGoal.revision }, 'test-forbidden')).status, 403);
    assert.equal((await request('/commands', first.accessToken, { ...command, companyId: 'another-company', expectedRevision: afterSecondGoal.revision }, 'test-cross-company')).status, 403);
    assert.equal((await request('/snapshot', 'invalid-session')).status, 401);
    pass('岗位与组织隔离，未认证会话不读取管理数据');

    const commission = afterSecondGoal.management.commission;
    assert(commission?.projects?.length && commission?.staff?.length, '模拟快照应提供安全裁剪后的提成项目和人员选项');
    const commissionForm = { amount: '1000', assignee: commission.staff[0].id, note: '模拟跨端确认', checked: true, file: { name: '模拟提成确认依据.pdf', sample: true }, expectedVersion: 0 };
    const policy = await request('/commands', first.accessToken, { kind: 'commission-policy', id: commission.projects[0].id, form: commissionForm, expectedRevision: afterSecondGoal.revision }, 'test-commission-0001');
    assert.equal(policy.status, 200);
    const observedPolicy = (await request('/snapshot', second.accessToken)).data;
    const policyRow = observedPolicy.management.commission.rows.find(row => row.projectId === commission.projects[0].id);
    assert.equal(policyRow.total, 100000);
    assert.equal(policyRow.version, 1);
    assert(!('policy' in policyRow) && !JSON.stringify(policyRow).includes('模拟提成确认依据.pdf'), '提成只读快照不能回传原凭据或内部规则对象');
    const changedPolicy = await request('/commands', second.accessToken, { kind: 'commission-policy', id: commission.projects[0].id, form: { ...commissionForm, amount: '1200', expectedVersion: policyRow.version }, expectedRevision: observedPolicy.revision }, 'test-commission-0002');
    assert.equal(changedPolicy.status, 200);
    const afterPolicy = (await request('/snapshot', first.accessToken)).data;
    assert.equal(afterPolicy.management.commission.rows.find(row => row.projectId === commission.projects[0].id).total, 120000);
    pass('提成规则经安全裁剪后可由两个会话查看并连续保存');

    failSave = true;
    const retry = { ...command, form: { ...command.form, effective: '10', expectedVersion: afterPolicy.management.goals.version }, expectedRevision: afterPolicy.revision };
    assert.equal((await request('/commands', first.accessToken, retry, 'test-save-failure')).status, 500);
    failSave = false;
    assert.equal((await request('/snapshot', second.accessToken)).data.revision, afterPolicy.revision);
    assert.equal((await request('/commands', first.accessToken, retry, 'test-save-failure')).status, 200);
    pass('保存故障不报成功，相同操作可重试且无半提交');
    await server.close();
    server = await createManagementSimulation({ dataFile, invites });
    await server.listen(0);
    baseUrl = 'http://127.0.0.1:' + server.address().port;
    assert.equal((await request('/snapshot', second.accessToken)).data.management.goals.effective, 10);
    pass('服务重启保留共享管理数据及有效测试会话');
    await server.close();
    const persisted = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
    const boss = persisted.storage['zhongshu-management-v1'].staff.find(row => row.id === second.actor.staffId);
    const originalPersonId = boss.personId;
    boss.personId = 'replacement-person';
    fs.writeFileSync(dataFile, JSON.stringify(persisted, null, 2));
    server = await createManagementSimulation({ dataFile, invites });
    await server.listen(0);
    baseUrl = 'http://127.0.0.1:' + server.address().port;
    assert.equal((await request('/snapshot', second.accessToken)).status, 403);
    pass('旧会话同时绑定真实人员和员工记录，人员替换后立即失效');
    await server.close();
    const disabled = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
    const disabledBoss = disabled.storage['zhongshu-management-v1'].staff.find(row => row.id === second.actor.staffId);
    disabledBoss.personId = originalPersonId;
    disabledBoss.active = false;
    fs.writeFileSync(dataFile, JSON.stringify(disabled, null, 2));
    server = await createManagementSimulation({ dataFile, invites });
    await server.listen(0);
    baseUrl = 'http://127.0.0.1:' + server.address().port;
    assert.equal((await request('/snapshot', second.accessToken)).status, 403);
    pass('每次请求重新校验人员在岗状态');
    const out = path.resolve('qa/management-completion-20261023');
    fs.mkdirSync(out, { recursive: true });
    fs.writeFileSync(path.join(out, 'simulation-results.json'), JSON.stringify({ result: 'passed', checks }, null, 2));
  } finally {
    failSave = false;
    await server.close();
    fs.rmSync(directory, { recursive: true, force: true });
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
