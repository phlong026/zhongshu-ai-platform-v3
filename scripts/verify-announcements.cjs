const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const navigation = require('../miniprogram/utils/page-navigation');
const ROOT = path.resolve(__dirname, '../miniprogram');
const LIST = '/pages/account/announcements/index';
const DETAIL = '/pages/account/announcements/detail';
const API = '/platform/announcements/app';
const LONG_ID = '9007199254740999123';
const UUID = '9043f245-d8ef-4f93-b75c-82f682b34e1d';
const tick = () => new Promise(resolve => setImmediate(resolve));
const flush = async () => { for (let i = 0; i < 8; i++) await tick(); };
const text = nodes => nodes.map(node => (node.text || '') + text(node.children || [])).join('');
const flat = nodes => nodes.flatMap(node => [node, ...flat(node.children || [])]);
const announcement = (id = LONG_ID, extra = {}) => ({ id, title: '公司测试公告', summary: '公告摘要', content: '第一段\n第二段',
  pinned: false, status: 'PUBLISHED', publishedAt: '2026-11-25T08:30:00Z', revision: 1, ...extra });
const reply = (request, data, code = 0) => request.success({ statusCode: 200, data: { code, data, msg: code ? '服务端拒绝访问' : '' } });

async function setup(role = 'sales', orgType = 'store') {
  const runtime = await connect({ lifecycles: true }), requests = [];
  const env = { runtime, requests, handler: () => undefined };
  wx.request = request => {
    const url = new URL(request.url).pathname.replace('/admin-api', '');
    requests.push({ url, request });
    const result = env.handler(url, request);
    if (result && result.pending) return;
    const data = result === undefined ? url === '/platform/profile/mine' ? { id: '701', nickname: '测试人员' }
      : url === '/platform/messages/center/summary' ? { count: 0 }
      : url === API ? { list: [], total: 0 } : [] : result;
    setImmediate(() => reply(request, data));
  };
  env.post = { postId: 'post-1', userId: '701', tenantId: '1', orgId: '31', roleCode: role, orgType, brandId: 'brand-1', orgName: '测试门店' };
  if (role) {
    auth.saveLogin({ accessToken: 'test-token', userId: '701' });
    auth.savePosts([env.post]); auth.switchPost(env.post.postId);
  } else auth.logout();
  env.open = async (url, method = 'navigateTo') => { await runtime.callWxMethod(method, { url }); await flush(); return getCurrentPages().at(-1); };
  env.reads = () => requests.filter(item => item.url.startsWith(API));
  env.render = page => render(path.join(ROOT, page.route + '.wxml'), page.data);
  return env;
}

test('业务、老板、工程和总部财务的我的共用独立公告入口，进入后可返回原岗位我的', async () => {
  for (const [role, orgType, profile] of [
    ['sales', 'store', '/pages/profile/index'], ['boss', 'store', '/pages/profile/index'],
    ['engineer', 'store', '/pages/profile/index'], ['finance', 'headquarters', '/pages/admin/finance-desk/index?view=profile'],
    ['hq-admin', 'headquarters', '/pages/profile/index']
  ]) {
    const env = await setup(role, orgType), page = await env.open(profile, 'reLaunch');
    const control = await (await env.runtime.currentPage()).$('button[data-action="announcements"]');
    assert.ok(control, role + ' has an announcement entry');
    const profileText = text(env.render(page));
    assert.ok(profileText.indexOf('公司公告') < profileText.indexOf('学习培训'));
    assert.ok(profileText.indexOf('帮助与反馈') < profileText.indexOf('学习培训'));
    await control.tap(); await flush();
    const list = getCurrentPages().at(-1);
    assert.equal(list.route, LIST.slice(1));
    assert.equal(navigation.parent(list), profile);
    navigation.back(); await flush();
    assert.equal(getCurrentPages().at(-1), page);
  }
});

