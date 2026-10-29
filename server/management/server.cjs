const crypto = require('node:crypto');
const http = require('node:http');
const path = require('node:path');
const { createRuntime, bootstrapStorage, copy } = require('../design-collaboration/domain-runtime.cjs');
const { createStore, acquireDataLock } = require('../design-collaboration/store.cjs');

const MODE = 'simulated-cross-device';
const SESSION_TTL = 12 * 60 * 60 * 1000;
const COMMANDS = new Set([
  'goals', 'company', 'assignments', 'operating-snapshot',
  'commission-policy', 'commission-release', 'commission-reconcile'
]);

class HttpError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const bodyHash = value => hash(JSON.stringify(value));
const operationKey = (actor, key) => hash([actor.companyId, actor.personId, actor.staffId, key].join('\0'));
const token = () => crypto.randomBytes(32).toString('base64url');
const actorView = actor => ({
  name: actor.name || actor.staffId || '', role: actor.role, personId: actor.personId,
  staffId: actor.staffId, companyId: actor.companyId, orgType: actor.orgType, active: actor.active !== false
});

function reply(response, status, data) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(data));
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let text = '';
    request.on('data', chunk => {
      text += chunk;
      if (text.length > 1024 * 1024) request.destroy(new HttpError(413, 'PAYLOAD_TOO_LARGE', '请求内容过大'));
    });
    request.on('end', () => {
      try { resolve(text ? JSON.parse(text) : {}); }
      catch (_) { reject(new HttpError(400, 'INVALID_JSON', '请求内容不是有效 JSON')); }
    });
    request.on('error', reject);
  });
}

function asHttpError(error) {
  if (error instanceof HttpError || error.status) return error;
  const message = error?.message || '模拟管理服务处理失败';
  if (/无权|权限|授权|身份|本人|本组织|公司或人员|账号已停用/.test(message)) return new HttpError(403, 'FORBIDDEN', message);
  return new HttpError(400, 'INVALID_COMMAND', message);
}

function sessionActor(state, rawToken) {
  if (!rawToken) throw new HttpError(401, 'UNAUTHORIZED', '请先接入模拟管理会话');
  const record = (state.sessions || []).find(row => row.tokenHash === hash(rawToken));
  if (!record || Date.parse(record.expiresAt) <= Date.now()) throw new HttpError(401, 'SESSION_EXPIRED', '模拟管理会话已过期，请重新接入');
  const runtime = createRuntime(copy(state.storage));
  let actor;
  try { actor = runtime.login(record.userId); }
  catch (error) { throw new HttpError(403, 'ACTOR_DISABLED', error.message || '当前测试账号不可用'); }
  if (!actor.active) throw new HttpError(403, 'ACTOR_DISABLED', '当前测试账号已停用');
  if (!record.staffId || !record.personId || actor.companyId !== record.companyId || actor.staffId !== record.staffId || actor.personId !== record.personId) throw new HttpError(403, 'ACTOR_CHANGED', '测试账号所属组织或人员已变化，请重新接入');
  return { record, runtime, actor };
}

function bearer(request) {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.authorization || '');
  return match?.[1] || '';
}

function managementView(runtime, actor) {
  if (!['boss', 'owner'].includes(actor.role)) return null;
  const W = runtime.requireMini('pages/admin/owner-model.js');
  const snapshot = W.snapshot();
  const home = runtime.requireMini('pages/admin/owner-home.js').home(snapshot);
  const business = runtime.requireMini('pages/admin/owner-business.js').business(snapshot);
  const performanceModel = runtime.requireMini('pages/admin/owner-performance.js');
  const performance = performanceModel.build(snapshot);
  const goalForm = performanceModel.formFor(snapshot);
  const settings = runtime.requireMini('pages/admin/owner-settings.js').load(snapshot.state);
  const commissionRows = runtime.requireMini('utils/collection-commission.js').rows(snapshot.state).map(row => ({
    id: row.id, projectId: row.projectId, project: row.project, customer: row.customer,
    total: row.total, assigneeId: row.assigneeId, assignee: row.assignee, version: row.version,
    stages: row.stages.map(stage => ({
      stage: stage.stage, label: stage.label, amount: stage.amount, required: stage.required,
      received: stage.received, eligible: stage.eligible, paid: stage.paid,
      outstanding: stage.outstanding, reviewDue: stage.reviewDue, status: stage.status
    }))
  }));
  const projectMap = new Map();
  for (const project of snapshot.projects.filter(row => row.recordFlow)) {
    const id = project.projectId || project.id;
    if (id && !projectMap.has(id)) projectMap.set(id, { id, name: project.name || project.customer || id });
  }
  const commission = {
    canEdit: actor.role === 'boss',
    projects: [...projectMap.values()],
    staff: snapshot.staff.filter(row => row.active !== false && row.personId).map(row => ({ id: row.id, name: row.name })),
    rows: commissionRows
  };
  return { period: snapshot.period, goals: { ...copy(snapshot.goals), departmentGoals:goalForm.departmentGoals, staffDepartmentGoals:goalForm.staffDepartmentGoals }, home: copy(home), business: copy(business), performance: copy(performance), settings: copy(settings), commission: copy(commission) };
}

function snapshotView(state, rawToken) {
  const { runtime, actor } = sessionActor(state, rawToken);
  return { mode: MODE, revision: state.revision, actor: actorView(actor), tasks: [], management: managementView(runtime, actor) };
}

