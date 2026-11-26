const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const root = path.resolve(__dirname, '..'), clone = x => JSON.parse(JSON.stringify(x));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; };
const tap = action => ({ currentTarget: { dataset: { action } } });
const flatten = nodes => nodes.flatMap(n => [n, ...flatten(n.children || [])]);
function fixture() {
  const stages = [
    { id: '900719925474099991', key: 'STAGE_900719925474099991', label: '签约定金', sequence: 1, amountCents: 4000, ratioBps: 4000, trigger: 'ON_SIGN' },
    { id: '900719925474099992', key: 'STAGE_900719925474099992', label: '开工材料款', sequence: 2, amountCents: 3000, ratioBps: 3000, trigger: 'BEFORE_START' },
    { id: '900719925474099993', key: 'STAGE_900719925474099993', label: '验收尾款', sequence: 3, amountCents: 3000, ratioBps: 3000, trigger: 'ON_FINISH' }
  ];
  return { payment: { paymentPlan: { contractId: '900719925474099999', revision: 4, version: 3, totalCents: 10000, assetId: '123', stages },
    preparedVersion: 3, needsRefresh: false, contractChanged: false },
    bundle: { detail: { id: '100', managerUserId: '11', managerName: '项目负责人', status: 'READY_START', preparedAt: '2026-11-24', preparedNote: '已核对现场', preparedWorkers: '本公司班组',
      nodes: [{ id: '101', nodeIndex: 1, milestone: 'START', name: '开工', status: 'PENDING', startDate: '2026-11-25', endDate: '2026-11-25' },
        { id: '102', nodeIndex: 2, name: '基础工程', status: 'PENDING', startDate: '2026-11-26', endDate: '2026-12-05' }] },
      collections: { stages: stages.map((r,i) => ({ stage: r.key, stageId: r.id, label: r.label, trigger: r.trigger, planVersion: 3, expectedCents: r.amountCents,
        confirmedCents: i === 0 ? 4000 : 0, remainingCents: i === 0 ? 0 : r.amountCents, waitingForWork: i === 2, due: i === 1 ? '2026-11-25' : null })), records: [] },
      materials: { plans: [] }, settle: {}, followups: [] }
  };
}
function moduleAt(relative, mocks, wx) {
  const file = path.join(root, relative), module = { exports: {} }, real = createRequire(file);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), wx }, { filename: file });
  return module.exports;
}
function harness(view = 'prepare', overrides = {}) {
  let generation = 1, postId = '7', definition;
  const f = fixture(), calls = [], reads = {}, h = { ...f, calls, overrides };
  let post = { postId, orgType: 'store', orgId: '9', userId: '11', roleCode: 'engineer' };
  const http = { token: () => 'token', currentPostId: () => postId, sessionGeneration: () => generation,
    sessionGuard: () => { const g = generation; return () => g === generation; },
    requestGuard: key => { const i = reads[key] = (reads[key] || 0) + 1, g = generation; return () => reads[key] === i && generation === g; },
    get: async url => { calls.push(['get', url]); return overrides.get ? overrides.get(url) : clone(h.payment); }
  };
  const api = { projects: { get: async () => overrides.project ? overrides.project() : clone(h.bundle.detail) }, materials: { summary: async () => clone(h.bundle.materials) },
    closure: { settleSummary: async () => clone(h.bundle.settle) }, cameras: { list: async () => [] },
    collections: { page: async () => clone(h.bundle.collections), followups: async () => clone(h.bundle.followups),
      dunning: async (...args) => { calls.push(['dunning', ...clone(args)]); return overrides.dunning ? overrides.dunning(...args) : { id: '201' }; },
      register: async (...args) => { calls.push(['collect', ...clone(args)]); return overrides.collect ? overrides.collect(...args) : { id: '301' }; }
    }, plan: {
      prepare: async (...args) => { calls.push(['prepare', ...clone(args)]); return overrides.prepare ? overrides.prepare(...args) : { id: '100' }; },
      start: async (...args) => { calls.push(['start', ...clone(args)]); return overrides.start ? overrides.start(...args) : { id: '100' }; }
    },
    uploadVoucher: async (...args) => { calls.push(['upload', ...args]); return overrides.upload ? overrides.upload(...args) : '900719925474099995'; }
  };
  const wx = { showToast: value => calls.push(['toast', value]), getStorageSync: () => '', getSystemInfoSync: () => ({}) };
  global.wx = wx;
  const P = moduleAt('miniprogram/utils/platform-engineering.js', { '../api/franchise/engineering': api, '../api/http': http }, wx);
  const Payments = moduleAt('miniprogram/pages/build/payment-plan.js', { '../../api/http': http, '../../api/franchise/engineering': api }, wx);
  const pageFile = path.join(root, 'miniprogram/pages/build/record/index.js'), real = createRequire(pageFile);
  const mocks = { '../payment-plan': Payments, '../../../utils/platform-engineering': P,
    '../../../api/http': http, '../../../api/auth': { currentApiPost: () => post }, '../../../api/franchise/engineering': api,
    '../../../utils/organization': { has: (role, desired) => role === desired || role === desired + '-lead' },
    '../../../utils/engineering-records': {}, '../../../utils/access': {}, '../../../utils/sample-files': {}, '../schedule-import': {},
    '../../../utils/page-navigation': { open: url => calls.push(['navigate', url]), back: () => {} },
    '../../../utils/account': { localDate: () => '2026-11-25' }
  };
  vm.runInNewContext(fs.readFileSync(pageFile, 'utf8'), { Page: p => definition = p, require: name => name in mocks ? mocks[name] : real(name), wx, console }, { filename: pageFile });
  const page = { ...definition, id: '100', stage: 'start', __identity: '7:1', data: { ...clone(definition.data), view },
    setData(patch) { for (const [key, value] of Object.entries(patch)) {
      const keys = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let obj = this.data;
      for (const part of keys.slice(0,-1)) obj = obj[part] || (obj[part] = {});
      obj[keys.at(-1)] = value;
    } }
  };
  h.page = page; h.P = P; h.Payments = Payments; h.http = http;
  h.open = () => page.refreshOnline(true);
  h.switchSession = (role = 'finance') => { generation++; postId = '8'; post = { ...post, postId, roleCode: role, userId: '22' }; };
  h.view = (v = view, opts = {}) => { const data = P.recordView(h.bundle, { view: v, stage: page.stage, writeRole: true, userId: '11', today: '2026-11-25', ...opts }); Payments.apply(data, h.bundle, h.payment, { view: v, reset: true }); return data; };
  h.nodes = () => flatten(render(pageFile.replace(/\.js$/, '.wxml'), page.data));
  return h;
}

