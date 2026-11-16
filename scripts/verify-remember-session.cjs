const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../miniprogram');
const TOKEN = 'zs-api-token-v1', RESUME = 'zs-api-resume-v1';
const post = (id = 11, roleCode = 'sales') => ({ postId: id, orgId: 31, roleCode });
const good = data => ({ statusCode: 200, data: { code: 0, data } });
const expired = () => Date.now() - 1000;
const future = () => Date.now() + 3600000;
const issued = (suffix = 'A', expiresTime = expired()) => ({ userId: 77, accessToken: 'access-' + suffix, refreshToken: 'refresh-' + suffix, expiresTime, password: 'never-store-this-password' });
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
const tick = () => new Promise(r => setImmediate(r));

// 每次 boot 都重新加载真实模块，只共享微信 storage，模拟杀进程后冷启动。
function boot(storage = new Map(), transport = () => good([post()])) {
  const requests = [], navigated = [], cache = new Map();
  let definition;
  const wx = {
    getStorageSync: key => storage.get(key) || '', setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key), getAccountInfoSync: () => ({ miniProgram: { appId: 'wx-fixture' } }),
    getDeviceInfo: () => ({ platform: 'ios' }), getSystemInfoSync: () => ({ platform: 'ios' }),
    reLaunch: options => navigated.push(options.url), switchTab: options => navigated.push(options.url),
    showToast() {},
    request(options) { requests.push(options); Promise.resolve().then(() => transport(options)).then(
      result => result && result.fail ? options.fail(result.fail) : options.success(result), error => options.fail(error)); },
    uploadFile(options) { requests.push(options); Promise.resolve().then(() => transport(options)).then(
      result => options.success({ ...result, data: JSON.stringify(result.data) }), error => options.fail(error)); }
  };
  const org = { apiContext: p => p, navigation: p => ({ tabs: [{ url: p.roleCode === 'boss' ? '/pages/admin/index?view=home' : '/pages/business/index', native: p.roleCode !== 'boss' }] }) };
  function load(file) {
    file = path.resolve(ROOT, file); if (!path.extname(file)) file += '.js';
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} }; cache.set(file, module);
    const requireLocal = id => {
      const resolved = path.resolve(path.dirname(file), id);
      if (resolved === path.join(ROOT, 'utils/access')) return { context: () => ({ loggedIn: false }) };
      if (resolved === path.join(ROOT, 'utils/organization')) return org;
      return load(resolved);
    };
    vm.runInNewContext('(function(require,module,exports){' + fs.readFileSync(file, 'utf8') + '\n})',
      { wx, console, Date, Promise, setTimeout, clearTimeout, Page: p => { definition = p; }, getCurrentPages: () => [] }, { filename: file })(requireLocal, module, module.exports);
    return module.exports;
  }
  const auth = load('api/auth.js'), http = load('api/http.js');
  function home() { load('pages/home/index.js'); return { ...definition, data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch); }, getTabBar: () => null }; }
  return { auth, http, home, storage, requests, navigated };
}

test('勾选后冷启动：过期访问凭据自动刷新、复核任职并恢复老板的原岗位', async () => {
  const first = boot(); first.auth.saveLogin(issued()); first.auth.savePosts([post(), post(12, 'boss')]); first.http.saveSession({ postId: 12 });
  assert.equal(JSON.stringify([...first.storage]).includes('never-store-this-password'), false);
  const cold = boot(first.storage, o => o.url.endsWith('/refresh-token') ? good(issued('renewed', future())) : good([post(), post(12, 'boss')]));
  await cold.home().onShow();
  assert.equal(cold.http.token(), 'access-renewed');
  assert.equal(cold.auth.currentApiPost().postId, 12);
  assert.deepEqual(cold.navigated, ['/pages/admin/index?view=home']);
  const refresh = cold.requests[0]; assert.ok(refresh.url.endsWith('/system/auth/refresh-token'));
  assert.equal(refresh.data, 'refreshToken=refresh-A'); assert.equal(refresh.header.Authorization, undefined);
  assert.equal(refresh.header['content-type'], 'application/x-www-form-urlencoded');
  assert.equal(cold.requests[1].header.Authorization, 'Bearer access-renewed');
});

test('有效登录冷启动不要求密码，也不额外刷新令牌；员工直接进入业务工作台', async () => {
  const first = boot(); first.auth.saveLogin(issued('A', future()));
  const cold = boot(first.storage); await cold.home().onShow();
  assert.equal(cold.requests.length, 1); assert.ok(cold.requests[0].url.endsWith('/posts/mine'));
  assert.deepEqual(cold.navigated, ['/pages/business/index']);
});

