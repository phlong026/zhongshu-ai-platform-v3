/**
 * 加盟协同域 · 设计赋能接入层（B2 板块 A，对应后端 /franchise/design/**）。
 * 仅访问 B 端原业务接口；业务键由持久意图管理，与服务间认证 nonce 无关。
 */
const http = require('../http');
const intents = require('../../utils/design-intent');
const pending = new Map();

async function write(method, path, data, key, operation) {
  const body = data === undefined ? undefined : JSON.parse(JSON.stringify(data));
  const intent = intents.begin(method + ' ' + path, body, key);
  const pendingKey = intent.scope + ':' + intent.generation + ':' + intent.record.requestKey;
  if (pending.has(pendingKey)) return pending.get(pendingKey);
  const request = http.request(method, path, body, { 'Idempotency-Key': intent.record.requestKey }).then(result => {
    intents.complete(intent);
    return result;
  }).catch(err => {
    intents.assertActive(intent);
    // B 仅在拒绝及解冻事务提交后透传此预算码；不确定结果统一为 1112100002。
    if (operation === 'CREATE_BUDGET_ESTIMATE' && err && err.code === 1071000002) {
      intents.reject(intent);
      console.warn('[design] budget rule rejected', path, err.code);
      throw Object.assign({}, err, { designTerminalRejected: true });
    }
    console.warn('[design] write failed; original intent retained', method, path, err && err.code);
    throw err;
  }).finally(() => pending.delete(pendingKey));
  pending.set(pendingKey, request);
  return request;
}

const cases = {
  /** 灵感库列表（游标分页，±1m 尺寸筛选）{styleCode?, floorCount?, faceWidth?, depth?, keyword?, cursor?, limit?} */
  page: (params) => http.get('/franchise/design/cases', params),
  /** 风格筛选项 */
  styles: () => http.get('/franchise/design/cases/styles'),
  /** 案例详情 */
  detail: (caseId) => http.get('/franchise/design/cases/' + caseId),
  /** 收藏/取消（服务账号名下，组织共享） */
  favorite: (caseId, key) => write('PUT', '/franchise/design/cases/' + caseId + '/favorite', undefined, key),
  unfavorite: (caseId, key) => write('DELETE', '/franchise/design/cases/' + caseId + '/favorite', undefined, key),
  /** 收藏列表 */
  favorites: (cursor, limit) => http.get('/franchise/design/favorites', { cursor: cursor || undefined, limit: limit || 20 })
};

const assets = {
  /** 上传票据 {assetType:'USER_SKETCH', mimeType, sizeBytes, sha256} → {assetId, uploadUrl}（COS=原始字节 PUT；local=dev 通道） */
  uploadTicket: (data, key) => write('POST', '/franchise/design/assets/upload-tickets', data, key),
  /** 完成上传（首次触发审核；终态重放=幂等读）→ {assetId, uploadStatus, rejectedReason?} */
  complete: (assetId, key) => write('POST', '/franchise/design/assets/' + assetId + '/complete', undefined, key),
  /** 换短期签名 URL（票据一次性消费在能力方内完成）→ {downloadUrl, expiresAt?} */
  downloadUrl: (assetId) => http.post('/franchise/design/assets/' + assetId + '/download-tickets')
};

const projects = {
  /** 创建项目（FLAT 线路禁参考/草图；SELF_UPLOAD+FLAT 纯文本输入）→ {projectId, replayed} */
  create: (data, key) => write('POST', '/franchise/design/projects', data, key),
  /** 项目列表；customerId 桥接过滤（先 CRM 可读校验） */
  list: (params) => http.get('/franchise/design/projects', params),
  /** 详情；jobId 触发拉式晋升（allowedActions 白名单） */
  detail: (projectId, jobId) => http.get('/franchise/design/projects/' + projectId, jobId ? { jobId } : undefined),
  /** 平面任务（无 prompt——需求在创建项目时冻结；服务端内聚取价）{count, resolution, orientation} */
  createFlatJob: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/flat-jobs', data, key),
  /** 立面任务 {count, resolution, orientation, styleCode, roofType, material, color}（全字段参与指纹） */
  createElevationJob: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/elevation-jobs', data, key),
  /** AI 任务轮询（job.userId 属主校验）→ {jobId, status, acceptedCount, progress, phase, allowedActions} */
  aiJob: (jobId) => http.get('/franchise/design/ai-jobs/' + jobId),
  /** 请求取消生成任务（QUEUED/RUNNING/VALIDATING → CANCEL_REQUESTED；退点随结算）→ AiJobView */
  cancelJob: (jobId, key) => write('POST', '/franchise/design/ai-jobs/' + jobId + '/cancellation-requests', {}, key),
  /** 补充需求 AI 润色 {note, line} → {polished}（限流：10 次/分·100 次/日） */
  notePolish: (data, key) => write('POST', '/franchise/design/note-polish', data, key),
  /** 选定平面候选 {jobId, candidateId} → {resultVersionId?, version?, selectionSaved, replayed} */
  selectFlat: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/flat-selections', data, key),
  /** 选定立面候选 */
  selectElevation: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/elevation-selections', data, key),
  /** 结果版本列表 */
  resultVersions: (projectId) => http.get('/franchise/design/projects/' + projectId + '/result-versions'),
  /** 调整请求 {reason(必填≤500), configUpdates?, count?} → {requestId, newJobId, replayed} */
  revision: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/revision-requests', data, key),
  /** 回写客户方案 → {schemeId, version, replayed} */
  saveToCustomer: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/save-to-customer', data, key)
};

const budget = {
  /** 区间规则元数据（三元组选择）→ {ruleVersionId, lowCentsPerSqm, highCentsPerSqm} 或 null */
  ruleInfo: (regionCode, structureType, materialGrade) =>
    http.get('/franchise/design/budget/rule-info', { regionCode, structureType, materialGrade }),
  /** 快速预算（buildingArea 整数 1-10000）→ {estimateId, totalLowCents, totalHighCents, ruleVersionId, disclaimer} */
  estimate: (projectId, data, key) => write('POST', '/franchise/design/projects/' + projectId + '/budget-estimates', data, key, 'CREATE_BUDGET_ESTIMATE')
};

module.exports = { cases, assets, projects, budget };
