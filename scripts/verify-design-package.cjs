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
  console.log(JSON.stringify({ ...report, renderedImages: imageChecks }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
