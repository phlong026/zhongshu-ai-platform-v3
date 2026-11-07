/**
 * 加盟协同域 · 工程全周期接入层（阶段 2，对应后端 /franchise/engineering/**）。
 * 序列化事实：projectId/supplyId/materialId/receiptId 为 Long 字符串（不透明值）；
 * 日期字段双形状（字符串或 [y,m,d] 数组）由 utils/platform-engineering.js dateOf 归一；
 * 金额一律分（cents）；写命令一律带 Idempotency-Key（后端三层幂等）。
 */
const http = require('../http');

const idem = (key, action) => (key
  ? { 'Idempotency-Key': key }
  : { 'Idempotency-Key': 'eng-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const projects = {
  /** 手工新建/增项（查重=同组织未归档项目名唯一 1_115_000_008）{name,customer,city,phone?,floors?,area?,parentProjectId?} */
  create: (data, key) => http.post('/franchise/engineering/projects', data, idem(key, 'create')),
  /** 列表（?status=；工程岗仅本人，财务线全量） */
  page: (status) => http.get('/franchise/engineering/projects', status ? { status } : undefined),
  /** 详情聚合（档案+计划+文档+尾段，按身份脱敏 D-13） */
  get: (id) => http.get('/franchise/engineering/' + id),
  /** 换负责人（在岗工程线校验 1_115_000_010 + CAS；旧负责人立即失权） */
  changeManager: (id, engineerUserId, note, key) =>
    http.post('/franchise/engineering/' + id + '/manager', { engineerUserId, note: note || '' }, idem(key, 'manager'))
};

const plan = {
  /** 开工准备：合同+凭据+说明+人员 → 两期约定即固化（无审批无门禁） */
  prepare: (id, data, key) => http.post('/franchise/engineering/' + id + '/prepare', data, idem(key, 'prepare')),
  /** 实际开工（START 期累计已核算门禁 1_115_000_007） */
  start: (id, data, key) => http.post('/franchise/engineering/' + id + '/start', data, idem(key, 'start')),
  /** 保存计划（整体提交；日期齐全+不重叠 002；已完成节点跳过） */
  save: (id, nodes, key) => http.put('/franchise/engineering/' + id + '/plan', { nodes }, idem(key, 'plan')),
  /** 整体改期（◆顺移算法+材料 required_date 随移+版本快照） */
  reschedule: (id, data, key) => http.post('/franchise/engineering/' + id + '/plan/reschedule', data, idem(key, 'shift')),
  /** 版本快照列表 */
  history: (id) => http.get('/franchise/engineering/' + id + '/plan/history'),
  /** 节点完成（材料门禁 007+completion 记录自动落；无收款门禁） */
  complete: (id, nodeId, data, key) =>
    http.post('/franchise/engineering/' + id + '/nodes/' + nodeId + '/complete', data, idem(key, 'node')),
  /** 撤回（最近完成+下节点无记录+未收封顶款 003） */
  reopen: (id, nodeId, reason, key) =>
    http.post('/franchise/engineering/' + id + '/nodes/' + nodeId + '/reopen', { reason }, idem(key, 'reopen')),
  /** 更正（日期链约束含封顶完成≤封顶款收款日 003） */
  correct: (id, nodeId, data, key) =>
    http.post('/franchise/engineering/' + id + '/nodes/' + nodeId + '/correct', data, idem(key, 'correct'))
};

const materials = {
  /** 材料计划登记（每次提交一个计划单分组 → materialPlanId） */
  submit: (id, data, key) => http.post('/franchise/engineering/' + id + '/materials', data, idem(key, 'mplan')),
  /** 需用数量调整（0=取消；边界 1_115_000_004） */
  adjust: (materialId, quantity, reason, key) =>
    http.put('/franchise/engineering/materials/' + materialId, { quantity, reason }, idem(key, 'madj')),
  /** 采购金额（terms 码；经 PayablePort 生成 finance 应付；已付款后拒改 004） */
  terms: (supplyId, data, key) =>
    http.post('/franchise/engineering/supplies/' + supplyId + '/terms', data, idem(key, 'terms')),
  /** 到货（三形态：新收货/补发到货 parentReceiptId/质检处置 qualityResolution；流水唯一 005） */
  arrival: (supplyId, data, key) =>
    http.post('/franchise/engineering/supplies/' + supplyId + '/arrival', data, idem(key, 'arrive')),
  /** 实到更正（留痕；累计与节点边界 004） */
  arrivalCorrection: (receiptId, actual, reason, key) =>
    http.post('/franchise/engineering/supplies/arrival-correction', { receiptId, actual, reason }, idem(key, 'acorr')),
  /** ◆材料去向（领用耗用/报损/退回/余料结转） */
  movement: (id, data, key) => http.post('/franchise/engineering/' + id + '/movements', data, idem(key, 'move')),
  /** 材料催到货（material_plan_id 维度，同日同内容幂等） */
  followup: (id, materialPlanId, promiseDate, note, key) =>
    http.post('/franchise/engineering/' + id + '/material-followups', { materialPlanId, promiseDate, note }, idem(key, 'mfollow')),
  /** 计划级汇总（到货+付款派生态；?materialPlanId=） */
  summary: (id, materialPlanId) => http.get('/franchise/engineering/' + id + '/materials/summary',
    materialPlanId ? { materialPlanId } : undefined),
  /** M07-T07：登记直接项目费用（purpose=LABOR 劳务/TRAVEL 差旅/OTHER 其它）{name,purpose,amount,note} */
  expenseRegister: (id, data, key) => http.post('/franchise/engineering/' + id + '/expenses', data, idem(key, 'expense')),
  /** M07-T07：费用挂 finance 应付 {supplier,basis}（付款沿 payable_id 经 finance 资金侧） */
  expenseTerms: (expenseId, data, key) => http.post('/franchise/engineering/expenses/' + expenseId + '/terms', data, idem(key, 'expense-terms')),
  /** M09-T04：确认费用变更（金额修订——退多付款不减正确费用）{newAmount,note} */
  expenseRevise: (expenseId, data, key) => http.post('/franchise/engineering/expenses/' + expenseId + '/revise', data, idem(key, 'expense-revise')),
  /** 取消费用（已挂应付拒绝——应付须财务侧原子关闭）{reason} */
  expenseCancel: (expenseId, reason, key) =>
    http.post('/franchise/engineering/expenses/' + expenseId + '/cancel', { reason }, idem(key, 'expense-cancel')),
  /** 费用清单 */
  expenseList: (id) => http.get('/franchise/engineering/' + id + '/expenses'),
};

const site = {
  /** 普通现场记录（note+多图资产；前置已开工） */
  create: (id, nodeId, note, fileAssetIds, occurredAt, key) =>
    http.post('/franchise/engineering/' + id + '/site-records', { nodeId, note, fileAssetIds, occurredAt }, idem(key, 'site')),
  /** 现场记录更正（原值入 corrections；completion 记录走节点更正） */
  correct: (recordId, note, fileAssetIds, reason, key) =>
    http.post('/franchise/engineering/site-records/' + recordId + '/correct', { note, fileAssetIds, reason }, idem(key, 'scorr')),
  list: (id) => http.get('/franchise/engineering/' + id + '/site-records')
};

const collections = {
  /** 两期汇总+登记列表 {stages:[{stage,expectedCents,confirmedCents,remainingCents,due}],records} */
  page: (id) => http.get('/franchise/engineering/' + id + '/collections'),
  /** 收款登记（collect 码；TOPPING 须封顶完成；累计≤应收 006；写通 finance）→ {collectionId,receiptId,replayed} */
  register: (id, data, key) => http.post('/franchise/engineering/' + id + '/collections', data, idem(key, 'collect')),
  /** 催收回访（stage 维度，同日同内容幂等；TOPPING 须封顶完成） */
  dunning: (id, stage, promiseDate, note, key) =>
    http.post('/franchise/engineering/' + id + '/dunning', { stage, promiseDate, note }, idem(key, 'dunning')),
  /** 催办记录（收款+材料双维度） */
  followups: (id) => http.get('/franchise/engineering/' + id + '/followups')
};

const closure = {
  /** 交付（开工+全节点+材料齐+全累计收款门禁 007） */
  deliver: (id, data, key) => http.post('/franchise/engineering/' + id + '/deliver', data, idem(key, 'deliver')),
  /** 确认结算（前置交付 009；返回结算屏汇总） */
  settle: (id, data, key) => http.post('/franchise/engineering/' + id + '/settle', data, idem(key, 'settle')),
  /** 归档（前置交付+结算 009） */
  archive: (id, key) => http.post('/franchise/engineering/' + id + '/archive', {}, idem(key, 'archive')),
  /** 结算屏汇总（材料聚合+项目费用 0 占位+两期收款） */
  settleSummary: (id) => http.get('/franchise/engineering/' + id + '/settle-summary')
};

/** 基-1 凭据资产（ENG_DRAWING/ENG_SITE_PHOTO；登录+任职即可） */
const assets = {
  uploadTicket: (assetType, mimeType, sizeBytes, sha256) =>
    http.post('/franchise/assets/upload-tickets', { assetType, mimeType, sizeBytes, sha256 }),
  complete: (assetId) => http.post('/franchise/assets/' + assetId + '/complete'),
  /** M07-T04 批次#36：授权查看——下载票据（COS=签名 URL+到期；local=local:// 占位） */
  downloadTicket: (assetId) => http.post('/franchise/assets/' + assetId + '/download-tickets', {})
};

/** base64 → 二进制字符串（每字符一字节，供 sha256Hex 按原始字节摘要——R1 P1-1：
 * 后端 complete 对存储字节算 SHA-256 与票据声明比对，对 base64 文本算 UTF-8 哈希必 SHA_MISMATCH） */
const BASE64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function base64ToBinary(b64) {
  const clean = String(b64).replace(/[^A-Za-z0-9+/]/g, '');
  const rem = clean.length % 4; // 尾部未满 4 字符的余位（无 '=' 补齐的流式 base64）
  const body = rem ? clean.slice(0, clean.length - rem) : clean;
  const tail = rem ? clean.slice(clean.length - rem) : '';
  let out = '';
  for (let i = 0; i < body.length; i += 4) {
    const n = (BASE64_CHARS.indexOf(body[i]) << 18) | (BASE64_CHARS.indexOf(body[i + 1]) << 12)
      | ((BASE64_CHARS.indexOf(body[i + 2]) & 63) << 6) | (BASE64_CHARS.indexOf(body[i + 3]) & 63);
    out += String.fromCharCode((n >> 16) & 255, (n >> 8) & 255, n & 255);
  }
  if (tail.length >= 2) {
    const n = (BASE64_CHARS.indexOf(tail[0]) << 18) | (BASE64_CHARS.indexOf(tail[1]) << 12)
      | (tail.length >= 3 ? (BASE64_CHARS.indexOf(tail[2]) & 63) << 6 : 0);
    out += String.fromCharCode((n >> 16) & 255);
    if (tail.length >= 3) out += String.fromCharCode((n >> 8) & 255);
  }
  return out;
}

/**
 * M07-T04 批次#36：工程凭据上传（基-1 票据链）。
 * R1 P1-6 响应丢失重试：同路径文件缓存 {sha, assetId}——重试复用已完成资产，
 * 业务幂等键（deliver 指纹含资产 ID）重放合法，不会「同键不同资产」。
 * R1 P1-2：字节通道走统一 http 封装（自动 Authorization/X-Post-Id/租户头+业务错误解包）。
 * R1 P1-3：complete 响应检查 uploadStatus——REJECTED（SHA_MISMATCH 等）抛 rejectedReason，
 * 拒绝资产不返回 assetId、不进业务调用。取消/超时（未 complete）无有效资产引用，不落业务记录。
 */
// 缓存键含平台组织+资产类型（同路径不同类型须重签票；换组织不复用上一组织的资产）
const voucherCache = new Map(); // 'orgId|assetType|filePath' -> { sha, assetId }
function voucherOrg() {
  try {
    const post = require('../auth').currentApiPost();
    if (post && post.orgId != null && String(post.orgId) !== '') return String(post.orgId);
  } catch (e) {}
  return 'na';
}
async function uploadVoucher(filePath, assetType) {
  if (!filePath) throw { code: 0, msg: '请选择凭据文件' };
  const financeApi = require('../finance');
  const fsData = await financeApi.readFileBase64(filePath);
  const binary = base64ToBinary(fsData);
  const sha = financeApi.sha256Hex(binary); // 原始字节摘要
  const cacheKey = voucherOrg() + '|' + assetType + '|' + filePath;
  const cached = voucherCache.get(cacheKey);
  if (cached && cached.sha === sha) return cached.assetId; // 重试复用（响应丢失场景）
  const stat = wx.getFileSystemManager().statSync(filePath);
  const sizeBytes = stat.size || binary.length;
  const ticket = await assets.uploadTicket(assetType, financeApi.guessMime(filePath), sizeBytes, sha);
  const assetId = ticket.assetId;
  if (ticket.uploadUrl && !/^local:\/\//.test(ticket.uploadUrl) && ticket.uploadUrl.indexOf('/platform/dev/') < 0) {
    // R3 P1-3/20 号契约 §2：生产 COS 签名 URL 原始字节 PUT（dev bytes 仅 local/zsdev profile 存在）
    await new Promise((resolve, reject) => {
      wx.request({
        url: ticket.uploadUrl, method: 'PUT',
        header: { 'content-type': financeApi.guessMime(filePath) },
        data: wx.base64ToArrayBuffer(fsData), // 原始字节（complete 校验 SHA 与申报一致）
        success: r => (r.statusCode >= 200 && r.statusCode < 300 ? resolve()
          : reject({ code: 0, msg: '凭据上传失败（' + r.statusCode + '），请重试' })),
        fail: () => reject({ code: 0, msg: '凭据上传网络失败，请重试' })
      });
    });
  } else {
    await http.post('/platform/dev/design/assets/' + assetId + '/bytes', { contentBase64: fsData });
  }
  const completed = await assets.complete(assetId);
  if (!completed || completed.uploadStatus !== 'ACCEPTED') {
    const reason = (completed && completed.rejectedReason) || '校验未通过';
    throw { code: 0, msg: '凭据校验未通过：' + reason + '，请更换文件后重试' };
  }
  voucherCache.set(cacheKey, { sha, assetId });
  return assetId;
}

module.exports = { projects, plan, materials, site, collections, closure, assets, uploadVoucher, base64ToBinary };
