// Full-coverage layout audit on the commission protocol: every route, every view.
// For each target it tries identities in priority order until the page renders (not access /
// missing), measures class-based geometry, scrolls to the bottom and takes a screenshot.
// Read-only: the shared runtime restores the original simulator storage.
//   LAYOUT_AUDIT_MATCH=regex   only targets whose label matches
//   LAYOUT_AUDIT_RESUME=1      keep rendered / permanently denied targets from results.json
//   LAYOUT_AUDIT_LIMIT=n       process at most n targets per run (use with a driver that restarts the IDE)
const fs = require('fs'), path = require('path');
process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9450';
const out = path.resolve(process.env.LAYOUT_AUDIT_OUT || 'qa/layout-audit-all');
process.env.FRANCHISE_NATIVE_OUT = out;
fs.mkdirSync(path.join(out, 'shots'), { recursive: true });
const { classesOf, measure, analyse } = require('./audit-layout-lib.cjs');
const matrix = require(path.resolve('qa/business-flow-priority-20261021/route-matrix.json')).groups;
const app = require(path.resolve('miniprogram/app.json'));
// Screenshots leave temp files in the simulator sandbox (10MB quota); remove them between targets.
const cleanTmp = () => { try { const base = path.join(process.env.LOCALAPPDATA || '', '微信开发者工具', 'User Data'); for (const h of fs.readdirSync(base)) { const root = path.join(base, h, 'WeappSimulator', 'WeappFileSystem'); if (!fs.existsSync(root)) continue; for (const u of fs.readdirSync(root)) for (const app of fs.readdirSync(path.join(root, u))) { const tmp = path.join(root, u, app, 'tmp'); if (fs.existsSync(tmp)) for (const x of fs.readdirSync(tmp)) fs.rmSync(path.join(tmp, x), { force: true, recursive: true }); } } } catch (e) { /* best effort */ } };
const only = process.env.LAYOUT_AUDIT_MATCH ? new RegExp(process.env.LAYOUT_AUDIT_MATCH) : null;

const ROLES = {
  workflow: ['sales-lead', 'boss', 'project-manager', 'hq-design-admin', 'finance-lead'],
  commission: ['sales-lead', 'hq-design-admin', 'project-manager', 'boss', 'finance-lead'],
  build: ['project-manager', 'boss', 'sales-lead', 'finance-lead'],
  admin: ['boss', 'finance-lead', 'sales-lead', 'hq-finance', 'hq-boss'],
  account: ['sales-lead', 'boss', 'finance-lead', 'project-manager', 'hq-design-admin'],
  studio: ['sales-lead', 'boss'],
  other: ['sales-lead', 'boss', 'finance-lead', 'project-manager', 'hq-design-admin', 'hq-finance']
};
// Workbench tab pages no longer render guest content (login guide only), so they are not audited as guest.
const guestAlso = new Set(['pages/home/index', 'pages/profile/index', 'pages/customers/index', 'pages/account/index']);
// views of the multi-view pages that still exist after the legacy chain was removed
const WORKFLOW_VIEWS = ['customers', 'customer', 'arrival', 'progress', 'inspiration', 'search', 'case', 'case-plan', 'ai-config', 'ai-results', 'result', 'budget', 'budget-result', 'history', 'preview', 'ai-facade', 'transfer', 'facade-config', 'facade-candidates', 'facade-result', 'money-review', 'task-access'];
const COMMISSION_VIEWS = ['submit', 'progress', 'feedback', 'handover', 'receive', 'admin'];

