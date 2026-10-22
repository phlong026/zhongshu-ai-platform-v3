const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');

const { bootstrapStorage, copy, createRuntime } = require('./domain-runtime.cjs');
const { acquireDataLock, atomicWrite, createStore } = require('./store.cjs');

const SESSION_LIFETIME = 24 * 60 * 60 * 1000;
const TRANSIENT_CODES = new Set([429, 500, 502, 503, 504]);
const TERMINAL_CODES = new Set([43101, 40003, 40037]);
const GATEWAY_TIMEOUT_MS = 2000;

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const jsonHash = value => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
const tokenHash = token => crypto.createHash('sha256').update(token).digest('hex');
const randomToken = () => crypto.randomBytes(32).toString('base64url');
const roleFor = userId => userId === 'hq-design-admin' ? 'hq-design-admin' : userId.startsWith('hq-finance') ? 'finance' : userId === 'sales-dual' ? 'sales-lead' : '';
const reply = (response, status, body) => {
  if (response.writableEnded) return;
  const data = JSON.stringify(body);
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(data), 'cache-control': 'no-store' });
  response.end(data);
};
const log = (level, event, fields = {}) => {
  const safe = Object.fromEntries(Object.entries(fields).filter(([key]) => ['requestId', 'jobId', 'code', 'route'].includes(key)));
  const line = JSON.stringify({ level, event, ...safe });
  (level === 'error' ? console.error : console.warn)(line);
};
const withTimeout = (promise, milliseconds, code) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => {
    const error = Error('模拟网关调用超时');
    error.code = code;
    reject(error);
  }, milliseconds);
  Promise.resolve(promise).then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
});

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 128 * 1024) throw new HttpError(413, 'request_too_large', '请求内容过大');
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch (_) { throw new HttpError(400, 'invalid_json', '请求内容不是有效JSON'); }
}

function actorFor(runtime, userId) {
  let actor;
  try { actor = runtime.login(userId, roleFor(userId)); }
  catch (error) {
    if (/停用|成员资格已撤回/.test(String(error.message || error))) {
      throw new HttpError(403, 'actor_inactive', '当前测试人员已停用或岗位已变化');
    }
    throw error;
  }
  if (!actor.active || actor.role !== roleFor(userId)) throw new HttpError(403, 'actor_inactive', '当前测试人员已停用或岗位已变化');
  return {
    name: actor.name, role: actor.role, companyId: actor.companyId,
    staffId: actor.staffId, personId: actor.personId || actor.staffId
  };
}

function currentActor(state, session) {
  const runtime = createRuntime(copy(state.storage));
  return actorFor(runtime, session.userId);
}

function receiptTasks(runtime, state) {
  const M = runtime.requireMini('pages/admin/model.js');
  return M.load().receipts
    .filter(row => row.status === '待登记' && row.source?.protocol === 'sales-offline-v1')
    .map(row => ({
      id: row.id, kind: 'receipt', customerId: row.source.customerId, name: row.customer,
      stage: row.source.stage, amount: row.amount,
      taskVersion: jsonHash([state.secret, row.id, row.source, row.amount])
    }));
}

function customerRows(runtime) {
  const B = runtime.requireMini('utils/business.js');
  const SF = runtime.requireMini('utils/sales-flow.js');
  return B.load().customers.filter(customer => customer.businessFlow?.type === 'sales-offline-v1').map(customer => {
    const raw = SF.raw(customer);
    const next = SF.next(raw);
    return {
      id: raw.id, name: raw.name, city: raw.city || '', state: next.state,
      scope: raw.request?.scope || '', requirement: raw.request?.text || '',
      requestVersion: raw.request?.version || 0, serviceVersion: raw.serviceOrders?.at(-1)?.version || 0,
      revision: raw.revision || 0, action: ['complete', 'dispatch'].includes(next.view) && next.actionable ? next.view : '',
      completedAt: raw.businessFlow.completed?.at || '', dispatchedAt: raw.businessFlow.dispatch?.at || '',
      recipient: raw.businessFlow.dispatch?.recipient || '', dispatchNote: raw.businessFlow.dispatch?.note || ''
    };
  });
}

