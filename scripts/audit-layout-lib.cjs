// Shared helpers for the simulator layout audits.
// Tag selectors are not matched by the simulator selector engine, so geometry is measured by the
// class names found in each page's WXML. Fonts and colours come from the static CSS scan instead.
const fs = require('fs'), path = require('path');

const classesOf = route => {
  const set = new Set();
  const read = f => { try { return fs.readFileSync(path.resolve('miniprogram', f), 'utf8'); } catch (e) { return ''; } };
  const src = read(route + '.wxml') + ' ' + read(route.replace(/\/index$/, '') + '/header/index.wxml');
  for (const m of src.matchAll(/class="([^"]*)"/g)) for (const c of m[1].replace(/\{\{[^}]*\}\}/g, ' ').split(/\s+/)) if (/^[A-Za-z_][\w-]*$/.test(c)) set.add(c);
  return [...set];
};

// Executed inside the mini-program page context.
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
  if (low.length) issues.push({ rule: 'tap-target', severity: 'P0', detail: low.length + ' 个可点元素高度低于 40px（建议 ≥44px），最小 ' + Math.round(Math.min(...low.map(e => e.height))) + 'px，示例 ' + JSON.stringify(low[0].dataset).slice(0, 60), classes: [...new Set(low.map(e => String(e.cls).trim().split(/\s+/)[0]).filter(Boolean))].slice(0, 8) });
  const mid = tappable.filter(e => e.height >= 40 && e.height < 44);
  if (mid.length) issues.push({ rule: 'tap-target', severity: 'P1', detail: mid.length + ' 个可点元素高度 40~44px', classes: [...new Set(mid.map(e => String(e.cls).trim().split(/\s+/)[0]).filter(Boolean))].slice(0, 8) });
  const narrow = tappable.filter(e => e.width < 44 && e.height >= 40);
  if (narrow.length) issues.push({ rule: 'tap-target-width', severity: 'P1', detail: narrow.length + ' 个可点元素宽度不足 44px' });
  if (!vis.length) issues.push({ rule: 'empty-page', severity: 'P0', detail: '页面没有可测量的元素（可能由组件渲染，需看截图）' });
  const bars = vis.filter(e => e.width > W * .8 && e.height > 50 && e.height < 220 && e.top > H * .6 && /page-actions|bottom|action|footer|bar/.test(e.cls));
  const bar = bars.sort((a, b) => a.top - b.top)[0] || null;
  return { issues, stats: { elements: vis.length, tappable: tappable.length, bar: bar ? { top: Math.round(bar.top), height: Math.round(bar.height), cls: String(bar.cls).split(' ')[0] } : null, minTapHeight: tappable.length ? Math.round(Math.min(...tappable.map(e => e.height))) : null } };
}

module.exports = { classesOf, measure, analyse };
