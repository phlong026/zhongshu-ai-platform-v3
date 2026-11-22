/**
 * 基-3 员工 profile 接入 verify：断言 utils/platform-profile 纯函数契约（零 wx 依赖，直接 require）。
 * 对应后端契约：monorepo docs/backend-parity/08-阶段0-员工profile接口-需求与验收.md。
 * 用法：node scripts/verify-profile-api.cjs
 */
const assert = require('node:assert/strict');
const P = require('../miniprogram/utils/platform-profile');

// ===== normalizeNickname：与后端同口径（trim 后 1–20 字）=====
assert.deepEqual(P.normalizeNickname(' 张业务 '), { ok: true, value: '张业务' }, 'trim 归一化');
assert.deepEqual(P.normalizeNickname('张'), { ok: true, value: '张' }, '1 字边界');
assert.equal(P.normalizeNickname('张'.repeat(20)).value.length, 20, '20 字边界');
for (const bad of ['', '   ', null, undefined, '张'.repeat(21)]) {
  const r = P.normalizeNickname(bad);
  assert.equal(r.ok, false, '非法输入须拒绝：' + JSON.stringify(bad));
  assert.match(r.error, /1–20个字/);
}

// ===== mergeProfile：姓名/手机号/头像 URL 后端为真源（基-1 上传通道就绪后）=====
const local = { apiUserId: '7', name: '旧名', phone: '13800002688', avatar: 'https://cdn/old.png' };
const merged = P.mergeProfile(local, { id: 7, nickname: '新名', mobile: '13911112222', avatar: 'https://cdn/a.png' }, 7);
assert.equal(merged.name, '新名', '姓名以后端为真源');
assert.equal(merged.phone, '13911112222', '手机号以后端为真源');
assert.equal(merged.avatar, 'https://cdn/a.png', '后端头像 URL 非空即覆盖（上传通道就绪，后端为真源）');
assert.equal(local.name, '旧名', '入参不被修改（纯函数）');

// 真实完整 DTO 中 null 为明确空值；只有同用户的缓存可供无响应时回填。
const cleared = P.mergeProfile(local, { id: 7, nickname: '新名', mobile: null, avatar: null }, '7');
assert.equal(cleared.name, '新名');
assert.equal(cleared.phone, '', '服务端无手机号时不保留缓存手机号');
assert.equal(cleared.avatar, '/assets/account-avatar.jpg', '服务端无头像时恢复默认头像');
assert.deepEqual(P.mergeProfile(local, null, 7), local, '同用户无响应时保留本人缓存');

// ===== maskPhone：前3后4打码（20 号任务书 AC4，syncApiProfile 回填展示用）=====
assert.equal(P.maskPhone('13800002688'), '138****2688', '11 位手机号前3后4');
assert.equal(P.maskPhone('13711112222'), '137****2222');
assert.equal(P.maskPhone(''), '', '空串原样');
assert.equal(P.maskPhone(null), '', 'null 安全');
assert.equal(P.maskPhone(undefined), '', 'undefined 安全');
assert.equal(P.maskPhone('12345'), '12345', '短串（<7 位）原样不打码');
// mergeProfile → maskPhone 联动：后端 mobile 回填后打码展示
assert.equal(P.maskPhone(P.mergeProfile(local, { id: 7, nickname: '李运营', mobile: '13711112222', avatar: null }, 7).phone), '137****2222', '回填联动打码');

// ===== account/index.js GET 读侧接线静态扫描（20 号任务书 AC3/D-2 硬约束）=====
const fs = require('node:fs');
const path = require('node:path');
const pageSrc = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.js'), 'utf8');
const seg = pageSrc.slice(pageSrc.indexOf('syncApiProfile(){'), pageSrc.indexOf('readAll(){'));
assert.ok(seg.length > 0, 'syncApiProfile 方法存在');
assert.ok(seg.includes("http.get('/platform/profile/mine')"), 'GET /platform/profile/mine 接线');
assert.ok(seg.includes('mergeProfile'), '回填走 mergeProfile');
assert.ok(/if\(!http\.token\(\)\)return;/.test(seg), '无 token 直接返回不拉取');
const profileUpdate = seg.indexOf('this.setData({profile,'); // 排除请求开始时的 profileLoading 更新。
assert.ok(seg.includes('A.mutate') && profileUpdate >= 0, 'A.mutate 与资料 setData 均在 syncApiProfile 段内');
assert.ok(seg.indexOf('A.mutate') < profileUpdate, '先 A.mutate 落 storage 再 setData（D-2 硬约束：navigateBack 复用旧实例靠 storage 重读兜底）');

