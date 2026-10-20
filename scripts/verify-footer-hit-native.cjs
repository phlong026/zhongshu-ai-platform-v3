const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
process.env.FRANCHISE_WS ||= 'ws://127.0.0.1:9777';
process.env.FRANCHISE_NATIVE_OUT ||= path.resolve('qa/creation-button-overlay-20261020/native');

// Element.tap dispatches to the selected node and cannot detect an overlay.
// Check viewport hit ownership before tapping the actual node at that point.
async function hitButton(r, cdp, selector, click = true) {
  const rect = (await r.rects([selector]))[0];
  assert(rect?.width && rect?.height, 'Rendered button: ' + selector);
  const doc = await cdp.sendCommand('DOM', 'getDocument', { depth: 1, pierce: true });
  const pageRoot = doc.root.children.find(n => n.nodeName.startsWith('pages/'));
  assert(pageRoot, 'Current page component');
  const selected = await cdp.sendCommand('DOM', 'querySelector', { nodeId: pageRoot.nodeId, selector, mode: 'composed' });
  assert(selected.nodeId > 0, 'DOM button: ' + selector);
  const node = (await cdp.sendCommand('DOM', 'describeNode', { nodeId: selected.nodeId, depth: -1, pierce: true })).node;
  const owned = new Set();
  function descendants(n) {
    owned.add(n.nodeId);
    for (const key of ['children', 'shadowRoots', 'pseudoElements']) for (const child of n[key] || []) descendants(child);
  }
  descendants(node);
  const hits = [];
  for (const fx of [0.12, 0.5, 0.88]) {
    for (const fy of [0.2, 0.5, 0.8]) {
      const point = { x: Math.round(rect.left + rect.width * fx), y: Math.round(rect.top + rect.height * fy) };
      const hit = await cdp.sendCommand('DOM', 'getNodeForLocation', point);
      const hitNode = (await cdp.sendCommand('DOM', 'describeNode', { nodeId: hit.nodeId, depth: 0 })).node;
      hits.push({ point, nodeId: hit.nodeId, attributes: hitNode.attributes, owned: owned.has(hit.nodeId) });
      assert(owned.has(hit.nodeId), selector + ' is covered at ' + JSON.stringify(point) + ': ' + JSON.stringify(hitNode.attributes));
    }
  }
  if (click) {
    await cdp.sendCommand('MDPElement', 'tap', { nodeId: hits[4].nodeId });
    await r.sleep(350);
  }
  return { selector, rect, hits, click: click ? 'MDPElement.tap on the actual center hit node' : 'hit ownership only' };
}

async function main() {
  const timer = setTimeout(() => { console.error('Native connect timeout'); process.exit(1); }, 35000);
  const r = await require('./franchise-native-runtime.cjs').connect('footer-hit');
  clearTimeout(timer);
  const checks = [], hits = [], screenshots = [];
  async function open(url, selector) {
    const target = new URL(url, 'http://local');
    const route = target.pathname.slice(1), view = target.searchParams.get('view');
    const method = route === 'pages/business/index' ? 'reLaunch' : 'redirectTo';
    await r.mp.evaluate(({ method, url }) => { setTimeout(() => wx[method]({ url }), 100); return true; }, { method, url });
    await r.until(async () => {
      const page = await r.current();
      return page.route === route && (!view || page.data.view === view) && (!selector || (await r.rects([selector]))[0]);
    }, url, 15000);
    await r.sleep(200);
  }
  try {
    await r.sleep(1200); // Allow the role landing navigation after IDE compilation.
    const cdp = r.mp.CDP();
    await cdp.enable();
    await cdp.sendCommand('DOM', 'enable', {});
    await open('/pages/creation/index', '.extract-button');
    hits.push(await hitButton(r, cdp, '.extract-button'));
    await r.until(async () => (await r.data()).view === 'remix' && (await r.rects(['.rewrite-generate']))[0], 'extract to remix');
    const before = await r.data();
    assert(before.original.includes('开间12米'), 'Use the current user reference copy');
    assert(!before.script, 'Fresh extraction starts without a rewritten script');
    await r.mp.evaluate(() => { wx.pageScrollTo({ scrollTop: 10000, duration: 0 }); return true; });
    await r.sleep(200);
    hits.push(await hitButton(r, cdp, '.rewrite-generate'));
    const first = await r.data();
    assert(first.script.includes('朝南采光'));
    assert.equal(first.rewriteVersion, (before.rewriteVersion || 0) + 1);
    assert(!first.operationError);
    screenshots.push(await r.capture('coordinate-first-generation'));
    checks.push('右侧按钮禁用时，左侧九个点均命中自身；点击实际命中节点生成 V1');
    hits.push(await hitButton(r, cdp, '.rewrite-generate'));
    assert.equal((await r.data()).rewriteVersion, first.rewriteVersion + 1);
    checks.push('两按钮启用时，左侧九个点均命中自身；点击实际命中节点重新生成 V2');
    hits.push(await hitButton(r, cdp, '.studio-bottom-pair .studio-primary'));
    await r.until(async () => (await r.data()).view === 'digital', 'coordinate digital navigation');
    assert((await r.data()).script.includes('朝南采光'));
    screenshots.push(await r.capture('coordinate-digital-next'));
    checks.push('右侧九个点均命中自身；点击实际命中节点进入口播并保留完整文案');
    const completed = (await r.data()).tasks.find(t => t.status === 'completed');
    assert(completed, 'Existing completed sample task');
    await open('/pages/studio/index?view=result&task=' + encodeURIComponent(completed.id), '.result-actions');
    hits.push(await hitButton(r, cdp, '.result-actions .studio-outline', false));
    hits.push(await hitButton(r, cdp, '.result-actions .studio-primary', false));
    checks.push('共用底栏的保存作品、去发布按钮各九个点均无相互遮挡');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(r.directory, 'results.json'), JSON.stringify({ result: 'passed', project: path.resolve('.'), method: '微信官方 SDK CDP DOM.getNodeForLocation 九点命中检查 + MDPElement.tap 点击中心实际命中的节点', checks, hits, screenshots, exceptions: r.errors }, null, 2));
    console.log(JSON.stringify({ result: 'passed', checks, points: hits.reduce((n, h) => n + h.hits.length, 0) }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(r.directory, 'failed.json'), JSON.stringify({ error: error.stack, current: await r.current(), hits, exceptions: r.errors }, null, 2));
    throw error;
  } finally {
    try {
      await r.replace(r.before);
      const normal = rows => JSON.stringify(rows.sort((a, b) => a[0].localeCompare(b[0])));
      const restored = normal(await r.snapshot()) === normal(r.before);
      fs.writeFileSync(path.join(r.directory, 'storage-restored.json'), JSON.stringify({ restored, keys: r.before.length }, null, 2));
      assert(restored, 'Original simulator storage restored');
    } finally {
      await r.mp.disconnect();
    }
  }
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });

module.exports = { hitButton };