test('正式付款计划保留长编号、全部自定义节点和比例，错序/重复/不安全编号不渲染', () => {
  const h = harness(), plan = h.Payments.parse(h.payment);
  assert.equal(plan.contractId, '900719925474099999'); assert.equal(plan.rows.length, 3);
  assert.equal(plan.rows[1].amountText, '30.00'); assert.equal(plan.rows[1].ratioText, '30.00%');
  h.payment.paymentPlan.contractId = Number.MAX_SAFE_INTEGER + 2;
  assert.throws(() => h.Payments.parse(h.payment), /不完整/);
  h.payment.paymentPlan.contractId = '91'; h.payment.paymentPlan.stages[1].key = h.payment.paymentPlan.stages[0].key;
  assert.throws(() => h.Payments.parse(h.payment), /不一致/);
});

test('工程准备只读继承正式节点，保存只提交版本/现场/班组/施工计划，不重填金额和合同文件', async () => {
  const h = harness(); await h.open();
  assert.equal(h.page.data.paymentPlan.rows.length, 3);
  assert.deepEqual(clone(h.page.data.fields.map(r => r.key)), ['workers', 'note']);
  assert.equal(h.page.data.form.note, '已核对现场'); assert.equal(h.page.data.form.workers, '本公司班组');
  assert.ok(['total','first','signAfter','baseDone','capDone','file'].every(k => !(k in h.page.data.form)));
  const keys = h.nodes().filter(n => ['input','textarea','picker'].includes(n.tag)).map(n => n.attrs?.['data-key']);
  assert.ok(!keys.includes('total') && !keys.includes('signAfter'));
  await h.page.perform(tap('prepare'));
  const saved = h.calls.find(c => c[0] === 'prepare');
  assert.deepEqual(Object.keys(saved[2]).sort(), ['nodes','note','paymentPlanVersion','workers']);
  assert.equal(saved[2].paymentPlanVersion, 3); assert.equal(saved[2].nodes[1].nodeIndex, 2);
  assert.equal(h.calls.filter(c => c[0] === 'get').length, 2);
});