function adminTasks(runtime) {
  return runtime.requireMini('utils/sales-flow.js').tasks()
    .filter(task => task.actionable && ['设计完成', '成果已下发'].includes(task.action))
    .map(task => ({
      id: task.id, kind: 'design', customerId: task.customerId, name: task.name,
      action: task.action === '设计完成' ? 'complete' : 'dispatch'
    }));
}

function messages(runtime) {
  const A = runtime.requireMini('utils/account.js');
  const account = A.load();
  return A.scoped(account, account.messages).filter(row => row.derived).map(row => ({
    id: row.id, customerId: row.objectId, title: row.title, description: row.description,
    read: !!row.read, at: row.date || ''
  }));
}

function recent(runtime) {
  return runtime.requireMini('utils/design-admin-workbench.js').load().recent.map(row => ({
    customerId: row.id, name: row.name, action: row.recentText, at: row.recentAt
  }));
}

function notificationPage(message) {
  return `/pages/collaboration/index?customerId=${encodeURIComponent(message.customerId)}`;
}

function projectNotificationState(state) {
  let runtime;
  try { runtime = createRuntime(copy(state.storage)); }
  catch (_) { throw new HttpError(500, 'notification_projection_failed', '通知投影失败，到账事务已回滚'); }
  let actor;
  try {
    actor = actorFor(runtime, 'hq-design-admin');
  } catch (error) {
    if (!(error instanceof HttpError) || error.code !== 'actor_inactive') throw error;
    return { actor: null, visible: [], error: { code: 'recipient_unavailable', at: new Date().toISOString() } };
  }
  let visible;
  try { visible = messages(runtime).filter(message => /可下发/.test(message.title)); }
  catch (_) { throw new HttpError(500, 'notification_projection_failed', '通知投影失败，到账事务已回滚'); }
  return { actor, visible, error: null };
}

function syncOutbox(state) {
  const { actor, visible, error } = projectNotificationState(state);
  state.outbox = state.outbox || [];
  if (error) {
    if (state.outboxDiagnostics?.code !== error.code) log('warn', 'notification_recipient_unavailable', { code: error.code });
    state.outboxDiagnostics = error;
    const subscription = state.subscriptions?.['hq-design-admin'];
    if (subscription) { subscription.consent = 'none'; subscription.credits = 0; }
    for (const job of state.outbox) if (job.actorStaffId === 'hq-design-admin' && !['delivered', 'failed', 'cancelled'].includes(job.status)) {
      job.status = 'recipient-unavailable';
      job.lastError = '当前无在岗设计行政接收人';
    }
    return;
  }
  state.outboxDiagnostics = { code: 'ok', at: new Date().toISOString() };
  for (const message of visible) {
    const eventId = message.id;
    const existing = state.outbox.find(job => job.eventId === eventId);
    if (existing?.status === 'recipient-unavailable') {
      existing.status = 'waiting-consent';
      existing.lastError = '';
    } else if (!existing) state.outbox.push({
      id: `mock-wechat:${jsonHash(eventId).slice(0, 24)}`, eventId, actorStaffId: actor.staffId,
      customerId: message.customerId, title: message.title, description: message.description,
      page: notificationPage(message), status: 'waiting-consent', attempts: 0, lastError: '', nextAttemptAt: ''
    });
  }
  const active = new Set(visible.map(message => message.id));
  for (const job of state.outbox) {
    if (job.actorStaffId === actor.staffId && !active.has(job.eventId) && !['delivered', 'failed', 'cancelled'].includes(job.status)) {
      job.status = 'cancelled';
      job.lastError = '当前业务状态已不再允许下发';
    }
  }
}