test('游客不显示内部公告入口，列表和详情深链接转登录且保留原目标', async () => {
  const env = await setup(null), profile = await env.open('/pages/profile/index');
  assert.equal(text(env.render(profile)).includes('公司公告'), false);
  const redirects = [];
  wx.redirectTo = options => redirects.push(options.url);
  for (const route of [LIST, DETAIL + '?id=' + LONG_ID]) {
    await env.open(route);
    const login = new URL(redirects.at(-1), 'https://fixture.invalid');
    assert.equal(login.searchParams.get('view'), 'login');
    assert.equal(login.searchParams.get('return'), route);
  }
  assert.equal(env.reads().length, 0);
});

test('缺失或缓存不匹配的当前岗位不发起公告请求', async () => {
  for (const posts of [[], [{ postId: 'another', roleCode: 'sales', orgType: 'store' }]]) {
    const env = await setup(); auth.savePosts(posts);
    for (const route of [LIST, DETAIL + '?id=' + LONG_ID]) {
      const page = await env.open(route);
      assert.match(page.data.error, /有效工作身份/);
      assert.equal(page.data.loading, false);
    }
    assert.equal(env.reads().length, 0);
  }
});

test('列表发送当前租户岗位，分页不重入，失败可按原范围重试', async () => {
  const env = await setup(); let pending;
  const first = Array.from({ length: 20 }, (_, index) => announcement(String(index + 1)));
  env.handler = (url, request) => {
    if (url !== API) return;
    if (request.data.pageSize === 20) return { list: first, total: 21 };
    pending = request; return { pending: true };
  };
  const page = await env.open(LIST);
  const initial = env.reads()[0].request;
  assert.deepEqual(initial.data, { pageNo: 1, pageSize: 20 });
  assert.equal(initial.header['X-Post-Id'], 'post-1');
  assert.equal(initial.header['tenant-id'], '1');
  assert.equal(page.data.items.length, 20); assert.equal(page.data.hasMore, true);
  const firstMore = page.loadMore(); page.loadMore(); page.onReachBottom();
  assert.equal(env.reads().length, 2);
  pending.fail({ errMsg: 'offline' }); await firstMore;
  assert.equal(page.data.items.length, 20); assert.equal(page.data.pageNo, 1); assert.ok(page.data.error);
  const retry = page.retry();
  assert.deepEqual(pending.data, { pageNo: 1, pageSize: 40 });
  reply(pending, { list: [...first, announcement(LONG_ID)], total: '21' }); await retry;
  assert.equal(page.data.items.length, 21); assert.equal(page.data.items.at(-1).id, LONG_ID);
  assert.equal(page.data.hasMore, false); assert.equal(page.data.error, '');
  await page.loadMore(); assert.equal(env.reads().length, 3);
  assert.ok(text(env.render(page)).includes('已显示全部公告'));
});

test('点加载更多时撤回前一页的公告，保留搜索并更新列表，不漏原第21条', async () => {
  const env = await setup();
  let ordered = Array.from({ length: 21 }, (_, i) => announcement(String(i + 1), { title: '工程公告 ' + (i + 1) }));
  env.handler = (url, { data }) => url === API
    ? { list: ordered.slice((data.pageNo - 1) * data.pageSize, data.pageNo * data.pageSize), total: ordered.length }
    : undefined;
  const page = await env.open(LIST);
  page.input({ detail: { value: '工程公告' } });
  await page.search();
  ordered = ordered.slice(1);
  await (await (await env.runtime.currentPage()).$('.announcement-more')).tap(); await flush();
  assert.deepEqual(page.data.items.map(row => row.id), ordered.map(row => row.id));
  assert.deepEqual(env.reads().at(-1).request.data, { pageNo: 1, pageSize: 40, keyword: '工程公告' });
  assert.equal(page.data.searchText, '工程公告');
  assert.equal(page.data.total, 20);
  assert.equal(page.data.hasMore, false);
  assert.ok(text(env.render(page)).includes('公告列表已更新'));
});

