const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { render } = require('./role-page-runtime.cjs');
const ROOT = path.resolve(__dirname, '../miniprogram');
const USER_A = '9007199254740999101', USER_B = '9007199254740999102';
const AVATAR = '/assets/account-avatar.jpg';
const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const good = data => ({ statusCode: 200, data: { code: 0, data } });
const tick = () => new Promise(resolve => setImmediate(resolve));
const flush = async () => { for (let i = 0; i < 8; i++) await tick(); };
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
const remote = (id, nickname = id === USER_A ? '账号甲' : '账号乙', avatar = 'https://example.test/' + id + '.png') =>
  ({ id, nickname, mobile: id === USER_A ? '13800000001' : '13900000002', avatar });
const text = nodes => nodes.map(node => (node.text || '') + text(node.children || [])).join('');

// 真实模块独立加载，只有微信 I/O 被替代；共享 storage 的第二次 boot 模拟杀进程冷启动。
function boot(storage = new Map()) {
  const cache = new Map(), requests = [], uploads = [], savedFiles = [], toasts = [];
  let definition;
  const env = { storage, requests, uploads, savedFiles, toasts, remote: remote(USER_A) };
  env.handle = options => {
    if (options.url.endsWith('/platform/profile/mine')) {
      if (options.method === 'PUT') Object.assign(env.remote, options.data);
      return good(clone(env.remote));
    }
    return good(options.url.includes('/messages/') ? { items: [], unread: 0, pending: 0, count: 0 } : []);
  };
  env.upload = () => good('https://example.test/uploaded.png');
  const wx = {
    getStorageSync: key => clone(storage.get(key)), setStorageSync: (key, value) => storage.set(key, clone(value)),
    removeStorageSync: key => storage.delete(key), getStorageInfoSync: () => ({ keys: [...storage.keys()] }),
    getDeviceInfo: () => ({ platform: 'devtools' }), getSystemInfoSync: () => ({ platform: 'devtools', statusBarHeight: 20 }),
    getAccountInfoSync: () => ({ miniProgram: { appId: 'test' } }),
    showToast: value => toasts.push(value.title), reLaunch() {}, redirectTo() {}, navigateTo() {}, switchTab() {},
    request(options) {
      requests.push(options);
      Promise.resolve().then(() => env.handle(options)).then(result => result.fail ? options.fail(result.fail) : options.success(result), options.fail);
    },
    uploadFile(options) {
      uploads.push(options);
      Promise.resolve().then(() => env.upload(options)).then(result => options.success({ ...result, data: JSON.stringify(result.data) }), options.fail);
    },
    saveFile(options) { savedFiles.push(options); }
  };
  storage.set('zs-api-base', 'https://api.test');
  const context = vm.createContext({ wx, console, Date, Promise, setTimeout, clearTimeout, setInterval, clearInterval,
    Page: value => { definition = value; }, getCurrentPages: () => [], getApp: () => ({ globalData: { windowInfo: { statusBarHeight: 20 } } }) });
  function load(file) {
    file = path.resolve(ROOT, file); if (!path.extname(file)) file += '.js';
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} }; cache.set(file, module);
    vm.runInContext('(function(require,module,exports){' + fs.readFileSync(file, 'utf8') + '\n})', context, { filename: file })(
      name => load(path.resolve(path.dirname(file), name)), module, module.exports);
    return module.exports;
  }
  env.http = load('api/http.js'); env.auth = load('api/auth.js'); env.account = load('utils/account.js');
  env.profile = load('utils/platform-profile.js');
  env.login = (id, options = {}) => {
    env.remote = remote(id);
    env.auth.saveLogin({ userId: id, accessToken: 'token-' + id,
      ...(options.refresh ? { refreshToken: 'refresh-' + id, expiresTime: options.expiresTime || Date.now() + 3600000 } : {}) }, options);
    env.auth.savePosts([{ userId: id, postId: 'post-' + id, orgId: '31', orgType: 'store', orgName: '测试门店', roleCode: 'sales' }]);
    env.auth.switchPost('post-' + id);
  };
  env.cacheProfile = profile => env.account.mutate(state => { state.profile = env.profile.mergeProfile(state.profile, profile, env.http.currentUserId()); });
  env.page = kind => {
    if (kind === 'home') definition = load('pages/profile/profile-page.js')(0);
    else load('pages/account/index.js');
    const page = { ...definition, data: clone(definition.data), options: {}, id: '', serviceId: 'store', getTabBar: () => null,
      setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let owner = this.data; for (const part of parts.slice(0, -1)) owner = owner[part] ||= {}; owner[parts.at(-1)] = clone(value); } } };
    if (kind === 'home') page.onShow();
    else { page.data.view = kind || 'profile'; page.options.view = page.data.view; page.refresh(true); }
    return page;
  };
  return env;
}

