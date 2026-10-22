// 展陈设计与众墅甄选目前只做展示，不绑定业务流程：
//  1. 每个加盟商人员（每个演示账号的每个岗位）的“我的”页都有 展陈设计、众墅甄选 和培训入口；
//  2. 两个展示页能打开，且没有下单、采购、订单类入口；
//  3. 旧的下单/订单/采购单链接统一回到展示页，不报错。
// Real Page handlers and WXML rendering in the Node runtime; not native renderer evidence.
const fs = require('fs'), path = require('path'), assert = require('assert/strict');
(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const { render } = require('./role-page-runtime.cjs');
  const A = require('../miniprogram/utils/account'), O = require('../miniprogram/utils/organization');
  const flat = ns => ns.flatMap(n => [n, ...flat(n.children || [])]);
  const text = n => ((n.text || '') + (n.children || []).map(text).join('')).replace(/\s+/g, ' ').trim();
  const pageText = p => text({ children: render(p.file, p.data) });
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const cur = () => getCurrentPages().at(-1);
  const open = url => { wx.reLaunch({ url }); assert(!cur().data.error, url + ' ' + cur().data.error); return cur(); };
  const people = A.demoLoginUsers.filter(p => p.companyId !== 'headquarters');
  assert(people.length >= 8, 'franchise demo identities');
  const forbidden = /提交需求|提交采购需求|我的订单|我的服务订单|我的采购单|服务流程|关联项目/;
  let seen = 0;
  for (const person of people) {
    for (const role of person.roles) {
      wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k));
      A.mutate(s => A.loginDemo(s, person.id));
      A.mutate(s => A.switchIdentity(s, s.session.companyId, role));
      const who = person.name + '/' + role;
      // the real tab page; boss and sales are redirected to their own profile pages
      let page = open('/pages/profile/index');
      if (page.route === 'pages/profile/index' && page.data.loggedIn === undefined) page = cur();
      const body = pageText(page);
      assert(body.includes('展陈设计'), who + ' 我的页缺少“展陈设计”：' + page.route);
      assert(body.includes('众墅甄选'), who + ' 我的页缺少“众墅甄选”：' + page.route);
      assert(/培训|众墅学堂|学习培训/.test(body), who + ' 我的页缺少培训入口：' + page.route);
      assert(!/我的订单/.test(body), who + ' 我的页不应再有“我的订单”');
      seen++;
    }
  }
  pass('A 所有加盟商人员岗位（' + seen + '个）的我的页都有展陈设计、众墅甄选和培训入口，且无“我的订单”');

  // the entries really open the showcase pages
  const sample = [['sales-dual', 'sales-lead'], ['boss', null], ['finance-dual', 'finance-lead'], ['engineering-dual', 'engineer'], ['engineering-dual', 'project-manager']];
  for (const [id, role] of sample) {
    wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k));
    A.mutate(s => A.loginDemo(s, id)); if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role));
    const services = open('/pages/account/index?view=services');
    assert.equal(services.data.view, 'services'); assert(!forbidden.test(pageText(services)), id + ' 展陈设计列表不应有下单/订单入口：' + (pageText(services).match(forbidden) || [])[0]);
    for (const sid of ['store', 'showroom', 'brand']) {
      const detail = open('/pages/account/index?view=service&service=' + sid);
      assert.equal(detail.data.view, 'service'); assert(!forbidden.test(pageText(detail)), id + ' 服务详情不应有下单入口：' + (pageText(detail).match(forbidden) || [])[0]);
      assert(!flat(render(detail.file, detail.data)).some(n => n.attrs?.['data-view'] === 'request'), '服务详情不应跳到需求提交');
    }
    const selection = open('/pages/account/index?view=selection');
    assert.equal(selection.data.view, 'selection'); assert(!forbidden.test(pageText(selection)), id + ' 众墅甄选不应有采购单入口：' + (pageText(selection).match(forbidden) || [])[0]);
    const product = selection.data.filteredProducts?.[0] || selection.data.products?.[0];
    assert(product, '甄选商品存在');
    const detail = open('/pages/account/index?view=product&id=' + encodeURIComponent(product.id));
    assert.equal(detail.data.view, 'product'); assert(!forbidden.test(pageText(detail)), id + ' 商品详情不应有采购入口：' + (pageText(detail).match(forbidden) || [])[0]);
    assert(!flat(render(detail.file, detail.data)).some(n => n.attrs?.['data-view'] === 'procure'), '商品详情不应跳到采购需求');
  }
  pass('B 展陈设计（店招/展厅/品牌物料）与众墅甄选（列表/商品详情）可打开，只展示、无下单和采购入口');

  const redirects = { request: 'services', orders: 'services', order: 'services', plan: 'services', delivery: 'services', cancel: 'services', procure: 'selection', purchases: 'selection', purchase: 'selection' };
  wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k));
  A.mutate(s => A.loginDemo(s, 'sales-dual'));
  for (const [view, target] of Object.entries(redirects)) { const p = open('/pages/account/index?view=' + view + '&id=x'); assert.equal(p.data.view, target, view + ' 应回到展示页 ' + target); assert(!p.data.error && !p.data.missing); }
  pass('C 旧的下单、订单、采购单链接统一回到展示页，不报错');

  fs.mkdirSync('qa/franchise-showcase-20261022', { recursive: true });
  fs.writeFileSync('qa/franchise-showcase-20261022/results.json', JSON.stringify({ result: 'passed', identities: seen, checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
  process.exit(0);
})().catch(e => { console.error(e.stack); process.exit(1); });