function snapshotFor(state, session) {
  const runtime = createRuntime(copy(state.storage));
  const actor = actorFor(runtime, session.userId);
  const projects = customerRows(runtime);
  const tasks = actor.role === 'finance' ? receiptTasks(runtime, state) : actor.role === 'hq-design-admin' ? adminTasks(runtime) : [];
  const ownMessages = actor.role === 'hq-design-admin' ? messages(runtime) : [];
  const ownRecent = actor.role === 'hq-design-admin' ? recent(runtime) : [];
  const subscription = state.subscriptions?.[actor.staffId] || { consent: 'none', credits: 0 };
  const ownJobs = (state.outbox || []).filter(job => job.actorStaffId === actor.staffId);
  const deliveries = (state.deliveries || []).filter(delivery => delivery.actorStaffId === actor.staffId).map(delivery => ({
    id: delivery.id, customerId: delivery.customerId, title: delivery.title,
    description: delivery.description, page: delivery.page, sentAt: delivery.sentAt, status: delivery.status
  }));
  return {
    mode: 'simulated-cross-device', revision: state.revision, actor, projects, tasks, messages: ownMessages, recent: ownRecent,
    wechat: {
      mode: 'mock', consent: subscription.consent, deliveries,
      jobs: ownJobs.map(job => ({ id: job.id, customerId: job.customerId, status: job.status, lastError: job.lastError, attempts: job.attempts }))
    }
  };
}

function domainError(error) {
  if (error instanceof HttpError) return error;
  if (error?.status >= 500) return new HttpError(error.status, error.code || 'persistence_failed', error.message || '模拟状态保存失败');
  const message = String(error.message || error);
  const forbidden = /权限|授权|岗位|人员|组织|本人|登录/.test(message);
  return new HttpError(forbidden ? 403 : 400, forbidden ? 'forbidden' : 'business_rule', message);
}

