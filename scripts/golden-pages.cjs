// Golden-master page snapshots: renders every route/view for every identity with the real Page
// handlers and WXML (Node runtime) and records the visible text and controls.
//   node scripts/golden-pages.cjs write <file>      write a snapshot
//   node scripts/golden-pages.cjs compare <file>    re-render and diff against a snapshot
// Used to prove that dead-code removal does not change what any page shows.
const fs = require('fs'), path = require('path');
const mode = process.argv[2], file = path.resolve(process.argv[3] || 'qa/golden-pages.json');
const matrix = require(path.resolve('qa/business-flow-priority-20261021/route-matrix.json')).groups;
const app = require(path.resolve('miniprogram/app.json'));
const WORKFLOW_VIEWS = ['customers', 'customer', 'arrival', 'progress', 'inspiration', 'search', 'case', 'case-plan', 'ai-config', 'ai-results', 'result', 'budget', 'budget-result', 'history', 'preview', 'ai-facade', 'transfer', 'facade-config', 'facade-candidates', 'facade-result', 'money-review', 'task-access'];
const COMMISSION_VIEWS = ['submit', 'progress', 'feedback', 'handover', 'receive', 'admin', 'policy'];
const ROLES = ['sales-lead', 'boss', 'project-manager', 'finance-lead', 'hq-design-admin', 'hq-finance', 'guest'];

(async () => {
  const fx = await require('./audit-fixtures.cjs')();
  const { render } = require('./role-page-runtime.cjs');
  const targets = [];
  const add = (route, view) => targets.push({ route, view, label: route + (view ? '?view=' + view : '') });
  const routes = [...app.pages, ...app.subPackages.flatMap(s => s.pages.map(p => s.root + '/' + p))];
  for (const r of routes) {
    if (r === 'pages/workflow/index') WORKFLOW_VIEWS.forEach(v => add(r, v));
    else if (r === 'pages/workflow/commission/index') COMMISSION_VIEWS.forEach(v => add(r, v));
    else if (r === 'pages/build/index') matrix.build.forEach(v => add(r, v));
    else if (r === 'pages/admin/index') matrix.admin.forEach(v => add(r, v));
    else if (r === 'pages/account/index') matrix.account.forEach(v => add(r, v));
    else if (r === 'pages/studio/index') matrix.studio.forEach(v => add(r, v));
    else add(r, null);
  }
  const flat = ns => ns.flatMap(n => [n, ...flat(n.children || [])]);
  const text = n => (n.text || '') + (n.children || []).map(text).join('');
  const install = rows => { wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k)); for (const [k, v] of JSON.parse(JSON.stringify(rows))) wx.setStorageSync(k, v); };
  const snap = {};
  for (const t of targets) for (const role of ROLES) {
    for (const id of t.view ? [fx.ids[0], fx.ids[1]] : [null]) {
      const key = t.label + '|' + role + '|' + (id ? fx.ids.indexOf(id) : '-');
      try {
        install(fx.rowsByRole[role]);
        const url = '/' + t.route + (t.view || id ? '?' + [t.view && 'view=' + t.view, id && 'id=' + encodeURIComponent(id)].filter(Boolean).join('&') : '');
        wx.reLaunch({ url });
        const p = getCurrentPages().at(-1);
        const nodes = render(p.file, p.data);
        snap[key] = {
          route: p.route, view: p.data.view, error: p.data.error || '', missing: !!p.data.missing,
          text: nodes.map(text).join('').replace(/\s+/g, ' ').replace(/\d{4}-\d\d-\d\d[T ]\d\d:\d\d(?::\d\d(?:\.\d+)?)?Z?/g, '<time>').replace(/\d{1,2}:\d\d(?::\d\d)?/g, '<hm>').trim(),
          controls: flat(nodes).filter(n => n.attrs && (n.attrs.bindtap || n.attrs.bindinput || n.attrs.bindchange)).map(n => (n.attrs.bindtap || n.attrs.bindinput || n.attrs.bindchange) + ':' + text(n).replace(/\s+/g, ' ').replace(/\d{4}-\d\d-\d\d[T ]\d\d:\d\d(?::\d\d(?:\.\d+)?)?Z?/g, '<time>').replace(/\d{1,2}:\d\d(?::\d\d)?/g, '<hm>').slice(0, 40))
        };
      } catch (e) { snap[key] = { throws: String(e.message || e).slice(0, 160) }; }
    }
  }
  const keys = Object.keys(snap);
  if (mode === 'write') {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(snap));
    console.log('golden written', keys.length, 'states,', keys.filter(k => snap[k].throws).length, 'throw,', keys.filter(k => snap[k].missing || snap[k].view === 'access').length, 'denied/missing');
  } else if (mode === 'compare') {
    const old = JSON.parse(fs.readFileSync(file, 'utf8'));
    const diffs = [];
    for (const k of new Set([...keys, ...Object.keys(old)])) if (JSON.stringify(old[k]) !== JSON.stringify(snap[k])) diffs.push(k);
    console.log('compared', keys.length, 'states;', diffs.length, 'differ');
    for (const k of diffs.slice(0, 25)) {
      const a = old[k] || {}, b = snap[k] || {};
      const why = a.throws !== b.throws ? 'throws ' + a.throws + ' -> ' + b.throws : a.text !== b.text ? 'text' : 'controls';
      console.log(' ', k, '|', why);
    }
    process.exitCode = diffs.length ? 1 : 0;
  } else { console.log('usage: write|compare <file>'); }
  process.exit(process.exitCode || 0);
})().catch(e => { console.error(e.stack); process.exit(2); });
