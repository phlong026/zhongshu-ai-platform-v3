/**
 * 加盟协同域 · 设计委托（M2 主链后段，对应后端 /franchise/design-policies、/franchise/commissions）。
 */
const http = require('../http');

function keyHeader(idempotencyKey) {
  return idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined;
}

const policies = {
  /** 本门店当前有效的标准设计费用 */
  current: () => http.get('/franchise/design-policies/current'),
  /** 保存标准设计费用（每次保存一个新版本） */
  save: (data) => http.post('/franchise/design-policies', data)
};

const commissions = {
  context: customerId => http.get('/franchise/customers/' + customerId + '/commission-context'),
  receivers: id => http.get('/franchise/commissions/' + id + '/handover-receivers'),
  downloadTicket: (id, seq, slot) => http.post('/franchise/commissions/' + id + '/dispatches/' + seq + '/files/' + slot + '/download-tickets'),
  /** 提交 / 重提委托 */
  submit: (customerId, data, idempotencyKey) =>
    http.post('/franchise/customers/' + customerId + '/commission', data, keyHeader(idempotencyKey)),
  /** 分页列表 */
  page: (pageNo, pageSize, keyword) =>
    http.get('/franchise/commissions', Object.assign({ pageNo, pageSize }, keyword ? { keyword } : {})),
  /** 详情（含 allowedActions 与显示状态） */
  get: (id) => http.get('/franchise/commissions/' + id),
  /** 老板审批费用 {approve, note} */
  feeDecide: (id, data, idempotencyKey) =>
    http.post('/franchise/commissions/' + id + '/fee-decision', data, keyHeader(idempotencyKey)),
  /**
   * 委托命令：design-complete / design-dispatch / commission-feedback /
   * packet-submit / packet-return / packet-receive
   */
  command: (id, action, data, idempotencyKey) =>
    http.post('/franchise/commissions/' + id + '/commands/' + action, data, keyHeader(idempotencyKey))
};

const pricing = {
  current: () => http.get('/franchise/design-pricing-policies/current'),
  save: (data, idempotencyKey) => http.post('/franchise/design-pricing-policies', data, keyHeader(idempotencyKey))
};
module.exports = { policies, pricing, commissions };
