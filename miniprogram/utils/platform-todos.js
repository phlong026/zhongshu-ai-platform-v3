/**
 * 平台待办视图模型（纯函数，零 wx/Storage 依赖——隔离验收 AC12 依赖此约束）。
 * 消费 GET /platform/todos 响应，产出工作台"平台待办"区块的状态机视图。
 * 状态机契约见 docs/backend-parity/01-待办中心接入-需求与验收.md §五。
 */
const LABELS = {
  'first-follow': '首次跟进',
  'visit': '到期回访',
  'submit-commission': '预算待委托',
  'lead-link': '客资待关联',
  'fee-approve': '委托费用待审批',
  'fee-pending': '委托费用审批中',
  'fee-returned': '委托费用已退回',
  'design-complete': '设计完成待下发',
  'design-dispatch': '设计下发',
  'tail-follow': '尾款跟进',
  'handover-prepare': '成果收图移交',
  'handover-returned': '移交退回',
  'handover-review': '移交核查',
  'handover-waiting': '移交核查中',
  'abnormal-link': '异常资金待关联',
  'bank-confirm': '到账待确认'
};
const MAX_SAFE_CENTS = 9007199254740991; // Number.MAX_SAFE_INTEGER

function initialState() {
  return { status: 'idle', rows: [], count: 0, anomaly: 0, warning: '', message: '', postKey: '' };
}

function postKeyOf(post) {
  return post ? [post.tenantId || '1', post.orgId, post.postId].join(':') : '';
}

/** 触发决策：reset（回 idle）/ skip（自动合并）/ load（发起请求并携带三重有效性上下文） */
function planLoad(state, post, ctx) {
  if (!post) return { action: 'reset' };
  const postKey = postKeyOf(post);
  const sessionGen = ctx.sessionGen();
  const merged = !ctx.manual && state.status === 'loading'
    && state.postKey === postKey && state.sessionGen === sessionGen;
  if (merged) return { action: 'skip' };
  return { action: 'load', postKey, loadGen: ctx.nextLoadGen(), sessionGen };
}

/** 请求成功：三重上下文（loadGen/sessionGen/postKey）与当前一致才写入，否则丢弃 */
function applyResponse(state, res, ctx, now) {
  if (!valid(ctx, now)) return { state, changed: false };
  if (!res || typeof res !== 'object' || !Array.isArray(res.items)) {
    return { state: degrade(state, '待办暂时无法加载'), changed: true };
  }
  let anomaly = 0;
  const failedDomains = Array.isArray(res.failedDomains)
    ? res.failedDomains.filter(d => typeof d === 'string' ? true : (anomaly += 1, false))
    : (anomaly += 1, []);
  const serverCount = Number.isSafeInteger(res.actionableCount) && res.actionableCount >= 0
    ? res.actionableCount : (anomaly += 1, 0);
  const seen = new Set();
  const rows = [];
  let count = 0;
  for (const item of res.items) {
    const row = rowOf(item);
    if (!row) { anomaly += 1; continue; }
    if (seen.has(row.key)) continue; // 去重保留首条
    seen.add(row.key);
    rows.push(row);
    if (row.clickable) count += 1;
  }
  const partial = failedDomains.length > 0;
  const status = partial ? (rows.length ? 'partial' : 'partial-empty') : 'ready';
  const warning = partial ? '部分待办暂时无法加载（' + failedDomains.join('、') + '）' : '';
  const mismatch = count !== serverCount ? '部分待办数据异常已过滤' : '';
  return {
    changed: true,
    state: {
      status, rows, count, postKey: ctx.postKey, sessionGen: ctx.sessionGen,
      anomaly: state.anomaly + anomaly,
      warning: [warning, mismatch].filter(Boolean).join('；'),
      message: ''
    }
  };
}

/** 请求失败（网络/超时/code≠0/403/形状非法）：degraded，保留会话 */
function applyFailure(state, err, ctx, now) {
  if (!valid(ctx, now)) return { state, changed: false };
  return { changed: true, state: { ...initialState(), status: 'degraded', postKey: ctx.postKey, sessionGen: ctx.sessionGen, message: (err && err.message) || '' } };
}

function valid(ctx, now) {
  return ctx.loadGen === now.loadGen && ctx.sessionGen === now.sessionGen && ctx.postKey === now.postKey;
}

function degrade(state, message) {
  return { ...initialState(), status: 'degraded', postKey: state.postKey, sessionGen: state.sessionGen, message };
}

/** 条目清洗：非法返回 null（调用方计 anomaly） */
function rowOf(item) {
  if (!item || typeof item !== 'object') return null;
  if (typeof item.key !== 'string' || !item.key) return null;
  if (typeof item.title !== 'string') return null;
  if (typeof item.actionable !== 'boolean') return null;
  const path = typeof item.appPath === 'string' ? item.appPath : '';
  const typeLabel = typeLabelOf(item.key, item.actionable);
  return {
    key: item.key,
    title: item.title,
    typeLabel,
    actionHint: item.actionable ? '可办理' : '等待他人办理',
    amount: formatAmount(item.amountCents),
    clickable: item.actionable,
    path: item.actionable ? path : '',
    hint: item.actionable && !path ? '暂不支持小程序内办理，请到工作台查看' : ''
  };
}

/** key 形如 franchise:fee-approve:{id}，取第二段映射事项标签 */
function typeLabelOf(key, actionable) {
  const prefix = String(key).split(':')[1] || '';
  return LABELS[prefix] || (actionable ? '待办理' : '进行中');
}

function formatAmount(cents) {
  if (typeof cents !== 'number' || !Number.isSafeInteger(cents) || cents < 0 || cents > MAX_SAFE_CENTS) return '';
  return '¥' + (cents / 100).toFixed(2);
}

module.exports = { initialState, postKeyOf, planLoad, applyResponse, applyFailure, rowOf, typeLabelOf, formatAmount, LABELS };
