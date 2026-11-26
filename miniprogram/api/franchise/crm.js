/**
 * 加盟协同域 · 客资与客户（M2 主链前段，对应后端 /franchise/leads、/franchise/customers）。
 * 客户跟进一律走八步流程（/workflow/**），小程序不再调用旧的 /follows。
 * 序列化事实：nextVisitDate / nextFollowOn=[年,月,日]；createTime/lastFollowAt=毫秒；Long 超出 ±(2^53-1) 为字符串（ID 一律不透明值）。
 */
const http = require('../http');
const regions = {
  children: parentCode => http.get('/franchise/crm/regions', parentCode ? { parentCode } : undefined)
};

const leads = {
  /** 登记客资：{name,phone,need,source} → {leadId, customerId, result: CREATED|LINKED|LINK_REQUIRED} */
  capture: (data, idempotencyKey) => http.post('/franchise/leads', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 待关联客资（老板看全部，负责人看管理范围，登记人看自己的） */
  pending: () => http.get('/franchise/leads/pending'),
  /** 关联同手机号原客户（带 revision） */
  link: (id, revision, idempotencyKey) => http.post('/franchise/leads/' + id + '/link', { revision }, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 作废待关联客资（带原因与 revision） */
  invalidate: (id, note, revision, idempotencyKey) => http.post('/franchise/leads/' + id + '/invalid', { note, revision }, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined)
};

const base = id => '/franchise/customers/' + encodeURIComponent(id);
const idem = idempotencyKey => (idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined);

const customers = {
  page: (pageNo, pageSize, keyword, followFilter, stablePrefix, previousPrefix) =>
    http.get('/franchise/customers', { pageNo, pageSize, ...(keyword ? {keyword} : {}), ...(followFilter ? {followFilter} : {}), ...(stablePrefix ? {stablePrefix:true} : {}), ...(previousPrefix ? {previousPrefix} : {}) }),
  /** 跟进汇总（与列表分栏同口径） */
  followSummary: () => http.get('/franchise/customers/follow-summary'),
  /** 详情（含升级前的跟进与方案版本；follows createTime ASC、schemes kind,version ASC） */
  get: (id) => http.get(base(id)),
  /** 编辑基本信息（phone/source 不可改，带 revision；幂等键可选） */
  update: (id, data, idempotencyKey) =>
    http.put(base(id), data, idem(idempotencyKey)),
  /** 八步跟进视图 */
  workflow: (id) => http.get(base(id) + '/workflow'),
  /** 跟进时间线（时间倒序分页，含升级前的跟进） */
  timeline: (id, pageNo, pageSize) => http.get(base(id) + '/workflow/timeline', { pageNo, pageSize: pageSize || 20 }),
  /** 流程状态 {revision,status,reasonCode?,reason?,nextFollowOn?} */
  workflowStatus: (id, data, idempotencyKey) =>
    http.post(base(id) + '/workflow/status', data, { 'Idempotency-Key': idempotencyKey }),
  /** 保存参考方案或预算版本（BUDGET / PLAN / FACADE；revision 服务端自取） */
  saveScheme: (id, data, idempotencyKey) =>
    http.post(base(id) + '/schemes', data, idem(idempotencyKey)),
  /** 责任转交（revision） */
  transfer: (id, data, idempotencyKey) =>
    http.post(base(id) + '/transfer', data, idem(idempotencyKey))
};

module.exports = { leads, customers, regions };