test('总数相同但撤回、置顶和新发公告改变顺序，完整重读而不是沿用旧20条', async () => {
  const env = await setup();
  let ordered = Array.from({ length: 21 }, (_, i) => announcement(String(i + 1)));
  env.handler = (url, { data }) => url === API
    ? { list: ordered.slice((data.pageNo - 1) * data.pageSize, data.pageNo * data.pageSize), total: ordered.length }
    : undefined;
  const page = await env.open(LIST);
  ordered = [announcement('99', { pinned: true }), ordered[20], ...ordered.slice(1, 20)];
  await page.loadMore();
  assert.deepEqual(page.data.items.map(row => row.id), ordered.map(row => row.id));
  assert.equal(page.data.total, 21);
  assert.equal(page.data.refreshNotice, '公告列表已更新');
});

test('超过一百条逐批核对：跨批重复不把不完整名单当成功，重试能恢复', async () => {
  const env = await setup();
  const ordered = Array.from({ length: 125 }, (_, i) => announcement(String(i + 1)));
  let inconsistent = true;
  env.handler = (url, { data }) => {
    if (url !== API) return;
    const offset = (data.pageNo - 1) * data.pageSize;
    const list = ordered.slice(offset, offset + data.pageSize);
    if (data.pageNo === 2 && inconsistent) list[0] = ordered[99];
    return { list, total: ordered.length };
  };
  const page = await env.open(LIST);
  for (let i = 0; i < 4; i++) await page.loadMore();
  assert.equal(page.data.items.length, 100);
  const before = env.reads().length;
  await page.loadMore();
  assert.equal(env.reads().length - before, 2, 'no runaway retries');
  assert.equal(page.data.items.length, 100); assert.match(page.data.error, /列表刚有变化/);
  await page.onReachBottom();
  assert.equal(env.reads().length - before, 2, 'retry is explicit after a failed refresh');
  assert.equal(page.data.hasMore, true);
  inconsistent = false;
  await page.retry();
  assert.equal(page.data.error, '');
  assert.equal(page.data.items.length, 120);
  assert.equal(page.data.items.at(-1).id, '120');
  await page.loadMore();
  assert.deepEqual(page.data.items.map(row => row.id), ordered.map(row => row.id));
  assert.equal(page.data.hasMore, false);
});

test('搜索确认重置页码，较晚的旧查询不覆盖新查询，清除搜索展示空态', async () => {
  const env = await setup(), pending = [];
  env.handler = (url, request) => url === API ? (pending.push(request), { pending: true }) : undefined;
  const page = await env.open(LIST);
  page.input({ detail: { value: '  最新公告  ' } });
  assert.equal(pending.length, 1, 'typing alone does not dispatch queries');
  const query = page.search(); assert.equal(pending[1].data.keyword, '最新公告');
  reply(pending[1], { list: [announcement('2', { title: '新查询' })], total: 1 }); await query;
  reply(pending[0], { list: [announcement('1', { title: '旧查询' })], total: 1 }); await flush();
  assert.equal(page.data.items[0].title, '新查询');
  const clear = page.clearSearch(); assert.equal(Object.hasOwn(pending[2].data, 'keyword'), false);
  reply(pending[2], { list: [], total: 0 }); await clear;
  assert.equal(page.data.keyword, ''); assert.equal(page.data.items.length, 0);
  assert.ok(text(env.render(page)).includes('暂无公告'));
});

test('真实UUID公告从列表点击到详情，正文按纯文本显示，时间固定北京并覆盖跨日', async () => {
  const env = await setup(), value = announcement(UUID, { publishedAt: '2026-11-25T20:30:00Z', pinned: true,
    content: '<script>alert(1)</script>\n第二段 & 完整内容' });
  env.handler = url => url === API ? { list: [value], total: 1 } : url === API + '/' + UUID ? value : undefined;
  const list = await env.open(LIST);
  assert.equal(list.data.items[0].date, '2026-11-26 04:30');
  await (await (await env.runtime.currentPage()).$('button[data-id="' + UUID + '"]')).tap(); await flush();
  const page = getCurrentPages().at(-1);
  assert.equal(page.route, DETAIL.slice(1)); assert.equal(page.id, UUID);
  assert.equal(page.data.announcement.content, value.content);
  const rendered = env.render(page);
  assert.ok(text(rendered).includes(value.content));
  assert.equal(flat(rendered).some(node => node.tag === 'rich-text' || node.tag === 'web-view'), false);
  assert.equal(navigation.parent(page), LIST);
  assert.equal(env.reads().at(-1).url, API + '/' + UUID);
});