// ===== account 页面行为：identity/profile 首次读真资料，onShow 与其他页不重复读 =====
const vm = require('node:vm');
let definition;
const profileGets = [];
const state = {
  session: { loggedIn: false, role: '', companyId: '', userId: '' },
  profile: { ...local },
  drafts: {}, favorites: [], messages: [], orders: [], companies: []
};
const account = {
  courseImage: '', services: [{ id: 'store', name: '门店服务', image: '' }], chapters: [], courseChapters: {}, roles: {},
  load: () => state, company: () => ({ roles: [] }), access: { context: () => ({ companyId: '', staffId: '', role: 'sales', active: true }) },
  permitted: () => true, order: () => null, displayOrder: value => value, visible: value => value,
  scoped: (value, rows) => rows || [], visibleOrders: () => [], mutate: fn => fn(state)
};
const currentPost = { postId: 'post-sales', userId: 7, orgId: 'store-1', orgType: 'store', roleCode: 'sales' };
const http = {
  token: () => 'token', currentUserId: () => '7', sessionGeneration: () => 1, requestGuard: () => () => true,
  get: url => new Promise((resolve, reject) => { if (url === '/platform/profile/mine') profileGets.push({ url, resolve, reject }); })
};
const organization = require('../miniprogram/utils/organization');
vm.runInNewContext(pageSrc, {
  Page: value => { definition = value; },
  require(name) {
    if (name === '../../utils/view-container') return { routeValue: value => value || '', fail() {} };
    if (name === '../../utils/learning-catalog') return require('../miniprogram/utils/learning-catalog');
    if (name === '../../utils/account-login') return require('../miniprogram/utils/account-login');
    if (name === '../../api/auth') return { currentApiPost: () => currentPost, apiPosts: () => [currentPost] };
    if (name === '../../api/http') return http;
    if (name === '../../api/org') return {};
    if (name === '../../utils/organization') return organization;
    if (name === '../../utils/sample-files') return { displayFile: value => value };
    if (name === './procurement') return { products: [], get: () => null, visibleProjects: () => [], list: () => [] };
    if (name === '../../utils/account') return account;
    if (name === '../../utils/platform-profile') return P;
    if (name === './platform-messages-page') return { invalidateAction() {}, sync() {} };
    if (name === '../../utils/platform-messages') return { totalUnread: value => value, mergeSources: () => [] };
    if (name === '../../api/franchise/content') return { drafts: { courseProgress: () => new Promise(() => {}) } };
    throw new Error('Unexpected module: ' + name);
  },
  wx: { redirectTo() {}, switchTab() {}, reLaunch() {}, showToast() {} },
  console, Date, Math, Promise, setInterval, clearInterval, encodeURIComponent
}, { filename: 'miniprogram/pages/account/index.js' });

function page(view) {
  const instance = { ...definition, options: {}, id: '', serviceId: 'store', data: JSON.parse(JSON.stringify(definition.data)) };
  instance.data.view = view;
  instance.setData = patch => {
    for (const [key, value] of Object.entries(patch)) {
      const keys = key.split('.');
      let target = instance.data;
      for (const part of keys.slice(0, -1)) target = target[part] ??= {};
      target[keys.at(-1)] = value;
    }
  };
  return instance;
}

const identityPage = page('identity');
identityPage.refresh(true);
assert.equal(profileGets.length, 1, 'identity 首次加载请求真实 profile');
identityPage.onShow();
assert.equal(profileGets.length, 1, 'identity 普通 onShow 不重复请求 profile');
const profilePage = page('profile');
profilePage.refresh(true);
assert.equal(profileGets.length, 2, 'profile 原首次读取行为保留');
page('learning').refresh(true);
assert.equal(profileGets.length, 2, 'learning 不得触发 profile 请求');

// 失败结束加载并显示可重试提示；同用户缓存仍可展示，不留下未处理拒绝。
(async () => {
  profileGets[1].reject({ message: 'fixture network failure' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(profilePage.data.profileLoading, false);
  assert.match(profilePage.data.profileError, /未加载.*重试/);
  assert.equal(profilePage.data.profile.name, local.name);
  assert.equal(profilePage.data.profile.avatar, local.avatar);
  assert.deepEqual(state.profile, local, '请求失败不能破坏同用户已有资料');
  console.log('verify-profile-api ✔  全部断言通过');
})().catch(error => { console.error(error); process.exitCode = 1; });
