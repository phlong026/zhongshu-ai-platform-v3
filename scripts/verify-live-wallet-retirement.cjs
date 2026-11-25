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
 let spec;const routes=[],writes=[];
 const file=path.join(root,'miniprogram/pages/wallet/packages/index.js');
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{wx:{redirectTo:o=>routes.push(o.url),setStorageSync:()=>writes.push('storage')},Page:p=>spec=p,require:()=>{throw Error('retired page cannot load payment dependencies');}});
 const page=pageInstance(spec);await page.onShow();page.choose();await page.confirmPending();
 assert.deepEqual(routes,['/pages/wallet/company-recharge/index']);assert.equal(writes.length,0);
 console.log('PASS 旧模拟套餐深链进入公司真实虚拟支付，未重放或删除历史草稿');
}
verifyCompanyPackage().catch(e=>{console.error(e);process.exitCode=1;});