test('撤回、拒权和岗位失效均清除已展示详情，过期会话可由全局登录流程接管', async () => {
  for (const code of [404, 403, 1103000002, 401]) {
    const env = await setup(); let pending;
    env.handler = url => url.startsWith(API) ? announcement() : undefined;
    const page = await env.open(DETAIL + '?id=' + LONG_ID);
    assert.ok(page.data.announcement);
    env.handler = (url, request) => url.startsWith(API) ? (pending = request, { pending: true }) : undefined;
    const refresh = page.onShow();
    assert.equal(page.data.announcement, null);
    reply(pending, null, code); await refresh;
    assert.equal(page.data.announcement, null); assert.equal(page.data.loading, false);
    if (page.visible) assert.ok(page.data.error, String(code));
    else assert.notEqual(getCurrentPages().at(-1), page, 'global session recovery may navigate away');
    assert.equal(text(env.render(page)).includes('第一段'), false);
  }
});

test('分页时权限撤销立即清空列表，错误响应不继续保留先前公告', async () => {
  const env = await setup(); let pending;
  env.handler = (url, request) => {
    if (url !== API) return;
    if (request.data.pageSize === 20) return {
      list: Array.from({ length: 20 }, (_, i) => announcement(String(i + 1))), total: 40
    };
    pending = request; return { pending: true };
  };
  const page = await env.open(LIST), more = page.loadMore();
  reply(pending, null, 403); await more;
  assert.equal(page.data.items.length, 0); assert.equal(page.data.hasMore, false);
  assert.match(page.data.error, /无权/);
});

test('同岗位换品牌或角色后，旧详情回包和拒权都不能覆盖新范围', async () => {
  for (const patch of [{ brandId: 'brand-2' }, { roleCode: 'finance', orgType: 'headquarters' }]) {
    const env = await setup(), pending = [];
    env.handler = (url, request) => url.startsWith(API) ? (pending.push(request), { pending: true }) : undefined;
    const page = await env.open(DETAIL + '?id=' + LONG_ID);
    auth.savePosts([{ ...env.post, ...patch }]);
    const fresh = page.onShow();
    assert.equal(page.data.announcement, null);
    reply(pending[1], announcement(LONG_ID, { title: '当前品牌公告' })); await fresh;
    reply(pending[0], announcement(LONG_ID, { title: '旧品牌公告' })); await flush();
    assert.equal(page.data.announcement.title, '当前品牌公告');
    const old = page.load(); auth.savePosts([{ ...env.post, ...patch, permissions: ['changed'] }]);
    const changed = page.onShow(); reply(pending[3], announcement(LONG_ID, { title: '当前权限公告' })); await changed;
    reply(pending[2], null, 403); await old;
    assert.equal(page.data.announcement.title, '当前权限公告');
  }
});

test('换品牌后旧列表回包不回填，隐藏卸载清内容且返回重新读取', async () => {
  const env = await setup(), pending = [];
  env.handler = (url, request) => url === API ? (pending.push(request), { pending: true }) : undefined;
  const page = await env.open(LIST);
  auth.savePosts([{ ...env.post, brandId: 'brand-2' }]);
  reply(pending[0], { list: [announcement()], total: 1 }); await flush();
  assert.equal(page.data.items.length, 0); assert.match(page.data.error, /身份已变化/);
  for (const action of ['onHide', 'onUnload']) {
    const reading = page.onShow(), request = pending.at(-1);
    page[action](); reply(request, { list: [announcement()], total: 1 }); await reading;
    assert.equal(page.data.items.length, 0);
    const before = pending.length, returned = page.onShow(); assert.equal(pending.length, before + 1);
    reply(pending.at(-1), { list: [announcement('9')], total: 1 }); await returned;
    assert.equal(page.data.items[0].id, '9');
  }
});

