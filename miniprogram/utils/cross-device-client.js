const KEY = 'zhongshu-collaboration-client-v1';
const COMMAND_PATTERN = /^\/(receipts\/[^/]+\/confirm|commissions\/[^/]+\/(complete|dispatch)|messages\/[^/]+\/read|subscriptions)$/;
const clone = require('./clone');

function normalizeBaseUrl(value) {
  const base = String(value || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\/[a-z0-9.-]+(?::\d{1,5})?$/i.test(base)) throw Error('请填写有效的联调服务地址，仅包含协议、主机及端口');
  return base;
}

function createClient(options = {}) {
  const platform = options.wx || wx;
  const interval = options.pollInterval || 1500;
  const storageKey = options.storageKey || KEY;
  const commandPattern = options.commandPattern || COMMAND_PATTERN;
  let state = platform.getStorageSync(storageKey) || null;
  let timer = null, listener = null, pulling = null, submitting = null, generation = 0, sequence = 0;
  const connected = () => !!(state?.token && Date.parse(state.expiresAt) > Date.now());
  const save = () => state ? platform.setStorageSync(storageKey, clone(state)) : platform.removeStorageSync(storageKey);
  const snapshot = () => clone(state?.snapshot || null);
  const sameActor = (left, right) => !!left && !!right && left.personId === right.personId && left.staffId === right.staffId && left.companyId === right.companyId && left.role === right.role;

  function request(baseUrl, token, path, method = 'GET', data, key) {
    return new Promise((resolve, reject) => {
      platform.request({
        url: baseUrl + '/sim/v1' + path, method, data, timeout: options.timeout || 8000,
        header: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
        success(response) {
          if (response.statusCode >= 200 && response.statusCode < 300) return resolve(response.data);
          const error = Error(response.data?.error?.message || '联调服务暂不可用');
          error.statusCode = response.statusCode;
          error.code = response.data?.error?.code || 'REQUEST_FAILED';
          reject(error);
        },
        fail(reason) {
          const error = Error('网络未同步，尚未确认保存：' + (reason.errMsg || '连接失败'));
          error.transport = true;
          reject(error);
        }
      });
    });
  }

  function accept(value) {
    if (!value || !Number.isSafeInteger(value.revision) || !value.actor || !Array.isArray(value.tasks)) throw Error('联调服务返回的数据不完整');
    if (value.actor.personId !== state.actor.personId || value.actor.companyId !== state.actor.companyId || value.actor.role !== state.actor.role) throw Error('联调身份已变化，请重新接入');
    if (state.snapshot && value.revision < state.snapshot.revision) return snapshot();
    state.snapshot = clone(value);
    save();
    listener?.({ snapshot: snapshot(), error: '' });
    return snapshot();
  }

  async function connect(baseUrl, inviteCode) {
    baseUrl = normalizeBaseUrl(baseUrl);
    if (!String(inviteCode || '').trim()) throw Error('请填写本人测试接入码');
    stop();
    const version = generation;
    const previous = state;
    const session = await request(baseUrl, '', '/sessions', 'POST', { inviteCode: inviteCode.trim() });
    if (!session.accessToken || session.mode !== 'simulated-cross-device' || !session.actor) throw Error('当前服务不支持跨端联调');
    const value = await request(baseUrl, session.accessToken, '/snapshot');
    if (version !== generation) throw Error('接入已取消');
    if (previous?.pending && (previous.baseUrl !== baseUrl || !sameActor(previous.actor, session.actor))) throw Error('上一笔操作结果待核实，只能由原服务的同一身份重新接入');
    state = { baseUrl, token: session.accessToken, expiresAt: session.expiresAt, actor: session.actor, ...(previous?.pending ? { pending: clone(previous.pending) } : {}) };
    return accept(value);
  }

  function refresh() {
    if (!connected()) return Promise.reject(Error('联调会话已过期，请重新接入'));
    if (pulling) return pulling;
    const version = generation, current = state;
    const pending = request(current.baseUrl, current.token, '/snapshot?sinceRevision=' + (current.snapshot?.revision || 0))
      .then(value => version === generation && current === state ? accept(value) : snapshot())
      .catch(error => {
        if (version === generation) listener?.({ snapshot: snapshot(), error: error.message });
        throw error;
      }).finally(() => { if (pulling === pending) pulling = null; });
    pulling = pending;
    return pending;
  }

  function command(path, input = {}) {
    if (submitting) return submitting;
    if (!connected()) return Promise.reject(Error('联调会话已过期，请重新接入'));
    commandPattern.lastIndex = 0;
    if (!commandPattern.test(path)) return Promise.reject(Error('不支持的联调办理动作'));
    const run = async () => {
      const current = state, signature = JSON.stringify({ path, input });
      if (current.pending && current.pending.signature !== signature) throw Error('上一笔操作结果待核实，请先重试原操作');
      const pending = current.pending || {
        key: 'sim-' + Date.now() + '-' + (++sequence) + '-' + Math.random().toString(36).slice(2),
        signature, path, data: { ...clone(input), expectedRevision: current.snapshot.revision }
      };
      current.pending = pending;
      save();
      let result, resultUnknown = false;
      for (let attempt = 0; ; attempt++) {
        try { result = await request(current.baseUrl, current.token, pending.path, 'POST', pending.data, pending.key); break; }
        catch (error) {
          const ambiguous = error.transport || error.statusCode >= 500;
          if (ambiguous) resultUnknown = true;
          if (attempt === 0 && ambiguous) continue;
          if (!resultUnknown && !error.transport && error.statusCode < 500) { delete current.pending; save(); }
          throw error;
        }
      }
      if (current !== state) throw Error('原联调会话已退出，请重新同步后查看办理记录');
      delete current.pending;
      save();
      try {
        let latest = await refresh();
        // A foreground poll may have started before this write committed.
        if (latest.revision < result.revision) latest = await refresh();
        if (latest.revision < result.revision) throw Error('尚未读取到已保存版本');
        return { ...result, synchronized: true };
      }
      catch (error) { return { ...result, synchronized: false, refreshError: '服务端已保存，正在重新同步：' + error.message }; }
    };
    submitting = run().finally(() => { submitting = null; });
    return submitting;
  }

  function start(callback) {
    stop();
    listener = callback;
    const version = generation;
    const poll = async () => {
      try { await refresh(); } catch (_) { /* The page receives the explicit failure through the listener. */ }
      if (version === generation && connected()) timer = setTimeout(poll, interval);
    };
    poll();
  }

  function stop() {
    generation += 1;
    if (timer) clearTimeout(timer);
    timer = null;
    listener = null;
    pulling = null;
  }

  function disconnect() { stop(); state = null; save(); }
  function retryPending() {
    if (!state?.pending) return refresh().then(() => ({ synchronized: true }));
    const pending = state.pending;
    return command(pending.path, JSON.parse(pending.signature).input);
  }
  return { connect, connected, snapshot, refresh, command, start, stop, disconnect, retryPending, pending: () => !!state?.pending, baseUrl: () => state?.baseUrl || '' };
}

module.exports = { createClient, normalizeBaseUrl };