test('按各个开工前节点净实收判断门槛，其他节点超收不能抵消，退款重新恢复欠款门槛', () => {
  const h = harness('start');
  h.bundle.collections.stages[0].confirmedCents = 10000;
  let data = h.view(); assert.equal(data.actions.length, 0); assert.match(data.next.url, /STAGE_900719925474099992/);
  h.bundle.collections.stages[1].confirmedCents = 3000;
  data = h.view(); assert.deepEqual(clone(data.actions.map(a => a.action)), ['start']); assert.equal(data.startBlocked, '');
  h.bundle.collections.stages[1].confirmedCents = 2999;
  data = h.view(); assert.equal(data.actions.length, 0); assert.match(data.startBlocked, /开工材料款/);
});

test('未约定开工前期款不强造首款门槛，付款计划变更必须重新准备，混合版本不能提交', () => {
  const h = harness('start'); h.payment.paymentPlan.stages.forEach(r => r.trigger = 'ON_FINISH');
  assert.equal(h.view().actions[0].action, 'start');
  h.payment.needsRefresh = true; h.payment.preparedVersion = 2;
  let data = h.view(); assert.equal(data.actions.length, 0); assert.match(data.next.url, /prepare$/);
  h.payment.needsRefresh = false; h.bundle.collections.stages[0].planVersion = 2;
  data = h.view('prepare'); assert.equal(data.paymentConflict, true); assert.equal(data.actions.length, 0);
  assert.match(data.paymentNotice, /重新加载/);
});

test('已关联合同作废或改绑不能回退手工三期；确无正式合同的旧项目继续显式原约定', () => {
  const h = harness(); h.payment.paymentPlan = null; h.payment.contractChanged = true;
  let data = h.view(); assert.equal(data.paymentConflict, true); assert.equal(data.fields.length, 0); assert.equal(data.actions.length, 0);
  h.payment.contractChanged = false;
  data = h.view(); assert.deepEqual(clone(data.fields.map(r => r.key)), ['total','signAfter','baseDone','capDone','file','note']);
  assert.equal(data.form.paymentPlanVersion, undefined); assert.match(data.paymentNotice, /手工项目/);
});

test('实际开工在两种付款入口据实登记三项，保留原日期、单份凭据和原重试键', async () => {
  for (const manual of [false, true]) {
    const h = harness('start', { start: () => { throw Error('未收到保存回执'); } });
    if (manual) {
      h.payment.paymentPlan = null;
      h.bundle.collections.stages = [{ stage: 'SIGN_AFTER', expectedCents: 4000, confirmedCents: 4000, remainingCents: 0, settled: true }];
    } else h.bundle.collections.stages[1].confirmedCents = 3000;
    await h.open();
    const page = h.page, keys = page.data.fields.map(field => field.key);
    assert.deepEqual(clone(keys), ['date', 'startSite', 'startOwner', 'startManager', 'note', 'file']);
    assert.ok(['startSite','startOwner','startManager'].every(key => !page.data.form[key]));
    const input = (key, value) => page.input({ currentTarget: { dataset: { key } }, detail: { value } });
    input('startSite', '进场道路可用，场地东侧待平整');
    input('startOwner', '业主未到场，约明日上午复核');
    input('startManager', '项目经理现场核对临电，等业主复核');
    input('note', '保留现场原备注');
    page.data.form.file = { path: 'wxfile://proof.pdf' };
    await page.perform(tap('start'));
    await page.retryStart();
    const writes = h.calls.filter(c => c[0] === 'start');
    assert.equal(writes.length, 2);
    assert.deepEqual(writes[0][2], { date: '2026-11-25',
      note: ['四通一平现场情况：进场道路可用，场地东侧待平整', '业主确认情况：业主未到场，约明日上午复核',
        '项目经理确认情况：项目经理现场核对临电，等业主复核', '现场备注：保留现场原备注'].join('\n'), fileAssetId: '900719925474099995' });
    assert.equal(writes[0][3], writes[1][3]);
    assert.equal(page.data.form.note, '保留现场原备注');
    input('startOwner', '业主仅核对通行道路');
    page.setFile('file', { path:'wxfile://other.pdf' });
    await page.perform(tap('start'));
    assert.equal(h.calls.filter(c => c[0] === 'start').length, 2);
    assert.equal(h.calls.filter(c => c[0] === 'upload').length, 1);
    assert.equal(page.data.form.startOwner, '业主未到场，约明日上午复核');
    assert.equal(page.data.form.file.path, 'wxfile://proof.pdf');
  }
});

