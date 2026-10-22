// The boss home "本月签约" figure counts design commissions submitted in the month, not signed contracts.
const assert = require('assert/strict');
(async () => {
  const fx = await require('./audit-fixtures.cjs')();
  const M = require('../miniprogram/pages/admin/owner-model'), B = require('../miniprogram/utils/business');
  const wan = yuan => (yuan / 10000).toLocaleString('en-US', { maximumFractionDigits: 2 });
  const copy = x => JSON.parse(JSON.stringify(x));
  const install = rows => { wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k)); for (const [k, v] of copy(rows)) wx.setStorageSync(k, v); };
  const bossRows = fx.rowsByRole.boss;
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const edit = fn => { const rows = copy(bossRows), i = rows.findIndex(r => r[0] === B.KEY); fn(rows[i][1]); return rows; };
  const commission = b => b.customers.find(c => c.businessFlow?.type === 'sales-offline-v1');

  install(bossRows);
  const base = commission(wx.getStorageSync(B.KEY));
  assert(base && base.serviceOrders.length, 'fixture has a submitted design commission');
  const total = Number(base.serviceOrders.at(-1).total);
  const snap = M.snapshot();
  assert.equal(snap.sales, wan(total), '本月签约等于本月提交的设计委托总额');
  pass('本月签约按本月提交的设计委托总额统计（' + wan(total) + '万）');

  install(edit(b => { const c = commission(b); c.businessFlow.submittedAt = '2020-01-05T09:00:00.000Z'; }));
  assert.equal(M.snapshot().sales, '0');
  pass('不在本月提交的设计委托不计入');

  install(edit(b => { const c = commission(b); c.serviceOrders.push({ ...c.serviceOrders.at(-1), version: c.serviceOrders.length + 1, total: total * 2 }); }));
  assert.equal(M.snapshot().sales, wan(total * 2));
  pass('同一委托修订后按最新服务单金额统计，不重复累加');

  install(edit(b => { b.customers.push({ ...copy(b.customers.find(c => !c.businessFlow) || b.customers[0]), id: 'legacy-contract', businessFlow: undefined, contract: { file: { name: '旧合同.pdf' }, date: new Date().toISOString().slice(0, 10), total: 500000 } }); }));
  assert.equal(M.snapshot().sales, wan(total));
  pass('旧合同不再计入本月签约');

  install(bossRows);
  const owner = require('../miniprogram/utils/access').ownerId(base);
  const row = M.snapshot().team.find(p => p.id === owner);
  assert(row && row.business, 'owner appears as a sales staff row');
  assert.equal(row.value, wan(total));
  pass('负责业务员的个人签约与公司口径一致');
  require('fs').mkdirSync('qa/owner-signing', { recursive: true });
  require('fs').writeFileSync('qa/owner-signing/results.json', JSON.stringify({ result: 'passed', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
  process.exit(0);
})().catch(e => { console.error(e.stack); process.exit(1); });