test('未勾选：当前运行可使用和续期，冷启动不保留登录凭据', async () => {
  const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('renewed', future())) : good('ok'));
  env.auth.saveLogin(issued(), { remember: false });
  assert.equal(env.storage.has(TOKEN), false); assert.equal(env.storage.has(RESUME), false);
  assert.equal(await env.http.get('/platform/example'), 'ok'); assert.equal(env.http.token(), 'access-renewed');
  const cold = boot(env.storage); await cold.home().onShow(); assert.equal(cold.http.token(), ''); assert.equal(cold.requests.length, 0);
});

test('并发业务请求只续期一次；同用户续期保留岗位和请求守卫', async () => {
  const pending = deferred(); const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? pending.promise : good('ok'));
  env.auth.saveLogin(issued()); env.http.saveSession({ postId: 11 }); const alive = env.http.sessionGuard();
  const requests = [env.http.get('/one'), env.http.get('/two'), env.http.get('/three')]; await tick();
  assert.equal(env.requests.length, 1); pending.resolve(good(issued('new', future()))); await Promise.all(requests);
  assert.equal(env.requests.filter(o => o.url.endsWith('/refresh-token')).length, 1);
  assert.equal(env.http.currentPostId(), 11); assert.equal(alive(), true);
});

for (const statusCode of [200, 401]) test('服务器认证过期 ' + statusCode + '：自动续期并只重试一次', async () => {
  let business = 0; const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('new', future())) : ++business === 1 ? { statusCode, data: { code: 401 } } : good('ok'));
  env.auth.saveLogin(issued('A', future())); assert.equal(await env.http.get('/protected'), 'ok');
  assert.equal(env.requests.length, 3); assert.equal(env.navigated.length, 0);
});

test('续期后的迟到旧 401 使用新令牌重试，不再重复续期', async () => {
  const late = deferred(); let calls = 0;
  const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('new', future())) : ++calls === 1 ? late.promise : calls === 2 ? { statusCode: 401 } : good('ok'));
  env.auth.saveLogin(issued('A', future())); const a = env.http.get('/slow'); const b = env.http.get('/fast');
  await b; late.resolve({ statusCode: 200, data: { code: 401 } }); await a;
  assert.equal(env.requests.filter(o => o.url.endsWith('/refresh-token')).length, 1);
});

test('30 天凭据失效或被吊销：清理持久会话并引导重新登录', async () => {
  const env = boot(undefined, () => ({ statusCode: 200, data: { code: 400, msg: '无效的刷新令牌' } }));
  env.auth.saveLogin(issued()); const results = await Promise.allSettled([env.http.get('/one'), env.http.get('/two')]);
  assert.ok(results.every(r => r.status === 'rejected' && r.reason.code === 401));
  assert.equal(env.storage.has(TOKEN), false); assert.equal(env.storage.has(RESUME), false); assert.equal(env.navigated.length, 1);
});

for (const failure of [{ fail: { errMsg: 'offline' } }, { statusCode: 503, data: { code: 503 } }]) test('网络或服务暂时不可用时保留记住的会话，稍后可恢复', async () => {
  let broken = true; const env = boot(undefined, o => broken ? failure : o.url.endsWith('/refresh-token') ? good(issued('new', future())) : good('ok'));
  env.auth.saveLogin(issued()); await assert.rejects(env.http.get('/protected'));
  assert.equal(env.http.token(), 'access-A'); assert.ok(env.storage.has(RESUME)); assert.equal(env.navigated.length, 0);
  broken = false; assert.equal(await env.http.get('/protected'), 'ok');
});

test('主动退出清除访问和续登凭据，迟到续登不能恢复已退出账号', async () => {
  const pending = deferred(); const env = boot(undefined, () => pending.promise); env.auth.saveLogin(issued());
  const result = assert.rejects(env.http.get('/protected'), error => error.silent === true); await tick(); env.auth.logout();
  pending.resolve(good(issued('late', future()))); await result;
  assert.equal(env.http.token(), ''); assert.equal(env.storage.has(RESUME), false);
  const cold = boot(env.storage); await cold.home().onShow(); assert.equal(cold.requests.length, 0);
});

test('旧账号在途续登不得覆盖新账号凭据、岗位或持久化选项', async () => {
  const pending = deferred(); const env = boot(undefined, () => pending.promise); env.auth.saveLogin(issued());
  const result = assert.rejects(env.http.get('/old'), error => error.silent === true); await tick();
  env.auth.saveLogin({ ...issued('B', future()), userId: 88 }, { remember: false }); env.auth.savePosts([post(22, 'boss')]); env.http.saveSession({ postId: 22 });
  pending.resolve(good(issued('late', future()))); await result;
  assert.equal(env.http.token(), 'access-B'); assert.equal(env.auth.currentApiPost().postId, 22); assert.equal(env.storage.has(RESUME), false);
});

