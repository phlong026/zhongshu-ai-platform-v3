const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs'),path = require('node:path'),vm = require('node:vm');
const { render } = require('./role-page-runtime.cjs');

const storage = {};
const requests = [];
global.wx = {
  getStorageSync(key) { return storage[key] || ''; },
  setStorageSync(key, value) { storage[key] = value; },
  removeStorageSync(key) { delete storage[key]; },
  request(options) {
    requests.push(options);
    options.success({ statusCode: 200, data: { code: 0, data: {} } });
  }
};

const http = require('../miniprogram/api/http');
const points = require('../miniprogram/api/company-points');

test('overlapping company point reads share in-flight requests without caching completed balances', async () => {
  const originalRequest = wx.request, captured = [];
  wx.request = options => { captured.push(options); };
  http.saveSession({ token: 'unit-token', postId: 'unit-post', tenantId: 'unit-tenant' });
  try {
    const a = points.summary(), b = points.summary();
    const c = points.myUsage({ page: 1, size: 1 }), d = points.myUsage({ page: 1, size: 1 });
    assert.equal(captured.length, 2);
    captured[0].success({ statusCode: 200, data: { code: 0, data: { balance: 10 } } });
    captured[1].success({ statusCode: 200, data: { code: 0, data: { used: 2 } } });
    assert.deepEqual(await Promise.all([a,b,c,d]), [{ balance: 10 },{ balance: 10 },{ used: 2 },{ used: 2 }]);
    const fresh = points.summary();
    assert.equal(captured.length, 3);
    captured[2].success({ statusCode: 200, data: { code: 0, data: { balance: 9 } } });
    assert.equal((await fresh).balance, 9);
  } finally { wx.request = originalRequest; }
});

test('in-flight company point reads separate queries and posts and retry after failure', async () => {
  const originalRequest = wx.request, captured = [];
  wx.request = options => { captured.push(options); };
  http.saveSession({ token: 'unit-token', postId: 'unit-old', tenantId: 'unit-tenant' });
  try {
    const old = points.summary();
    const rejected = assert.rejects(old, error => error.code === points.STALE);
    http.saveSession({ token: 'unit-token', postId: 'unit-new', tenantId: 'unit-tenant' });
    const next = points.summary(), pageOne = points.myUsage({ page: 1 }), pageTwo = points.myUsage({ page: 2 });
    assert.equal(captured.length, 4);
    captured[0].success({ statusCode: 200, data: { code: 0, data: { balance: 999 } } });
    captured[1].success({ statusCode: 200, data: { code: 0, data: { balance: 7 } } });
    captured[2].success({ statusCode: 200, data: { code: 0, data: { used: 1 } } });
    captured[3].success({ statusCode: 200, data: { code: 0, data: { used: 2 } } });
    await rejected;
    assert.deepEqual(await Promise.all([next,pageOne,pageTwo]), [{ balance: 7 },{ used: 1 },{ used: 2 }]);
    const failed = points.summary();
    captured[4].success({ statusCode: 200, data: { code: 500, msg: 'unit failure' } });
    await assert.rejects(failed);
    const retry = points.summary();
    assert.equal(captured.length, 6);
    captured[5].success({ statusCode: 200, data: { code: 0, data: { balance: 6 } } });
    assert.equal((await retry).balance, 6);
  } finally { wx.request = originalRequest; }
});

test('company point API uses the platform session and current post', async () => {
  http.saveSession({ token: 'platform-token', postId: 'post-9', tenantId: 'tenant-2' });
  await points.summary();
  await points.myUsage({ month: '2026-11', page: 2, size: 10 });

  assert.equal(requests[0].url.endsWith('/admin-api/franchise/company-points/summary'), true);
  assert.equal(requests[0].header.Authorization, 'Bearer platform-token');
  assert.equal(requests[0].header['X-Post-Id'], 'post-9');
  assert.equal(requests[1].url.includes('/franchise/company-points/my-usage?month=2026-11&page=2&size=10'), true);
});