test('开工选填兼容既有备注，组合 UTF-16 长度在上传前检查，付款与岗位门槛持续生效', async () => {
  const h = harness('start', { start: () => { throw {code:400, msg:'已拒绝的测试命令'}; } });
  await h.open();
  assert.equal(h.page.data.fields.length, 0);
  await h.page.perform(tap('start'));
  assert.equal(h.calls.filter(c => c[0] === 'upload').length, 0);
  h.bundle.collections.stages[1].confirmedCents = 3000;
  await h.open();
  let form = h.page.data.form;
  form.note = '只记原备注';
  form.file = { path: 'wxfile://old.pdf' };
  await h.page.perform(tap('start'));
  assert.equal(h.calls.find(c => c[0] === 'start')[2].note, form.note);
  form = h.page.data.form;
  form.note = '';
  const prefix = '业主确认情况：';
  form.startOwner = '水'.repeat(1000 - prefix.length - 2) + '😀';
  await h.page.perform(tap('start'));
  assert.equal(h.calls.filter(c => c[0] === 'start').at(-1)[2].note.length, 1000);
  form = h.page.data.form;
  form.startOwner += '水';
  await h.page.perform(tap('start'));
  assert.match(h.page.data.error, /1000/);
  assert.equal(h.calls.filter(c => c[0] === 'upload').length, 2);
  assert.equal(form.startOwner.length + prefix.length, 1001);
  form.startOwner = '';
  await h.page.perform(tap('start'));
  assert.equal(h.calls.filter(c => c[0] === 'start').at(-1)[2].note, '');
  h.payment.preparedVersion = 2; h.payment.needsRefresh = true;
  await h.open(); assert.equal(h.page.data.fields.length, 0);
  h.payment.needsRefresh = false; h.bundle.collections.stages[0].planVersion = 2;
  await h.open(); assert.equal(h.page.data.fields.length, 0);
  h.bundle.collections.stages[0].planVersion = 3; h.switchSession('finance'); await h.open();
  assert.equal(h.page.data.fields.length, 0);
});

test('开工回执在途切后台再返回仍锁定，确认有权后只重放原正文、原资产及原键', async () => {
  const answer = deferred(), h = harness('start', {start: () => answer.promise}), page = h.page;
  h.bundle.collections.stages[1].confirmedCents = 3000;
  await h.open();
  Object.assign(page.data.form, {startOwner:'业主未到场', file:{path:'wxfile://first.pdf'}});
  const sent = page.perform(tap('start'));
  await new Promise(resolve => setImmediate(resolve));
  const first = h.calls.find(row => row[0] === 'start');
  page.onHide(); page.onShow(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.startSaveState, 'sending'); assert.equal(page.data.busy, true);
  page.input({currentTarget:{dataset:{key:'startOwner'}},detail:{value:'业主已到场'}});
  page.setFile('file', {path:'wxfile://other.pdf'}); await page.perform(tap('start')); await page.retryStart();
  assert.equal(h.calls.filter(row => row[0] === 'start').length, 1);
  page.onHide(); answer.reject(Error('回执丢失')); await sent;
  const reread = deferred(); h.overrides.project = () => reread.promise;
  page.onShow(); await page.retryStart();
  assert.equal(page.data.p, null); assert.equal(page.data.startCanRetry, false);
  reread.resolve(clone(h.bundle.detail)); await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.startCanRetry, true);
  delete h.overrides.project;
  h.overrides.start = (_id, body) => {
    h.bundle.detail.actualStartAt = body.date;
    h.bundle.detail.siteRecords = [{id:'8',node_id:'101',occurred_at:body.date,note:body.note,completion:true,
      author_user_id:'11',file_asset_ids:JSON.stringify([body.fileAssetId])}];
    return clone(h.bundle.detail);
  };
  // Even if a temporary file or UI state changed, the business retry reads neither.
  page.data.form.startOwner = '过期控件'; page.data.form.file = {path:'wxfile://replaced.pdf'};
  await page.retryStart();
  const writes = h.calls.filter(row => row[0] === 'start');
  assert.deepEqual(writes, [first,first]); assert.equal(h.calls.filter(row => row[0] === 'upload').length, 1);
  assert.equal(page.data.startSaveState, ''); assert.equal(page.data.p.actualStart.note, '业主确认情况：业主未到场');
});