function applyCommand(runtime, actor, input) {
  if (!['boss', 'owner'].includes(actor.role)) throw new HttpError(403, 'FORBIDDEN', '当前岗位不能办理管理模拟操作');
  if (input.companyId && input.companyId !== actor.companyId) throw new HttpError(403, 'FORBIDDEN', '不能办理其他组织的管理数据');
  if (input.kind === 'goals') {
    runtime.requireMini('pages/admin/owner-model.js').saveGoals(input.period, input.form || {});
    return { id: 'goals:' + input.period };
  }
  const M = runtime.requireMini('pages/admin/model.js');
  const action = input.kind === 'company' ? 'management-company' : input.kind === 'assignments' ? 'management-assignments' : input.kind;
  const id = input.id || actor.companyId;
  const result = M.mutate(state => M.apply(state, action, id, input.form || {}));
  if (!result) throw Error('当前管理动作尚未接入领域模型');
  return typeof result === 'object' ? copy(result) : { id: result };
}

async function createManagementSimulation(options = {}) {
  if (!path.isAbsolute(options.dataFile || '')) throw Error('dataFile 必须是绝对路径');
  const invites = { ...(options.invites || {}) };
  if (!Object.keys(invites).length) throw Error('至少配置一个私有测试接入码');
  const initial = {
    schemaVersion: 1, mode: MODE, revision: 1,
    storage: copy(options.initialStorage || bootstrapStorage().storage), sessions: [], idempotency: {}
  };
  const lock = await acquireDataLock(options.dataFile);
  const store = createStore(options.dataFile, initial, options.persist);
  let server;
  try { await store.initialize(); }
  catch (error) { await lock.release(); throw error; }

  async function createSession(inviteCode) {
    const userId = invites[String(inviteCode || '')];
    if (!userId) throw new HttpError(403, 'INVALID_INVITE', '测试接入码无效');
    const runtime = createRuntime(copy(store.read().storage));
    let actor;
    try { actor = runtime.login(userId); }
    catch (error) { throw new HttpError(403, 'ACTOR_DISABLED', error.message || '测试账号不可用'); }
    if (!actor.active) throw new HttpError(403, 'ACTOR_DISABLED', '当前测试账号已停用');
    const accessToken = token();
    const expiresAt = new Date(Date.now() + SESSION_TTL).toISOString();
    await store.transact(state => {
      state.sessions = (state.sessions || []).filter(row => Date.parse(row.expiresAt) > Date.now());
      state.sessions.push({ tokenHash: hash(accessToken), userId, staffId: actor.staffId, personId: actor.personId, companyId: actor.companyId, expiresAt });
    });
    return { mode: MODE, accessToken, actor: actorView(actor), expiresAt };
  }

  async function command(rawToken, input, idempotencyKey) {
    if (!COMMANDS.has(input.kind)) throw new HttpError(400, 'UNSUPPORTED_COMMAND', '不支持的管理模拟动作');
    if (!idempotencyKey || idempotencyKey.length > 160) throw new HttpError(400, 'IDEMPOTENCY_REQUIRED', '请提供有效的 Idempotency-Key');
    if (!Number.isSafeInteger(input.expectedRevision)) throw new HttpError(400, 'REVISION_REQUIRED', '请提供当前服务端版本');
    const authenticated = sessionActor(store.read(), rawToken).actor;
    if (!['boss', 'owner'].includes(authenticated.role)) throw new HttpError(403, 'FORBIDDEN', '当前岗位不能办理管理模拟操作');
    if (input.companyId && input.companyId !== authenticated.companyId) throw new HttpError(403, 'FORBIDDEN', '不能办理其他组织的管理数据');
    const signature = bodyHash(input);
    const scopedKey = operationKey(authenticated, idempotencyKey);
    const existing = store.read().idempotency?.[scopedKey];
    if (existing) {
      if (existing.signature !== signature) throw new HttpError(409, 'IDEMPOTENCY_CONFLICT', '该幂等键已用于另一笔操作');
      return copy(existing.response);
    }
    return store.transact(state => {
      const duplicate = state.idempotency?.[scopedKey];
      if (duplicate) {
        if (duplicate.signature !== signature) throw new HttpError(409, 'IDEMPOTENCY_CONFLICT', '该幂等键已用于另一笔操作');
        return copy(duplicate.response);
      }
      if (input.expectedRevision !== state.revision) throw new HttpError(409, 'STALE_REVISION', '管理数据已更新，请刷新后重试');
      const { runtime, actor } = sessionActor(state, rawToken);
      const result = applyCommand(runtime, actor, input);
      state.storage = copy(runtime.storage);
      state.revision += 1;
      const response = { revision: state.revision, duplicate: false, result };
      state.idempotency[scopedKey] = { signature, response: copy(response), companyId: actor.companyId, personId: actor.personId, staffId: actor.staffId, at: new Date().toISOString() };
      return response;
    });
  }

  server = http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://127.0.0.1');
      if (request.method === 'POST' && url.pathname === '/sim/v1/sessions') return reply(response, 200, await createSession((await readJson(request)).inviteCode));
      if (request.method === 'GET' && url.pathname === '/sim/v1/snapshot') return reply(response, 200, snapshotView(store.read(), bearer(request)));
      if (request.method === 'POST' && url.pathname === '/sim/v1/commands') return reply(response, 200, await command(bearer(request), await readJson(request), String(request.headers['idempotency-key'] || '')));
      throw new HttpError(404, 'NOT_FOUND', '模拟管理接口不存在');
    } catch (rawError) {
      const error = asHttpError(rawError);
      reply(response, error.status || 500, { error: { code: error.code || 'INTERNAL_ERROR', message: error.message } });
    }
  });

  return {
    listen(port, host = '127.0.0.1') { return new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, () => { server.off('error', reject); resolve(this); }); }); },
    address() { return server.address(); },
    async close() { await store.idle(); if (server.listening) await new Promise(resolve => server.close(resolve)); await lock.release(); }
  };
}

module.exports = { createManagementSimulation };
