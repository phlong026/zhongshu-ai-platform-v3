/**
 * 加盟协同域 · 客资与客户（M2 主链前段，对应后端 /franchise/leads、/franchise/customers）。
 * 序列化事实：nextVisitDate=[年,月,日]；createTime/lastFollowAt=毫秒；Long 超出 ±(2^53-1) 为字符串（ID 一律不透明值）。
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

const customers = {
  /** 分页列表；keyword 按称呼、手机号、省市（空关键词不传参——undefined 会被工具端序列化成字面量） */
  page: (pageNo, pageSize, keyword) =>
    http.get('/franchise/customers', keyword ? { pageNo, pageSize, keyword } : { pageNo, pageSize }),
  /** 详情（含跟进与方案版本；follows createTime ASC、schemes kind,version ASC） */
  get: (id) => http.get('/franchise/customers/' + id),
  /** 编辑基本信息（phone/source 不可改，带 revision；幂等键可选） */
  update: (id, data, idempotencyKey) =>
    http.put('/franchise/customers/' + id, data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 登记跟进 {note, nextVisitDate:[y,m,d]|null}（revision 服务端自取） */
  follow: (id, data, idempotencyKey) =>
    http.post('/franchise/customers/' + id + '/follows', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 保存参考方案或预算版本（BUDGET / PLAN / FACADE；revision 服务端自取） */
  saveScheme: (id, data, idempotencyKey) =>
    http.post('/franchise/customers/' + id + '/schemes', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  /** 责任转交 {targetUserId, reason, revision}（幂等键可选；已知后端重放指纹缺口 S1-11b） */
  transfer: (id, data, idempotencyKey) =>
    http.post('/franchise/customers/' + id + '/transfer', data, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined)
};

module.exports = { leads, customers, regions };
