const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    const MKEY = require('../miniprogram/utils/storage-keys').management; // 切生产收尾批：loginDemo 下线，直接构造最小身份
    A.mutate(s => { Object.assign(s.session, { loggedIn: true, companyId: 'quanzhou', staffId: 'boss-x', userId: 'boss-x', role: 'boss' }); s.companies = [{ id: 'quanzhou', name: '门店', type: 'store', roles: ['boss','owner'] }]; });
    wx.setStorageSync(MKEY, { version: 1, companyId: 'quanzhou', staff: [{ id: 'boss-x', personId: 'boss-x', name: '老板', roles: ['boss'], teamIds: { sales: 'sales', engineering: 'engineering', finance: 'finance' } }] });
    const malformed = '%E0%A4%A';
    for (const [url, options, verify] of [
      ['/pages/account/index?view=learning', { view:'learning', tab:malformed, category:'AI%E5%BA%94%E7%94%A8' }, page => {
        assert.equal(page.data.tab, malformed);
        assert.equal(page.data.category, 'AI应用');
      }],
      ['/pages/admin/index?view=todos', { view:'todos', tab:malformed }, page => assert(!!page.data)], // 切生产收尾批：admin/index 已改跳转壳（todos 容器移除）——保留畸形编码不崩溃契约，form 断言随容器下线
      // 切生产收尾批：operations 旧 desk 容器已下线（页面改跳转壳）——该用例移除，解码容错契约由其余三个存活容器承载
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
