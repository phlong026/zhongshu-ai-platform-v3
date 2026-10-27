/**
 * 加盟协同域 · 客资与客户（M2 主链前段，对应后端 /franchise/leads、/franchise/customers）。
 */
const http = require('../http');

const leads = {
  /** 登记客资：{name, phone, need, source} → {leadId, customerId, result} */
  capture: (data, idempotencyKey) => http.post('/franchise/leads', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 待关联客资（老板看全部，负责人看管理范围，登记人看自己的） */
  pending: () => http.get('/franchise/leads/pending'),
  /** 关联同手机号原客户（带 revision） */
  link: (id, revision) => http.post('/franchise/leads/' + id + '/link', { revision }),
  /** 作废待关联客资（带原因与 revision） */
  invalidate: (id, note, revision) => http.post('/franchise/leads/' + id + '/invalid', { note, revision })
};

const customers = {
  /** 分页列表；keyword 按称呼或手机号 */
  page: (pageNo, pageSize, keyword) =>
    http.get('/franchise/customers', { pageNo, pageSize, keyword: keyword || undefined }),
  /** 详情（含跟进与方案版本） */
  get: (id) => http.get('/franchise/customers/' + id),
  /** 编辑基本信息（手机号不可改，带 revision） */
  update: (id, data) => http.put('/franchise/customers/' + id, data),
  /** 登记跟进 {note, nextVisitDate} */
  follow: (id, data, idempotencyKey) =>
    http.post('/franchise/customers/' + id + '/follows', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 保存参考方案或预算版本（BUDGET / PLAN / FACADE） */
  saveScheme: (id, data) => http.post('/franchise/customers/' + id + '/schemes', data),
  /** 责任转交 {targetUserId, reason, revision} */
  transfer: (id, data) => http.post('/franchise/customers/' + id + '/transfer', data)
};

module.exports = { leads, customers };
