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
assert(wxml.includes('data-handler="findAccounts"'), '账号简写查询入口已接线');
assert(wxml.includes('data-handler="selectAccount"'), '多账号匹配支持明确选择');
assert(wxml.includes('匹配较多，请补充地区或姓名缩小范围'), '账号匹配被截断时提示继续缩小范围');
assert(wxml.includes("'accountLogin'"), '账号密码登录入口已接线');
assert(wxml.includes('data-handler="clearDefaultAccount"'), '默认账号可以清除');
assert(wxml.includes('data-handler="retryWechatBind"'), '微信绑定失败可以重试');
assert(!wxml.includes('open-type="getPhoneNumber"'), '微信登录不再请求手机号授权');
assert(wxml.includes('bindtap="wechatLogin"'), '微信普通按钮登录已接线');

assert(authSource.includes("'/platform/auth/account/matches'"), 'auth API 提供账号简写匹配');
assert(authSource.includes("'/platform/auth/account/login'"), 'auth API 提供账号密码登录');
assert(authSource.includes("'/platform/auth/wechat-bind'"), 'auth API 提供同账号微信绑定');
assert(pageSource.includes('preferredPostId'), '登录后优先进入后端指定的门店岗位');
assert(pageSource.includes('requirePasswordChange'), '初始密码登录必须完成改密');
assert(pageSource.includes('bindWechatAfterLogin'), '首次微信登录会转入已有账号绑定');
assert(pageSource.includes("'form.password':''"), '账号输入或选择变化时清除旧密码');

const storage = new Map();
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key)
};
const login = require('../miniprogram/utils/account-login');

assert.equal(login.validNewPassword('abc1234567'), true, '10位字母数字密码有效');
assert.equal(login.validNewPassword('abc1234567' + '中'.repeat(20)), true, '含扩展字符但UTF8不超过72字节有效');
assert.equal(login.validNewPassword('abc1234567' + '中'.repeat(21)), false, 'BCrypt输入超过72个UTF8字节无效');
assert.equal(login.validNewPassword('abcdefghij'), false, '纯字母密码无效');
assert.equal(login.validNewPassword('1234567890'), false, '纯数字密码无效');
assert.equal(login.validNewPassword('abc123'), false, '不足10位密码无效');

const account = { accountId: '88', loginName: 'hjmz-jxnc-gaoxin-lizhong', orgName: '合家美宅-江西南昌-高新-李忠', ownerName: '李忠' };
login.saveDefault(account);
assert.deepEqual(login.defaultAccount(), account, '默认账号仅保存账号标识与展示信息');
assert.equal(JSON.stringify(storage.get(login.DEFAULT_KEY)).includes('password'), false, '默认账号不保存密码');
login.clearDefault();
assert.equal(login.defaultAccount(), null, '默认账号可清除');

console.log('账号登录契约：UI、API、首次改密与默认账号断言通过');