test('返回原工程先读到实际开工时展示原单，已核实的记录不重复提交', async () => {
  for(const note of ['业主确认情况：待复核','他人记录的新事实']){
    const answer=deferred(), h=harness('start',{start:()=>answer.promise}), page=h.page;
    h.bundle.collections.stages[1].confirmedCents=3000;await h.open();
    Object.assign(page.data.form,{startOwner:'待复核',file:{path:'wxfile://fact.pdf'}});
    const sending=page.perform(tap('start'));await new Promise(resolve=>setImmediate(resolve));
    page.onHide();assert.deepEqual(clone(page.data.form),{});
    h.bundle.detail.actualStartAt='2026-11-25';
    h.bundle.detail.siteRecords=[{id:'9',node_id:'101',occurred_at:'2026-11-25',completion:true,note,author_user_id:'11',file_asset_ids:'["900719925474099995"]'}];
    page.onShow();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(page.data.p.actualStart.note,note);assert.equal(page.data.startSaveState,'');
    if(note==='他人记录的新事实')assert.match(page.data.error,/另一份开工记录/);
    answer.reject(Error('迟到回执'));await sending;await page.retryStart();await page.perform(tap('start'));
    assert.equal(h.calls.filter(row=>row[0]==='start').length,1);
  }
});

test('待核对的开工遇转交清页，切岗和换工程后的迟到回执不能显示或代为重试', async () => {
  for (const change of ['transfer','role','project']) {
    const delayed = deferred(), h = harness('start', {start: () => delayed.promise}), page = h.page;
    h.bundle.collections.stages[1].confirmedCents = 3000; await h.open();
    Object.assign(page.data.form, {startOwner:'旧业主情况',file:{path:'wxfile://old.pdf'}});
    const sending = page.perform(tap('start')); await new Promise(resolve => setImmediate(resolve));
    page.onHide();
    if (change === 'transfer') h.overrides.project = () => {throw {code:1115000000,msg:'工程已转交'};};
    if (change === 'role') h.switchSession('finance');
    if (change === 'project') { page.id='200';h.bundle.detail.id='200'; }
    page.onShow(); await new Promise(resolve => setImmediate(resolve));
    delayed.reject(Error('前工程的迟到回执')); await sending; await page.retryStart();
    assert.equal(h.calls.filter(row=>row[0]==='start').length,1);
    assert.equal(page.data.startCanRetry,false);assert.equal(page.data.startSaveState,'');assert.ok(!page.data.form.startOwner);
    assert.doesNotMatch(page.data.error,/迟到回执/);
    if(change==='transfer') assert.equal(page.data.p,null);
  }
});

test('开工传输确定被拒才能修改，切换工程期间完成上传不会发出旧工程开工', async () => {
  const pause=deferred(), h=harness('start',{upload:()=>pause.promise}), page=h.page;
  h.bundle.collections.stages[1].confirmedCents=3000;await h.open();
  Object.assign(page.data.form,{startOwner:'旧现场',file:{path:'wxfile://old.pdf'}});
  const sending=page.perform(tap('start'));page.onHide();page.id='200';h.bundle.detail.id='200';page.onShow();
  await new Promise(resolve=>setImmediate(resolve));pause.resolve('88');await sending;
  assert.equal(h.calls.filter(r=>r[0]==='start').length,0);
  assert.equal(page.data.startSaveState,'');assert.ok(!page.data.form.startOwner);
  delete h.overrides.upload;h.overrides.start=()=>{throw {code:400,msg:'已拒绝，未保存'};};
  Object.assign(page.data.form,{startOwner:'新现场待核对',file:{path:'wxfile://new.pdf'}});
  await page.perform(tap('start'));assert.equal(page.data.form.startOwner,'新现场待核对');assert.equal(page.data.error,'已拒绝，未保存');
  page.input({currentTarget:{dataset:{key:'startOwner'}},detail:{value:'经业主另行核对'}});
  await page.perform(tap('start'));
  const writes=h.calls.filter(r=>r[0]==='start');assert.equal(writes.length,2);assert.notEqual(writes[0][3],writes[1][3]);
  assert.match(writes[1][2].note,/经业主另行核对/);assert.equal(writes[1][1],'200');
});

