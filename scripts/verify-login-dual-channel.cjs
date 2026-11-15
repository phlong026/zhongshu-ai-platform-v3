// 登录双通道改造静态验证：账号密码登录 + 微信一键登录 + tenant-id。
// 用法：node scripts/verify-login-dual-channel.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

const wxml = read('miniprogram/pages/account/index.wxml');
const js = read('miniprogram/pages/account/index.js');
const auth = read('miniprogram/api/auth.js');
const http = read('miniprogram/api/http.js');
const wxss = read('miniprogram/pages/account/index.wxss');

// 1) 双通道 UI：账号匹配/密码面板 + 微信一键按钮
assert(wxml.includes("loginMode===(index===0?'account':'wechat')"), '登录页提供账号/微信双通道切换');
assert(wxml.includes('data-handler="findAccounts"'), '账号通道提供简写查找');
assert(wxml.includes("'accountLogin'"), '账号通道提供密码登录');
assert(wxml.includes('bindtap="wechatLogin"') && !wxml.includes('open-type="getPhoneNumber"'), '微信通道使用普通一键登录按钮');
// 切生产剔除批：演示账号入口已下线——断言反向（不得再出现演示登录入口）
assert(!wxml.includes('data-handler="login" bindtap="login"'), '演示账号入口已下线（切生产）');

// 2) 登录逻辑：防重复提交、协议校验、首次微信转已有账号绑定
assert(js.includes('if(this.data.loginBusy)return'), '登录动作防重复提交');
assert(js.includes("return this.fail('请先阅读并同意用户协议和隐私政策')"), '两种登录方式都要求先勾选协议');
assert(js.includes('result.needPhone') && js.includes('bindWechatAfterLogin'), '首次微信登录转已有账号绑定');
assert(js.includes("auth.appId()"), '微信登录回传小程序 AppID 供后端白名单校验');
assert(js.includes('auth.myPosts()') && js.includes('preferredPostId'), '登录成功后拉取任职并优先进入默认门店岗位');

// 3) API 层：账号与微信绑定接口、请求头契约
assert(auth.includes("'/platform/auth/account/matches'") && auth.includes("'/platform/auth/account/login'"), 'auth.js 定义账号匹配与密码登录接口');
assert(auth.includes("'/platform/auth/wechat-bind'"), 'auth.js 定义微信绑定接口');
assert(auth.includes('getAccountInfoSync().miniProgram.appId'), 'auth.js 提供 appId 读取');
assert(http.includes("'tenant-id': tenantId()"), 'http 层所有请求带 tenant-id 头');
assert(http.includes('zs-api-tenant-v1'), 'tenant-id 支持通过 storage 覆盖（默认 1）');

// 4) 样式：微信绿主按钮与账号匹配列表
assert(wxss.includes('wechat-login-btn') && wxss.includes('#07c160'), '微信一键登录按钮使用微信绿（官方 #07c160，选择器随官方化改造放宽）');
assert(wxss.includes('.account-match'), '账号候选有独立样式');

console.log('verify-login-dual-channel: 全部断言通过（账号密码/微信双通道契约完整）');