test('late company point response is rejected after a post switch', async () => {
  let resolveRequest;
  wx.request = (options) => { resolveRequest = options.success; };
  http.saveSession({ token: 'platform-token', postId: 'post-a', tenantId: 'tenant-2' });
  const pending = points.summary();
  http.saveSession({ token: 'platform-token', postId: 'post-b', tenantId: 'tenant-2' });
  resolveRequest({ statusCode: 200, data: { code: 0, data: { balance: 99 } } });
  await assert.rejects(pending, (error) => error && error.code === points.STALE);
});

test('company point formatter preserves BIGINT values exactly and rejects unsafe numbers', () => {
  assert.equal(points.formatCredits('9223372036854775807'), '9,223,372,036,854,775,807');
  assert.equal(points.formatCredits('0001200'), '1,200');
  assert.equal(points.formatCredits(9007199254740991), '9,007,199,254,740,991');
  assert.throws(() => points.formatCredits(9007199254740992), /INVALID_CREDIT_VALUE/);
  assert.throws(() => points.formatCredits('12.5'), /INVALID_CREDIT_VALUE/);
  assert.throws(() => points.formatCredits(' 12'), /INVALID_CREDIT_VALUE/);
});

test('point asset card keeps visible loading, error and ready states without wallet navigation', () => {
  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');

  assert.equal(definition.data.state, 'loading');
  assert.equal(typeof definition.methods.retry, 'function');
  assert.equal('goToRecharge' in definition.methods, false);
  assert.equal('goToRecords' in definition.methods, false);
});