test('已开工从真实 START 完成原单显示原文、办理人和精确凭据，旧项目不补造确认', async () => {
  const h = harness('start'), d = h.bundle.detail, original = '老开工本上原样记录\n仅查验用水';
  h.page.data.form.startOwner = '未提交的旧草稿';
  d.actualStartAt = '2026-11-25';
  d.timelineActors = { 'eng-start': '原经办周师傅' };
  d.siteRecords = [
    { id: '8', node_id: '102', occurred_at: '2026-11-25', completion: true, note: '其他工序', file_asset_ids: '["11"]' },
    { id: '7', node_id: '101', occurred_at: '2026-11-26', completion: true, note: '不同日期', file_asset_ids: '["12"]' },
    { id: '6', node_id: '101', occurred_at: '2026-11-25', completion: false, note: '当日普通巡场' },
    { id: '5', node_id: '101', occurred_at: '2026-11-25', completion: true, note: original,
      author_user_id: '900719925474099991', file_asset_ids: '[900719925474099995]' }
  ];
  await h.open();
  assert.equal(h.page.data.p.actualStart.note, original);
  assert.equal(h.page.data.form.startOwner, undefined);
  assert.equal(h.page.data.p.actualStart.actor, '原经办周师傅');
  assert.deepEqual(clone(h.page.data.p.actualStart.assetIds), ['900719925474099995']);
  assert.equal(h.page.data.fields.length, 0);
  assert.ok(h.nodes().some(n => n.attrs?.['data-asset-id'] === '900719925474099995' && n.attrs?.bindtap === 'previewAsset'));
  delete d.timelineActors;
  await h.open(); assert.match(h.page.data.p.actualStart.actor, /900719925474099991/);
  d.siteRecords.pop();
  await h.open();
  assert.equal(h.page.data.p.actualStart.date, '2026-11-25');
  assert.equal(h.page.data.p.actualStart.note, '');
  assert.deepEqual(clone(h.page.data.p.actualStart.assetIds), []);
  assert.equal(h.page.data.p.actualStart.actor, '');
});

test('节点条件引用必须对应本工程真实节点，缺日期/准备说明不能提交继承', async () => {
  const h = harness(); await h.open();
  h.page.data.paymentPlan.rows[1].trigger = 'NODE_COMPLETED'; h.page.data.paymentPlan.rows[1].nodeIndex = 99;
  await h.page.perform(tap('prepare')); assert.equal(h.calls.filter(c => c[0] === 'prepare').length, 0); assert.match(h.page.data.error, /不存在/);
  h.page.data.paymentPlan.rows[1].nodeIndex = 2; h.page.data.form.nodes[0].start = '';
  await h.page.perform(tap('prepare')); assert.match(h.page.data.error, /起止日期/);
});

test('未知保存结果冻结原版本、施工节点及幂等键，显式重试不创建新命令', async () => {
  const h = harness('prepare', { prepare: async () => { throw { code: -1, msg: 'timeout' }; } }); await h.open();
  await h.page.perform(tap('prepare')); assert.equal(h.page.data.paymentUncertain, true);
  h.page.input({ currentTarget: { dataset: { key: 'note' } }, detail: { value: '不应写入' } });
  h.page.nodeInput({ currentTarget: { dataset: { index: 1, key: 'name' } }, detail: { value: '不应改名' } });
  assert.equal(h.page.data.form.note, '已核对现场'); assert.equal(h.page.data.form.nodes[1].name, '基础工程');
  await h.page.perform(tap('prepare')); assert.equal(h.calls.filter(c => c[0] === 'prepare').length, 1);
  const before = h.calls.filter(c => c[0] === 'get').length; await h.page.refreshOnline(); assert.equal(h.calls.filter(c => c[0] === 'get').length, before);
  h.overrides.prepare = async () => ({ id: '100' }); await h.page.retryPayment();
  const saves = h.calls.filter(c => c[0] === 'prepare'); assert.deepEqual(saves[0].slice(1), saves[1].slice(1));
  assert.equal(h.page.data.paymentUncertain, false); assert.equal(h.page.__paymentPending, null);
});

