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
  /** 创建生成任务 kind=digital {voice,text,avatar,title?,subtitle?} | kind=ai_video {description,references?,resolution,duration,ratio} */
  create: (data, key) => http.post('/franchise/content/tasks', data, idem(key, 'task')),
  /** 任务列表（?status= 过滤七态） */
  page: (status) => http.get('/franchise/content/tasks', status ? { status } : undefined),
  /** 任务详情（轮询）：{id,kind,status,provider,providerTaskId,engine,model,resultUrl,failedReason,result} */
  get: (id) => http.get('/franchise/content/tasks/' + id),
  /** 任务→作品（一任务一件，重复返既有）→ {workId,kind,status} */
  saveWork: (id, title) => http.post('/franchise/content/tasks/' + id + '/save-work', title ? { title } : {}),
  /** 成片直链 HEAD 过期探测 → {expired,message}（expired=true 时展示失效文案） */
  resultUrlStatus: (id) => http.get('/franchise/content/tasks/' + id + '/result-url-status')
};

const drafts = {
  /** 我的作品列表 */
  works: () => http.get('/franchise/content/drafts'),
  /** 作品状态 draft|saved */
  updateStatus: (workId, status) => http.put('/franchise/content/drafts/' + workId + '/status', { status }),
  /** 视频草稿（一人一份；无则 {exists:false}） */
  videoDraft: () => http.get('/franchise/content/video-draft'),
  /** 保存视频草稿（upsert）{mode,description?,firstFrameAssetId?,lastFrameAssetId?,parameters?,references?} */
  saveVideoDraft: (data) => http.put('/franchise/content/video-draft', data),
  /** 课程章节完成（幂等） */
  completeChapter: (courseId, chapterIndex) =>
    http.post('/franchise/content/course-progress', { courseId, chapterIndex }),
  /** 课程进度列表 */
  courseProgress: (courseId) =>
    http.get('/franchise/content/course-progress', courseId ? { courseId } : undefined)
};

const assets = {
  /** 内容素材上传票据（CONTENT_ASSET：jpeg/png/mp3/mp4 ≤50MB，B_END 免审）→ {assetId,uploadUrl} */
  uploadTicket: (data) => http.post('/franchise/content/assets/upload-tickets', data),
  /** 上传完成（免审直 ACCEPTED）→ {assetId,status} */
  complete: (assetId) => http.post('/franchise/content/assets/' + assetId + '/complete'),
  /** 下载地址（生产=COS 签名 URL，供生成服务拉取）→ {downloadUrl,expiresAt} */
  downloadUrl: (assetId) => http.post('/franchise/content/assets/' + assetId + '/download-tickets')
};

const personas = {
  /** 人设组织库列表（sales 选用；维护限 sales-lead/boss/owner） */
  page: () => http.get('/franchise/content/personas'),
  create: (data) => http.post('/franchise/content/personas', data),
  update: (id, data) => http.put('/franchise/content/personas/' + id, data),
  remove: (id) => http.delete('/franchise/content/personas/' + id)
};

const publications = {
  /** 创建发布记录（direct-v1 五态；幂等 duplicate 返回既有） */
  create: (data, key) => http.post('/franchise/content/publications', data, idem(key, 'pub')),
  /** 发布列表（?status= publishing|published|verify|failed|scheduled） */
  page: (status) => http.get('/franchise/content/publications', status ? { status } : undefined),
  /** 演示已发布 → 待回执核验 */
  verify: (id) => http.post('/franchise/content/publications/' + id + '/verify'),
  /** 回执发布链接（全局唯一，重复登记 1_114_000_003） */
  link: (id, publishedUrl) => http.post('/franchise/content/publications/' + id + '/link', { publishedUrl }),
  /** 标记发布失败 */
  fail: (id, reason) => http.post('/franchise/content/publications/' + id + '/fail', reason ? { reason } : {})
};

const growth = {
  /** 获客聚合 {publications,leads,converted,perPublication}；客资登记复用 api/franchise/crm.js capture（source=内容发布:{publicationId}） */
  summary: () => http.get('/franchise/content/growth/summary')
};

module.exports = { links, scripts, tasks, drafts, assets, personas, publications, growth };