async function createSimulationServer({ dataFile, invites, gateway, now = () => new Date(), persist } = {}) {
  if (!dataFile || !path.isAbsolute(dataFile)) throw Error('dataFile必须是绝对路径');
  if (!invites || !Object.keys(invites).length) throw Error('请配置测试邀请码');
  const allowedUsers = new Set(['hq-finance', 'hq-finance-2', 'hq-design-admin', 'sales-dual']);
  for (const userId of Object.values(invites)) if (!allowedUsers.has(userId)) throw Error(`不支持的模拟身份：${userId}`);
  const fixture = bootstrapStorage();
  const dataLock = await acquireDataLock(dataFile);
  const initial = {
    schemaVersion: 1, mode: 'simulated-cross-device', revision: 1,
    secret: crypto.randomBytes(32).toString('hex'), storage: fixture.storage, fixtureCustomerId: fixture.customerId,
    sessions: [], idempotency: {}, subscriptions: {}, outbox: [], deliveries: []
  };
  const store = createStore(dataFile, initial, persist);
  try { await store.initialize(); }
  catch (error) { await dataLock.release(); throw error; }
  const gatewayFile = `${dataFile}.mock-gateway.json`;
  const deliveredEvents = new Set();
  try {
    if (!gateway && fs.existsSync(gatewayFile)) {
      const ledger = JSON.parse(await fs.promises.readFile(gatewayFile, 'utf8'));
      for (const eventId of ledger.events || []) deliveredEvents.add(eventId);
    }
  } catch (error) { await dataLock.release(); throw error; }
  // Injected gateways receive a stable eventId and must provide the same idempotency guarantee.
  const mockGateway = gateway || {
    async send(message) {
      if (deliveredEvents.has(message.eventId)) return { status: 'duplicate' };
      await atomicWrite(gatewayFile, { mode: 'mock', events: [...deliveredEvents, message.eventId] });
      deliveredEvents.add(message.eventId);
      return { status: 'delivered' };
    }
  };
  let httpServer;
  let pumping = false;
  let activePump = null;
  let pumpRequested = false;
  let retryTimer = null;
  let stopping = false;

  async function authenticate(request) {
    const value = String(request.headers.authorization || '');
    if (!value.startsWith('Bearer ')) throw new HttpError(401, 'authentication_required', '请先使用测试邀请码登录');
    const state = store.read();
    const hash = tokenHash(value.slice(7));
    const session = state.sessions.find(row => row.tokenHash === hash && Date.parse(row.expiresAt) > now().getTime());
    if (!session) throw new HttpError(401, 'invalid_session', '测试会话不存在或已过期');
    currentActor(state, session);
    return { state, session };
  }

  function schedulePump(delay = 0) {
    if (stopping) return;
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = setTimeout(() => {
      retryTimer = null;
      if (activePump) { pumpRequested = true; return; }
      activePump = pump()
        .catch(error => log('error', 'outbox_pump_failed', { code: error.code || 'internal_error' }))
        .finally(() => {
          activePump = null;
          if (pumpRequested && !stopping) { pumpRequested = false; schedulePump(); }
        });
    }, delay);
  }

  async function pump() {
    if (pumping || stopping) return;
    pumping = true;
    try {
      while (!stopping) {
        const state = store.read();
        const timestamp = now().toISOString();
        const candidate = (state.outbox || []).find(job =>
          ['waiting-consent', 'pending'].includes(job.status) &&
          (!job.nextAttemptAt || job.nextAttemptAt <= timestamp) &&
          state.subscriptions?.[job.actorStaffId]?.consent === 'accepted' &&
          (state.subscriptions?.[job.actorStaffId]?.credits || 0) > 0
        );
        if (!candidate) break;
        const claim = await store.transact(draft => {
          syncOutbox(draft);
          const job = draft.outbox.find(row => row.id === candidate.id);
          const subscription = draft.subscriptions?.[job?.actorStaffId];
          if (!job || !['waiting-consent', 'pending'].includes(job.status) || subscription?.consent !== 'accepted' || !subscription.credits) return null;
          job.status = 'sending';
          job.attempts += 1;
          job.lastError = '';
          job.nextAttemptAt = '';
          draft.revision += 1;
          return copy(job);
        });
        if (!claim) continue;
        let gatewayResult;
        try {
          gatewayResult = await withTimeout(mockGateway.send({
            eventId: claim.eventId, customerId: claim.customerId, title: claim.title,
            description: claim.description, page: claim.page, mock: true
          }), GATEWAY_TIMEOUT_MS, 504);
          if (!['delivered', 'duplicate'].includes(gatewayResult?.status)) {
            const error = Error(gatewayResult?.message || '模拟网关未确认送达');
            error.code = gatewayResult?.code || gatewayResult?.errcode || gatewayResult?.status || 500;
            throw error;
          }
        } catch (error) {
          const parsedCode = Number(error.code || error.status || 500);
          const code = Number.isFinite(parsedCode) && parsedCode > 0 ? parsedCode : 500;
          await store.transact(draft => {
            const job = draft.outbox.find(row => row.id === claim.id);
            if (!job || job.status !== 'sending') return;
            job.lastError = String(error.message || '模拟网关失败');
            if (TRANSIENT_CODES.has(code) && job.attempts < 3) {
              job.status = 'pending';
              job.nextAttemptAt = new Date(now().getTime() + [25, 75, 150][job.attempts - 1]).toISOString();
            } else {
              job.status = 'failed';
              if (TERMINAL_CODES.has(code)) job.lastError = `${code}: ${job.lastError}`;
            }
            draft.revision += 1;
          });
          log(TERMINAL_CODES.has(code) ? 'warn' : 'error', 'mock_gateway_failed', { jobId: claim.id, eventId: claim.eventId, code });
          const latest = store.read().outbox.find(row => row.id === claim.id);
          if (latest?.status === 'pending') { schedulePump(Math.max(1, Date.parse(latest.nextAttemptAt) - now().getTime())); break; }
          continue;
        }
        await store.transact(draft => {
          syncOutbox(draft);
          const job = draft.outbox.find(row => row.id === claim.id);
          const subscription = draft.subscriptions?.[claim.actorStaffId];
          if (!job || job.status !== 'sending') return;
          if (subscription?.consent !== 'accepted' || !subscription.credits) {
            job.status = 'waiting-consent';
            job.lastError = '模拟订阅授权已撤销';
            draft.revision += 1;
            return;
          }
          job.status = 'delivered';
          subscription.credits -= 1;
          if (!draft.deliveries.some(row => row.eventId === job.eventId)) draft.deliveries.push({
            id: `mock-delivery:${jsonHash(job.eventId).slice(0, 24)}`, eventId: job.eventId,
            actorStaffId: job.actorStaffId, customerId: job.customerId, title: job.title,
            description: job.description, page: job.page, sentAt: now().toISOString(), status: 'mock-delivered'
          });
          draft.revision += 1;
        });
      }
    } finally { pumping = false; }
  }

  async function sessionResponse(body) {
    const code = String(body.inviteCode || '');
    const userId = Object.prototype.hasOwnProperty.call(invites, code) ? invites[code] : '';
    if (!userId) throw new HttpError(403, 'invalid_invite', '测试邀请码无效');
    const token = randomToken();
    const expiresAt = new Date(now().getTime() + SESSION_LIFETIME).toISOString();
    let actor;
    let revision;
    await store.transact(state => {
      const runtime = createRuntime(copy(state.storage));
      actor = actorFor(runtime, userId);
      state.sessions.push({ tokenHash: tokenHash(token), userId, expiresAt });
      state.revision += 1;
      revision = state.revision;
    });
    return { accessToken: token, expiresAt, mode: 'simulated-cross-device', actor, revision };
  }

  async function runCommand(request, route, body, session, change) {
    const key = String(request.headers['idempotency-key'] || '').trim();
    if (!key) throw new HttpError(400, 'idempotency_required', '缺少Idempotency-Key');
    if (!Number.isSafeInteger(body.expectedRevision)) throw new HttpError(400, 'revision_required', '请提交expectedRevision');
    const identity = `${session.userId}:${request.method}:${route}:${key}`;
    const payloadHash = jsonHash(body);
    const before = store.read();
    const existing = before.idempotency[identity];
    if (existing) {
      if (existing.payloadHash !== payloadHash) throw new HttpError(409, 'idempotency_conflict', '同一幂等键不能用于不同请求');
      return { ...copy(existing.response), duplicate: true };
    }
    if (before.revision !== body.expectedRevision) throw new HttpError(409, 'revision_conflict', '数据已更新，请刷新后重试');
    const response = await store.transact(state => {
      const concurrentDuplicate = state.idempotency[identity];
      if (concurrentDuplicate) {
        if (concurrentDuplicate.payloadHash !== payloadHash) throw new HttpError(409, 'idempotency_conflict', '同一幂等键不能用于不同请求');
        return { ...copy(concurrentDuplicate.response), duplicate: true };
      }
      if (state.revision !== body.expectedRevision) throw new HttpError(409, 'revision_conflict', '数据已更新，请刷新后重试');
      const runtime = createRuntime(state.storage);
      const actor = actorFor(runtime, session.userId);
      const result = change(runtime, state, actor);
      syncOutbox(state);
      state.revision += 1;
      const value = { revision: state.revision, duplicate: false, result: result || {} };
      state.idempotency[identity] = { payloadHash, response: value };
      return value;
    });
    schedulePump();
    return response;
  }

  async function routeRequest(request, response) {
    const url = new URL(request.url, 'http://localhost');
    const route = url.pathname;
    if (request.method === 'POST' && route === '/sim/v1/sessions') return reply(response, 200, await sessionResponse(await readJson(request)));
    const { session } = await authenticate(request);
    if (request.method === 'GET' && route === '/sim/v1/snapshot') return reply(response, 200, snapshotFor(store.read(), session));
    if (request.method !== 'POST') throw new HttpError(404, 'not_found', '接口不存在');
    const body = await readJson(request);

    let match = route.match(/^\/sim\/v1\/receipts\/(.+)\/confirm$/);
    if (match) {
      const id = decodeURIComponent(match[1]);
      const value = await runCommand(request, route, body, session, (runtime, state, actor) => {
        if (actor.role !== 'finance') throw new HttpError(403, 'forbidden', '仅总部财务可确认到账');
        const M = runtime.requireMini('pages/admin/model.js');
        const tasks = receiptTasks(runtime, state);
        const task = tasks.find(row => row.id === id);
        if (!task || task.taskVersion !== body.taskVersion) throw new HttpError(409, 'task_changed', '到账任务已变化，请刷新');
        if (body.proofRef !== 'mock-bank-receipt') throw new HttpError(400, 'invalid_mock_proof', '模拟联调仅接受mock-bank-receipt凭据');
        const book = M.load();
        const row = book.receipts.find(receipt => receipt.id === id && receipt.status === '待登记');
        if (!row) throw new HttpError(409, 'task_changed', '到账任务已办理，请刷新');
        return M.mutate(next => M.apply(next, 'incoming-register', id, {
          amount: body.amount, date: body.date, transaction: body.transaction,
          file: { name: '模拟银行到账凭据.pdf', sample: true }, checked: body.checked,
          note: body.note || '模拟跨端到账确认', expectedSource: JSON.stringify(row.source), expectedAmount: String(row.amount)
        }));
      });
      return reply(response, 200, value);
    }

    match = route.match(/^\/sim\/v1\/commissions\/(.+)\/(complete|dispatch)$/);
    if (match) {
      const id = decodeURIComponent(match[1]);
      const action = match[2];
      const value = await runCommand(request, route, body, session, (runtime, _state, actor) => {
        if (actor.role !== 'hq-design-admin') throw new HttpError(403, 'forbidden', '仅总部设计行政可办理');
        const SF = runtime.requireMini('utils/sales-flow.js');
        const raw = SF.raw({ id });
        if (!raw?.id) throw new HttpError(404, 'not_found', '设计委托不存在');
        if (action === 'complete') return SF.run(id, 'design-complete', { checked: body.checked, note: body.note || '' }, raw.revision);
        return SF.run(id, 'design-dispatch', { checked: body.checked, recipient: body.recipient, note: body.note }, raw.revision);
      });
      return reply(response, 200, value);
    }

    if (route === '/sim/v1/subscriptions') {
      const value = await runCommand(request, route, body, session, (_runtime, state, actor) => {
        if (actor.role !== 'hq-design-admin') throw new HttpError(403, 'forbidden', '仅行政测试账号可模拟订阅授权');
        state.subscriptions = state.subscriptions || {};
        const current = state.subscriptions[actor.staffId] || { consent: 'none', credits: 0 };
        current.consent = body.accepted === true ? 'accepted' : 'rejected';
        if (body.accepted === true) current.credits += 1;
        else current.credits = 0;
        state.subscriptions[actor.staffId] = current;
        return { consent: current.consent };
      });
      return reply(response, 200, value);
    }

    match = route.match(/^\/sim\/v1\/messages\/(.+)\/read$/);
    if (match) {
      const id = decodeURIComponent(match[1]);
      const value = await runCommand(request, route, body, session, (runtime, _state, actor) => {
        if (actor.role !== 'hq-design-admin') throw new HttpError(403, 'forbidden', '当前岗位无权读取该消息');
        const A = runtime.requireMini('utils/account.js');
        return A.mutate(account => A.markMessageRead(account, id));
      });
      return reply(response, 200, value);
    }
    throw new HttpError(404, 'not_found', '接口不存在');
  }

  httpServer = http.createServer((request, response) => {
    const requestId = crypto.randomBytes(6).toString('hex');
    request.setTimeout(5000, () => {
      if (!response.headersSent) reply(response, 408, { error: { code: 'request_timeout', message: '请求处理超时' } });
      request.destroy();
    });
    routeRequest(request, response).catch(error => {
      const safe = domainError(error);
      if ((safe.status || 500) >= 500) log('error', 'http_request_failed', { requestId, route: request.url.split('?')[0], code: safe.code || 'internal_error' });
      reply(response, safe.status || 500, { error: { code: safe.code || 'internal_error', message: safe.status ? safe.message : '模拟服务处理失败' } });
    });
  });
  httpServer.requestTimeout = 5000;
  httpServer.headersTimeout = 6000;

  try {
    for (const job of store.read().outbox || []) if (job.status === 'sending') await store.transact(state => {
      const row = state.outbox.find(item => item.id === job.id);
      if (row?.status === 'sending') row.status = 'pending';
    });
  } catch (error) { await dataLock.release(); throw error; }
  schedulePump();

  return {
    listen(port = 0, host = '127.0.0.1') {
      return new Promise((resolve, reject) => {
        const failed = async error => { httpServer.off('error', failed); await dataLock.release(); reject(error); };
        httpServer.once('error', failed);
        httpServer.listen(port, host, () => { httpServer.off('error', failed); resolve(this.address()); });
      });
    },
    async close() {
      stopping = true;
      if (retryTimer) clearTimeout(retryTimer);
      if (httpServer.listening) await new Promise((resolve, reject) => httpServer.close(error => error ? reject(error) : resolve()));
      if (activePump) await activePump;
      await store.idle();
      await dataLock.release();
    },
    address() { return httpServer.address(); },
    service: {
      snapshot: () => store.read(),
      mutateFixture: (change, refreshOutbox = true) => store.transact(state => {
        change(state);
        state.revision += 1;
        if (refreshOutbox) syncOutbox(state);
      })
    }
  };
}

module.exports = { createSimulationServer };
