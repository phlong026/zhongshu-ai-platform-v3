const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const authSource = read('miniprogram/api/auth.js');
const pageSource = read('miniprogram/pages/account/index.js');
const wxml = read('miniprogram/pages/account/index.wxml');

assert(!wxml.includes('手机号登录'), '登录页已移除手机号登录');
assert(!wxml.includes('data-handler="sendCaptcha"'), '登录页已移除短信验证码入口');
assert(wxml.includes('请输入管理员提供的完整账号'), '账号输入要求完整账号');
assert(!/findAccounts|selectAccount|accountMatches/.test(wxml), '登录页面不检索或展示匿名账号候选');
assert(wxml.includes("'accountLogin'"), '账号密码登录入口已接线');
assert(wxml.includes('data-handler="clearDefaultAccount"'), '默认账号可以清除');
assert(!wxml.includes('绑定已有账号') && !wxml.includes('toggleWechatBinding'), '不要求用户手动绑定微信');
assert(wxml.includes('open-type="getPhoneNumber"') && wxml.includes('bindgetphonenumber="onWechatPhoneNumber"'), '首次微信通过官方手机号授权自动识别账号');
assert(wxml.includes('bindtap="wechatLogin"'), '微信普通按钮登录已接线');

assert(!authSource.includes('/platform/auth/account/matches'), 'auth API 不提供匿名人员或账号检索');
assert(authSource.includes('function accountLoginByName('), '完整账号与密码直接提交认证');
assert(authSource.includes("'/platform/auth/account/login'"), 'auth API 提供账号密码登录');
assert(authSource.includes('phoneCode: opts.phoneCode') && !authSource.includes('/platform/auth/wechat-bind'), '客户端只提交微信授权 code，不手动绑定');
assert(pageSource.includes('preferredPostId'), '登录后优先进入后端指定的门店岗位');
assert(pageSource.includes('requirePasswordChange'), '初始密码登录必须完成改密');
assert(pageSource.includes('wechatPhoneRequired') && !pageSource.includes('bindWechatAfterLogin'), '首次微信授权留在微信通道');
assert(pageSource.includes("'form.password':''"), '账号输入或选择变化时清除旧密码');

const storage = new Map();
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key)
};
const login = require('../miniprogram/utils/account-login');

for (const value of ['123456', 'abcdef', 'abc123', 'lili123', 'lili2123', 'a'.repeat(64)]) {
  assert.equal(login.validNewPassword(value), true, '6–64位无需组合字母和数字：' + value);
}
for (const value of [null, '', '12345', 'a'.repeat(65)]) {
  assert.equal(login.validNewPassword(value), false, '不足6位或超过64位无效');
}
assert.equal(login.validNewPassword('abc1234567' + '中'.repeat(20)), true, '含扩展字符但UTF8不超过72字节有效');
assert.equal(login.validNewPassword('abc1234567' + '中'.repeat(21)), false, 'BCrypt输入超过72个UTF8字节无效');
assert.ok(wxml.includes('6–64位密码') && pageSource.includes('新密码须为6–64位'), '页面提示与实际密码规则一致');
assert.ok(!/10-64位|同时包含字母和数字/.test(wxml + pageSource), '清理旧密码复杂度提示');

const account = { accountId: '88', loginName: 'hjmz-jxnc-gaoxin-lizhong', orgName: '合家美宅-江西南昌-高新-李忠', ownerName: '李忠',personName:'李忠',roleCode:'boss',roleName:'老板',shortOrgName:'合家美宅 · 江西南昌高新' };
login.saveDefault(account);
assert.deepEqual(login.defaultAccount(), {accountId:account.accountId,loginName:account.loginName}, '默认账号仅保存已验证账号标识与登录名');
assert(!/ownerName|personName|orgName|roleCode|roleName|shortOrgName/.test(JSON.stringify(storage.get(login.DEFAULT_KEY))), '默认账号不缓存人员和组织资料');
assert.equal(JSON.stringify(storage.get(login.DEFAULT_KEY)).includes('password'), false, '默认账号不保存密码');
login.clearDefault();
assert.equal(login.defaultAccount(), null, '默认账号可清除');

console.log('账号登录契约：UI、API、首次改密与默认账号断言通过');

const policy={agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'};
assert.equal(login.consent(policy),null);
login.saveConsent(policy);assert.equal(login.consent(policy).accepted,true);
assert.equal(login.consent({...policy,privacyVersion:'new'}),null,'协议版本更新须重新同意');
login.clearConsent();assert.equal(login.consent(policy),null);