test('版本冲突可保留现场填写后显式刷新新计划，不能自动以新版本重复提交', async () => {
  const h = harness('prepare', { prepare: async () => { throw { code: 1115000001, msg: '付款计划已更新，请刷新合同付款计划后重试' }; } }); await h.open();
  h.page.data.form.note = '尚未提交的现场情况'; await h.page.perform(tap('prepare'));
  assert.equal(h.page.data.paymentConflict, true); assert.equal(h.page.data.form.note, '尚未提交的现场情况');
  await h.page.perform(tap('prepare')); assert.equal(h.calls.filter(c => c[0] === 'prepare').length, 1);
  h.payment.paymentPlan.version = 4; h.payment.needsRefresh = true; h.bundle.collections.stages.forEach(r => r.planVersion = 4);
  await h.page.reloadPayment(); assert.equal(h.page.data.paymentConflict, false); assert.equal(h.page.data.form.paymentPlanVersion, 4);
  assert.equal(h.page.data.form.note, '尚未提交的现场情况'); assert.equal(h.calls.filter(c => c[0] === 'prepare').length, 1);
});

test('写前迟到GET不能覆盖成功保存或新版计划；切岗迟到写结果不回填旧项目', async () => {
  const h = harness(); await h.open(); const old = deferred(); h.overrides.get = () => old.promise;
  const loading = h.page.refreshOnline(); h.overrides.get = undefined;
  await h.page.perform(tap('prepare'));
  old.resolve({ paymentPlan: null, needsRefresh: false, contractChanged: false, preparedVersion: null }); await loading;
  assert.equal(h.page.data.paymentPlan.version, 3);
  const waiting = deferred(); h.overrides.prepare = () => waiting.promise;
  const saving = h.page.perform(tap('prepare')); h.switchSession(); h.page.onShow();
  waiting.resolve({ id: '100' }); await saving;
  assert.equal(h.page.__paymentPending, null); assert.equal(h.page.data.paymentUncertain, false);
  assert.equal(h.page.data.form.note || '', '');
});

test('撤权清除付款计划、草稿、本地凭据和历史，未知响应保持原请求可核对', async () => {
  const h = harness('prepare', { prepare: async () => { throw { code: 403, msg: '岗位已撤销' }; } }); await h.open();
  h.page.data.form.file = { path: '/private.pdf' }; h.page.data.docs = ['private'];
  await h.page.perform(tap('prepare')); assert.equal(h.page.data.p, null); assert.equal(h.page.data.paymentPlan, null);
  assert.deepEqual(clone(h.page.data.form), {}); assert.deepEqual(clone(h.page.data.docs), []); assert.equal(h.page.__paymentPending, null);
  const damaged = harness('prepare', { prepare: async () => ({ id: 'other-project' }) }); await damaged.open();
  await damaged.page.perform(tap('prepare')); assert.equal(damaged.page.data.paymentUncertain, true);
});

test('工程收款/催收保持真实稳定key及服务端名称，任意节点和尾款不落首款', async () => {
  const h = harness('collection'); h.page.stage = 'STAGE_900719925474099992'; await h.open();
  assert.equal(h.page.data.partLabel, '开工材料款'); assert.equal(h.page.data.form.stage, h.page.stage);
  assert.equal(h.page.data.actions[0].action, 'dunning');
  await h.page.perform(tap('dunning'));
  assert.equal(h.calls.find(c => c[0] === 'dunning')[2], 'STAGE_900719925474099992');
  const stages = h.P.stagesView([{ stage: 'STAGE_123', stageId: '123', label: '指定节点款', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }]);
  assert.equal(stages[0].stage, 'STAGE_123'); assert.equal(stages[0].key, 'STAGE_123'); assert.equal(stages[0].name, '指定节点款');
  await h.P.perform('collect','100',{ stage:'STAGE_123',amount:'1',transaction:'receipt-1',file:{path:'/voucher.pdf'} },null,'collect-key');
  await h.P.perform('collect','100',{ stage:'tail',amount:'1',transaction:'receipt-2',file:{path:'/voucher.pdf'} },null,'tail-key');
  assert.deepEqual(h.calls.filter(c => c[0] === 'collect').map(c => c[2].stage), ['STAGE_123','tail']);
  await assert.rejects(h.P.perform('collect','100',{amount:'1'},null,'empty'), /付款节点/);
  h.page.stage = 'unknown'; h.page.initialized = false; await h.page.refreshOnline(true);
  assert.equal(h.page.data.actions.length, 0); assert.match(h.page.data.partLabel, /不存在/);
});

