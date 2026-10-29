const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/customer-workspace-20261023');
async function main() {
  await require(path.join(root, 'scripts/role-page-runtime.cjs')).connect();
  const A = require(path.join(root, 'miniprogram/utils/account'));
  const B = require(path.join(root, 'miniprogram/utils/business'));
  A.mutate(a => A.loginDemo(a, 'sales-dual'));
  const customer = B.add({ name: '张建国家', phone: '13900008824', province: '湖北省', city: '黄冈市', source: '朋友介绍', summary: '两层乡墅，首层老人房，二层露台' });
  const rows = wx.getStorageInfoSync().keys.filter(k => k.startsWith('zhongshu-')).map(k => [k, wx.getStorageSync(k)]);
  const r = await require(path.join(root, 'scripts/franchise-native-runtime.cjs')).connect('customer-workspace', { directory, wsEndpoint: process.env.FRANCHISE_WS || 'ws://127.0.0.1:9994', realEvents: true });
  const report = { evidence: 'WeChat native Element.tap/input', result: 'running', customer: { id: customer.id, name: customer.name, owner: customer.owner }, checks: [] };
  try {
    await r.replace(rows);
    const url = '/pages/workflow/index?view=customer&id=' + customer.id;
    await r.open(url, '.customer-tabs', { relaunch: true });
    assert.equal((await r.data()).canFollow, true);
    await r.sleep(900);
    await r.capture('overview-first-follow');
    const overview = await r.rects(['.customer-follow-actions', '.customer-banner']);
    report.overview = { rects: overview, window: await r.mp.evaluate(() => wx.getWindowInfo()) };
    assert(overview[0].bottom <= report.overview.window.windowHeight, '概况底部按钮位于可见页面');
    await r.capture('overview-first-follow');
    await r.event({ selector: '.customer-tabs button', dataset: { index: 1 } }, 'customerTab');
    const headings = await (await r.mp.currentPage()).$$('.heading');
    assert.deepEqual(await Promise.all(headings.map(h => h.text().then(s => s.replace(/\s/g, '')))), ['客户资料编辑', '沟通记录', '业务办理']);
    await r.capture('follow-empty-top');
    await r.event('[data-modal="follow"]', 'modal');
    await r.event('textarea[data-key="followContent"]', 'input', { value: '与张建国确认两层乡墅及老人房、露台需求，下一次核对尺寸。' });
    await r.event('input[data-key="appointment"]', 'input', { value: A.localDate() + ' 16:30' });
    await r.event('.sheet-save', 'saveModal');
    assert(!(await r.data()).error, (await r.data()).error);
    assert.equal((await r.data()).activeCustomerTab, 1);
    assert.equal((await r.data()).customer.id, customer.id);
    assert.equal((await r.data()).customer.follows.length, 1);
    await r.capture('follow-saved-bottom');
    report.checks.push('原生页面点击首次跟进，真实输入保存成功，客户与负责人一致，保留跟进栏目');
    for (const view of ['search', 'ai-config', 'budget', 'request']) {
      await r.open(url, '.customer-tabs', { relaunch: true });
      await r.sleep(800);
      await r.event({ selector: '.customer-tabs button', dataset: { index: 1 } }, 'customerTab');
      await r.event({ selector: '.tool-grid button', dataset: { view } }, 'go');
      await r.until(async () => { const d = await r.data(); return view === 'request' ? d.view === 'request' || (await r.current()).route === 'pages/workflow/commission/index' : d.view === view; }, '业务入口 ' + view);
      assert.equal((await r.data()).customer.id, customer.id);
      assert(!(await r.data()).error, (await r.data()).error);
    }
    report.checks.push('原生点击检索户型、AI设计、快速预算、委托设计，四条入口保留同一客户');
    await r.open(url + '&tab=1', '.customer-tabs');
    await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); p.update(n => { const follow = n.follows[0]; for (let i = 1; i < 12; i++) n.follows.push({...follow, content: '第' + (i + 1) + '次沟通：' + follow.content}); }, '长沟通记录布局验收'); });
    assert(!(await r.data()).error, (await r.data()).error);
    assert.equal((await r.data()).customer.follows.length, 12);
    await r.sleep(500);
    const button = await (await r.mp.currentPage()).$('[data-modal="follow"]');
    const offset = await button.offset();
    await r.mp.evaluate(top => wx.pageScrollTo({scrollTop: top, duration: 0}), offset.top);
    await r.sleep(250);
    const bottom = await r.rects(['.follow-card', '.tool-grid', '.customer-follow-actions']);
    assert(bottom[0].bottom <= bottom[1].top && bottom[1].bottom <= bottom[2].top, '长记录后的业务办理与跟进按钮顺序正确且不遮挡');
    await r.capture('follow-long-bottom');
    report.checks.push('十二条沟通记录可滚动到底，业务办理与底部按钮无重叠');
    report.result = 'passed';
    report.exceptions = r.errors;
    assert.equal(r.errors.length, 0);
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
main().catch(e => { console.error(e.stack); process.exitCode = 1; });
