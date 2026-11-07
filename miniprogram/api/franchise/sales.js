/**
 * 加盟协同域 · 业务交易流（B2 板块 B，对应后端 /franchise/sales/**）。
 * 写命令一律携带 Idempotency-Key（同键同内容安全重试，异内容 1099000000）。
 */
const http = require('../http');

const idem = (key, action) => (key ? { 'Idempotency-Key': key } : { 'Idempotency-Key': 'sale-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const requirements = {
  /** 需求登记 {customerId, width?, depth?, floors?, style?, prompt?, note?} → {requirementId, version} */
  create: (data, key) => http.post('/franchise/sales/requirements', data, idem(key, 'req')),
  /** 需求列表（版本倒序） */
  list: (customerId) => http.get('/franchise/sales/requirements', { customerId })
};

const schemes = {
  /** 方案升级 reference→formal → {schemeId, stage} */
  promote: (schemeId, key) => http.post('/franchise/sales/schemes/' + schemeId + '/promote', {}, idem(key, 'promote'))
};

const quotes = {
  /** 创建报价：服务端计算金额 → {quoteId, totalCents, items}（items.main/decor 行含 label/quantity/unitPriceCents） */
  create: (data, key) => http.post('/franchise/sales/quotes', data, idem(key, 'quote')),
  /** 报价列表 */
  list: (customerId) => http.get('/franchise/sales/quotes', { customerId }),
  /** 导出规范票据 JSON（冻结快照；priceVersion 缺省时字段省略） */
  exportSheet: (quoteId) => http.get('/franchise/sales/quotes/' + quoteId + '/export-sheet')
};

const contracts = {
  /** M06-T02：创建合同（WAIT_SIGN，三期计划签约后/基础完成后/封顶后，0=未约定，Σ三期=total）→ {contractId, status, stages} */
  create: (data, key) => http.post('/franchise/sales/contracts', data, idem(key, 'contract')),
  /** 合同列表（响应附 stages 回款计划） */
  list: (customerId) => http.get('/franchise/sales/contracts', { customerId }),
  /** 签订 {signedDate?, assetId?, revision} → {status:'SIGNED'} */
  sign: (contractId, data, key) => http.post('/franchise/sales/contracts/' + contractId + '/sign', data, idem(key, 'sign')),
  /** 作废（PENDING 计划软删，PAID 不动）{reason?, revision} */
  void: (contractId, data, key) => http.post('/franchise/sales/contracts/' + contractId + '/void', data, idem(key, 'void'))
};

const stages = {
  /** M06-T02：分笔办理回款（写通 B1 账簿）{amountCents, method, transactionNo?, receivedDate?} → {receiptId, stageStatus, collectedCents, replayed} */
  collect: (stageId, data, key) => http.post('/franchise/sales/stages/' + stageId + '/collect', data, idem(key, 'collect')),
  /** M06-T02：三期收付统一视图（各期约定额/累计分笔/余额/状态）→ [{stageId, stage, label, amountCents, collectedCents, balanceCents, status}] */
  summary: (contractId) => http.get('/franchise/sales/contracts/' + contractId + '/stage-summary')
};

const deliveries = {
  /** 创建交付（四节点登记制）{contractId, requirementId?} → {deliveryId} */
  create: (data, key) => http.post('/franchise/sales/deliveries', data, idem(key, 'delivery')),
  /** 推进节点 node: design-delivered/design-confirmed/construction-delivered/construction-accepted（顺序强制） */
  advance: (deliveryId, node, key) => http.post('/franchise/sales/deliveries/' + deliveryId + '/advance', { node }, idem(key, 'dv-' + node)),
  /** 交付查询（按合同） */
  get: (contractId) => http.get('/franchise/sales/deliveries', { contractId })
};

module.exports = { requirements, schemes, quotes, contracts, stages, deliveries };
