const assert = require('node:assert/strict');
const test = require('node:test');
const P = require('../miniprogram/utils/platform-performance');
const http = require('../miniprogram/api/http');
const api = P.api.cycles;
const period = '2026-11';
const id = '90071992547409931', other = '90071992547409932', company = '90071992547409933';
function cycle(userId = id, overrides = {}) {
  return { companyId: company, userId, period, status: 'PENDING_REVIEW', revision: 1, canReview: false,
    submission: JSON.stringify({ note: '本期说明', sources: [{ key: 'sale-contract:11', type: 'auto:contract',
      sourceType: 'contract', sourceId: '90071992547409011', customerId: '90071992547409022', date: '2026-11-21',
      value: '90071992547409931' }], actuals: {}, goals: {}, history: [] }), review: null, ...overrides };
}
function start(t, team = false) {
  const actor = { user: id, post: '17', org: company, role: team ? 'boss' : 'sales', gen: 0 };
  const stub = (object, name, fn) => t.mock.method(object, name, fn);
  stub(http, 'currentUserId', () => actor.user); stub(http, 'currentPostId', () => actor.post);
  stub(http, 'tenantId', () => '1'); stub(http, 'sessionGeneration', () => actor.gen);
  stub(P, 'loadMembers', async () => [{id:other, name:'林工'}]);
  stub(P, 'apiPost', () => ({ orgId: actor.org, postId: actor.post, roleCode: actor.role }));
  let def;
  global.Page = page => { def = page; };
  let opened;
  global.wx = { navigateTo: ({ url }) => { opened = url; }, showToast() {}, setNavigationBarTitle() {} };
  const path = require.resolve('../miniprogram/pages/admin/tasks/cycle');
  delete require.cache[path]; require(path);
  const page = { ...def, data: structuredClone(def.data), setData(patch) { Object.assign(this.data, structuredClone(patch)); } };
  const event = (key, value) => ({ currentTarget: { dataset: { [key]: value } } });
  page.onLoad({ scope: team ? 'team' : 'mine', period });
  return { page, actor, event, opened: () => opened };
}
test('本人当期提交绑定当前身份与版本，丢回执重试沿用原幂等键，并以返回状态显示', async t => {
  const { page } = start(t);
  t.mock.method(api, 'list', async () => [cycle(other)]);
  await page.onShow();
  assert.equal(page.data.current.userId, id); assert.equal(page.data.canSubmit, true);
  page.inputNote({ detail: { value: '本期回款' } });
  const calls = [];
  t.mock.method(api, 'submit', async (...args) => {
    calls.push(args);
    if (calls.length === 1) throw { message: '网络超时' };
    return cycle();
  });
  await page.submit(); await page.submit();
  assert.equal(calls.length, 2); assert.deepEqual(calls[0], calls[1]);
  assert.deepEqual(calls[0].slice(0, 3), [period, id, '本期回款']);
  assert.ok(calls[0][3]); assert.equal(calls[0][4], 0);
  assert.equal(page.data.current.statusLabel, '待复核'); assert.equal(page.data.canSubmit, true);
});
test('复核只针对本组织他人，保留精确原单和金额，签名变化以退回结果显示', async t => {
  const { page, event, opened } = start(t, true);
  t.mock.method(api, 'list', async () => [cycle(id, {canReview: true}), cycle(other,{canReview: true}),cycle('999',{companyId:'777',canReview:true})]);
  await page.onShow(); assert.deepEqual(page.data.members.map(r => r.userId), [other]);
  assert.equal(page.data.members[0].name, '林工'); assert.equal(page.data.canReview, true);
  assert.equal(page.data.snapshot.sources[0].valueText, '900719925474099.31 元');
  page.openSource(event('index', 0));
  assert.equal(opened(), '/pages/workflow/index?view=contract&id=90071992547409022&contractId=90071992547409011');
  t.mock.method(api, 'review', async (p,u,approve,note,key,rev) => {
    assert.deepEqual([p,u,approve,rev],[period,other,true,1]); assert.ok(key);
    return cycle(other,{revision:2,status:'RETURNED',review:JSON.stringify({result:'退回',reason:'提交后来源已变化'})});
  });
  await page.review(event('action','approve'));
  assert.equal(page.data.current.statusLabel, '已退回');
  assert.equal(page.data.snapshot.review.reason, '提交后来源已变化');
});
test('旧版本展示当时的提交、意见和缺失明细，不沿用新快照或提供历史写操作', async t => {
  const { page } = start(t);
  const history = [{revision:1,status:'RETURNED',submission:{note:'旧说明'},review:{reason:'请补充'}}];
  t.mock.method(api, 'list', async () => [cycle(id,{revision:2,submission:JSON.stringify({note:'新说明',sources:[],history})})]);
  await page.onShow(); page.selectVersion({detail:{value:'1'}});
  assert.equal(page.data.snapshot.note,'旧说明'); assert.equal(page.data.snapshot.hasSources,false);
  assert.equal(page.data.snapshot.review.reason,'请补充'); assert.equal(page.data.canSubmit,false);
});
test('前台刷新隐藏旧团队资料，岗位切换后早到和晚到响应均不会恢复旧权限', async t => {
  const { page,actor }=start(t,true); let resolve;
  const mock=t.mock.method(api,'list',async()=>[cycle(other,{canReview:true})]);
  await page.onShow(); assert.equal(page.data.canReview,true);
  page.onHide(); assert.equal(page.data.current,null);
  mock.mock.mockImplementation(()=>new Promise(done=>resolve=done));
  const old=page.onShow(); assert.equal(page.data.current,null);
  actor.gen++;actor.post='19';actor.role='sales'; await page.onShow();
  resolve([cycle(other,{canReview:true})]); await old;
  assert.equal(page.data.current,null); assert.equal(page.data.canReview,false);
});
test('过期更新重新读取正式版本，用户重新确认后再交',async t=>{
  const {page}=start(t); let calls=0;
  t.mock.method(api,'list',async()=>++calls===1?[]:[cycle(id,{revision:3,status:'RETURNED'})]);
  t.mock.method(api,'submit',async()=>{throw {code:409,message:'版本已变化'};});
  await page.onShow();page.inputNote({detail:{value:'第一次说明'}});await page.submit();
  assert.equal(calls,2);assert.equal(page.data.current.revision,3);
  assert.equal(page.data.note,'');assert.equal(page.data.canSubmit,true);
});

