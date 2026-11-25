const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
(async () => {
  const report = require('./verify-package-resources.cjs')();
  const app = require('../miniprogram/app.json');
  assert(!app.pages.includes('pages/design/index'));
  assert(app.subPackages.some(pkg => pkg.root === 'pages/design' && pkg.pages.includes('index')));
  const { connect, render } = require('./role-page-runtime.cjs');
  const runtime = await connect();
  const appContext = getApp();
  global.getApp = () => appContext;
  const config = require('../project.config.json');
  const excluded = new Set(config.packOptions.ignore.filter(rule => rule.type === 'file').map(rule => rule.value));
  const owner = file => app.subPackages.find(pkg => file.startsWith(pkg.root + '/'))?.root || 'main';
  const imageChecks = [];
  function checkImages(label) {
    const current = getCurrentPages().at(-1), images = [];
    function visit(nodes) {
      for (const node of nodes) {
        if (node.tag === 'image') images.push(node.attrs.src);
        visit(node.children || []);
      }
    }
    visit(render(current.file, current.data));
    assert(images.length, label + ': images are rendered');
    for (const src of images) {
      assert(src?.startsWith('/'), label + ': valid local image path: ' + src);
      const file = src.slice(1);
      assert(fs.existsSync(path.resolve('miniprogram', file)), label + ': missing ' + file);
      assert(!excluded.has(file), label + ': excluded ' + file);
      assert(owner(file) === 'main' || owner(file) === owner(current.route), label + ': cross-package image ' + file);
    }
    imageChecks.push({ label, count: images.length });
  }
  const A = require('../miniprogram/utils/account');
  (function(role){const MKEY=require('../miniprogram/utils/storage-keys').management;A.mutate(s=>{Object.assign(s.session,{loggedIn:true,companyId:'quanzhou',staffId:role+'-x',userId:role+'-x',role:role});s.companies=[{id:'quanzhou',name:'门店',type:'store',roles:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner']}];});wx.setStorageSync(MKEY,{version:1,companyId:'quanzhou',staff:[{id:role+'-x',personId:role+'-x',name:role,roles:[role],teamIds:{sales:'sales',engineering:'engineering',finance:'finance'}}]});})('sales'); // 切生产收尾批：loginDemo 下线，直接构造最小身份
  // Image rendering must not depend on wx.loadSubpackage or another package being downloaded.
  wx.loadSubpackage = () => { throw Error('Design entrance must use its own package images'); };
  await runtime.callWxMethod('reLaunch', { url: '/pages/design/index' });
  const page = getCurrentPages().at(-1);
  assert.equal(page.route, 'pages/design/index');
  // 切生产收尾批（Codex R2 P2）：cases/entries/explore/studio 段随演示 seed 与本地链下线摘除——
  // 存活断言保留：分包归属（主包无 design 页）、loadSubpackage 禁依赖、入口渲染图分包归属
  checkImages('design entrance');
  // Sales no longer owns a scheme Tab: old paths must enter the retained inspiration tool.
  global.wx.request = o => o.fail && o.fail({ errMsg: 'request:fail no-handler' }); // explore 拉数走错误态（route/view 仍设置）
  const auth = require('../miniprogram/api/auth'), httpApi = require('../miniprogram/api/http');
  auth.saveLogin({ accessToken: 'design-t', posts: [] });
  for (const role of ['sales', 'sales-lead']) {
    auth.savePosts([{ postId: 21, roleCode: role, orgType: 'store' }]);
    httpApi.saveSession({ postId: 21 });
    await runtime.callWxMethod('reLaunch', { url: '/pages/design/index' });
    const inspiration = getCurrentPages().at(-1);
    assert.equal(inspiration.route, 'design-migration/pages/library/index', role + ' 旧方案入口进入灵感检索');
    assert.equal(inspiration.data.accessReady, true, role + ' 可以读取保留的灵感库');
  }
  // In-flight legacy design administration is a separate migration task. Its existing
  // authorized tools retain their subpackage and asset contract until that migration.
  auth.savePosts([{ postId: 22, roleCode: 'hq-design-admin', orgType: 'headquarters' }]);
  httpApi.saveSession({ postId: 22 });
  await runtime.callWxMethod('reLaunch', { url: '/pages/design/index' });
  const dpage = getCurrentPages().at(-1);
  dpage.onShow();
  assert.equal(dpage.data.entranceEnabled, true, '在途旧设计行政授权入口保持可用');
  const targets = {
    inspiration: { route: 'design-migration/pages/library/index' },
    budget: { route: 'design-migration/pages/budget/quick' }
  };
  for (const entry of dpage.data.entries) {
    dpage.open({ currentTarget: { dataset: { view: entry.view } } });
    const target = getCurrentPages().at(-1);
    const expected = targets[entry.view];
    assert.equal(target.route, expected.route, entry.view + ' 入口跳转目标');
    if (expected.line) assert.equal(target.data.line, expected.line, entry.view + ' 设计类型透传');
  }
  assert.deepEqual(dpage.data.entries.map(entry => entry.view), ['inspiration', 'budget'], '历史设计入口只保留灵感库和快速预算');
  assert(!fs.readFileSync('miniprogram/pages/design/index.wxml', 'utf8').includes('开始AI设计'), '旧页面不再邀请发起收费生成');
  console.log(JSON.stringify({ ...report, renderedImages: imageChecks }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