for (const view of ['home', 'profile', 'identity']) test(view + ': 换账号后请求未完成或失败都不显示上一账号，null资料明确清空', async () => {
  const env = boot(); env.login(USER_A); env.cacheProfile(remote(USER_A));
  const pending = deferred(), original = env.handle;
  env.login(USER_B);
  env.handle = options => options.url.endsWith('/profile/mine') ? pending.promise : original(options);
  const page = env.page(view);
  assert.equal(env.account.load().profile.name, '');
  assert.equal(view === 'home' ? page.data.name : page.data.profile.name, '');
  if (view === 'profile') assert.equal(page.data.form.name, '');
  const file = path.join(ROOT, view === 'home' ? 'pages/profile/index.wxml' : 'pages/account/index.wxml');
  assert.equal(text(render(file, page.data)).includes('账号甲'), false);
  pending.resolve({ fail: { errMsg: 'offline' } }); await flush();
  assert.match(page.data.profileError, /资料暂未加载/);
  assert.equal(view === 'home' ? page.data.avatar : page.data.profile.avatar, AVATAR);

  env.remote = { id: USER_B, nickname: '账号乙', mobile: null, avatar: null };
  env.handle = original;
  if (view === 'home') page.onShow(); else await page.syncApiProfile();
  await flush();
  const saved = env.account.load().profile;
  assert.equal(saved.apiUserId, USER_B); assert.equal(saved.name, '账号乙');
  assert.equal(saved.phone, ''); assert.equal(saved.avatar, AVATAR);
  assert.equal(view === 'home' ? page.data.name : page.data.profile.name, '账号乙');
});

test('复用的编辑页换账号清空旧输入，同用户切岗与续期保留已归属资料', async () => {
  const env = boot(); env.login(USER_A, { refresh: true, expiresTime: Date.now() - 1000 });
  env.cacheProfile(remote(USER_A));
  const original = env.handle;
  env.handle = options => options.url.endsWith('/refresh-token')
    ? good({ userId: USER_A, accessToken: 'renewed', refreshToken: 'next', expiresTime: Date.now() + 3600000 }) : original(options);
  const page = env.page('profile'); await flush();
  assert.equal(env.http.token(), 'renewed');
  assert.equal(env.account.load().profile.name, '账号甲');
  env.auth.switchPost('other-post');
  assert.equal(env.account.load().profile.avatar, remote(USER_A).avatar);
  page.data.form.name = '甲尚未保存的姓名';
  const pending = deferred(); env.login(USER_B);
  env.handle = options => options.url.endsWith('/profile/mine') ? pending.promise : original(options);
  page.onShow();
  assert.equal(page.data.form.name, ''); assert.equal(page.data.profile.name, '');
  pending.resolve(good(remote(USER_B))); await flush();
  assert.equal(page.data.form.name, '账号乙');
  env.login(USER_B);
  assert.equal(env.account.load().profile.name, '', '同用户真正重新登录也重新读取资料');
});

for (const method of ['logout', 'clearSession', 'http401', 'body401']) test(method + ' 清除资料归属且保留无关业务草稿', async () => {
  const env = boot(); env.login(USER_A); env.cacheProfile(remote(USER_A));
  env.account.mutate(state => { state.drafts.keep = { content: '业务草稿' }; state.personal = { demo: { note: '离线数据' } }; });
  if (method === 'logout') env.auth.logout();
  else if (method === 'clearSession') env.http.clearSession();
  else {
    env.handle = () => ({ statusCode: method === 'http401' ? 401 : 200, data: { code: 401 } });
    await assert.rejects(env.http.get('/protected'), error => error.code === 401);
  }
  const state = env.account.load(), stored = env.storage.get(env.account.KEY);
  assert.equal(env.http.currentUserId(), ''); assert.equal(state.profile.name, '访客');
  assert.equal(state.profile.phone, ''); assert.equal(state.profile.avatar, AVATAR);
  assert.equal(Object.hasOwn(stored.profile, 'apiUserId'), false);
  assert.equal(stored.drafts.keep.content, '业务草稿'); assert.equal(stored.personal.demo.note, '离线数据');
});

