// Read-only layout / usability audit over the real simulator.
// Reuses the business-flow trace fixtures: for every traced page state it installs
// the role's storage, opens the page, measures rendered geometry and takes a screenshot.
// Original simulator storage is restored by the shared runtime.
const fs = require('fs'), path = require('path');
process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9450';
const out = path.resolve(process.env.LAYOUT_AUDIT_OUT || 'qa/layout-audit-20261021');
process.env.FRANCHISE_NATIVE_OUT = out;
fs.mkdirSync(path.join(out, 'shots'), { recursive: true });
const trace = require('./business-flow-fixtures.cjs')(process.env.BUSINESS_FLOW_TRACE || 'qa/business-flow-priority-20261021/final-trace.json').steps;
const copy = x => JSON.parse(JSON.stringify(x));
const only = process.env.LAYOUT_AUDIT_MATCH ? new RegExp(process.env.LAYOUT_AUDIT_MATCH) : null;

const cases = [], seen = new Set();
const roleOf = rows => rows.find(r => r[0] === 'zhongshu-account-v1')?.[1]?.session?.role || 'guest';
function add(rows, url, label) {
  const role = roleOf(rows), key = role + '|' + url;
  if (seen.has(key)) return;
  seen.add(key); cases.push({ rows, url, label, role });
}
for (const s of trace) add(s.before, s.url, s.label);
// Tab landing pages for every role that appears in the trace.
const roleRows = new Map();
for (const s of trace) { const r = roleOf(s.before); if (!roleRows.has(r)) roleRows.set(r, s.before); }
for (const [role, rows] of roleRows) for (const p of ['home', 'business', 'engineering', 'management', 'profile'])
  add(rows, '/pages/' + p + '/index', '首页入口：' + p);

// Tag selectors are not matched by the simulator selector engine, so measure by the class
// names found in each page's WXML. Fonts and colours come from the static CSS scan instead.
const classesOf = route => {
  const set = new Set();
  const read = f => { try { return fs.readFileSync(path.resolve('miniprogram', f), 'utf8'); } catch (e) { return ''; } };
  const src = read(route + '.wxml') + ' ' + read(route.replace(/\/index$/, '') + '/header/index.wxml');
  for (const m of src.matchAll(/class="([^"]*)"/g)) for (const c of m[1].replace(/\{\{[^}]*\}\}/g, ' ').split(/\s+/)) if (/^[A-Za-z_][\w-]*$/.test(c)) set.add(c);
  return [...set];
};
const measure = selector => new Promise(resolve => {
  const win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
  wx.createSelectorQuery().selectAll(selector).boundingClientRect().exec(res => resolve(JSON.stringify({
    win: { w: win.windowWidth, h: win.windowHeight },
    els: (res[0] || []).map(e => ({ cls: e.class || '', id: e.id || '', left: e.left, right: e.right, top: e.top, bottom: e.bottom, width: e.width, height: e.height, dataset: JSON.parse(JSON.stringify(e.dataset || {})) }))
  })));
  setTimeout(() => resolve(null), 6000);
});

function analyse(m) {
  const issues = [], W = m.win.w, H = m.win.h;
  const vis = m.els.filter(e => e.width > 0 && e.height > 0);
  const over = vis.filter(e => (e.right > W + 1 || e.left < -1) && e.width < W * 3);
  if (over.length) issues.push({ rule: 'overflow-x', severity: 'P0', detail: over.length + ' 个元素超出屏幕宽度 ' + W + 'px，示例 .' + String(over[0].cls).split(' ')[0] + ' left=' + Math.round(over[0].left) + ' right=' + Math.round(over[0].right) });
  const actionData = d => d && Object.keys(d).some(k => /^(view|url|id|role|action|index|key|tab|record|step|type|name|route)$/i.test(k));
  const tappable = vis.filter(e => actionData(e.dataset));
  const low = tappable.filter(e => e.height < 40);
  if (low.length) issues.push({ rule: 'tap-target', severity: 'P0', detail: low.length + ' 个可点元素高度低于 40px（建议 ≥44px），最小 ' + Math.round(Math.min(...low.map(e => e.height))) + 'px，示例 .' + String(low[0].cls).split(' ')[0] + ' ' + JSON.stringify(low[0].dataset).slice(0, 60) });
  const mid = tappable.filter(e => e.height >= 40 && e.height < 44);
  if (mid.length) issues.push({ rule: 'tap-target', severity: 'P1', detail: mid.length + ' 个可点元素高度 40~44px' });
  const narrow = tappable.filter(e => e.width < 44 && e.height >= 40);
  if (narrow.length) issues.push({ rule: 'tap-target-width', severity: 'P1', detail: narrow.length + ' 个可点元素宽度不足 44px' });
  if (!vis.length) issues.push({ rule: 'empty-page', severity: 'P0', detail: '页面没有可测量的元素' });
  const bars = vis.filter(e => e.width > W * .8 && e.height > 50 && e.height < 220 && e.top > H * .6 && /page-actions|bottom|action|footer|bar/.test(e.cls));
  const bar = bars.sort((a, b) => a.top - b.top)[0] || null;
  return { issues, stats: { elements: vis.length, tappable: tappable.length, bar: bar ? { top: Math.round(bar.top), height: Math.round(bar.height), cls: String(bar.cls).split(' ')[0] } : null, minTapHeight: tappable.length ? Math.round(Math.min(...tappable.map(e => e.height))) : null } };
}

