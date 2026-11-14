import { createHmac, timingSafeEqual } from 'node:crypto';
import { sha, safeFailureCode } from './runtime.mjs';

// 后端 → ai-runtime 的内部入口（方案 A）：与 runtime → 后端 同一套 HMAC，共用 ZS_INTERNAL_SECRET：
//   HMAC-SHA256(secret, "{timestamp}\n{METHOD}\n{path}\n{sha256(body)}")，头 X-ZS-Timestamp / X-ZS-Signature。
// 这里只承载补充需求润色；出图仍是 runtime 主动领任务，本文件不改变既有出图链路。
export const NOTE_POLISH_PATH = '/internal/note-polish';
export const MAX_BODY_BYTES = 4096;
const REPLAY_WINDOW_SECONDS = 300;
const MAX_NOTE = 200;

export function verifySignature({ secret, method, path, headers, body, now = Date.now() }) {
  const timestamp = headers['x-zs-timestamp'], signature = headers['x-zs-signature'];
  if (!secret || typeof timestamp !== 'string' || typeof signature !== 'string' || !/^\d{1,12}$/.test(timestamp)) return false;
  if (Math.abs(Math.floor(now / 1000) - Number(timestamp)) > REPLAY_WINDOW_SECONDS) return false;
  const expected = createHmac('sha256', secret).update(`${timestamp}\n${method}\n${path}\n${sha(body)}`).digest();
  const actual = Buffer.from(signature, 'hex');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

const reply = (status, body) => ({ status, body });

/**
 * 返回 async (request) => { status, body }。request = { method, path, headers(小写键), body: Buffer }。
 * 纯函数式处理便于不起 socket 测试；main.mjs 负责读取（≤4KB）并转发。
 */
export function createInternalHandler({ settings, provider, journal, maxConcurrent = 4, log = () => {} }) {
  let active = 0;
  return async function handle({ method, path, headers, body }) {
    if (path !== NOTE_POLISH_PATH) return reply(404, { error: 'NOT_FOUND' });
    if (method !== 'POST') return reply(405, { error: 'METHOD_NOT_ALLOWED' });
    // 先验签再解析：未通过签名的请求不触碰任何业务逻辑，也不暴露校验细节
    if (!verifySignature({ secret: settings.secret, method, path, headers: headers || {}, body })) {
      return reply(401, { error: 'UNAUTHORIZED' });
    }
    let input;
    try { input = JSON.parse(body.toString('utf8')); } catch { return reply(400, { error: 'BAD_REQUEST' }); }
    const note = typeof input?.note === 'string' ? input.note.trim() : '';
    const line = input?.line === undefined ? 'FLAT' : input.line;
    const receiptId = input?.receiptId;
    if (!note || note.length > MAX_NOTE || !['FLAT', 'ELEVATION'].includes(line)
        || typeof receiptId !== 'string' || !/^[a-zA-Z0-9:_-]{1,160}$/.test(receiptId)) return reply(400, { error: 'BAD_REQUEST' });
    const storageUnavailable = settings.capacityGuard ? !journal.capacityHealthy?.() : journal.healthy && !journal.healthy();
    if (storageUnavailable) {
      return reply(503, { error: 'STORAGE_UNAVAILABLE' });
    }
    if (active >= maxConcurrent) return reply(429, { error: 'BUSY' });
    active++;
    try {
      const polished = await journal.onceReceipt(receiptId, sha(body),
        capacity => { capacity.assertReady(); return provider.polishNote(note, line, AbortSignal.timeout(25000)); },
        settings.capacityGuard ? 256 * 1024 : 0);
      return reply(200, { polished });
    } catch (error) {
      if (error.message === 'RECEIPT_CONFLICT') return reply(409, { error: 'RECEIPT_CONFLICT' });
      if (error.message === 'PROVIDER_OUTCOME_UNCERTAIN') return reply(409, { error: 'OUTCOME_UNKNOWN' });
      // 日志也只记录白名单错误码，不留上游正文、用户需求或凭据回显。
      log({ event: 'note_polish_failed', reason: safeFailureCode(error) });
      return reply(502, { error: 'PROVIDER_FAILED' });
    } finally { active--; }
  };
}

/**
 * 读取请求体，超过上限返回 null（调用方回 413）。超限后不再缓存、只排空到 64KB 为止，
 * 超过硬上限才断开连接——直接 destroy 会让客户端只看到连接重置而拿不到 413。
 */
function readBody(request, limit) {
  const HARD_LIMIT = 64 * 1024;
  return new Promise(resolve => {
    const chunks = []; let size = 0, overflow = false, done = false;
    const finish = value => { if (!done) { done = true; resolve(value); } };
    request.on('data', chunk => {
      size += chunk.length;
      if (size > limit) overflow = true;
      if (!overflow) chunks.push(chunk);
      if (size > HARD_LIMIT) { finish(null); request.destroy(); }
    });
    request.on('end', () => finish(overflow ? null : Buffer.concat(chunks)));
    request.on('error', () => finish(null));
  });
}

/**
 * node:http 的请求监听器：/health 保持原有语义（healthy() 为真 200，否则 503），
 * 润色走 internal；其余一律 404。main.mjs 只负责把存活判断与依赖传进来。
 */
export function createRequestListener({ internal, healthy, version }) {
  return async (request, response) => {
    const send = (status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body)); };
    if (request.url === '/health') {
      const up = healthy();
      return send(up ? 200 : 503, { status: up ? 'UP' : 'DOWN', version });
    }
    if (request.url !== NOTE_POLISH_PATH) { response.writeHead(404); response.end(); return; }
    const body = await readBody(request, MAX_BODY_BYTES);
    if (body === null) { response.setHeader('Connection', 'close'); return send(413, { error: 'PAYLOAD_TOO_LARGE' }); }
    const result = await internal({ method: request.method, path: request.url,
      headers: request.headers, body });
    return send(result.status, result.body);
  };
}
