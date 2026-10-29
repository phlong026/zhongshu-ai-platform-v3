const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/hq-finance-metrics-20261023/native');

async function main() {
  const fixture = path.join(root, 'qa/full-flow-acceptance-20261023/page-business/fixtures/B06.json');
  assert(fs.existsSync(fixture), '先运行 node scripts/verify-six-role-flow.cjs 生成同一项目的业务与财务记录');
  await require('./role-page-runtime.cjs').connect();
  const rows = JSON.parse(fs.readFileSync(fixture));
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  rows.forEach(([key, value]) => wx.setStorageSync(key, value));
  const A = require('../miniprogram/utils/account');
  const D = require('../miniprogram/utils/finance-desk');
  A.mutate(account => A.loginDemo(account, 'hq-finance-dual'));
  D.setPeriod('2026-10');
  const expected = D.home(D.load());
  const seeded = wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]);
  const r = await require('./franchise-native-runtime.cjs').connect('hq-finance-metrics', { directory, wsEndpoint: process.env.FRANCHISE_WS || 'ws://127.0.0.1:9994', realEvents: true });
  const report = { result: 'running', evidence: 'WeChat native Element.tap/trigger(change)', metrics: expected.metrics, checks: [] };
  async function home() {
    await r.open('/pages/management/index', '.fin-home-metrics', { relaunch: true });
    await r.until(async () => (await r.data()).financeDesk?.metrics?.length === 3, '总部三个指标');
    await r.sleep(500);
  }
  try {
    await r.replace(seeded);
    await home();
    assert.deepEqual((await r.data()).financeDesk.metrics, expected.metrics);
    const cards = await (await r.mp.currentPage()).$$('.fin-home-metric');
    assert.equal(cards.length, 3);
    const rects = await Promise.all(cards.map(async element => ({ offset: await element.offset(), size: await element.size() })));
    assert(rects.every(rect => Math.abs(rect.offset.top - rects[0].offset.top) < 2), '三个指标在同一行');
    await r.capture('headquarters-home');
    report.checks.push('罗财务总部首页恰有本月实收、待收金额、总收款金额三个收款指标');
    for (const index of [0, 1, 2]) {
      await home();
      await r.event({ selector: '.fin-home-metric', dataset: { index } }, 'financeMetric');
      await r.until(async () => (await r.current()).route === 'pages/admin/finance-desk/index' && (await r.data()).ready, '财务指标明细');
      const data = await r.data();
      assert(!data.error, data.error);
      if (index === 1) {
        assert.equal(data.view, 'partners');
        assert.equal(D.number(data.rows.reduce((sum, row) => sum + row.designRemaining + row.advanceRemaining, 0)), expected.metrics[index].value);
      } else {
        assert.equal(data.flowScope, index === 2 ? 'all' : 'current');
        assert(data.rows.length && data.rows.every(row => row.category === '实收'));
        assert.equal(D.number(data.rows.reduce((sum, row) => sum + row.amount, 0)), expected.metrics[index].value);
        assert.equal(data.stats[0].value, expected.metrics[index].value);
      }
      await r.until(async () => !!(await r.rects(['.fin-heading']))[0], '财务明细可见内容');
      await r.sleep(350);
      await r.capture(['month-receipts', 'receivables', 'all-receipts'][index]);
    }
    report.checks.push('三项卡片均真实点击成功，收款与待收明细合计分别等于首页指标');
    A.mutate(account => A.loginDemo(account, 'finance-dual'));
    const storeAccount = wx.getStorageSync('zhongshu-account-v1');
    await r.mp.evaluate(account => {
      wx.setStorageSync('zhongshu-account-v1', account);
      getCurrentPages().at(-1).refresh();
    }, storeAccount);
    assert.equal((await r.data()).flowScope, 'period');
    assert.notEqual((await r.data()).periodText, '全部月份');
    assert(!(await r.data()).error, (await r.data()).error);
    report.checks.push('累计页面注入门店登录态并刷新，原总部范围与Tab状态正确清除');
    await r.replace(seeded);
    await home();
    const monthPicker = await (await r.mp.currentPage()).$('.fin-home picker');
    await monthPicker.trigger('change', { value: '2026-09' });
    r.interactions.push({ route: 'pages/management/index', selector: '.fin-home picker', via: 'Element.trigger(change)' });
    await r.until(async () => (await r.data()).financeDesk.period === '2026-09', '切换财务月份');
    assert.equal((await r.data()).financeDesk.period, '2026-09');
    assert.equal((await r.data()).financeDesk.metrics[2].value, expected.metrics[2].value);
    assert.equal((await r.data()).financeDesk.metrics[1].value, expected.metrics[1].value);
    report.checks.push('切换月份更新月实收，累计收款及当前待收保持相同口径');
    // Reuse the same customer/project records when checking the store role.
    await r.login('finance-dual', 'finance');
    const store = (await r.data()).financeDesk;
    assert.deepEqual(store.metrics.map(row => row.label), ['本月已登记收款', '本月已登记付款', '合同待收', '待登记事项']);
    await r.capture('store-home');
    report.checks.push('王财务门店四项指标保持原收付款职责');
    assert.equal(r.errors.length, 0);
    report.result = 'passed';
    report.exceptions = r.errors;
  } catch (error) {
    report.result = 'failed';
    report.error = error.message;
    throw error;
  } finally {
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify(report, null, 2));
    await r.restore();
  }
  console.log(JSON.stringify(report, null, 2));
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
