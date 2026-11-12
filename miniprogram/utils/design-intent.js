/** 设计写意图：先落盘再发送；仅保存摘要、业务键和已确认的资源 ID。 */
const http = require('../api/http');
const auth = require('../api/auth');
const { sha256Hex } = require('../api/finance');

function digest(value) {
  const json = JSON.stringify(value, (_, item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
    const sorted = {};
    Object.keys(item).sort().forEach(key => { sorted[key] = item[key]; });
    return sorted;
  });
  const bytes = encodeURIComponent(json).replace(/%([0-9A-F]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
  return sha256Hex(bytes);
}

function context() {
  const post = auth.currentApiPost();
  if (!http.token() || !post || !post.userId || !post.orgId || !http.currentPostId() ||
      String(post.postId) !== String(http.currentPostId())) {
    throw { code: 'DESIGN_IDENTITY_REQUIRED', silent: true, message: '工作身份已变化，请重新进入设计页' };
  }
  return digest([http.base(), String(http.tenantId()), String(post.orgId), String(post.userId), String(post.postId), post.roleCode]);
}

function storage(action, key, value) {
  try {
    if (action === 'read') return wx.getStorageSync(key);
    if (action === 'write') return wx.setStorageSync(key, value);
    return wx.removeStorageSync(key);
  } catch (err) {
    console.warn('[design-intent] storage failed', action);
    throw { code: 'DESIGN_INTENT_STORAGE', message: '无法保存或恢复原操作，请勿重复提交，稍后重试' };
  }
}

function readRecord(key) {
  const record = storage('read', key);
  if (record && (record.version !== 1 || typeof record.requestKey !== 'string' || !record.requestKey || typeof record.fingerprint !== 'string')) {
    throw { code: 'DESIGN_INTENT_STORAGE', message: '原操作记录异常，请先核对已有方案，暂不重复提交' };
  }
  return record;
}

function saved(action) {
  return readRecord('zs-design-intent-v1:' + context() + ':' + digest(['action', action]));
}

function begin(action, payload, requestKey, referenceAssets) {
  if (requestKey !== undefined && (typeof requestKey !== 'string' || !requestKey.trim() || requestKey.trim() !== requestKey || requestKey.length > 200)) {
    throw { code: 'DESIGN_INTENT_KEY', message: '操作标识无效' };
  }
  const scope = context(), generation = http.sessionGeneration();
  const storageKey = 'zs-design-intent-v1:' + scope + ':' + digest(requestKey === undefined ? ['action', action] : ['key', requestKey]);
  const fingerprint = digest([action, payload]);
  let record = readRecord(storageKey);
  const fresh = !record || (requestKey === undefined && record.rejected === true);
  if (!fresh && record.fingerprint !== fingerprint) {
    throw { code: 'DESIGN_INTENT_CONFLICT', message: '原操作结果尚需确认，请恢复原参数重试，不能用同一操作标识提交不同内容' };
  }
  if (fresh) {
    const previous = record;
    record = { version: 1, requestKey: requestKey === undefined ? 'dsn-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + Math.random().toString(36).slice(2) : requestKey, fingerprint };
    // 终态拒绝后的新操作可复用已确认项目；调用方仍须核对项目输入摘要。
    if (previous && previous.projectId && previous.projectFingerprint) {
      record.projectId = previous.projectId; record.projectFingerprint = previous.projectFingerprint;
    }
    // 引用与首次意图原子落盘，避免两次写入之间退出后无法重建原请求。
    if (Array.isArray(referenceAssets)) {
      record.referenceAssets = referenceAssets.map(ref => ({ sha256: String(ref.sha256), assetId: String(ref.assetId) }));
    }
    storage('write', storageKey, record);
  }
  return { storageKey, record, fresh, explicit: requestKey !== undefined, scope, generation };
}

function assertActive(intent) {
  if (intent.generation !== http.sessionGeneration() || intent.scope !== context()) {
    throw { code: 'DESIGN_SESSION_CHANGED', silent: true, message: '旧身份的设计操作已保留，返回原身份后可重试' };
  }
}

function checkpoint(intent, ids) {
  assertActive(intent);
  const record = Object.assign({}, intent.record);
  // 不接受表单、响应正文或短期签名 URL。
  for (const field of ['projectId', 'assetId', 'projectFingerprint']) if (ids[field] != null) record[field] = String(ids[field]);
  if (['READY', 'UNKNOWN', 'EXPIRED'].includes(ids.uploadState)) record.uploadState = ids.uploadState;
  storage('write', intent.storageKey, record);
  intent.record = record;
}

function complete(intent) {
  assertActive(intent);
  // 显式业务键保留摘要绑定，重启后也不能以同键更换 payload。
  if (!intent.explicit) storage('remove', intent.storageKey);
}

// 只能由 operation 专属、后端已确认的终态拒绝调用，不能按任意错误清理意图。
function reject(intent) {
  assertActive(intent);
  const record = Object.assign({}, intent.record, { rejected: true });
  storage('write', intent.storageKey, record);
  intent.record = record;
}

module.exports = { begin, saved, checkpoint, complete, reject, assertActive, context, digest };
