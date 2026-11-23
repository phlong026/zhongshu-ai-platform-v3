/**
 * 加盟协同域 · 内容创作获客接入层（阶段 1.5，对应后端 /franchise/content/**）。
 * 序列化事实：taskId/workId/publicationId 为 Long 字符串（不透明值，不参与算术）；
 * 任务七态 QUEUED/SUBMITTING/RUNNING/ARCHIVING/SUCCEEDED/FAILED/SUBMISSION_UNCERTAIN
 * 由 utils/platform-content.js 映射为页面展示态；写命令一律带 Idempotency-Key。
 */
const http = require('../http');

const idem = (key, action) => (key
  ? { 'Idempotency-Key': key }
  : { 'Idempotency-Key': 'content-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const links = {
  /** 分享链接解析（豆一豆同款接口，任务化）{url} → {taskId,status}；幂等重放返回既有任务 */
  parse: (url, key) => http.post('/franchise/content/links/parse', { url }, idem(key, 'link'))
};

const scripts = {
  /** 文案提取（ASR；同素材 sha256 命中缓存零外呼）{assetId?,sourceUrl?,durationSec?,sha256?} → {taskId,status} */
  extract: (data, key) => http.post('/franchise/content/scripts/extract', data, idem(key, 'asr')),
  /** 口播文案改写（DeepSeek 同款；未配置 Key 时 rules 兜底 engine='rules'） */
  optimize: (data, key) => http.post('/franchise/content/scripts/optimize', data, idem(key, 'copy')),
  /** 文案历史（?taskId= 过滤）；详情含 engine/model/prompt 快照 */
  page: (taskId) => http.get('/franchise/content/scripts', taskId ? { taskId } : undefined),
  get: (id) => http.get('/franchise/content/scripts/' + id)
};

const tasks = {
  /** 创建生成任务返回统一任务 DTO {id,kind,title,status,...}；兼容旧 taskId 回包，Long ID 保持字符串。 */
  create: (data, key) => http.post('/franchise/content/tasks', data, idem(key, 'task'))
    .then(task => task && ({ ...task, id: String(task.id || task.taskId || '') })),
  /** 任务列表（?status= 过滤七态） */
  page: (status) => http.get('/franchise/content/tasks', status ? { status } : undefined),
  /** 从本人任务中心移除任务；后端仅记录个人隐藏，不删除云端任务或积分流水。 */
  dismiss: id => http.post('/franchise/content/tasks/' + encodeURIComponent(id) + '/dismiss', {}),
  /** 任务详情（轮询）：{id,kind,title,status,provider,providerTaskId,engine,model,resultUrl,failedReason,result} */
  get: (id) => http.get('/franchise/content/tasks/' + id),
  /** 本人 AI 视频任务的可编辑输入副本；不含内部授权或供应商快照。 */
  editableInput: id => http.get('/franchise/content/tasks/' + encodeURIComponent(id) + '/editable-input'),
  /** 只恢复原任务的本地文案保存，服务端不重新生成或计费。 */
  retrySave: id => http.post('/franchise/content/tasks/' + encodeURIComponent(id) + '/retry-save', {}),
  /** 任务→作品（一任务一件，重复返既有）→ {workId,kind,status}；幂等键由调用方生成存 this（重试复用） */
  saveWork: (id, title, key) => http.post('/franchise/content/tasks/' + id + '/save-work', title ? { title } : {}, idem(key, 'save-work')),
  /** 成片直链 HEAD 过期探测 → {expired,message}（expired=true 时展示失效文案） */
  resultUrlStatus: (id) => http.get('/franchise/content/tasks/' + id + '/result-url-status')
};

const drafts = {
  /** 我的作品列表 */
  works: () => http.get('/franchise/content/drafts'),
  /** 作品状态 draft|saved；幂等键由调用方生成存 this（重试复用） */
  updateStatus: (workId, status, key) => http.put('/franchise/content/drafts/' + workId + '/status', { status }, idem(key, 'work-status')),
  /** 视频草稿（一人一份；无则 {exists:false}） */
  videoDraft: (draftKey='new') => http.get('/franchise/content/video-draft', {draftKey}),
  /** 保存视频草稿（upsert）{mode,description?,firstFrameAssetId?,lastFrameAssetId?,parameters?,references?} */
  saveVideoDraft: (data) => http.put('/franchise/content/video-draft', data),
  /** 课程章节完成（幂等）；幂等键由调用方生成存 this（重试复用） */
  completeChapter: (courseId, chapterIndex, key) =>
    http.post('/franchise/content/course-progress', { courseId, chapterIndex }, idem(key, 'chapter')),
  /** 课程进度列表 */
  courseProgress: (courseId) =>
    http.get('/franchise/content/course-progress', courseId ? { courseId } : undefined)
};

// The same authorized library serves management and video selection.
const materialBase = '/franchise/content/material-library';
const materials = {
  page: query => http.get(materialBase, query),
  upload: body => http.post(materialBase + '/upload-tickets', body),
  complete: id => http.post(materialBase + '/' + encodeURIComponent(id) + '/complete', {}),
  download: id => http.post(materialBase + '/' + encodeURIComponent(id) + '/download-tickets', {}),
  update: (id, body) => http.put(materialBase + '/' + encodeURIComponent(id), body),
  remove: id => http.del(materialBase + '/' + encodeURIComponent(id))
};

const assets = {
  /** 常见媒体原文件 ≤50MB；B 校验并转换为生成服务可使用的格式。 */
  uploadTicket: (data) => http.post('/franchise/content/assets/upload-tickets', data),
  /** 完成上传及格式处理；重试始终使用同一 assetId。 */
  complete: (assetId) => http.post('/franchise/content/assets/' + assetId + '/complete', undefined, undefined, { timeout: 120000 }),
  /** 下载地址（生产=COS 签名 URL，供生成服务拉取）→ {downloadUrl,expiresAt} */
  downloadUrl: (assetId) => http.post('/franchise/content/assets/' + encodeURIComponent(assetId) + '/download-tickets')
};

const personas = {
  /** 人设组织库列表（sales 选用；维护限 sales-lead/boss/owner） */
  page: () => http.get('/franchise/content/personas'),
  create: (data) => http.post('/franchise/content/personas', data),
  update: (id, data) => http.put('/franchise/content/personas/' + id, data),
  remove: (id) => http.del('/franchise/content/personas/' + id)
};

const publications = {
  /** 创建发布记录（direct-v1 五态；幂等 duplicate 返回既有） */
  create: (data, key) => http.post('/franchise/content/publications', data, idem(key, 'pub')),
  /** 发布列表（?status= publishing|published|verify|failed|scheduled） */
  page: (status) => http.get('/franchise/content/publications', status ? { status } : undefined),
  /** Resolve a published record to its original saved video; backend checks organization and published status. */
  playback: id => http.get('/franchise/content/publications/' + encodeURIComponent(id) + '/playback'),
  /** 演示已发布 → 待回执核验；幂等键由调用方生成存 this（重试复用） */
  verify: (id, key) => http.post('/franchise/content/publications/' + id + '/verify', {}, idem(key, 'pub-verify')),
  /** 回执发布链接（全局唯一，重复登记 1_114_000_003）；幂等键由调用方生成存 this（重试复用） */
  link: (id, publishedUrl, key) => http.post('/franchise/content/publications/' + id + '/link', { publishedUrl }, idem(key, 'pub-link')),
  /** 标记发布失败；幂等键由调用方生成存 this（重试复用） */
  fail: (id, reason, key) => http.post('/franchise/content/publications/' + id + '/fail', reason ? { reason } : {}, idem(key, 'pub-fail'))
};

const growth = {
  /** 获客聚合 {publications,leads,converted,perPublication}；客资登记复用 api/franchise/crm.js capture（source=内容发布:{publicationId}） */
  summary: () => http.get('/franchise/content/growth/summary'),
  /** 当前客户可见的视频咨询来源；后端按客户负责人/团队权限过滤。 */
  customerSources: customerId => http.get('/franchise/content/growth/customers/' + encodeURIComponent(customerId) + '/sources')
};

const clones = {
  /** 已处理素材转入数字人服务；同一素材重试返回原上传记录。 */
  assetUpload: (personaId, data) => http.post('/franchise/content/personas/' + encodeURIComponent(personaId) + '/clone-asset-uploads', data, undefined, { timeout:120000 }),
  fileTicket: (personaId, data) => http.post('/franchise/content/personas/' + encodeURIComponent(personaId) + '/clone-file-tickets', data),
  fileReceipt: (id, data) => http.post('/franchise/content/clone-uploads/' + encodeURIComponent(id) + '/file-receipt', data),
  uploadTicket: (personaId, data) => http.post('/franchise/content/personas/' + encodeURIComponent(personaId) + '/clone-upload-tickets', data),
  completeUpload: id => http.post('/franchise/content/clone-uploads/' + encodeURIComponent(id) + '/complete', {}),
  quote: kind => http.get('/franchise/content/clones/quote', { kind }),
  /** Each clone reports canRename, canDelete and canSetDefault for the active post. */
  list: personaId => http.get('/franchise/content/personas/' + encodeURIComponent(personaId) + '/clones'),
  create: (personaId, data, key) => http.post('/franchise/content/personas/' + encodeURIComponent(personaId) + '/clones', data, idem(key, 'clone')),
  refresh: id => http.post('/franchise/content/clones/' + encodeURIComponent(id) + '/refresh', {}),
  setDefault: id => http.put('/franchise/content/clones/' + encodeURIComponent(id) + '/default', {}),
  rename: (id, title) => http.put('/franchise/content/clones/' + encodeURIComponent(id) + '/title', { title }),
  remove: id => http.del('/franchise/content/clones/' + encodeURIComponent(id))
};
module.exports = { links, scripts, tasks, drafts, assets, materials, personas, clones, publications, growth };
