const assert = require('node:assert/strict');
const test = require('node:test');
const accounts = require('../miniprogram/pages/studio/publication-accounts');
const pending = require('../miniprogram/pages/studio/publication-pending');

function fixture(overrides = {}) {
  const calls = [], storage = new Map();
  let current = true;
  const page = { actorScope: 'post-30', data: { view: 'publish', selectedAccount: 0, publisherAccountId: '',
    publisherAccounts: [], publisherAccountsAll: [], publicationAccount: '', title: '标题', publishText: '文案', workId: '80' },
    setData(patch) { Object.assign(this.data, patch); }, persistEditor() {}, go() {} };
  const env = {
    post: { orgId: '20', postId: '30' },
    http: { token: () => 'token', currentUserId: () => '10', tenantId: () => '1', sessionGuard: () => () => current },
    studio: { scope: () => 'post-30', publicationPlatforms: [{platform:'douyin'},{platform:'channels'}] },
    wx: { showModal: ({success}) => success({confirm:true}), showToast() {},
      getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key) },
    validate:()=>'',message:error=>error.message,
    api: { publisherAccounts: {
      list: async platform => { calls.push(['list', platform]); return []; },
      create: async body => { calls.push(['create', body]); return {id:'101'}; },
      update: async (id, body) => { calls.push(['update', id, body]); return {id}; }, ...overrides },
      publications: { create: async body => { calls.push(['publication', body]); return {id:'90'}; } } }
  };
  return { page, env, calls, revoke: () => {current=false;} };
}

test('one enabled account for the selected platform is selected; disabled and other platforms are excluded', async () => {
  const f = fixture({list:async()=>[
    {id:'101',platform:'douyin',displayName:'门店',identifier:'shop01',enabled:true},
    {id:'102',platform:'douyin',displayName:'旧账号',enabled:false},
    {id:'103',platform:'channels',displayName:'其他平台',enabled:true}
  ]});
  await accounts.load(f.page,f.env);
  assert.equal(f.page.data.publisherAccounts.length,1);
  assert.equal(f.page.data.publisherAccountId,'101');
  assert.equal(f.page.data.publisherAccountsAll.length,2);
});

test('multiple accounts require a deliberate choice and preserve a valid prior choice', async () => {
  const rows=[{id:'101',platform:'douyin',enabled:true},{id:'102',platform:'douyin',enabled:true}];
  const f=fixture({list:async()=>rows});
  await accounts.load(f.page,f.env);
  assert.equal(f.page.data.publisherAccountId,'');
  accounts.select(f.page,'102');
  await accounts.load(f.page,f.env);
  assert.equal(f.page.data.publisherAccountId,'102');
});

test('switching platforms clears the prior account and ignores out-of-order responses', async () => {
  const resolve={};const f=fixture({list:platform=>new Promise(done=>{resolve[platform]=done;})});
  const old=accounts.load(f.page,f.env);
  const next=accounts.selectPlatform(f.page,f.env,1);
  assert.equal(f.page.data.publisherAccountId,'');
  resolve.channels([{id:'103',platform:'channels',enabled:true}]);await next;
  resolve.douyin([{id:'101',platform:'douyin',enabled:true}]);await old;
  assert.equal(f.page.data.publisherAccountId,'103');
  assert.equal(f.page.data.publisherAccounts[0].platform,'channels');
});

test('load errors stay distinct from an empty directory and block publishing until retry succeeds', async () => {
  let fail=true;const f=fixture({list:async()=>{if(fail)throw Error('连接暂不可用');return [];}});
  await accounts.load(f.page,f.env);
  assert.equal(f.page.data.publisherAccountsLoaded,false);
  assert.equal(f.page.data.publisherAccountsError,'连接暂不可用');
  await pending.submit(f.page,f.env);
  assert.equal(f.calls.some(c=>c[0]==='publication'),false);
  fail=false;await accounts.load(f.page,f.env);
  assert.equal(f.page.data.publisherAccountsLoaded,true);
  assert.equal(f.page.data.publisherAccountsError,'');
  assert.deepEqual(f.page.data.publisherAccounts,[]);
});

test('renaming changes only displayName, while disabling preserves the directory entry', async () => {
  const row={id:'101',platform:'douyin',displayName:'旧名称',identifier:'shop01',enabled:true};
  const f=fixture({list:async()=>[row]});await accounts.load(f.page,f.env);
  accounts.edit(f.page,'101');f.page.data.publisherAccountNameInput='新名称';
  await accounts.save(f.page,f.env);
  assert.deepEqual(f.calls.find(c=>c[0]==='update'),['update','101',{displayName:'新名称'}]);
  await accounts.toggle(f.page,f.env,'101');
  assert.deepEqual(f.calls.filter(c=>c[0]==='update').at(-1),['update','101',{enabled:false}]);
});

test('account creation requires a real identifier and submits the selected platform', async () => {
  const f=fixture();accounts.edit(f.page);
  f.page.data.publisherAccountNameInput='门店账号';
  await accounts.save(f.page,f.env);assert.equal(f.calls.length,0);
  f.page.data.publisherAccountIdentifierInput='shop01';await accounts.save(f.page,f.env);
  assert.deepEqual(f.calls[0],['create',{platform:'douyin',displayName:'门店账号',identifier:'shop01'}]);
});

test('revoked sessions neither apply account results nor send queued directory mutations', async () => {
  let resolve;const f=fixture({list:()=>new Promise(done=>{resolve=done;})});
  const flight=accounts.load(f.page,f.env);f.revoke();resolve([{id:'101',platform:'douyin',enabled:true}]);await flight;
  assert.deepEqual(f.page.data.publisherAccounts,[]);
  f.page.data.publisherAccountNameInput='账号';f.page.data.publisherAccountIdentifierInput='shop01';
  await accounts.save(f.page,f.env);assert.equal(f.calls.length,0);
});