(async () => {
  const r = await require('./franchise-native-runtime.cjs').connect('layout-audit', { directory: out });
  const results = [];
  const save = result => fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result, count: results.length, total: cases.length, results, exceptions: r.errors }, null, 2));
  try {
    let index = 0;
    for (const c of cases.filter(c => !only || only.test(c.label + ' ' + c.url))) {
      index++;
      const name = String(index).padStart(3, '0') + '-' + c.role;
      try {
        await r.open('/pages/account/index?view=policy', '.account');
        await r.replace(c.rows);
        await r.open(c.url);
        await r.sleep(450);
        const data = await r.data();
        const route = new URL(c.url, 'http://local').pathname.slice(1), selector = classesOf(route).map(x => '.' + x).join(',');
        const m = JSON.parse((await r.mp.evaluate(measure, selector)) || 'null');
        const a = m ? analyse(m) : { issues: [{ rule: 'measure-timeout', severity: 'P1', detail: '取不到布局数据' }], stats: {} };
        // scroll to the bottom and check the last content against the fixed bar
        let bottom = null;
        try {
          await r.mp.evaluate(() => new Promise(res => { wx.pageScrollTo({ scrollTop: 99999, duration: 0, complete: () => setTimeout(res, 250) }); }));
          const m2 = JSON.parse((await r.mp.evaluate(measure, selector)) || 'null');
          if (m2 && a.stats.bar) {
            const bar = a.stats.bar, content = m2.els.filter(e => e.width > 0 && e.height > 0 && e.top < bar.top + 4 && e.height < 600 && !/page-actions|action-panel|tab/.test(e.cls) && e.bottom <= bar.top + bar.height);
            const lowest = content.length ? Math.max(...content.filter(e => e.top < bar.top).map(e => e.bottom)) : 0;
            bottom = { lowestContentBottom: Math.round(lowest), barTop: bar.top, gap: Math.round(bar.top - lowest) };
            if (bottom.gap < 0) a.issues.push({ rule: 'bar-overlap', severity: 'P0', detail: '滚到底后内容仍被底部固定栏遮挡 ' + (-bottom.gap) + 'px' });
            else if (bottom.gap < 12) a.issues.push({ rule: 'bar-gap', severity: 'P1', detail: '内容与底部固定栏间距仅 ' + bottom.gap + 'px' });
          }
          await r.mp.evaluate(() => new Promise(res => { wx.pageScrollTo({ scrollTop: 0, duration: 0, complete: res }); }));
        } catch (e) { /* page has no scrolling – ignore */ }
        const shot = 'shots/' + name + '.png';
        await r.mp.screenshot({ path: path.join(out, shot) });
        results.push({ n: index, role: c.role, label: c.label, url: c.url, route: data && (await r.current()).route, view: data?.view, shot, bottom, ...a });
        console.log(`${name} ${c.label} → ${a.issues.length} 项 ${a.issues.map(i => i.severity + ':' + i.rule).join(' ')}`);
      } catch (e) {
        results.push({ n: index, role: c.role, label: c.label, url: c.url, error: String(e.message || e).slice(0, 300), issues: [{ rule: 'open-failed', severity: 'P0', detail: String(e.message || e).slice(0, 200) }], stats: {} });
        console.log(`${name} ${c.label} ✗ ${String(e.message || e).slice(0, 120)}`);
      }
      save('running');
    }
    save('done');
  } finally {
    await r.restore();
  }
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
