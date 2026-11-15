/**
 * 加盟协同域 · 设计赋能接入层（B2 板块 A，对应后端 /franchise/design/**）。
 * 代理 C 端设计能力给 B 端员工；写命令透传 Idempotency-Key 给 design 自带幂等。
 */
const http = require('../http');

const idem = (key, action) => (key ? { 'Idempotency-Key': key } : { 'Idempotency-Key': 'design-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const cases = {
  /** 灵感库列表（游标分页，±1m 尺寸筛选）{styleCode?, floorCount?, faceWidth?, depth?, keyword?, cursor?, limit?} */
  page: (params) => http.get('/franchise/design/cases', params),
  /** 风格筛选项 */
  styles: () => http.get('/franchise/design/cases/styles'),
  /** 案例详情 */
  detail: (caseId) => http.get('/franchise/design/cases/' + encodeURIComponent(caseId)),
  /** A 公开案例图片：由 B 验证图片属于该案例后换取短链，与 B 生成素材分开。 */
  downloadUrl: (caseId, assetId) => http.post('/franchise/design/cases/' + encodeURIComponent(caseId) + '/assets/' + encodeURIComponent(assetId) + '/download-tickets'),
  /** 收藏/取消（保存在 B，按员工及组织隔离） */
  favorite: (caseId) => http.put('/franchise/design/cases/' + caseId + '/favorite'),
  unfavorite: (caseId) => http.del('/franchise/design/cases/' + caseId + '/favorite'),
  /** 收藏列表 */
  favorites: (cursor, limit) => http.get('/franchise/design/favorites', { cursor: cursor || undefined, limit: limit || 20 })
};

const assets = {
  /** 上传票据 {assetType:'USER_SKETCH', mimeType, sizeBytes, sha256} → {assetId, uploadUrl}（COS=原始字节 PUT；local=dev 通道） */
  uploadTicket: (data) => http.post('/franchise/design/assets/upload-tickets', data),
  /** 完成上传（首次触发审核；终态重放=幂等读）→ {assetId, uploadStatus, rejectedReason?} */
  complete: (assetId) => http.post('/franchise/design/assets/' + assetId + '/complete'),
  /** 设计候选/结果短期URL（remote固定由能力方读取；B输入预览用franchise/assets）→ {downloadUrl, expiresAt?} */
  downloadUrl: (assetId) => http.post('/franchise/design/assets/' + assetId + '/download-tickets')
};

const projects = {
  /** 创建项目（FLAT 线路禁参考/草图；SELF_UPLOAD+FLAT 纯文本输入）→ {projectId, replayed} */
  create: (data, key) => http.post('/franchise/design/projects', data, idem(key, 'proj')),
  /** 项目列表；customerId 桥接过滤（先 CRM 可读校验） */
  list: (params) => http.get('/franchise/design/projects', params),
  /** 详情；jobId 触发拉式晋升（allowedActions 白名单） */
  detail: (projectId, jobId) => http.get('/franchise/design/projects/' + projectId, jobId ? { jobId } : undefined),
  /** 平面任务（无 prompt——需求在创建项目时冻结；服务端内聚取价）{count, resolution, orientation} */
  createFlatJob: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/flat-jobs', data, idem(key, 'flat')),
  /** 立面任务 {count, resolution, orientation, styleCode, roofType, material, color}（全字段参与指纹） */
  createElevationJob: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/elevation-jobs', data, idem(key, 'elev')),
  /** AI 任务轮询（job.userId 属主校验）→ {jobId, status, acceptedCount, progress, phase, allowedActions} */
  aiJob: (jobId) => http.get('/franchise/design/ai-jobs/' + jobId),
  /** 请求取消生成任务（QUEUED/RUNNING/VALIDATING → CANCEL_REQUESTED；退点随结算）→ AiJobView */
  cancelJob: (jobId) => http.post('/franchise/design/ai-jobs/' + jobId + '/cancellation-requests', {}),
  /** 补充需求 AI 润色 {note, line} → {polished}（限流：10 次/分·100 次/日） */
  notePolish: (data) => http.post('/franchise/design/note-polish', data),
  /** 选定平面候选 {jobId, candidateId} → {resultVersionId?, version?, selectionSaved, replayed} */
  selectFlat: (projectId, data) => http.post('/franchise/design/projects/' + projectId + '/flat-selections', data),
  /** 选定立面候选 */
  selectElevation: (projectId, data) => http.post('/franchise/design/projects/' + projectId + '/elevation-selections', data),
  /** 结果版本列表 */
  resultVersions: (projectId) => http.get('/franchise/design/projects/' + projectId + '/result-versions'),
  /** 调整请求 {reason(必填≤500), configUpdates?, count?} → {requestId, newJobId, replayed} */
  revision: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/revision-requests', data, idem(key, 'rev')),
  /** 回写客户方案 → {schemeId, version, replayed} */
  saveToCustomer: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/save-to-customer', data, idem(key, 'save'))
};

const budget = {
  /** 区间规则元数据（三元组选择）→ {ruleVersionId, lowCentsPerSqm, highCentsPerSqm} 或 null */
  ruleInfo: (regionCode, structureType, materialGrade) =>
    http.get('/franchise/design/budget/rule-info', { regionCode, structureType, materialGrade }),
  /** 快速预算（buildingArea 整数 1-10000）→ {estimateId, totalLowCents, totalHighCents, ruleVersionId, disclaimer} */
  estimate: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/budget-estimates', data, idem(key, 'budget'))
};

module.exports = { cases, assets, projects, budget };
