const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    A.mutate(account => A.loginDemo(account, 'finance-dual'));
    const open = url => {
      wx.reLaunch({ url });
      const page = getCurrentPages().at(-1);
      assert.equal(page.data.error, '');
      return page;
    };
    const navigation = async () => {
      assert(await (await runtime.currentPage()).$('workspace-nav'), '财务首页和账本必须始终提供底部导航');
    };
    open('/pages/admin/finance-desk/index');
    await navigation();
    const page = open('/pages/admin/finance-desk/index?view=flows&tab=reconcile');
    assert.equal(page.data.flowTab, 'reconcile', '直达月末对账时不能落到应收');
    await navigation();
    assert.equal(await (await (await runtime.currentPage()).$('.fin-card .fin-title')).text(), '月末对账汇总');
    page.switchWorkspaceTab({ key: 'finance-home' });
    assert.equal(page.data.view, 'desk');
    await navigation();
    page.switchWorkspaceTab({ key: 'finance-books' });
    assert.equal(page.data.view, 'flows');
    assert.equal(page.data.flowTab, 'reconcile', '返回账本应保留原分段');
    let definition;
    const navFile = path.resolve('miniprogram/custom-tab-bar/index.js');
    vm.runInNewContext(fs.readFileSync(navFile, 'utf8'), {
      require:createRequire(navFile), Component:value => { definition = value; }, getCurrentPages, wx
    });
    const nav = {
      data:{}, properties:{ activeIndex:17 },
      setData(value) { Object.assign(this.data, value); }, ...definition.methods
    };
    nav.refresh();
    assert.equal(nav.data.selected, 17, '同一财务页的账本分段必须选中“账本”');
    console.log('PASS 财务首页、账本和对账直达保留导航与原分段');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
