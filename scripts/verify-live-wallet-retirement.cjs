const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const pages = [
  'miniprogram/pages/wallet/recharge/index',
  'miniprogram/pages/wallet/records/index'
];

function loadPage(base, token) {
  let definition;
  const calls = [];
  const http = { token: () => token };
  const navigation = { home: () => calls.push({ kind: 'home' }) };
  const wx = {
    redirectTo: options => calls.push({ kind: 'redirectTo', url: options.url }),
    switchTab: options => calls.push({ kind: 'switchTab', url: options.url })
  };
  const source = fs.readFileSync(path.join(root, base + '.js'), 'utf8');
  vm.runInNewContext(source, {
    Page: value => { definition = value; },
    require: request => {
      if (request === '../../../api/http') return http;
      if (request === '../../../utils/page-navigation') return navigation;
      throw new Error('退役钱包页不得导入旧模块：' + request);
    },
    wx,
    encodeURIComponent
  }, { filename: base + '.js' });
  return { definition, calls };
}

function pageInstance(definition) {
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); }
  };
}

for (const base of pages) {
  const source = fs.readFileSync(path.join(root, base + '.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(root, base + '.wxml'), 'utf8');
  assert(!source.includes("api/wallet"), base + ' 不得继续导入旧个人钱包 API');
  assert(!source.includes('ensureSession'), base + ' 不得继续桥接 /platform/wallet/session');
  for (const handler of ['load', 'confirmPay', 'continuePay', 'loadOrders', 'loadLedger', 'refreshAccount', 'goRecharge', 'goToRecords']) {
    assert(!new RegExp('\\b' + handler + '\\s*\\(').test(source), base + ' 应删除旧处理器 ' + handler);
  }
  assert(wxml.includes('公司积分池'), base + ' 必须明确公司积分池是当前唯一入口');
  assert(wxml.includes('bindtap="openCompanyPoints"'), base + ' 必须提供个人页公司积分入口');
  assert(!/confirmPay|continuePay|goRecharge|goToRecords/.test(wxml), base + ' 视图不得保留旧充值动作');

  const logged = loadPage(base, 'platform-token');
  const loggedPage = pageInstance(logged.definition);
  loggedPage.onLoad();
  loggedPage.onShow();
  assert.equal(loggedPage.data.loggedIn, true, base + ' 应识别有效平台会话');
  assert.equal(logged.calls.length, 0, base + ' 生命周期不得发请求或自动跳转');
  loggedPage.openCompanyPoints();
  loggedPage.returnWorkbench();
  assert.deepEqual(logged.calls, [
    { kind: 'switchTab', url: '/pages/profile/index' },
    { kind: 'home' }
  ], base + ' 登录后应进入个人页公司积分卡或返回当前岗位工作台');

  const guest = loadPage(base, '');
  const guestPage = pageInstance(guest.definition);
  guestPage.onLoad();
  guestPage.onShow();
  assert.equal(guestPage.data.loggedIn, false, base + ' 应识别无平台会话');
  assert.equal(guest.calls.length, 0, base + ' 无会话生命周期也不得请求旧钱包');
  guestPage.openCompanyPoints();
  assert.equal(guest.calls.length, 1);
  assert.equal(guest.calls[0].kind, 'redirectTo');
  assert.match(guest.calls[0].url, /^\/pages\/account\/index\?view=login&return=/, base + ' 无会话应提供登录入口');
  assert.equal(decodeURIComponent(guest.calls[0].url.split('return=')[1]), '/pages/profile/index');
}

console.log('旧个人钱包退役：2 个注册入口行为通过');

async function verifyCompanyPackage() {
  let spec, modal, attempts=0;
  const store=new Map(), bodies=[];
  const api={fingerprint:()=> '1:2',isStale:()=>false,formatCredits:String,
    packages:async()=>[{id:'101',version:'4',name:'体验',priceFen:990,basePoints:100,bonusPoints:20}],
    purchase:async(plan,key)=>{bodies.push(JSON.stringify([plan,key]));if(++attempts===1)throw {code:-1,message:'网络中断'};return {id:'888',priceFen:990,points:120};}};
  const wx={showModal(o){modal=o;},getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k)};
  const file=path.join(root,'miniprogram/pages/wallet/packages/index.js');
  vm.runInNewContext(fs.readFileSync(file,'utf8'),{wx,Page:p=>{spec=p;},require:n=>{
    if(n.endsWith('company-points'))return api;
    if(n.endsWith('/auth'))return {currentApiPost:()=>({orgId:1,userId:7})};
    if(n.endsWith('/http'))return {currentPostId:()=>2,token:()=>true,tenantId:()=>1};
    if(n.endsWith('/finance'))return {idemKey:()=> 'test-package-key'};
    throw Error(n);
  }});
  const p=pageInstance(spec);await p.onShow();
  const e={currentTarget:{dataset:{id:'101'}}};p.choose(e);modal.success({confirm:false});
  assert.equal(attempts,0);p.choose(e);await modal.success({confirm:true});
  assert.equal(store.size,1);
  p.onUnload();const restored=pageInstance(spec);await restored.onShow();await restored.confirmPending();
  assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);assert.equal(restored.data.receipt.credited,'120');assert.equal(store.size,0);
  console.log('PASS 公司套餐确认、取消、断网后重开原单与积分回执');
}
verifyCompanyPackage().catch(e=>{console.error(e);process.exitCode=1;});