test('后台异常返回草稿或不匹配详情ID时拒绝展示，不回退演示内容', async () => {
  for (const value of [announcement(LONG_ID, { status: 'DRAFT' }), announcement('42')]) {
    const env = await setup(); env.handler = url => url.startsWith(API) ? value : undefined;
    const page = await env.open(DETAIL + '?id=' + LONG_ID);
    assert.equal(page.data.announcement, null); assert.ok(page.data.error);
  }
});

test('新增总部管理员可在真实身份选择器启用并直达我的，登录落地不触发旧设计和业务查询', async () => {
  const env = await setup('hq-admin', 'headquarters');
  const identity = await env.open('/pages/account/index?view=identity', 'reLaunch');
  const option = identity.apiPostOptions().find(item => item.postId === env.post.postId);
  assert.equal(option.name, '总部管理员'); assert.equal(option.enabled, true);
  identity.selectApiPost({ currentTarget: { dataset: { postId: env.post.postId } } }); await flush();
  let page = getCurrentPages().at(-1);
  assert.equal(page.route, 'pages/profile/index'); assert.equal(page.data.role, '总部管理员');
  const workbench = require('../miniprogram/utils/workbench-nav')();
  assert.deepEqual(workbench.tabs.map(tab => tab.key), ['profile']);
  assert.equal(workbench.canBusiness, false); assert.equal(workbench.canEngineering, false); assert.equal(workbench.canManagement, false);
  identity.goAfterLogin(); await flush();
  page = getCurrentPages().at(-1); assert.equal(page.route, 'pages/profile/index');
  assert.equal(env.requests.some(item => /\/franchise\/(customers|commissions|design)/.test(item.url)), false);
  assert.ok(await (await env.runtime.currentPage()).$('button[data-action="announcements"]'));
});

test('真实登录完成流程等待有效岗位，再恢复公告列表及UUID/长数字详情', async () => {
  for (const destination of [LIST, DETAIL + '?id=' + UUID, DETAIL + '?id=' + LONG_ID]) {
    const env = await setup(null); let postsRequest;
    env.post = { ...env.post, roleCode: 'hq-admin', orgType: 'headquarters' };
    env.handler = (url, request) => {
      if (url === '/platform/posts/mine') { postsRequest = request; return { pending: true }; }
      if (url.startsWith(API + '/')) return announcement(url.slice((API + '/').length));
    };
    const login = await env.open('/pages/account/index?view=login&return=' + encodeURIComponent(destination), 'reLaunch');
    const generation = login.cancelLoginFlow({ clearSensitive: true });
    login.finishAccountLogin({ accessToken: 'login-result-token', userId: '701', preferredPostId: env.post.postId }, generation);
    await flush();
    assert.equal(getCurrentPages().at(-1), login, 'cannot resume a protected route before posts are verified');
    assert.equal(env.reads().length, 0);
    reply(postsRequest, [env.post]); await flush();
    const page = getCurrentPages().at(-1);
    assert.equal('/' + page.route, destination.split('?')[0]);
    if (destination.includes('?')) assert.equal(page.data.announcement.id, destination.split('=')[1]);
    assert.equal(http.currentPostId(), env.post.postId);
    assert.equal(env.reads().at(-1).request.header['X-Post-Id'], env.post.postId);
  }
});

test('登录return白名单拒绝外链、其他模块、路径穿越、无效ID及额外参数', async () => {
  for (const destination of ['https://example.test/', '//example.test/', '/pages/workflow/index?view=customer',
    LIST + '?other=1', DETAIL + '?id=../profile', DETAIL + '?id=invalid', DETAIL + '?id=' + UUID + '&extra=1']) {
    const env = await setup(null);
    env.post = { ...env.post, roleCode: 'hq-admin', orgType: 'headquarters' };
    env.handler = url => url === '/platform/posts/mine' ? [env.post] : undefined;
    const login = await env.open('/pages/account/index?view=login&return=' + encodeURIComponent(destination), 'reLaunch');
    const generation = login.cancelLoginFlow({ clearSensitive: true });
    login.finishAccountLogin({ accessToken: 'login-result-token', userId: '701', preferredPostId: env.post.postId }, generation);
    await flush();
    assert.equal(getCurrentPages().at(-1).route, 'pages/profile/index', destination);
    assert.equal(env.reads().length, 0);
  }
});
