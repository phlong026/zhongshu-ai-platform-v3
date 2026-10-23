const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  for (const id of ['sales-dual', 'boss', 'finance-dual', 'hq-finance-dual', 'hq-design-admin', 'engineering-dual']) {
    Account.mutate(state => Account.loginDemo(state, id));
    wx.reLaunch({ url: '/pages/profile/index' });
    const page = getCurrentPages().at(-1);
    assert(!page.data.entries.some(row => row.text === '跨设备联调'), id + ' 不应显示联调入口');
    const link = await (await runtime.currentPage()).$('[data-url="/pages/collaboration/index"]');
    assert(!link, id + ' 不应渲染跨设备联调按钮');
    console.log('PASS 我的页面移除跨设备联调：' + id);
  }
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
