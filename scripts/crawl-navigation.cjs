// Navigation crawler: starts at each identity's home pages, taps every control that navigates, and checks the
// page it lands on (renders, is not denied / missing / in error). State is reset before every tap, so side
// effects of one control never leak into the next.
//   node scripts/crawl-navigation.cjs [roles=comma,list] [--depth=4] [--out=qa/crawl-navigation]
const fs = require('fs'), path = require('path');
const arg = n => (process.argv.find(a => a.startsWith('--' + n + '=')) || '').split('=')[1];
const depthMax = Number(arg('depth') || 4), out = path.resolve(arg('out') || 'qa/crawl-navigation');
const only = (process.argv.slice(2).find(a => !a.startsWith('--')) || '').split(',').filter(Boolean);
const ROLES = ['sales-lead', 'boss', 'finance-lead', 'project-manager', 'hq-design-admin', 'hq-finance', 'guest'];
const STARTS = {
  'sales-lead': ['/pages/business/index', '/pages/customers/index', '/pages/creation/index', '/pages/design/index', '/pages/profile/index'],
  boss: ['/pages/admin/home/index', '/pages/admin/finance/index', '/pages/admin/performance/index', '/pages/admin/profile/index'],
  'finance-lead': ['/pages/management/index', '/pages/admin/finance-desk/index?view=flows', '/pages/admin/finance-desk/index?view=projects', '/pages/profile/index'],
  'project-manager': ['/pages/build/home/index', '/pages/build/projects/index', '/pages/build/materials/index', '/pages/profile/index'],
  'hq-design-admin': ['/pages/workflow/commission/index?view=admin', '/pages/profile/index'],
  'hq-finance': ['/pages/management/index', '/pages/admin/finance-desk/index?view=flows', '/pages/admin/finance-desk/index?view=partners', '/pages/profile/index'],
  guest: ['/pages/home/index', '/pages/business/index', '/pages/engineering/index', '/pages/management/index', '/pages/profile/index']
};
(async () => {
  const fx = await require('./audit-fixtures.cjs')();
  const { render } = require('./role-page-runtime.cjs');
  const flat = ns => ns.flatMap(n => [n, ...flat(n.children || [])]);
  const text = n => ((n.text || '') + (n.children || []).map(text).join('')).replace(/\s+/g, ' ').trim();
  const install = rows => { wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k)); for (const [k, v] of JSON.parse(JSON.stringify(rows))) wx.setStorageSync(k, v); };
  const datasetOf = n => Object.fromEntries(Object.entries(n.attrs).filter(([k, v]) => k.startsWith('data-') && v !== undefined).map(([k, v]) => [k.slice(5).replace(/-([a-z])/g, (_, x) => x.toUpperCase()), v]));
  // record navigation calls while still letting the runtime perform them
  let calls = [];
  for (const m of ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab']) { const orig = wx[m]; wx[m] = o => { calls.push({ method: m, url: o.url }); return orig(o); }; }
  wx.navigateBack = () => { calls.push({ method: 'navigateBack', url: '' }); };
  const state = p => ({ route: p.route, view: p.data.view || '', error: p.data.error || '', missing: !!p.data.missing, access: p.data.view === 'access' });
  const healthy = s => !s.error && !s.missing && !s.access;
  const normalise = u => { try { const x = new URL(u, 'http://l'); const q = [...x.searchParams.entries()].filter(([k]) => ['view', 'id', 'tab', 'record', 'checkIndex'].includes(k)).map(([k, v]) => k + '=' + v).join('&'); return x.pathname.replace(/^\//, '') + (q ? '?' + q : ''); } catch (e) { return u; } };
  const report = {};
  fs.mkdirSync(out, { recursive: true });
  for (const role of ROLES.filter(r => !only.length || only.includes(r))) {
    const rows = fx.rowsByRole[role], seen = new Map(), issues = [], edges = [];
    const queue = STARTS[role].map(u => ({ url: u, depth: 0, via: 'start' }));
    let taps = 0;
    while (queue.length) {
      const { url, depth, via } = queue.shift();
      const key = normalise(url);
      if (seen.has(key)) continue;
      let page, st;
      try { install(rows); wx.reLaunch({ url }); page = getCurrentPages().at(-1); st = state(page); } catch (e) { seen.set(key, { throws: e.message }); issues.push({ type: 'OPEN_THROWS', url, via, detail: String(e.message).slice(0, 140) }); continue; }
      seen.set(key, st);
      if (!healthy(st)) { issues.push({ type: st.access ? 'DENIED' : st.missing ? 'MISSING' : 'ERROR', url, via, landed: st.route + (st.view ? '?view=' + st.view : ''), detail: st.error }); continue; }
      if (depth >= depthMax) continue;
      const nodes = flat(render(page.file, page.data));
      const controls = []; const dedupe = new Set();
      for (const n of nodes) {
        const handler = n.attrs && (n.attrs.bindtap || n.attrs.catchtap);
        if (!handler || /\{\{/.test(handler) || typeof page[handler] !== 'function') continue;
        const ds = datasetOf(n), k = handler + JSON.stringify(ds);
        if (dedupe.has(k)) continue; dedupe.add(k);
        controls.push({ handler, ds, label: text(n).slice(0, 24) });
      }
      for (const c of controls) {
        taps++;
        try {
          install(rows); wx.reLaunch({ url }); const p = getCurrentPages().at(-1); calls = [];
          const before = state(p);
          const r = p[c.handler]({ currentTarget: { dataset: c.ds }, target: { dataset: c.ds }, detail: {} });
          if (r && typeof r.then === 'function') r.catch(() => {});
          const nav = calls.filter(x => x.method !== 'navigateBack').pop();
          if (!nav) continue;
          const after = state(getCurrentPages().at(-1));
          const target = nav.url;
          edges.push({ from: key, control: c.handler + ':' + c.label, to: normalise(target), landed: after.route + (after.view ? '?view=' + after.view : '') });
          if (!healthy(after)) issues.push({ type: after.access ? 'DENIED' : after.missing ? 'MISSING' : 'ERROR', from: key, control: c.handler + ' "' + c.label + '"', url: target, landed: after.route + (after.view ? '?view=' + after.view : ''), detail: after.error });
          queue.push({ url: target, depth: depth + 1, via: key + ' > ' + c.label });
        } catch (e) {
          const msg = String(e.message || e);
          if (!/权限|无权|请先|请选择|请填写|不能|未授权|不存在|is not a function/.test(msg)) issues.push({ type: 'HANDLER_THROWS', from: key, control: c.handler + ' "' + c.label + '"', detail: msg.slice(0, 160) });
        }
      }
    }
    report[role] = { pages: seen.size, taps, edges: edges.length, issues };
    console.log(role.padEnd(16), 'pages', String(seen.size).padStart(3), 'taps', String(taps).padStart(4), 'edges', String(edges.length).padStart(4), 'issues', issues.length);
    fs.writeFileSync(path.join(out, role + '.json'), JSON.stringify({ role, pages: [...seen.entries()], edges, issues }, null, 1));
  }
  fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify(Object.fromEntries(Object.entries(report).map(([k, v]) => [k, { pages: v.pages, taps: v.taps, edges: v.edges, issues: v.issues.length }])), null, 2));
  process.exit(0);
})().catch(e => { console.error(e.stack); process.exit(2); });