test('旧会话401和迟到资料响应不能清理或覆盖新账号', async () => {
  const env = boot(); env.login(USER_A);
  const oldProfile = deferred(), old401 = deferred(), original = env.handle;
  env.handle = options => options.url.endsWith('/profile/mine') ? oldProfile.promise : options.url.endsWith('/old') ? old401.promise : original(options);
  const page = env.page('profile');
  const rejected = assert.rejects(env.http.get('/old'), error => error.silent === true);
  await tick();
  env.login(USER_B); env.cacheProfile(remote(USER_B));
  old401.resolve({ statusCode: 401, data: { code: 401 } }); oldProfile.resolve(good(remote(USER_A)));
  await rejected; await flush();
  assert.equal(env.http.currentUserId(), USER_B); assert.equal(env.account.load().profile.name, '账号乙');
  assert.notEqual(page.data.profile.name, '账号甲');
});

test('冷启动只展示当前记住账号的缓存，不记住会话与旧无归属访客资料均清场', () => {
  const remembered = boot(); remembered.login(USER_A); remembered.cacheProfile(remote(USER_A));
  const cold = boot(remembered.storage);
  assert.equal(cold.http.currentUserId(), USER_A); assert.equal(cold.account.load().profile.name, '账号甲');

  const transient = boot(); transient.login(USER_A, { remember: false }); transient.cacheProfile(remote(USER_A));
  assert.equal(transient.http.currentUserId(), USER_A); assert.equal(transient.storage.has('zs-api-user-v1'), false);
  const guest = boot(transient.storage);
  assert.equal(guest.http.token(), ''); assert.equal(guest.account.load().profile.name, '访客');
  assert.equal(Object.hasOwn(guest.storage.get(guest.account.KEY).profile, 'apiUserId'), false);

  const legacy = boot(); legacy.account.mutate(state => { state.profile = { name: '旧账号甲', phone: '13800000000', avatar: '/old-avatar' }; });
  assert.equal(legacy.account.load().profile.name, '访客');
  assert.equal(legacy.storage.get(legacy.account.KEY).profile.phone, '');
  legacy.account.mutate(state => { state.session.loggedIn = true; state.profile = { name: '离线演示', phone: '', avatar: '/local-demo' }; });
  assert.equal(legacy.account.load().profile.name, '离线演示', '现有合法离线登录资料不变');
});

test('旧令牌缺身份时不信历史资料，本次认证GET可展示；已有续登userId仍可用', async () => {
  const env = boot();
  env.account.mutate(state => { state.profile = { name: '未归属旧资料', phone: '13800000000', avatar: '/old' }; });
  env.storage.set(env.http.TOKEN_KEY, 'legacy-token');
  env.auth.savePosts([{ postId: 'legacy', orgId: '31', roleCode: 'sales', orgType: 'store' }]);
  assert.equal(env.http.currentUserId(), ''); assert.equal(env.account.load().profile.name, '');
  const page = env.page('profile'); await flush();
  assert.equal(page.data.profile.name, '账号甲', '当前认证响应可以展示');
  assert.equal(env.account.load().profile.name, '', '缺少可信登录归属时不跨加载复用缓存');
  env.storage.set('zs-api-resume-v1', { userId: USER_A, refreshToken: 'old-refresh', expiresAt: Date.now() + 3600000 });
  env.cacheProfile(remote(USER_A));
  assert.equal(env.http.currentUserId(), USER_A); assert.equal(env.account.load().profile.name, '账号甲');
});

test('后端资料ID与登录ID不一致时拒绝写缓存，ID不经过Number截断', async () => {
  const env = boot(); env.login(USER_A); env.remote = remote(USER_B);
  const page = env.page('profile'); await flush();
  assert.match(page.data.profileError, /资料暂未加载/);
  assert.equal(env.http.currentUserId(), USER_A); assert.equal(env.account.load().profile.name, '');
  assert.equal(page.data.profile.name, '');
});

