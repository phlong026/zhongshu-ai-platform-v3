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

// ===== mergeProfile：姓名/手机号后端为真源，头像保留本地 =====
const local = { name: '旧名', phone: '13800002688', avatar: '/assets/account-avatar.jpg' };
const merged = P.mergeProfile(local, { id: 7, nickname: '新名', mobile: '13911112222', avatar: 'https://cdn/a.png' });
assert.equal(merged.name, '新名', '姓名以后端为真源');
assert.equal(merged.phone, '13911112222', '手机号以后端为真源');
assert.equal(merged.avatar, '/assets/account-avatar.jpg', '本地头像路径不被后端 URL 覆盖（基-1 前不上传）');
assert.equal(local.name, '旧名', '入参不被修改（纯函数）');

// remote 缺字段/null 安全：本地原样保留
const kept = P.mergeProfile(local, { id: 7, nickname: '', mobile: null, avatar: null });
assert.equal(kept.name, '旧名');
assert.equal(kept.phone, '13800002688');
assert.deepEqual(P.mergeProfile(local, null), local, 'remote null 安全');

console.log('verify-profile-api ✔  全部断言通过');
