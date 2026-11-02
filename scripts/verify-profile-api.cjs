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

// ===== maskPhone：前3后4打码（20 号任务书 AC4，syncApiProfile 回填展示用）=====
assert.equal(P.maskPhone('13800002688'), '138****2688', '11 位手机号前3后4');
assert.equal(P.maskPhone('13711112222'), '137****2222');
assert.equal(P.maskPhone(''), '', '空串原样');
assert.equal(P.maskPhone(null), '', 'null 安全');
assert.equal(P.maskPhone(undefined), '', 'undefined 安全');
assert.equal(P.maskPhone('12345'), '12345', '短串（<7 位）原样不打码');
// mergeProfile → maskPhone 联动：后端 mobile 回填后打码展示
assert.equal(P.maskPhone(P.mergeProfile(local, { id: 9, nickname: '李运营', mobile: '13711112222' }).phone), '137****2222', '回填联动打码');

// ===== account/index.js GET 读侧接线静态扫描（20 号任务书 AC3/D-2 硬约束）=====
const fs = require('node:fs');
const path = require('node:path');
const pageSrc = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.js'), 'utf8');
const seg = pageSrc.slice(pageSrc.indexOf('syncApiProfile(){'));
assert.ok(seg.length > 0, 'syncApiProfile 方法存在');
assert.ok(seg.includes("http.get('/platform/profile/mine')"), 'GET /platform/profile/mine 接线');
assert.ok(seg.includes('mergeProfile'), '回填走 mergeProfile');
assert.ok(/if\(!http\.token\(\)\)return;/.test(seg), '无 token 直接返回不拉取');
assert.ok(seg.includes('A.mutate') && seg.includes('this.setData({profile'), 'A.mutate 与 setData 均在 syncApiProfile 段内');
assert.ok(seg.indexOf('A.mutate') < seg.indexOf('this.setData({profile'), '先 A.mutate 落 storage 再 setData（D-2 硬约束：navigateBack 复用旧实例靠 storage 重读兜底）');
assert.ok(seg.includes('.catch(()=>{})'), '失败静默降级不白屏');
assert.ok(pageSrc.includes("if(v==='profile'&&reset)this.syncApiProfile();"), 'refresh 末尾仅 profile 视图 onLoad（reset）触发');

console.log('verify-profile-api ✔  全部断言通过');
