'use strict';

const format = require('./format');
function id(value) {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= '9223372036854775807') ? value : null;
}
function required(value) { const result = id(value); if (!result) throw Error('消息目标编号无效'); return result; }
function round(value) {
  const raw = String(value == null ? '' : value);
  const result = /^[1-9]\d{0,9}$/.test(raw) ? Number(raw) : NaN;
  if (!Number.isInteger(result) || result > 2147483647) throw Error('审核轮次无效');
  return result;
}
function row(value) {
  if (!value || !id(value.messageId)) throw Error('消息编号无效');
  const action = value.actionType || 'NONE', state = value.actionState || 'NONE';
  if (!['NONE', 'SELECT_FLAT', 'SELECT_ELEVATION', 'RESUBMIT_SUBMISSION'].includes(action)
      || !['NONE', 'PENDING', 'COMPLETED', 'UNAVAILABLE'].includes(state)
      || ((action === 'NONE') !== (state === 'NONE'))) throw Error('消息处理状态无效');
  return { id: value.messageId, title: String(value.title || '业务状态已更新'), content: String(value.content || ''),
    read: value.read === true, state, action,
    stateText: { NONE: '通知', PENDING: '待处理', COMPLETED: '已完成', UNAVAILABLE: '已失效' }[state],
    actionText: state === 'PENDING' ? { SELECT_FLAT: '选择平面方案', SELECT_ELEVATION: '选择立面方案', RESUBMIT_SUBMISSION: '查看意见并修改' }[action] : '查看详情',
    time: format.shortTime(value.occurredAt || value.sentAt), receiptError: '', targetError: '', reading: false, opening: false };
}
// Only typed, owner-authorized targets can become a route. There is no latest-project or global-job fallback.
function targetUrl(target) {
  if (!target || typeof target !== 'object') throw Error('消息目标不可用');
  switch (target.type) {
    case 'RESULT': return '/design-migration/pages/ai-design/result?projectId=' + required(target.projectId) + '&resultVersionId=' + required(target.resultVersionId);
    case 'JOB': {
      if (!['FLAT', 'ELEVATION'].includes(target.phase)) throw Error('任务线路无效');
      return '/design-migration/pages/ai-design/generating?jobId=' + required(target.jobId) + '&projectId=' + required(target.projectId)
        + '&stage=' + (target.phase === 'FLAT' ? 'plane' : 'elevation');
    }
    case 'SUBMISSION': return '/design-migration/pages/profile/record?type=submissions&id=' + required(target.submissionId)
      + (target.reviewRound == null ? '' : '&reviewRound=' + round(target.reviewRound));
    case 'QUOTE': return '/design-migration/pages/budget/quote?budgetId=' + required(target.budgetId) + '&quoteId=' + required(target.quoteId) + '&revisionId=' + required(target.revisionId);
    case 'ORDER': return '/pages/payment/success?orderId=' + required(target.orderId);
    case 'PROJECT': return '/design-migration/pages/profile/record?type=projects&id=' + required(target.projectId);
    default: throw Error('消息目标不可用');
  }
}
module.exports = { id, round, row, targetUrl };