test('原生注册、任务页入口与当前版本的按钮，可执行的 API 同时携带岗位之外的用户、版本及幂等键', async t => {
  const path=require('node:path'), {render}=require('./role-page-runtime.cjs');
  const app=require('../miniprogram/app.json');
  assert.ok(app.subPackages.find(group=>group.root==='pages/admin').pages.includes('tasks/cycle'));
  function buttons(file,data) {
    const found=[];
    function visit(node) { if(node.tag==='button')found.push(node.attrs); (node.children||[]).forEach(visit); }
    render(path.join(__dirname,'../miniprogram/pages/admin/tasks',file+'.wxml'),data).forEach(visit);
    return found;
  }
  const entry={ready:true,view:'mine',members:[],tabs:[],goalRows:[],rows:[],form:{},typeIndex:0,staff:[],types:[]};
  assert.ok(buttons('index',entry).some(row=>row.bindtap==='openCycle'));
  assert.ok(!buttons('index',{...entry,view:'member'}).some(row=>row.bindtap==='openCycle'));
  const {page}=start(t); t.mock.method(api,'list',async()=>[]);
  await page.onShow(); assert.deepEqual(buttons('cycle',page.data).map(b=>b.bindtap),['submit']);
  const calls=[]; t.mock.method(http,'post',async(...args)=>{calls.push(args);});
  await api.submit(period,id,'提交','s',0); await api.review(period,other,false,'退回','r',6);
  assert.deepEqual(calls,[
    ['/franchise/performance/cycles/submit',{period,userId:id,note:'提交',expectedRevision:0},{'Idempotency-Key':'s'}],
    ['/franchise/performance/cycles/review',{period,userId:other,approve:false,note:'退回',expectedRevision:6},{'Idempotency-Key':'r'}]
  ]);
});
test('只有退回状态本人能重新提交，同次连点只请求一次，没有父单的历史记录仅显示编号',async t=>{
  const {page,event,opened}=start(t);let finish;
  const past={note:'历史',sources:[{key:'sale-contract:11',type:'auto:contract',value:'4500',sourceType:'contract',sourceId:'11',date:'2026-11-11'}]};
  t.mock.method(api,'list',async()=>[cycle(id,{revision:4,status:'RETURNED',submission:JSON.stringify(past)})]);
  const cmd=t.mock.method(api,'submit',(...args)=>{assert.equal(args[4],4);return new Promise(resolve=>{finish=resolve;});});
  await page.onShow();assert.equal(page.data.canSubmit,true);assert.equal(page.data.snapshot.sources[0].sourceId,'11');
  page.openSource(event('index',0));assert.equal(opened(),undefined);
  page.inputNote({detail:{value:'补充'}});const first=page.submit();await page.submit();assert.equal(cmd.mock.callCount(),1);
  finish(cycle(id,{revision:5}));await first;assert.equal(page.data.current.revision,5);
});
test('同一岗位收回许可后，前台只保留服务器重新授权的数据，旧的管理结果及草稿全清',async t=>{
  const {page}=start(t,true); let count=0;
  t.mock.method(api,'list',async()=>{ if(++count===1)return [cycle(other,{canReview:true})]; throw {code:403,message:'已撤销查看权限'}; });
  await page.onShow();page.inputNote({detail:{value:'上一岗位草稿'}});
  const reload=page.onShow();assert.equal(page.data.current,null);await reload;
  assert.equal(page.data.current,null);assert.equal(page.data.members.length,0);assert.equal(page.data.note,'');
  assert.equal(page.data.canReview,false);assert.match(page.data.error,/已撤销/);
});
test('旧版 JSONB 中尚未加引号的大整数保留全部位数',()=>{
  const {versions}=require('../miniprogram/pages/admin/tasks/cycle-version');
  const submission='{"note":"行内 90071992547409999","actuals":{"business":{"auto:collectionsAmount":90071992547409999}},"history":[]}';
  const result=versions(cycle(id,{submission}),'sales')[0];
  assert.equal(result.note,'行内 90071992547409999'); assert.equal(result.actuals[0].value,'900719925474099.99 元');
});