test('切岗期间同一凭据的服务端续期串行，旧结果作废且新岗位正常完成请求', async () => {
  const pending = deferred(); let refreshes = 0;
  const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? ++refreshes === 1 ? pending.promise : good(issued('second', future())) : good('ok'));
  env.auth.saveLogin(issued()); const old = assert.rejects(env.http.get('/old'), e => e.silent === true); await tick();
  env.http.saveSession({ postId: 22 }); const next = env.http.get('/new'); await tick(); assert.equal(refreshes, 1);
  pending.resolve(good(issued('first', future()))); await old; assert.equal(await next, 'ok');
  assert.equal(refreshes, 2); assert.equal(env.http.currentPostId(), 22); assert.equal(env.http.token(), 'access-second');
});

test('不接受续登响应切换用户', async () => {
  const env = boot(undefined, () => good({ ...issued('wrong', future()), userId: 88 })); env.auth.saveLogin(issued());
  await assert.rejects(env.http.get('/protected'), e => e.code === 401); assert.equal(env.http.token(), '');
});

test('续期后仍然 401 不循环请求', async () => {
  const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('new', future())) : { statusCode: 401 });
  env.auth.saveLogin(issued('A', future())); await assert.rejects(env.http.get('/protected'), e => e.code === 401);
  assert.equal(env.requests.length, 3); assert.equal(env.http.token(), '');
});

test('独立钱包会话不续期或清除平台登录', async () => {
  const env = boot(undefined, () => ({ statusCode: 401 })); env.auth.saveLogin(issued());
  await assert.rejects(env.http.request('GET', '/wallet', null, { Authorization: 'Bearer wallet' }, { skipSessionGuard: true }), e => e.code === 401);
  assert.equal(env.requests.length, 1); assert.equal(env.http.token(), 'access-A');
});

test('头像上传在认证过期时续期并重试', async () => {
  let uploads = 0; const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('new', future())) : ++uploads === 1 ? { statusCode: 200, data: { code: 401 } } : good('https://example.test/avatar.png'));
  env.auth.saveLogin(issued('A', future())); assert.equal(await env.http.upload('/infra/file/upload', 'wxfile://fixture'), 'https://example.test/avatar.png');
  assert.equal(uploads, 2); assert.equal(env.requests.length, 3);
});

test('启动复核没有有效任职时，不能依靠旧岗位缓存进入工作台', async () => {
  const env = boot(undefined, () => good([])); env.auth.saveLogin(issued('A', future())); env.auth.savePosts([post()]);
  await env.home().onShow(); assert.equal(env.http.token(), ''); assert.equal(env.auth.apiPosts().length, 0);
  assert.deepEqual(env.navigated, ['/pages/account/index?view=login']);
});

test('启动复核期间离开首页，不抢回用户导航', async () => {
  const pending = deferred(); const env = boot(undefined, () => pending.promise); env.auth.saveLogin(issued('A', future()));
  const page = env.home(); const result = page.onShow(); page.onHide(); pending.resolve(good([post()])); await result;
  assert.equal(env.navigated.length, 0); assert.ok(env.http.token());
});

test('启动复核期间退出，不写回旧任职', async () => {
  const pending = deferred(); const env = boot(undefined, () => pending.promise); env.auth.saveLogin(issued('A', future()));
  const result = assert.rejects(env.auth.restoreSession(), e => e.silent === true); env.auth.logout(); pending.resolve(good([post()])); await result;
  assert.equal(env.auth.apiPosts().length, 0); assert.equal(env.http.token(), '');
});

test('协议、账号匹配等公开请求不携带旧令牌，不抢占或清除正在恢复的账号', async () => {
  const env = boot(undefined, () => ({ statusCode: 200, data: { code: 401 } })); env.auth.saveLogin(issued());
  await assert.rejects(env.auth.loginPolicy(), e => e.code === 401);
  assert.equal(env.requests.length, 1); assert.equal(env.requests[0].header.Authorization, undefined);
  assert.equal(env.requests[0].header['X-Post-Id'], undefined); assert.equal(env.http.token(), 'access-A');
  assert.ok(env.storage.has(RESUME)); assert.equal(env.navigated.length, 0);
});

test('后端无时区的到期时间按北京时间解析，冷启动仍可续登', async () => {
  const env = boot(undefined, o => o.url.endsWith('/refresh-token') ? good(issued('new', future())) : good('ok'));
  env.auth.saveLogin(issued('A', '2026-01-01T12:34:56'));
  assert.equal(env.storage.get(RESUME).expiresAt, Date.parse('2026-01-01T12:34:56+08:00'));
  assert.equal(await env.http.get('/protected'), 'ok'); assert.equal(env.http.token(), 'access-new');
});
