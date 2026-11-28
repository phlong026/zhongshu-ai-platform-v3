/**
 * 历史设计委托读取、费用复核与原移交办理。
 */
const http = require('../http');

function keyHeader(idempotencyKey) {
  return idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined;
}

const policies = {
  /** 本门店原标准设计费用，只读 */
  current: () => http.get('/franchise/design-policies/current')
};

const commissions = {
  simulatedReceipt: id => http.get('/franchise/commissions/'+id+'/simulated-payment-receipt'),
  historicalSettlement: (id, data, idempotencyKey) =>
    http.post('/franchise/commissions/' + id + '/historical-settlement', data, keyHeader(idempotencyKey)),
  context: customerId => http.get('/franchise/customers/' + customerId + '/commission-context'),
  receivers: id => http.get('/franchise/commissions/' + id + '/handover-receivers'),
  downloadTicket: (id, seq, slot) => http.post('/franchise/commissions/' + id + '/dispatches/' + seq + '/files/' + slot + '/download-tickets'),
  /** 分页列表 */
  page: (pageNo, pageSize, keyword, statuses) =>
    http.get('/franchise/commissions', Object.assign({ pageNo, pageSize }, keyword ? { keyword } : {}, statuses ? { statuses } : {})),
  /** 详情（含 allowedActions 与显示状态） */
  get: (id) => http.get('/franchise/commissions/' + id),
  /** 原移交命令：packet-submit / packet-return / packet-receive */
  command: (id, action, data, idempotencyKey) =>
    http.post('/franchise/commissions/' + id + '/commands/' + action, data, keyHeader(idempotencyKey))
};

const pricing = {
  current: () => http.get('/franchise/design-pricing-policies/current')
};
module.exports = { policies, pricing, commissions };
