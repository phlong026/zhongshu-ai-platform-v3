// Main-chain hand-off walk for the engineering stage. Replays the recorded engineering scenes
// (qa/engineering-records-20261021/page-scenes.json, written by verify-engineering-records.cjs) and asks, for every step:
//   1. REACH  – starting from the acting person's own home page and following only visible controls
//               (at most --depth taps), can they open the page where the step is recorded?
//   2. SWEEP  – after the step, does every control on the home pages of the people involved still open a
//               healthy page, and does it open the page the link asked for (no silent redirect / fallback)?
//   node scripts/walk-main-chain.cjs [--depth=3] [--out=qa/walk-main-chain]
const fs = require('fs'), path = require('path');
const arg = n => (process.argv.find(a => a.startsWith('--' + n + '=')) || '').split('=')[1];
const depthMax = Number(arg('depth') || 3), out = path.resolve(arg('out') || 'qa/walk-main-chain');
const LOGIN = { li: 'manager-li', 'wang-finance':'finance-wang' };
// who looks at the chain after every step: [login id, role, home]
const WATCH = [
  ['engineering-dual', 'project-manager', '/pages/build/home/index'],
  ['manager-li', 'engineer', '/pages/build/home/index'],
  ['finance-dual', 'finance', '/pages/management/index'],
  ['finance-dual', 'finance-lead', '/pages/management/index'],
  ['boss', null, '/pages/admin/home/index']
];
const homeOf = (staffId, role) => role === 'finance' || role === 'finance-lead' ? '/pages/management/index' : '/pages/build/home/index';
(async () => {
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const { render } = require('./role-page-runtime.cjs');
  const scenes = JSON.parse(fs.readFileSync(path.resolve('qa/engineering-records-20261021/page-scenes.json'), 'utf8'));
  const flat = ns => ns.flatMap(n => [n, ...flat(n.children || [])]);
  const text = n => ((n.text || '') + (n.children || []).map(text).join('')).replace(/\s+/g, ' ').trim();
  const install = rows => { wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k)); for (const [k, v] of JSON.parse(JSON.stringify(rows))) wx.setStorageSync(k, v); };
  const login = (id, role) => { A.mutate(s => A.loginDemo(s, id)); if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role)); };
  const datasetOf = n => Object.fromEntries(Object.entries(n.attrs).filter(([k, v]) => k.startsWith('data-') && v !== undefined).map(([k, v]) => [k.slice(5).replace(/-([a-z])/g, (_, x) => x.toUpperCase()), v]));
  let calls = [];
  for (const m of ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab']) { const orig = wx[m]; wx[m] = o => { calls.push({ method: m, url: o.url }); return orig(o); }; }
  wx.navigateBack = () => { calls.push({ method: 'navigateBack', url: '' }); };
  const KEYS = ['view', 'id', 'tab', 'record', 'stage', 'nodeId', 'planId'];
  const key = u => { try { const x = new URL(u, 'http://l'); const q = KEYS.map(k => [k, x.searchParams.get(k)]).filter(([, v]) => v).map(([k, v]) => k + '=' + v).join('&'); return x.pathname.slice(1) + (q ? '?' + q : ''); } catch (e) { return u; } };
  const state = p => ({ route: p.route, view: p.data.view || '', error: p.data.error || '', missing: !!p.data.missing, access: p.data.view === 'access' });
  const healthy = s => !s.error && !s.missing && !s.access;
  const asked = u => { const x = new URL(u, 'http://l'); return { route: x.pathname.slice(1), view: x.searchParams.get('view') || '' }; };
  const drift = (u, s) => { const a = asked(u); if (a.route === 'pages/account/index') return ''; if (a.route !== s.route) return 'route → ' + s.route + (s.view ? '?view=' + s.view : ''); if (a.view && s.view && a.view !== s.view) return 'view → ' + s.view; return ''; };
  // every navigating control of the page opened at `url`: [{label, target, landed}]
  function links(rows, who, url) {
    install(rows); login(...who); wx.reLaunch({ url });
    const page = getCurrentPages().at(-1), st = state(page);
    if (!healthy(st)) return { st, items: [] };
    const seen = new Set(), items = [];
    for (const n of flat(render(page.file, page.data))) {
      const handler = n.attrs && (n.attrs.bindtap || n.attrs.catchtap);
      if (!handler || /\{\{/.test(handler) || typeof page[handler] !== 'function') continue;
      const ds = datasetOf(n), k = handler + JSON.stringify(ds);
      if (seen.has(k)) continue; seen.add(k);
      items.push({ handler, ds, label: text(n).slice(0, 24) });
    }
    const result = [];
    for (const c of items) {
      try {
        install(rows); login(...who); wx.reLaunch({ url }); const p = getCurrentPages().at(-1); calls = [];
        const r = p[c.handler]({ currentTarget: { dataset: c.ds }, target: { dataset: c.ds }, detail: {} });
        if (r && typeof r.then === 'function') r.catch(() => {});
        const nav = calls.filter(x => x.method !== 'navigateBack').pop();
        if (!nav) continue;
        result.push({ label: c.handler + ':' + c.label, target: nav.url, landed: state(getCurrentPages().at(-1)) });
      } catch (e) { /* guarded control (needs input) */ }
    }
    return { st, items: result };
  }
  const report = [], problems = [];
  fs.mkdirSync(out, { recursive: true });
  scenes.forEach((scene, i) => {
    const who = [LOGIN[scene.staffId] || scene.staffId, scene.role === 'finance' ? 'finance' : scene.role];
    const goal = key(scene.url), start = homeOf(scene.staffId, scene.role);
    // 1. reach: breadth-first over visible controls from the actor's home
    let found = null; const seen = new Set([key(start)]); let frontier = [{ url: start, path: ['首页'] }];
    for (let depth = 0; depth < depthMax && !found; depth++) {
      const next = [];
      for (const node of frontier) {
        const { items } = links(scene.before, who, node.url);
        for (const it of items) {
          const k = key(it.target);
          if (k === goal) { found = node.path.concat(it.label.split(':')[1] || it.label); break; }
          if (!seen.has(k) && healthy(it.landed)) { seen.add(k); next.push({ url: it.target, path: node.path.concat(it.label.split(':')[1] || it.label) }); }
        }
        if (found) break;
      }
      frontier = next;
    }
    const step = { n: i + 1, action: scene.action, view: scene.view, actor: who.join('/'), goal, reach: found ? found.join(' › ') : null };
    if (!found) problems.push({ type: 'UNREACHABLE', step: step.n + ' ' + scene.action, detail: who.join('/') + ' 从首页 ' + depthMax + ' 步内点不到 ' + goal });
    // 2. sweep: the people involved, after the step
    step.sweep = {};
    for (const [id, role, home] of WATCH) {
      const { st, items } = links(scene.after, [id, role], home);
      if (!healthy(st)) { problems.push({ type: 'HOME_BROKEN', step: step.n + ' ' + scene.action, detail: id + '/' + role + ' ' + home + ' ' + (st.error || (st.access ? 'access' : 'missing')) }); continue; }
      let ok = 0;
      for (const it of items) {
        if (!healthy(it.landed)) problems.push({ type: it.landed.access ? 'DENIED' : it.landed.missing ? 'MISSING' : 'ERROR', step: step.n + ' ' + scene.action, detail: id + '/' + role + ' "' + it.label + '" → ' + it.target + ' ' + (it.landed.error || '') });
        else { const d = drift(it.target, it.landed); if (d) problems.push({ type: 'DRIFT', step: step.n + ' ' + scene.action, detail: id + '/' + role + ' "' + it.label + '" ' + it.target + ' ' + d }); else ok++; }
      }
      step.sweep[id + '/' + (role || 'boss')] = ok + '/' + items.length;
    }
    report.push(step);
    console.log(String(step.n).padStart(2), scene.action.padEnd(14), (step.actor).padEnd(26), step.reach ? 'OK  ' + step.reach : 'NO PATH ' + goal);
  });
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ steps: report, problems }, null, 2));
  console.log('\nproblems', problems.length);
  const byType = {};
  for (const p of problems) { const k = p.type + ' ' + p.detail; byType[k] = (byType[k] || 0) + 1; }
  Object.entries(byType).slice(0, 60).forEach(([k, v]) => console.log(v, k.slice(0, 240)));
  process.exit(problems.length ? 1 : 0);
})().catch(e => { console.error(e.stack); process.exit(2); });
