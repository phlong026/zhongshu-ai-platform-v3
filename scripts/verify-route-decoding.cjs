const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    A.mutate(account => A.loginDemo(account, 'boss'));
    const malformed = '%E0%A4%A';
    for (const [url, options, verify] of [
      ['/pages/account/index?view=learning', { view:'learning', tab:malformed, category:'AI%E5%BA%94%E7%94%A8' }, page => {
        assert.equal(page.data.tab, malformed);
        assert.equal(page.data.category, 'AI应用');
      }],
      ['/pages/admin/index?view=todos', { view:'todos', tab:malformed }, page => assert.equal(page.data.form.tab, malformed)],
      ['/pages/admin/operations/index?view=desk', { view:'desk', id:malformed, purpose:malformed }, page => assert.equal(page.id, malformed)],
      ['/pages/admin/finance-desk/index?view=flows', { view:'flows', id:malformed, category:malformed }, page => {
        assert.equal(page.id, malformed);
        assert.equal(page.data.category, malformed);
      }]
    ]) {
      wx.reLaunch({ url });
      const page = getCurrentPages().at(-1);
      assert.doesNotThrow(() => page.onLoad(options), url + ' 畸形编码不能导致页面崩溃');
      verify(page);
    }
    console.log('PASS 四个容器的合法路由值与畸形编码均安全处理');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
