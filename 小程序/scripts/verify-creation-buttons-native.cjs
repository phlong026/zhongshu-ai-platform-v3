const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
process.env.FRANCHISE_WS ||= 'ws://127.0.0.1:9777';
process.env.FRANCHISE_NATIVE_OUT ||= path.resolve('qa/creation-buttons-20261020/native');

(async () => {
  const timer = setTimeout(() => { console.error('Native connect timeout'); process.exit(1); }, 35000);
  const r = await require('./franchise-native-runtime.cjs').connect('creation-buttons');
  clearTimeout(timer);
  const checks = [], screenshots = [];
  async function tap(selector) {
    let el;
    await r.until(async () => {
      r.mp.pageMap.clear();
      el = await (await r.mp.currentPage()).$(selector);
      return !!el;
    }, selector, 12000);
    await el.tap();
    await r.sleep(300);
  }
  async function shot(name) {
    await r.mp.evaluate(() => { wx.hideToast(); return true; });
    await r.sleep(150);
    screenshots.push(await r.capture(name));
  }
  async function input(selector, value) {
    r.mp.pageMap.clear();
    const el = await (await r.mp.currentPage()).$(selector);
    assert(el, selector);
    await el.input(value);
    await r.sleep(200);
  }
  try {
    await r.open('/pages/creation/index', '.extract-button');
    const button = await (await r.mp.currentPage()).$('.extract-button');
    assert.equal(await button.text(), '提取文案');
    await shot('extract-copy-button');
    checks.push('当前项目原生按钮显示“提取文案”');
    await tap('.extract-button');
    await r.until(async () => (await r.current()).route === 'pages/studio/index' && (await r.data()).view === 'remix', 'extract opens remix');
    await r.until(async () => (await r.rects(['.rewrite-generate']))[0], 'rewrite button rendered');
    const before = await r.data();
    assert(before.original.includes('开间12米'));
    assert(!before.script);
    await r.mp.evaluate(() => { wx.pageScrollTo({ scrollTop: 10000, duration: 0 }); return true; });
    await r.sleep(200);
    await shot('rewrite-before-tap');
    await tap('.rewrite-generate');
    const generated = await r.data();
    fs.writeFileSync(path.join(r.directory, 'rewrite-tap-state.json'), JSON.stringify(generated, null, 2));
    assert(!generated.operationError, generated.operationError);
    assert(generated.script.includes('朝南采光') && generated.script.includes('12米开间'), '二创结果应保留提取原文的事实');
    assert.equal(generated.rewriting, false);
    assert.equal(generated.rewriteVersion, 1);
    await shot('rewrite-generated');
    checks.push('从视频示例提取原文后，原生点击按钮生成二创 V1');
    await tap('.rewrite-generate');
    assert.equal((await r.data()).rewriteVersion, 2);
    checks.push('重复点击重新生成二创 V2，无锁死');
    await input('.original-text', '');
    assert.equal((await r.data()).rewriteVersion, 0);
    await tap('.rewrite-generate');
    assert((await r.data()).operationError.includes('原文'));
    assert.equal(await (await (await r.mp.currentPage()).$('.rewritten-card .operation-error')).text(), '请先提取或填写原文');
    assert.equal((await r.data()).rewriting, false);
    await shot('rewrite-error-visible');
    await input('.original-text', '一家三代计划建两层乡墅，一层老人房要方便，厨房靠近餐厅。');
    await tap('.rewrite-tabs button[data-index="1"]');
    await input('.custom-ip', '');
    await tap('.rewrite-generate');
    assert((await r.data()).operationError.includes('创作身份'));
    assert.equal((await r.data()).rewriting, false);
    await input('.custom-ip', '服务三代同堂家庭的乡村设计师');
    await tap('.rewrite-generate');
    assert(!(await r.data()).operationError);
    assert((await r.data()).script.includes('乡村设计师'));
    assert((await r.data()).script.includes('一层老人房'));
    assert.equal((await r.data()).rewriteVersion, 1);
    await shot('rewrite-edited-recovered');
    checks.push('原文为空、自定义身份为空均在结果区显示错误；补齐后可重新生成，无锁死');
    await tap('.studio-bottom-pair .studio-primary');
    await r.until(async () => (await r.data()).view === 'digital', 'digital next step');
    assert((await r.data()).script.includes('一层老人房'));
    await shot('digital-next-step');
    checks.push('原生点击制作数字人口播，完整二创文案交接到下一步');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    const result = { result: 'passed', project: path.resolve('.'), scope: '当前项目与原有本地存储；原生 Element.tap；模拟二创，未接入真实 AI', checks, screenshots, exceptions: r.errors, system: await r.mp.systemInfo() };
    fs.writeFileSync(path.join(r.directory, 'results.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify({ result: result.result, checks, screenshots }, null, 2));
  } catch (error) {
    try { await shot('failure'); } catch (_) {}
    fs.writeFileSync(path.join(r.directory, 'failed.json'), JSON.stringify({ error: error.stack, current: await r.current(), checks, exceptions: r.errors }, null, 2));
    throw error;
  } finally {
    await r.restore();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