test('原单较多时按页展示，翻页后打开当页原单',async t=>{
  const {page,event,opened}=start(t);
  const sources=Array.from({length:36},(_,i)=>({key:'customer:'+i,type:'metric:effectiveCustomers',value:'100',
    sourceType:'customer',sourceId:String(i+1),customerId:String(i+1),date:'2026-11-01'}));
  t.mock.method(api,'list',async()=>[cycle(id,{submission:JSON.stringify({sources})})]);
  await page.onShow();assert.equal(page.data.snapshot.sources.length,30);
  page.pageSources(event('step','next'));assert.equal(page.data.snapshot.sources.length,6);
  page.openSource(event('index',0));assert.equal(opened(),'/pages/workflow/index?view=customer&id=31');
});

test('本人可显式更新待审当前版，旧提交只读，管理人不能沿用被替代版本',async t=>{
  const {page}=start(t);
  t.mock.method(api,'list',async()=>[cycle(id,{revision:1})]);
  const updated=cycle(id,{revision:2,submission:JSON.stringify({note:'更正说明',sources:[],history:[
    {revision:1,status:'PENDING_REVIEW',submission:{note:'原待审说明',sources:[]}}
  ]})});
  t.mock.method(api,'submit',async(p,u,n,key,expected)=>{
    assert.deepEqual([p,u,n,expected],[period,id,'更正说明',1]);assert.ok(key);return updated;
  });
  await page.onShow();assert.equal(page.data.canSubmit,true);
  page.inputNote({detail:{value:'更正说明'}});await page.submit();
  assert.equal(page.data.current.revision,2);assert.equal(page.data.snapshot.note,'更正说明');
  page.selectVersion({detail:{value:'1'}});assert.equal(page.data.snapshot.note,'原待审说明');assert.equal(page.data.canSubmit,false);
});

test('在前台切换岗位后，成员、历史、分页、原单、月份、输入和写操作全部即时清除旧详情',async t=>{
  const {page,actor,event,opened}=start(t,true);
  const row=cycle(other,{canReview:true,submission:JSON.stringify({note:'团队私有',sources:[],
    history:[{revision:1,status:'RETURNED',submission:{note:'历史私有'}}]})});
  t.mock.method(api,'list',async()=>[row]);
  const actions=[
    ()=>page.selectMember(event('user',other)), ()=>page.selectVersion({detail:{value:'1'}}),
    ()=>page.pageSources(event('step','next')), ()=>page.openSource(event('index',0)),
    ()=>page.month({detail:{value:'2026-12'}}), ()=>page.inputNote({detail:{value:'前岗位输入'}}),
    ()=>page.review(event('action','approve')), ()=>page.submit(), ()=>page.onShow()
  ];
  for (const action of actions) {
    actor.gen++;actor.post='17';actor.role='boss';await page.onShow();assert(page.data.current);
    actor.gen++;actor.post='18';actor.role='sales';await action();
    assert.equal(page.data.current,null);assert.equal(page.data.snapshot,null);assert.equal(page.data.members.length,0);
    assert.equal(page.data.canReview,false); assert.equal(page.data.note,''); assert.equal(page.records.length,0); assert.equal(page.versionRows.length,0);
  }
  assert.equal(opened(),undefined);
});

test('切到另一个有权主管岗时立即清屏，等新公司的列表返回后才可看新成员',async t=>{
  const {page,actor,event}=start(t,true);
  let next;
  const listing=t.mock.method(api,'list',async()=>[cycle(other,{canReview:true})]);
  await page.onShow();assert.equal(page.data.current.userId,other);
  listing.mock.mockImplementation(()=>new Promise(done=>{next=done;}));
  actor.post='82';actor.org='82';actor.gen++;page.selectMember(event('user',other));
  assert.equal(page.data.current,null);assert.equal(page.data.snapshot,null);assert.equal(page.data.members.length,0);
  next([cycle(other,{canReview:true}),cycle('83',{companyId:'82',canReview:true})]);
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(page.data.members.map(row=>row.userId),['83']);assert.equal(page.data.current.userId,'83');
});
test('写入回执到达前已切换岗位时，同屏旧资料和写回结果一并清除',async t=>{
  const {page,actor,event}=start(t,true);
  t.mock.method(api,'list',async()=>[cycle(other,{canReview:true})]);let finish;
  t.mock.method(api,'review',()=>new Promise(done=>{finish=done;}));
  await page.onShow();const pending=page.review(event('action','approve'));assert.equal(page.data.saving,true);
  actor.post='55';actor.gen++;actor.role='sales';
  finish(cycle(other,{revision:2,status:'REVIEWED'}));await pending;
  assert.equal(page.data.current,null);assert.equal(page.data.snapshot,null);assert.equal(page.data.members.length,0);
  assert.equal(page.data.saving,false);assert.equal(page.data.canReview,false);
});