test('未触发施工条件的节点不得催收，旧基础/封顶深链仍指向原期次', async () => {
  const h = harness('collection'); h.page.stage = 'STAGE_900719925474099993'; await h.open();
  assert.equal(h.page.data.actions.length, 0); assert.equal(h.page.data.installments[2].waitingForWork, true);
  const legacy = h.P.stagesView([{ stage:'BASE_DONE',label:'原基础期款',expectedCents:100,remainingCents:100 },{stage:'CAP_DONE',expectedCents:200,remainingCents:200}]);
  assert.deepEqual(clone(legacy.map(r=>[r.stage,r.key])), [['base','BASE_DONE'],['topping','CAP_DONE']]);
  h.bundle.collections.stages = [{ stage:'BASE_DONE',label:'原基础期款',expectedCents:100,confirmedCents:0,remainingCents:100 }];
  h.page.stage = 'base'; await h.page.refreshOnline(true); assert.equal(h.page.data.form.stage,'BASE_DONE');
  await h.page.perform(tap('dunning')); assert.equal(h.calls.find(c=>c[0]==='dunning')[2],'BASE_DONE');
});

test('工程负责人按期申报部分到账，回执丢失时沿用同一原件、请求及业务键', async () => {
  let tries = 0;
  const h = harness('collection', { collect: async () => {
    if (++tries === 1) throw Error('未收到回执');
    return { stageStatus: 'PENDING_CONFIRMATION', declarationId: '900719925474009999' };
  } });
  h.bundle.collections.declarations = [];
  const target = h.bundle.collections.stages[1]; h.page.stage = target.stage; await h.open();
  assert.deepEqual(clone(h.page.data.actions.map(action => action.action)), ['dunning', 'declare']);
  Object.assign(h.page.data.form, { amount: '5.25', date: '2026-11-25', transaction: '原始流水-07', file: { path: '/orig.pdf' } });
  await h.page.perform(tap('declare'));
  assert.equal(h.page.data.paymentUncertain, true);
  h.page.data.form.amount = '0.01';
  await h.page.retryPayment();
  const attempts = h.calls.filter(([kind]) => kind === 'collect');
  assert.equal(attempts.length, 2);
  assert.deepEqual(attempts[0], attempts[1]);
  assert.equal(attempts[0][2].amount, '5.25'); assert.equal(attempts[0][2].planVersion, target.planVersion);
  assert.equal(attempts[0][2].stageId, target.stageId); assert.ok(attempts[0][2].fileAssetId);
  assert.equal(h.calls.filter(([kind]) => kind === 'upload').length, 1);
  assert.equal(h.page.data.paymentUncertain, false);
});

test('稳定CAP_DONE深链仍保留原封顶收款门槛，不因URL改为key绕过原界面条件', () => {
  const h = harness('collection');
  h.bundle.collections.stages = [{ stage: 'CAP_DONE', label: '原封顶约定', expectedCents: 100, confirmedCents: 0, remainingCents: 100 }];
  let data = h.P.recordView(h.bundle, { view: 'collection', stage: 'CAP_DONE', financeRole: true, today: '2026-11-25' });
  assert.equal(data.actions.length, 0); assert.equal(data.partLabel, '原封顶约定');
  Object.assign(h.bundle.detail.nodes[1], { milestone: 'TOPPING', status: 'DONE' });
  data = h.P.recordView(h.bundle, { view: 'collection', stage: 'CAP_DONE', financeRole: true, today: '2026-11-25' });
  assert.equal(data.actions[0].action, 'collect'); assert.equal(data.form.stage, 'CAP_DONE');
});
