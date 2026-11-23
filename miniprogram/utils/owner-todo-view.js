const reviewDomain = 'owner-commission-review';
const sources = [
  { key: 'all', label: '全部' }, { key: 'business', label: '业务' },
  { key: 'engineering', label: '工程' }, { key: 'finance', label: '财务' }
];

// Match issued keys, not titles or money amounts. An engineering collection is still engineering.
function sourceOf(key) {
  const [domain, action] = key.split(':');
  if (domain === 'fr-eng' || domain === 'eng-collect' ||
      (domain === 'franchise' && action === 'handover-review')) return 'engineering';
  if (['fr-fin', 'fr-fin-workbench', 'fr-design-fin', reviewDomain].includes(domain) ||
      (domain === 'fr-sale-finance' && action !== 'returned')) return 'finance';
  if (['fr-sale', 'fr-sale-finance', 'franchise'].includes(domain)) return 'business';
  return 'other';
}

function board(items) {
  const unique = new Map();
  for (const item of items || []) {
    if (!item || typeof item.key !== 'string' || !item.key ||
        !item.clickable || item.disabled || !item.path || unique.has(item.key)) continue;
    // An outstanding contract stage is a balance, not automatically an assignment to the owner.
    if (item.key.startsWith('fr-sale:stage-pending:')) continue;
    const source = sourceOf(item.key);
    const decision = item.key.startsWith('franchise:fee-approve:') || item.key.startsWith(reviewDomain + ':');
    const label = (sources.find(t => t.key === source) || {}).label || '其他';
    unique.set(item.key, { ...item, source, decision, actionLabel: '去处理',
      ownerMeta: [label, source === 'finance' && decision ? '回款变化待核对' : item.typeLabel === '待办理' ? '' : item.typeLabel].filter(Boolean).join(' · ') });
  }
  const all = [...unique.values()];
  const decisions = all.filter(t => t.decision);
  const rows = decisions.concat(all.filter(t => !t.decision));
  return { rows, total: rows.length, decisionTotal: decisions.length };
}

function filter(rows, source, decisionOnly) {
  return rows.filter(item => (source === 'all' || item.source === source) && (!decisionOnly || item.decision));
}

function mergeReviews(response, reviews, failed = false) {
  if (!response || !Array.isArray(response.items)) return response;
  if (failed || !Array.isArray(reviews)) {
    return { ...response, failedDomains: [...(response.failedDomains || []), '提成复核'] };
  }
  const items = [...response.items], seen = new Set(items.map(item => item.key));
  const stages = { start: 'SIGN_AFTER', base: 'BASE_DONE', topping: 'CAP_DONE' };
  let count = 0;
  for (const row of reviews) {
    if (!row || !/^\d+$/.test(String(row.projectId))) continue;
    for (const stage of row.stages || []) {
      if (!stage?.canReconcile || !stages[stage.stage]) continue;
      const key = reviewDomain + ':' + row.projectId + ':' + stages[stage.stage];
      if (seen.has(key)) continue;
      seen.add(key); count++;
      items.push({ key, title: '项目提成复核 · ' + row.project, actionable: true,
        appPath: '/pages/admin/commission/index?id=' + row.projectId + '&reviewStage=' + stages[stage.stage] });
    }
  }
  return { ...response, items, actionableCount: response.actionableCount + count };
}

// The same review predicate and route already used by the commission screen; no new workflow.
function load(post) {
  const pending = require('../api/todos').list();
  if (post.orgType !== 'store' || post.roleCode !== 'boss') return pending;
  const P = require('./platform-performance');
  const reviews = P.api.commission.rows().then(rows => Array.isArray(rows)
    ? rows.map(row => P.commissionRowView(row, post.roleCode, row.projectName)) : null)
    .catch(error => {
      if (error?.silent || [401, 403, 1103000002].includes(Number(error?.code)) || error?.postInvalid) throw error;
      return null;
    });
  return Promise.all([pending, reviews]).then(([response, rows]) => mergeReviews(response, rows, !rows));
}

module.exports = { sources, board, filter, load, mergeReviews };
