// Full-coverage layout audit: every route and every view in the route matrix.
// For each target it tries roles in priority order until the page renders (not access / missing),
// measures class-based geometry, scrolls to the bottom, and takes a screenshot. Read-only; the
// shared runtime restores the original simulator storage.
const fs = require('fs'), path = require('path');
process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9450';
const out = path.resolve(process.env.LAYOUT_AUDIT_OUT || 'qa/layout-audit-all-20261021');
process.env.FRANCHISE_NATIVE_OUT = out;
fs.mkdirSync(path.join(out, 'shots'), { recursive: true });
const { classesOf, measure, analyse } = require('./audit-layout-lib.cjs');
const trace = require('./business-flow-fixtures.cjs')(process.env.BUSINESS_FLOW_TRACE || 'qa/business-flow-priority-20261021/final-trace.json').steps;
const matrix = require(path.resolve('qa/business-flow-priority-20261021/route-matrix.json')).groups;
const app = require(path.resolve('miniprogram/app.json'));
const copy = x => JSON.parse(JSON.stringify(x));
const only = process.env.LAYOUT_AUDIT_MATCH ? new RegExp(process.env.LAYOUT_AUDIT_MATCH) : null;
const ACCOUNT = 'zhongshu-account-v1';
const roleOf = rows => rows.find(r => r[0] === ACCOUNT)?.[1]?.session?.role || 'guest';

// Full data state: the end of the traced main chain, with the account swapped per role.
const finalRows = copy(trace[trace.length - 1].after);
const accountByRole = new Map();
for (const s of trace) {
  const a = s.before.find(r => r[0] === ACCOUNT), role = roleOf(s.before);
  if (a && !accountByRole.has(role)) accountByRole.set(role, copy(a[1]));
}
const rowsFor = role => {
  if (role === 'guest') return [];
  const rows = copy(finalRows), i = rows.findIndex(r => r[0] === ACCOUNT);
  if (i >= 0) rows[i][1] = copy(accountByRole.get(role)); else rows.push([ACCOUNT, copy(accountByRole.get(role))]);
  return rows;
};
const wf = trace.find(s => s.area === 'workflow');
const customerId = new URL(wf.url, 'http://local').searchParams.get('id');
const ids = [customerId, 'biz-' + customerId, 'project-from-lead-' + customerId.split('lead-')[1], 'business-design:' + customerId + ':1:first'];
const ROLES = {
  workflow: ['sales-lead', 'boss', 'hq-design-lead', 'hq-designer', 'project-manager', 'finance-lead'],
  build: ['project-manager', 'boss', 'sales-lead', 'finance-lead'],
  admin: ['boss', 'finance-lead', 'sales-lead', 'hq-designer'],
  account: ['sales-lead', 'boss', 'finance-lead', 'project-manager'],
  studio: ['sales-lead', 'boss'],
  other: ['sales-lead', 'boss', 'finance-lead', 'project-manager', 'hq-designer']
};
const guestAlso = new Set(['pages/home/index', 'pages/profile/index', 'pages/business/index', 'pages/engineering/index', 'pages/management/index', 'pages/customers/index', 'pages/account/index', 'pages/demo/index']);

const targets = [];
const addT = (route, view, area) => { if (!targets.some(t => t.route === route && t.view === view)) targets.push({ route, view, area, label: route + (view ? '?view=' + view : '') }); };
const multi = { 'pages/workflow/index': 'workflow', 'pages/build/index': 'build', 'pages/admin/index': 'admin', 'pages/account/index': 'account', 'pages/studio/index': 'studio' };
const standalone = [...app.pages, ...app.subPackages.flatMap(s => s.pages.map(p => s.root + '/' + p))];
for (const route of standalone) {
  if (multi[route]) for (const v of matrix[multi[route]]) addT(route, v, multi[route]);
  else addT(route, null, 'other');
}
addT('pages/growth/index', 'capture', 'other');
addT('pages/workflow/explore/index', 'budget', 'workflow');
addT('pages/workflow/explore/index', 'budget-result', 'workflow');
const urlFor = (t, id) => '/' + t.route + (t.view || id ? '?' + [t.view ? 'view=' + t.view : '', id ? 'id=' + encodeURIComponent(id) : ''].filter(Boolean).join('&') : '');

(async () => {
  const r = await require('./franchise-native-runtime.cjs').connect('layout-audit-all', { directory: out });
  const prevFile = path.join(out, 'results.json');
  const resume = process.env.LAYOUT_AUDIT_RESUME === '1' && fs.existsSync(prevFile) ? JSON.parse(fs.readFileSync(prevFile, 'utf8')).results : [];
  const keep = x => x.status === 'rendered' || (x.status === 'not-renderable' && (x.attempts || []).every(a => /access|missing|不存在|无权|请选择/.test(a.note || '')));
  const kept = new Map(resume.filter(keep).map(x => [x.label, x]));
  const results = [...kept.values()];
  const limit = Number(process.env.LAYOUT_AUDIT_LIMIT || 0);
  let processed = 0;
  const save = result => fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result, count: results.length, total: targets.length, results, exceptions: r.errors }, null, 2));
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
      const idList = t.area !== 'other' && t.area !== 'studio' && t.view ? ids : [null, ...ids.slice(0, 2)];
      try {
        outer: for (const role of roles) for (const id of idList) {
          const url = urlFor(t, id);
          try {
            await r.open('/pages/account/index?view=policy', '.account');
            await r.replace(rowsFor(role));
            await r.open(url);
            await r.sleep(400);
            const d = await r.data();
            attempts.push({ role, id: id ? id.slice(0, 24) : null, ok: !!acceptable(d), note: d && (d.missing ? 'missing' : d.view === 'access' ? 'access' : d.error || '') });
            if (acceptable(d)) { accepted = { role, url }; break outer; }
          } catch (e) { attempts.push({ role, id: id ? id.slice(0, 24) : null, ok: false, note: String(e.message || e).slice(0, 80) }); }
        }
        if (!accepted) {
          results.push({ n: index, label: t.label, route: t.route, view: t.view, status: 'not-renderable', attempts: attempts.slice(0, 8), issues: [{ rule: 'not-renderable', severity: 'P1', detail: '所有岗位均无法渲染（无权限或记录缺失）' }], stats: {} });
          console.log(name + ' ' + t.label + ' ✗ 所有岗位均无法渲染');
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
        results.push({ n: index, label: t.label, route: t.route, view: t.view, role: accepted.role, url: accepted.url, shot, bottom, status: 'rendered', attempts: attempts.length, ...a });
        console.log(name + ' ' + t.label + ' [' + accepted.role + '] → ' + a.issues.length + ' 项 ' + a.issues.map(i => i.severity + ':' + i.rule).join(' '));
      } catch (e) {
        results.push({ n: index, label: t.label, status: 'error', error: String(e.message || e).slice(0, 300), issues: [{ rule: 'open-failed', severity: 'P0', detail: String(e.message || e).slice(0, 200) }], stats: {} });
        console.log(name + ' ' + t.label + ' ✗ ' + String(e.message || e).slice(0, 120));
      }
      save('running');
    }
    const left = targets.filter(t => !kept.has(t.label) && !results.some(x => x.label === t.label && keep(x))).length;
    console.log('REMAINING ' + left);
    save(left ? 'partial' : 'done');
  } finally {
    await r.restore();
  }
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
