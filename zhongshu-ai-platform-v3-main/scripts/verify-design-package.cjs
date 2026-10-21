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
  A.mutate(state => A.loginDemo(state, 'sales-dual'));
  // Image rendering must not depend on wx.loadSubpackage or another package being downloaded.
  wx.loadSubpackage = () => { throw Error('Design entrance must use its own package images'); };
  await runtime.callWxMethod('reLaunch', { url: '/pages/design/index' });
  const page = getCurrentPages().at(-1);
  assert.equal(page.route, 'pages/design/index');
  assert.equal(page.data.cases.length, 4);
  checkImages('design entrance');
  for (const item of page.data.cases) {
    assert(item.image.startsWith('/pages/design/assets/'));
    const local = fs.readFileSync(path.resolve('miniprogram', '.' + item.image));
    const original = fs.readFileSync(path.resolve('miniprogram', '.' + item.image.replace('/design/', '/workflow/')));
    assert(local.equals(original), 'Preserve the original full image');
  }
  for (const entry of page.data.entries) {
    await runtime.callWxMethod('reLaunch', { url: '/pages/design/index' });
    getCurrentPages().at(-1).open({ currentTarget: { dataset: { view: entry.view } } });
    assert.equal(getCurrentPages().at(-1).route, 'pages/workflow/explore/index');
    assert.equal(getCurrentPages().at(-1).data.view, entry.view);
    if (entry.view === 'inspiration') checkImages('inspiration catalog');
  }
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/explore/index?view=case&case=ZS001' });
  checkImages('case facade');
  getCurrentPages().at(-1).caseTab({ currentTarget: { dataset: { index: 1 } } });
  checkImages('case floor plan');
  require('../miniprogram/utils/sales-demo').ensure();
  const state = require('../miniprogram/utils/studio').read();
  const params = '&work=' + encodeURIComponent(state.works[0].id) + '&task=' + encodeURIComponent(state.tasks.find(task => task.status === 'completed').id) + '&record=' + encodeURIComponent(state.records[0].id);
  for (const view of ['remix', 'digital', 'tasks', 'result', 'publish', 'records', 'detail', 'works', 'library', 'recent']) {
    await runtime.callWxMethod('reLaunch', { url: '/pages/studio/index?view=' + view + params });
    checkImages('studio ' + view);
  }
  console.log(JSON.stringify({ ...report, designImages: 4, entranceRoutes: 4, renderedImages: imageChecks }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