for (const phase of ['upload', 'put']) test('头像' + phase + '阶段换账号，旧操作不写新账号也不弹成功提示', async () => {
  const env = boot(); env.login(USER_A);
  const page = env.page('profile'); await flush();
  const pending = deferred(), original = env.handle;
  if (phase === 'upload') env.upload = () => pending.promise;
  else env.handle = options => options.method === 'PUT' ? pending.promise : original(options);
  env.toasts.length = 0;
  const operation = page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://old-avatar' } }); await tick();
  env.login(USER_B); env.cacheProfile(remote(USER_B)); page.onShow(); await flush();
  pending.resolve(phase === 'upload' ? good('https://example.test/old-upload.png') : good(remote(USER_A, '甲旧保存', 'https://example.test/old-upload.png')));
  await operation; await flush();
  assert.equal(env.account.load().profile.apiUserId, USER_B);
  assert.equal(env.account.load().profile.name, '账号乙'); assert.equal(page.data.form.name, '账号乙');
  assert.equal(env.account.load().profile.avatar, remote(USER_B).avatar);
  if (phase === 'upload') assert.equal(env.requests.filter(o => o.method === 'PUT').length, 0);
  assert.equal(env.toasts.includes('头像已保存'), false);
});

for (const boundary of ['login', 'hide', 'unload']) test('本地头像saveFile迟到跨越' + boundary + '时不落资料', async () => {
  const env = boot(); env.account.mutate(state => { state.session.loggedIn = true; state.profile.name = '离线演示'; });
  const page = env.page('profile');
  page.saveAvatarLocal('wxfile://local-avatar');
  assert.equal(env.savedFiles.length, 1);
  if (boundary === 'login') { env.login(USER_B); env.cacheProfile(remote(USER_B)); }
  else if (boundary === 'hide') page.onHide(); else page.onUnload();
  env.savedFiles[0].success({ savedFilePath: 'wxfile://saved-old-avatar' });
  assert.notEqual(env.account.load().profile.avatar, 'wxfile://saved-old-avatar');
  assert.equal(env.toasts.length, 0);
  if (boundary === 'login') assert.equal(env.account.load().profile.name, '账号乙');
});

test('资料保存成功使旧GET失效，页面与缓存不回退到保存前', async () => {
  const env = boot(); env.login(USER_A);
  const page = env.page('profile'); await flush();
  const pending = deferred(), original = env.handle;
  env.handle = options => options.method === 'GET' && options.url.endsWith('/profile/mine') ? pending.promise : original(options);
  const read = page.syncApiProfile(); await tick();
  page.data.form.name = '修改后的姓名';
  await page.saveProfile();
  pending.resolve(good(remote(USER_A))); await read;
  assert.equal(page.data.profile.name, '修改后的姓名'); assert.equal(env.account.load().profile.name, '修改后的姓名');
});

test('资料加载中离开再返回会重新请求，不永久停在加载态', async () => {
  const env = boot(); env.login(USER_A);
  const pending = deferred(), original = env.handle;
  env.handle = options => options.url.endsWith('/profile/mine') ? pending.promise : original(options);
  const page = env.page('profile'); await tick();
  assert.equal(page.data.profileLoading, true);
  page.onHide();
  pending.resolve(good(remote(USER_A, '离开前的旧响应'))); await flush();
  assert.equal(page.data.profile.name, '');
  env.handle = original; page.onShow(); await flush();
  assert.equal(page.data.profileLoading, false); assert.equal(page.data.profile.name, '账号甲');
});

test('有效离线会话的本地头像保存保持可用', () => {
  const env = boot(); env.account.mutate(state => { state.session.loggedIn = true; state.profile.name = '离线演示'; });
  const page = env.page('profile');
  page.saveAvatarLocal('wxfile://local-avatar');
  env.savedFiles[0].success({ savedFilePath: 'wxfile://saved-local-avatar' });
  assert.equal(env.account.load().profile.avatar, 'wxfile://saved-local-avatar');
  assert.ok(env.toasts.some(value => value.includes('已保存到本机')));
});
