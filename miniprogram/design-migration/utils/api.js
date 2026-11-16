'use strict';



var http = require('./request');



var BASE = '/app-api/design/v1';



module.exports = {

  // ---- 身份 ----

  login: function (wxCode) {

    return http.post(BASE + '/auth/wechat-login', { code: wxCode });

  },

  getAccessGrant: function (options) {

    return http.get(BASE + '/access-grant', undefined, options);

  },

  redeemAccessCode: function (accessCode, binding) {

    var body = { accessCode: accessCode };

    // 账户绑定（C07）：兑换时登记的加盟商公司信息，与激活码绑定落库

    if (binding) {

      body.companyName = binding.companyName;

      body.contactName = binding.contactName;

      body.contactPhone = binding.contactPhone;

    }

    return http.post(BASE + '/access-code-redemptions', body);

  },

  getProfile: function () {

    return http.get(BASE + '/profile');

  },

  updateProfile: function (nickname, avatarAssetId) {

    var body = { nickname: nickname };

    if (avatarAssetId) body.avatarAssetId = String(avatarAssetId);

    return http.patch(BASE + '/profile', body);

  },

  // 客服入口配置（C06）：未配置时后端下发空串，前端据此隐藏入口，不展示打不通的假号码

  getSupportEntry: function () {

    return http.get(BASE + '/support-entry');

  },



  // ---- 首页 / 户型库 ----

  getHome: function () {

    return http.get(BASE + '/home');

  },

  // 平面线路默认参考案例：层数一致优先、建筑面积最接近且已授权生成参考；无可用案例时 data 为空

  recommendCase: function (floorCount, area) {

    return http.get(BASE + '/cases/recommendation?floorCount=' + encodeURIComponent(floorCount) + '&area=' + encodeURIComponent(area));

  },

  // T26-07：仅返回真实、已授权的候选；查询与预览不会创建生成任务。

  getGenerationCaseCandidates: function (params) {

    var qs = Object.keys(params || {}).filter(function (key) { return params[key] != null && params[key] !== ''; })

      .map(function (key) { return encodeURIComponent(key) + '=' + encodeURIComponent(params[key]); }).join('&');

    return http.get(BASE + '/cases/generation-candidates' + (qs ? '?' + qs : ''));

  },

  getGenerationCaseCandidate: function (caseId, caseVersionId) {

    return http.get(BASE + '/cases/' + encodeURIComponent(caseId) + '/generation-candidate?caseVersionId=' + encodeURIComponent(caseVersionId));

  },

  listCases: function (params) {

    var qs = Object.keys(params || {}).filter(function (k) { return params[k] != null; })

      .map(function (k) { return k + '=' + encodeURIComponent(params[k]); }).join('&');

    return http.get(BASE + '/cases' + (qs ? '?' + qs : ''));

  },

  getCaseDetail: function (caseId) {

    return http.get(BASE + '/cases/' + caseId);

  },

  favorite: function (caseId) {

    return http.put(BASE + '/cases/' + caseId + '/favorite');

  },

  unfavorite: function (caseId) {

    return http.del(BASE + '/cases/' + caseId + '/favorite');

  },

  listFavorites: function (cursor, limit) {

    return http.get(BASE + '/favorites?cursor=' + (cursor || '') + '&limit=' + (limit || 20));

  },



  // ---- 设计项目 / AI 任务 ----

  listProjects: function (cursor, limit) {

    return http.get(BASE + '/design-projects?limit=' + (limit || 20) + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''));

  },

  // 删除我的设计项目（后端软删；最新任务进行中会返回 1_071_000_006）

  deleteProject: function (projectId) {

    return http.del(BASE + '/design-projects/' + encodeURIComponent(projectId));

  },

  // 方案重命名（仅属主；名称去首尾空白后 1~60 字符）

  renameProject: function (projectId, name) {

    return http.patch(BASE + '/design-projects/' + encodeURIComponent(projectId) + '/name', { name: name });

  },

  listSubmissions: function (cursor, limit) {

    return http.get(BASE + '/submissions?limit=' + (limit || 20) + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''));

  },

  getSubmission: function (id, reviewRound) {

    return http.get(BASE + '/submissions/' + encodeURIComponent(id) + (reviewRound == null ? '' : '?reviewRound=' + encodeURIComponent(reviewRound)));

  },

  resubmitSubmission: function (id, note, key, expectedRound) {

    const body = { note: note };

    if (expectedRound != null) body.expectedRound = String(expectedRound);

    return http.post(BASE + '/submissions/' + encodeURIComponent(id) + '/resubmissions', body, { 'Idempotency-Key': key });

  },

  listRechargeOrders: function (pageNo, pageSize) {

    return http.get(BASE + '/recharge-orders?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20));

  },

  listPointLedger: function (pageNo, pageSize) {

    return http.get(BASE + '/point-ledger?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20));

  },

  notePolish: function (note, line) {

    return http.post(BASE + '/ai-design/note-polish', { note: note, line: line || 'FLAT' });

  },

  createProject: function (body, idemKey) {

    return http.post(BASE + '/design-projects', body || null, idemKey ? { 'Idempotency-Key': idemKey } : undefined);

  },

  getProject: function (projectId, jobId) {

    return http.get(BASE + '/design-projects/' + projectId + (jobId ? '?jobId=' + jobId : ''));

  },

  getGenerationQuote: function (stage, count, resolution) {

    return http.get(BASE + '/generation-price-quotes?stage=' + encodeURIComponent(stage) + '&count=' + count + '&resolution=' + encodeURIComponent(resolution || '4K'));

  },

  createFlatJob: function (projectId, count, idemKey, priceConfirmation, options) {

    return http.post(BASE + '/design-projects/' + projectId + '/flat-jobs',

      { count: count, resolution: (options && options.resolution) || '4K', orientation: (options && options.orientation) || 'LANDSCAPE', priceConfirmation: priceConfirmation }, { 'Idempotency-Key': idemKey });

  },

  selectFlat: function (projectId, jobId, candidateId) {

    return http.post(BASE + '/design-projects/' + projectId + '/flat-selections',

      { jobId: String(jobId), candidateId: String(candidateId) });

  },

  createElevationJob: function (projectId, config, idemKey) {

    return http.post(BASE + '/design-projects/' + projectId + '/elevation-jobs',

      config, { 'Idempotency-Key': idemKey });

  },

  selectElevation: function (projectId, jobId, candidateId) {

    return http.post(BASE + '/design-projects/' + projectId + '/elevation-selections',

      { jobId: String(jobId), candidateId: String(candidateId) });

  },

  createRevisionRequest: function (projectId, body, idemKey) {

    return http.post(BASE + '/design-projects/' + projectId + '/revision-requests',

      body || {}, { 'Idempotency-Key': idemKey });

  },

  getResultVersions: function (projectId) {

    return http.get(BASE + '/design-projects/' + projectId + '/result-versions');

  },

  getAiJob: function (jobId) {

    return http.get(BASE + '/ai-jobs/' + jobId);

  },

  cancelJob: function (jobId) {

    return http.post(BASE + '/ai-jobs/' + jobId + '/cancellation-requests');

  },



  // ---- 充值 ----

  listRechargePlans: function () {

    return http.get(BASE + '/recharge-plans');

  },

  // T-XPAY：loginCode 为支付前临时 wx.login 一次性 code（服务端 code2session 换 session_key 签名用，

  // 换取即弃不落库）；老通道后端忽略。请求头 X-WX-Login-Code，禁止放 URL/查询串。

  createRechargeOrder: function (planId, idemKey, loginCode) {

    var headers = { 'Idempotency-Key': idemKey };

    if (loginCode) headers['X-WX-Login-Code'] = loginCode;

    return http.post(BASE + '/recharge-orders?planId=' + planId, null, headers);

  },

  getRechargeOrder: function (orderId) {

    return http.get(BASE + '/recharge-orders/' + orderId);

  },

  // T13-24：恢复流程重新领取 payParams（禁止重复建单，同 orderNo 幂等）；T-XPAY 附一次性 code 强制重签

  getPayParams: function (orderId, loginCode) {

    var headers = loginCode ? { 'X-WX-Login-Code': loginCode } : undefined;

    return http.get(BASE + '/recharge-orders/' + orderId + '/pay-params', headers);

  },

  getPointAccount: function () {

    return http.get(BASE + '/point-account');

  },



  // ---- 消息 ----

  listMessages: function (cursor, limit, category) {

    return http.get(BASE + '/messages?cursor=' + encodeURIComponent(cursor || '') + '&limit=' + (limit || 20)

      + (category ? '&category=' + encodeURIComponent(category) : ''));

  },

  getMessageCounts: function () { return http.get(BASE + '/messages/counts'); },

  getMessageTarget: function (id) { return http.get(BASE + '/messages/' + encodeURIComponent(id) + '/target'); },

  getUnreadCount: function () {

    return http.get(BASE + '/messages/unread-count');

  },

  markMessageRead: function (messageId) {

    return http.post(BASE + '/messages/' + messageId + '/read-receipts');

  },



  // ---- 资产 ----

  getUploadTicket: function (assetType, mimeType, sizeBytes, sha256) {

    return http.post(BASE + '/assets/upload-tickets',

      { assetType: assetType, mimeType: mimeType, sizeBytes: String(sizeBytes), sha256: sha256 });

  },

  completeUpload: function (assetId) {

    return http.post(BASE + '/assets/' + assetId + '/complete');

  },

  createDownloadTicket: function (assetId) {

    return http.post(BASE + '/assets/' + assetId + '/download-tickets');

  },

  resolveDownload: function (assetId, ticket) {

    return http.post(BASE + '/assets/' + encodeURIComponent(assetId) + '/downloads', { ticket: ticket });

  },

  // 开发期资产字节端点 URL 构造器（GET 下载 / POST multipart 直传共用同一路径）。

  // 该端点是二进制/multipart，不走 http 的 JSON 封装；此处仅收敛散落在

  // assets.js / ai-design/index.js / avatar-upload.js 的硬编码路径，调用方自行

  // 用 wx.request/wx.uploadFile 拼 config.apiBase + 本 URL。切 COS 预签名后此路径退役。

  assetContentUrl: function (assetId) {

    return BASE + '/assets/' + encodeURIComponent(assetId) + '/content';

  },



  // ---- 预算 ----

  createBudgetDraft: function (key) { return http.post(BASE + '/budget-drafts', {}, { 'Idempotency-Key': key }); },

  getBudgetDrafts: function (cursor) { return http.get(BASE + '/budget-drafts?limit=20' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '')); },

  getBudgetDraft: function (id) { return http.get(BASE + '/budget-drafts/' + encodeURIComponent(id)); },

  saveBudgetDraft: function (id, expectedVersion, state) { return http.put(BASE + '/budget-drafts/' + encodeURIComponent(id), { expectedVersion, state }); },

  renameBudgetDraft: function (id, name) { return http.put(BASE + '/budget-drafts/' + encodeURIComponent(id) + '/name', { name }); },

  importBudgetDraft: function (id, expectedVersion, projectId, resultVersionId) {

    return http.post(BASE + '/budget-drafts/' + encodeURIComponent(id) + '/import', { expectedVersion, projectId, resultVersionId: resultVersionId || null });

  },

  createIndependentBudget: function (id, body, key) { return http.post(BASE + '/budget-drafts/' + encodeURIComponent(id) + '/estimates', body, { 'Idempotency-Key': key }); },

  getIndependentBudgetHistory: function (options) {

    const q = options || {};

    return http.get(BASE + '/budget-estimates/independent?savedOnly=' + (q.savedOnly !== false) + '&limit=20'

      + (q.draftId ? '&draftId=' + encodeURIComponent(q.draftId) : '') + (q.cursor ? '&cursor=' + encodeURIComponent(q.cursor) : ''));

  },

  getIndependentBudgetQuotes: function (id) { return http.get(BASE + '/budget-estimates/' + encodeURIComponent(id) + '/quotes'); },

  getPublicBudgetQuote: function (budgetId, quoteId) {

    return http.get(BASE + '/budget-estimates/' + encodeURIComponent(budgetId) + '/quotes/' + encodeURIComponent(quoteId));

  },

  getBudgetInputs: function (projectId, resultVersionId) {

    return http.get(BASE + '/design-projects/' + encodeURIComponent(projectId) + '/budget-inputs'

      + (resultVersionId == null ? '' : '?resultVersionId=' + encodeURIComponent(resultVersionId)));

  },

  getBudgetRegions: function () {

    return http.get(BASE + '/budget/regions');

  },

  // 用户自定义地区（仅创建人可见；与平台地区在前端合并展示）

  listMyRegions: function () {

    return http.get(BASE + '/budget/my-regions');

  },

  createMyRegion: function (name) {

    return http.post(BASE + '/budget/my-regions', { name: name });

  },

  getBudgetOptions: function (regionCode) {

    return http.get(BASE + '/budget/options?regionCode=' + encodeURIComponent(regionCode));

  },

  // T14 我的当地单价：基准价与账号覆盖价并列；覆盖跟账号永久生效，恢复默认即回基准价

  getMyPrices: function (regionCode) {

    return http.get(BASE + '/budget/my-prices?regionCode=' + encodeURIComponent(regionCode));

  },

  setMyPrice: function (optionId, regionCode, unitPriceCents, reason) {

    var body = { unitPriceCents: unitPriceCents };

    if (reason) body.reason = reason;

    return http.put(BASE + '/budget/my-prices/' + encodeURIComponent(optionId) + '?regionCode=' + encodeURIComponent(regionCode), body);

  },

  resetMyPrice: function (optionId, regionCode) {

    return http.del(BASE + '/budget/my-prices/' + encodeURIComponent(optionId) + '?regionCode=' + encodeURIComponent(regionCode));

  },

  createItemizedBudget: function (projectId, input, idemKey) {

    return http.post(BASE + '/design-projects/' + encodeURIComponent(projectId) + '/budget-estimates/itemized',

      input, { 'Idempotency-Key': idemKey });

  },

  getBudgetPointQuote: function () {

    return http.get(BASE + '/budget-point-quotes');

  },

  getBudgetEstimate: function (budgetId, revisionId) {

    return http.get(BASE + '/budget-estimates/' + encodeURIComponent(budgetId)

      + (revisionId == null ? '' : '?revisionId=' + encodeURIComponent(revisionId)));

  },

  saveBudgetEstimate: function (budgetId, idemKey) {

    return http.post(BASE + '/budget-estimates/' + encodeURIComponent(budgetId) + '/save', {},

      { 'Idempotency-Key': idemKey });

  },

  getBudgetHistory: function (projectId, options) {

    const query = options || {};

    const params = ['savedOnly=' + (query.savedOnly !== false), 'limit=' + encodeURIComponent(query.limit || 20)];

    if (query.cursor != null) params.push('cursor=' + encodeURIComponent(query.cursor));

    return http.get(BASE + '/design-projects/' + encodeURIComponent(projectId) + '/budget-estimates?' + params.join('&'));

  },

  getBudgetQuotes: function (projectId) {

    return http.get(BASE + '/design-projects/' + encodeURIComponent(projectId) + '/budget-quotes');

  },



  // ---- 投稿 ----

  validatePublication: function (projectId, data) {

    return http.post(BASE + '/design-projects/' + projectId + '/publication-validations', data || null);

  },

  submitForPublication: function (projectId, data, idemKey) {

    return http.post(BASE + '/design-projects/' + projectId + '/submissions', data,

      { 'Idempotency-Key': idemKey });

  },



  // ---- 协议 ----

  // 小程序仅读取协议版本与同意事实、发起/下载数据主体请求；同意记录由后端在登录链路落库。

  getPrivacyConsents: function () {

    return http.get(BASE + '/privacy-consents');

  },

  listPrivacyRequests: function () { return http.get(BASE + '/privacy-requests'); },

  createPrivacyDownloadTicket: function (id) { return http.post(BASE + '/privacy-requests/' + encodeURIComponent(id) + '/download-tickets'); },



  // ---- 数据主体请求（M10：数据导出 / 账号关闭）----

  // P0 后端只登记；导出包下载闭环走 privacy-requests 的 download-tickets/content。

  // 进度查询端点（GET /data-export-requests/{id} 等）暂无页面使用，接入时再补封装。

  createDataExportRequest: function () {

    return http.post(BASE + '/data-export-requests');

  },

  createAccountClosureRequest: function () {

    return http.post(BASE + '/account-closure-requests');

  }

};