test('point asset card renders active company balance and personal monthly usage', async () => {
  points.summary = () => Promise.resolve({ balance: '9223372036854775807', status: 'ACTIVE' });
  points.myUsage = () => Promise.resolve({ used: '345' });
  http.saveSession({ token: 'platform-token', postId: 'post-ready', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(instance.data.state, 'ready');
  assert.equal(instance.data.balanceText, '9,223,372,036,854,775,807');
  assert.equal(instance.data.usedText, '345');
});

test('point asset card shows suspended accounts explicitly and rejects unknown status', async () => {
  points.summary = () => Promise.resolve({ balance: '100', status: 'SUSPENDED' });
  points.myUsage = () => Promise.resolve({ used: '20' });
  http.saveSession({ token: 'platform-token', postId: 'post-suspended', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(instance.data.state, 'suspended');
  assert.equal(instance.data.balanceLabel, '账户余额');

  points.summary = () => Promise.resolve({ balance: '100', status: 'FUTURE_STATE' });
  const originalWarn = console.warn;
  console.warn = () => {};
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  console.warn = originalWarn;
  assert.equal(instance.data.state, 'error');
});

test('point asset card exposes a retryable error without showing backend details', async () => {
  points.summary = () => Promise.reject({ code: 500, message: 'database detail' });
  points.myUsage = () => Promise.resolve({ used: 1 });
  http.saveSession({ token: 'platform-token', postId: 'post-error', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  const originalWarn = console.warn;
  console.warn = () => {};
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  console.warn = originalWarn;

  assert.equal(instance.data.state, 'error');
  assert.equal(instance.data.errorMessage, '积分信息加载失败，请稍后重试');
});

const tick=()=>new Promise(resolve=>setImmediate(resolve));
// Real component + company-points API; only the HTTP boundary and identity storage are replaced.
function pointCard(options={}){
 const root=path.resolve(__dirname,'../miniprogram'),cache=new Map(),requests=[],patches=[];
 const session={token:'token-1',generation:1,userId:'user-1',tenantId:'tenant-1',postId:'post-1',post:{userId:'user-1',tenantId:'tenant-1',postId:'post-1',orgId:'org-1',roleCode:'sales',orgType:'store',permissions:['points:read']}};
 const http={token:()=>session.token,sessionGeneration:()=>session.generation,currentUserId:()=>session.userId,tenantId:()=>session.tenantId,currentPostId:()=>session.postId,
  get:url=>url.endsWith('/recharge/catalog')?(options.packages?options.packages():Promise.resolve([{id:'plan-1'}])).then(plans=>({enabled:true,plans})):new Promise((resolve,reject)=>requests.push({url,resolve,reject}))};
 let definition;
 function load(file){
  file=path.resolve(root,file);if(!path.extname(file))file+='.js';if(cache.has(file))return cache.get(file).exports;
  const module={exports:{}};cache.set(file,module);
  vm.runInNewContext('(function(require,module,exports){'+fs.readFileSync(file,'utf8')+'\n})',{Component:v=>definition=v,console:{warn(){}},wx:{navigateTo(){}}},{filename:file})(name=>{
   const dependency=path.resolve(path.dirname(file),name);
   if(dependency===path.join(root,'api/http'))return http;
   if(dependency===path.join(root,'api/auth'))return {currentApiPost:()=>session.post};
   return load(dependency);
  },module,module.exports);return module.exports;
 }
 load('components/point-asset-card/index.js');
 const card={...definition.methods,data:structuredClone(definition.data),setData(patch){patches.push(structuredClone(patch));Object.assign(this.data,patch);}};
 const respond=(start=0,balance='80',used='3')=>requests.slice(start,start+2).forEach(r=>r.resolve(r.url.endsWith('/summary')?{balance,status:'ACTIVE'}:{used}));
 return {card,definition,requests,patches,session,respond};
}
test('point card attached/show share one loading render and one pair of pending API reads',async()=>{
 const h=pointCard();h.definition.lifetimes.attached.call(h.card);h.definition.pageLifetimes.show.call(h.card);
 assert.equal(h.requests.length,2);assert.equal(h.patches.filter(p=>p.state==='loading').length,1);
 h.respond();await tick();assert.equal(h.card.data.balanceText,'80');
});
test('same-scope point refresh keeps amounts, state and package entry until the latest balances arrive',async()=>{
 const h=pointCard();h.card.refresh();h.respond();await tick();assert.equal(h.card.data.purchaseAvailable,true);
 h.card.refresh();h.card.refresh();assert.equal(h.requests.length,4);
 assert.equal(h.card.data.balanceText,'80');assert.equal(h.card.data.usedText,'3');assert.equal(h.card.data.state,'ready');assert.equal(h.card.data.purchaseAvailable,true);
 h.respond(2,'65','18');await tick();assert.equal(h.card.data.balanceText,'65');assert.equal(h.card.data.usedText,'18');
});
test('ordinary point refresh failure keeps amounts with a retry hint in the existing heading',async()=>{
 const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();h.requests[2].reject({code:500,message:'offline'});h.requests[3].resolve({used:'3'});await tick();
 assert.equal(h.card.data.balanceText,'80');assert.equal(h.card.data.state,'ready');assert(h.card.data.errorMessage);
 const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children)).join('');
 const nodes=render(path.resolve(__dirname,'../miniprogram/components/point-asset-card/index.wxml'),h.card.data);
 assert(text(nodes).includes('更新失败'));assert(text(nodes).includes('80'));
 h.card.retry();h.respond(4,'70');await tick();assert.equal(h.card.data.balanceText,'70');assert.equal(h.card.data.errorMessage,'');
});
test('point API reads and the card isolate complete session/post permissions, including same-post role changes',async()=>{
 for(const field of ['generation','userId','tenantId','postId','post.userId','post.tenantId','post.orgId','post.postId','post.roleCode','post.orgType','post.permissions']){
  const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();
  if(field.startsWith('post.')){const key=field.slice(5);h.session.post[key]=key==='permissions'?['points:new']:h.session.post[key]+'-new';}else h.session[field]+='-new';
  h.card.refresh();assert.equal(h.card.data.balanceText,'--',field);assert.equal(h.requests.length,6,field);
  h.respond(4,'50','6');await tick();h.respond(2,'999','999');await tick();assert.equal(h.card.data.balanceText,'50',field);
 }
});
test('hidden or detached point reads cannot update the card and a return starts fresh reads before old requests settle',async()=>{
 for(const detached of [false,true]){
  const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();
  const stop=detached?h.definition.lifetimes.detached:h.definition.pageLifetimes.hide;assert.equal(typeof stop,'function');stop.call(h.card);
  h.definition.pageLifetimes.show.call(h.card);assert.equal(h.requests.length,6,'return cannot reuse the pre-operation request');
  h.respond(4,'40','9');await tick();h.requests[2].reject({code:403});h.requests[3].resolve({used:'99'});await tick();
  assert.equal(h.card.data.balanceText,'40');assert.equal(h.card.data.usedText,'9');
 }
});
test('current point permission failures discard cached balances while late logout responses cannot refill them',async()=>{
 for(const error of [{code:401},{code:403},{postInvalid:true}]){
  const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();h.requests[2].reject(error);h.requests[3].resolve({used:'3'});await tick();
  assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');assert.equal(h.card.data.purchaseAvailable,false);
 }
 const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();h.session.token='';h.session.generation++;h.respond(2,'999');await tick();
 assert.equal(h.card.data.visible,false);assert.equal(h.card.data.balanceText,'--');
});
test('a salesperson without company recharge permission keeps the readable company balances',async()=>{
 let reject;const h=pointCard({packages:()=>new Promise((_,fail)=>reject=fail)});
 assert.equal(h.session.post.roleCode,'sales');assert(!h.session.post.permissions.includes('platform:wallet:recharge'));
 h.card.refresh();h.respond();await tick();assert.equal(h.card.data.balanceText,'80');
 reject({code:403});await tick();
 assert.equal(h.card.data.balanceText,'80');assert.equal(h.card.data.usedText,'3');
 assert.equal(h.card.data.state,'ready');assert.equal(h.card.data.errorMessage,'');assert.equal(h.card.data.purchaseAvailable,false);
});
test('a later package authentication or revoked-post failure still clears private balances',async()=>{
 for(const error of [{code:401},{postInvalid:true}]){
  let reject;const h=pointCard({packages:()=>new Promise((_,fail)=>reject=fail)});h.card.refresh();h.respond();await tick();assert.equal(h.card.data.balanceText,'80');
  reject(error);await tick();assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.purchaseAvailable,false);
 }
});
test('balance and personal usage render independently and a first-load failure affects only its metric',async()=>{
 for(const failed of ['balance','usage']){
  const h=pointCard();h.card.refresh();
  const summary=h.requests[0],usage=h.requests[1],success=failed==='balance'?usage:summary,failure=failed==='balance'?summary:usage;
  success.resolve(failed==='balance'?{used:'7'}:{balance:'90',status:'ACTIVE'});await tick();
  assert.equal(h.card.data[failed==='balance'?'usedText':'balanceText'],failed==='balance'?'7':'90');
  assert.equal(h.card.data[failed==='balance'?'balanceText':'usedText'],'--');assert.equal(h.card.data.refreshing,true);
  failure.reject({code:500,message:'database detail'});await tick();
  assert.equal(h.card.data[failed==='balance'?'usedText':'balanceText'],failed==='balance'?'7':'90');
  assert.equal(h.card.data[failed+'Error'],true);assert.equal(h.card.data[failed==='balance'?'usageError':'balanceError'],false);
  assert.equal(h.card.data.refreshing,false);assert(!h.card.data.errorMessage.includes('database'));
  const nodes=render(path.resolve(__dirname,'../miniprogram/components/point-asset-card/index.wxml'),h.card.data);
  const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children)).join('');
  assert(text(nodes).includes('更新失败'));assert(text(nodes).includes(failed==='balance'?'7':'90'));
  h.card.retry();h.respond(2,'85','12');await tick();
  assert.equal(h.card.data.balanceText,'85');assert.equal(h.card.data.usedText,'12');assert.equal(h.card.data.errorMessage,'');
 }
});
test('a failed metric keeps its prior value while the other metric updates in the same identity',async()=>{
 const h=pointCard();h.card.refresh();h.respond();await tick();
 h.card.refresh();h.requests[2].reject({code:500});h.requests[3].resolve({used:'9'});await tick();
 assert.equal(h.card.data.balanceText,'80');assert.equal(h.card.data.usedText,'9');assert.equal(h.card.data.balanceError,true);
 h.card.refresh();h.requests[4].resolve({balance:'60',status:'ACTIVE'});h.requests[5].reject({code:500});await tick();
 assert.equal(h.card.data.balanceText,'60');assert.equal(h.card.data.usedText,'9');assert.equal(h.card.data.balanceError,false);assert.equal(h.card.data.usageError,true);
});
test('a core permission failure cannot be undone by a later successful companion read',async()=>{
 for(const error of [{code:401},{code:403},{postInvalid:true}]){
  for(const failed of [0,1]){
   const h=pointCard();h.card.refresh();h.respond();await tick();h.card.refresh();
   h.requests[2+failed].reject(error);await tick();
   assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');
   h.requests[2+(1-failed)].resolve(failed===0?{used:'99'}:{balance:'999',status:'ACTIVE'});await tick();
   assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');assert.equal(h.card.data.purchaseAvailable,false);
  }
 }
});
test('a package authentication failure blocks an outstanding personal usage response',async()=>{
 for(const error of [{code:401},{postInvalid:true}]){
  let reject;const h=pointCard({packages:()=>new Promise((_,fail)=>reject=fail)});h.card.refresh();
  h.requests[0].resolve({balance:'80',status:'ACTIVE'});await tick();
  reject(error);await tick();h.requests[1].resolve({used:'99'});await tick();
  assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');assert.equal(h.card.data.purchaseAvailable,false);
 }
});
test('a late optional package denial from the previous organization cannot replace the current result',async()=>{
 const packages=[];const h=pointCard({packages:()=>new Promise((resolve,reject)=>packages.push({resolve,reject}))});
 h.card.refresh();h.respond();await tick();h.session.post.orgId='org-2';h.card.refresh();h.respond(2,'70','13');await tick();
 packages[1].resolve([{id:'new-plan'}]);await tick();assert.equal(h.card.data.purchaseAvailable,true);
 packages[0].reject({code:403});await tick();
 assert.equal(h.card.data.purchaseAvailable,true);assert.equal(h.card.data.balanceText,'70');assert.equal(h.card.data.usedText,'13');assert.equal(h.card.data.errorMessage,'');
});
test('switching from a rechargeable identity to salesperson and back keeps only the current identity balances',async()=>{
 for(const anotherCompany of [false,true]){
  const packages=[];const h=pointCard({packages:()=>new Promise((resolve,reject)=>packages.push({resolve,reject}))});
  const switchIdentity=role=>{
   h.session.postId='post-'+role;h.session.post.postId=h.session.postId;h.session.post.roleCode=role;
   h.session.post.orgId=role==='sales'&&anotherCompany?'org-2':'org-1';
   h.session.post.permissions=role==='owner'?['platform:wallet:recharge']:['points:read'];
  };
  switchIdentity('owner');h.card.refresh();h.respond(0,'1000','10');await tick();packages[0].resolve([{id:'owner-plan'}]);await tick();
  assert.equal(h.card.data.balanceText,'1,000');assert.equal(h.card.data.usedText,'10');assert.equal(h.card.data.purchaseAvailable,true);
  h.card.refresh(); // Leave an old identity's core reads pending while the user switches.
  switchIdentity('sales');h.card.refresh();
  assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');assert.equal(h.card.data.purchaseAvailable,false);
  h.respond(4,'950','12');await tick();packages[1].reject({code:403});await tick();
  assert.equal(h.card.data.balanceText,'950');assert.equal(h.card.data.usedText,'12');assert.equal(h.card.data.state,'ready');assert.equal(h.card.data.errorMessage,'');
  h.requests[2].reject({code:403});h.requests[3].resolve({used:'999'});await tick();
  assert.equal(h.card.data.balanceText,'950');assert.equal(h.card.data.usedText,'12');assert.equal(h.card.data.errorMessage,'');
  h.card.refresh();h.respond(6,'930','15');await tick(); // Leave salesperson's optional package read pending.
  switchIdentity('owner');h.card.refresh();
  assert.equal(h.card.data.balanceText,'--');assert.equal(h.card.data.usedText,'--');assert.equal(h.card.data.purchaseAvailable,false);
  h.respond(8,'920','17');await tick();packages[3].resolve([{id:'owner-plan'}]);await tick();
  packages[2].reject({code:403});await tick();
  assert.equal(h.card.data.balanceText,'920');assert.equal(h.card.data.usedText,'17');assert.equal(h.card.data.purchaseAvailable,true);
  assert.equal(h.card.data.state,'ready');assert.equal(h.card.data.errorMessage,'');
 }
});
