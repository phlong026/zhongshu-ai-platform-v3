/**
 * 财务账簿访问层（B1 接入）：finance-desk 工作台对接 /franchise/finance/**。
 * 写操作自动携带 Idempotency-Key（键由调用方生成传入，同键同内容安全重试）。
 */
const http = require('./http');

/** 生成幂等键：fin-{动作}-{uuid}（随机 16 位十六进制） */
function randomHex() {
  let hex = '';
  for (let i = 0; i < 16; i += 1) hex += Math.floor(Math.random() * 16).toString(16);
  return hex;
}

/** 生成幂等键：fin-{动作}-{uuid} */
function idemKey(action) {
  return 'fin-' + action + '-' + randomHex();
}

/** 实收列表（本公司；status 可选："已登记" 等） */
function receipts(status) {
  return http.get('/franchise/finance/receipts', status ? { status } : undefined);
}

/** 付款列表 */
function payments(status) {
  return http.get('/franchise/finance/payments', status ? { status } : undefined);
}

/** 应付列表 */
function payables(status) {
  return http.get('/franchise/finance/payables', status ? { status } : undefined);
}

/** 退款列表 */
function refunds() {
  return http.get('/franchise/finance/refunds');
}

/** 登记实收（manual 来源；sourceType/designCommissionId/designStage 走 design 门禁） */
function registerReceipt(body, idemKey) {
  return http.post('/franchise/finance/receipts', body, { 'Idempotency-Key': idemKey || idemKey('receipt') });
}

/** 登记付款（payableId 必填） */
function registerPayment(body, idemKey) {
  return http.post('/franchise/finance/payments', body, { 'Idempotency-Key': idemKey || idemKey('payment') });
}

/** 登记退款（线下事实登记制：登记即已核实） */
function registerRefund(body, idemKey) {
  return http.post('/franchise/finance/refunds', body, { 'Idempotency-Key': idemKey || idemKey('refund') });
}

/** 登记应付 */
function registerPayable(body, idemKey) {
  return http.post('/franchise/finance/payables', body, { 'Idempotency-Key': idemKey || idemKey('payable') });
}

/** 到账确认（唯一入账；幂等重放返回首次 confirmId） */
function confirmBank(bankFlowId, receiptId, idemKey) {
  return http.post('/franchise/finance/bank-confirms', { bankFlowId, receiptId },
    { 'Idempotency-Key': idemKey || idemKey('confirm') });
}

/** 银行流水列表（待匹配在前） */
function bankFlows() {
  return http.get('/franchise/finance/bank-flows', { status: '待匹配' });
}

/** 项目账聚合 */
function projectAccount(projectId) {
  return http.get('/franchise/finance/project-accounts/' + projectId);
}

module.exports = {
  idemKey, receipts, payments, payables, refunds,
  registerReceipt, registerPayment, registerRefund, registerPayable,
  confirmBank, bankFlows, projectAccount
};