(async () => {
  const fx = await require('./audit-fixtures.cjs')();
  const targets = [];
  const addT = (route, view, area) => { if (!targets.some(t => t.route === route && t.view === view)) targets.push({ route, view, area, label: route + (view ? '?view=' + view : '') }); };
  const standalone = [...app.pages, ...app.subPackages.flatMap(s => s.pages.map(p => s.root + '/' + p))];
  for (const route of standalone) {
    if (route === 'pages/workflow/index') for (const v of WORKFLOW_VIEWS) addT(route, v, 'workflow');
    else if (route === 'pages/workflow/commission/index') for (const v of COMMISSION_VIEWS) addT(route, v, 'commission');
    else if (route === 'pages/build/index') for (const v of matrix.build) addT(route, v, 'build');
    else if (route === 'pages/admin/index') for (const v of matrix.admin) addT(route, v, 'admin');
    else if (route === 'pages/account/index') for (const v of matrix.account) addT(route, v, 'account');
    else if (route === 'pages/studio/index') for (const v of matrix.studio) addT(route, v, 'studio');
    else addT(route, null, 'other');
  }
  addT('pages/growth/index', 'capture', 'other');
  addT('pages/workflow/explore/index', 'budget', 'workflow');
  addT('pages/workflow/explore/index', 'budget-result', 'workflow');
  const urlFor = (t, id) => '/' + t.route + (t.view || id ? '?' + [t.view ? 'view=' + t.view : '', id ? 'id=' + encodeURIComponent(id) : ''].filter(Boolean).join('&') : '');

  const r = await require('./franchise-native-runtime.cjs').connect('layout-audit-all', { directory: out });
  const prevFile = path.join(out, 'results.json');
  const resume = process.env.LAYOUT_AUDIT_RESUME === '1' && fs.existsSync(prevFile) ? JSON.parse(fs.readFileSync(prevFile, 'utf8')).results : [];
  const keep = x => x.status === 'rendered' || (x.status === 'not-renderable' && (x.attempts || []).every(a => /access|missing|不存在|无权|请选择/.test(a.note || '')));
  const kept = new Map(resume.filter(keep).map(x => [x.label, x]));
  const results = [...kept.values()];
  const limit = Number(process.env.LAYOUT_AUDIT_LIMIT || 0);
  let processed = 0;
  const save = result => fs.writeFileSync(prevFile, JSON.stringify({ result, count: results.length, total: targets.length, results, exceptions: r.errors }, null, 2));
  const acceptable = d => d && !d.missing && d.view !== 'access' && !d.error;
  try {
    let index = 0;
    for (const t of targets.filter(t => (!only || only.test(t.label)) && !kept.has(t.label))) {
      if (limit && processed >= limit) break;
      processed++;
      index++;
      const name = String(index).padStart(3, '0'), attempts = [];
      let accepted = null;
      const roles = [...(ROLES[t.area] || ROLES.other)];
      if (guestAlso.has(t.route)) roles.push('guest');
      const idList = ['workflow', 'commission', 'build', 'admin'].includes(t.area) && t.view ? fx.ids : [null, ...fx.ids.slice(0, 2)];
      try {
        outer: for (const role of roles) for (const id of idList) {
          const url = urlFor(t, id);
          try {
            await r.open('/pages/account/index?view=policy', '.account');
            await r.replace(fx.rowsByRole[role]);
            await r.open(url);
            await r.sleep(400);
            const d = await r.data();
            attempts.push({ role, id: id ? id.slice(0, 24) : null, ok: !!acceptable(d), note: d && (d.missing ? 'missing' : d.view === 'access' ? 'access' : d.error || '') });
            if (acceptable(d)) { accepted = { role, url }; break outer; }
          } catch (e) { attempts.push({ role, id: id ? id.slice(0, 24) : null, ok: false, note: String(e.message || e).slice(0, 80) }); }
        }
        if (!accepted) {
          results.push({ n: index, label: t.label, route: t.route, view: t.view, status: 'not-renderable', attempts: attempts.slice(0, 8), issues: [{ rule: 'not-renderable', severity: 'P1', detail: '所有身份均无法渲染（无权限或记录缺失）' }], stats: {} });
          console.log(name + ' ' + t.label + ' x not renderable');
          save('running');
          continue;
        }
        const selector = classesOf(t.route).map(x => '.' + x).join(',');
        const m = JSON.parse((await r.mp.evaluate(measure, selector)) || 'null');
        const a = m ? analyse(m) : { issues: [{ rule: 'measure-timeout', severity: 'P1', detail: '取不到布局数据' }], stats: {} };
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
        } catch (e) { /* page without scrolling */ }
        const shot = 'shots/' + name + '.png';
        await r.mp.screenshot({ path: path.join(out, shot) });
        cleanTmp();
        results.push({ n: index, label: t.label, route: t.route, view: t.view, role: accepted.role, url: accepted.url, shot, bottom, status: 'rendered', attempts: attempts.length, ...a });
        console.log(name + ' ' + t.label + ' [' + accepted.role + '] -> ' + a.issues.length + ' ' + a.issues.map(i => i.severity + ':' + i.rule).join(' '));
      } catch (e) {
        results.push({ n: index, label: t.label, status: 'error', error: String(e.message || e).slice(0, 300), issues: [{ rule: 'open-failed', severity: 'P0', detail: String(e.message || e).slice(0, 200) }], stats: {} });
        console.log(name + ' ' + t.label + ' x ' + String(e.message || e).slice(0, 120));
      }
      save('running');
    }
    const left = targets.filter(t => !results.some(x => x.label === t.label && keep(x))).length;
    console.log('REMAINING ' + left);
    save(left ? 'partial' : 'done');
  } finally {
    await r.restore();
  }
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
