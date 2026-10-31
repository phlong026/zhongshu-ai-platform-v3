// 登录双通道改造静态验证：手机号验证码登录 + 微信一键登录 + 演示入口保留 + tenant-id。
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

// 1) 双通道 UI：seg 切换 + 手机号验证码面板 + 微信一键按钮
assert(wxml.includes("loginMode===(index===0?'sms':'wechat')"), '登录页提供手机号/微信双通道切换');
assert(wxml.includes('data-handler="sendCaptcha"'), '手机号通道有获取验证码按钮');
assert(wxml.includes('data-handler="smsLogin"'), '手机号通道有验证码登录按钮');
assert(wxml.includes('open-type="getPhoneNumber"') && wxml.includes('bindgetphonenumber="wechatLogin"'), '微信通道用手机号快捷授权一键登录');
assert(wxml.includes('data-handler="login" bindtap="login"'), '演示账号入口保留');

// 2) 登录逻辑：倒计时、防重复提交、协议校验、needPhone 二段式
assert(js.includes('countdown:60'), '验证码发送后启动60秒倒计时');
assert(js.includes('if(this.data.loginBusy)return'), '登录动作防重复提交');
assert(js.includes("return this.fail('请先阅读并同意用户协议和隐私政策')"), '两种登录方式都要求先勾选协议');
assert(js.includes('result.needPhone'), '微信登录处理 needPhone 二段式手机号授权');
assert(js.includes("auth.appId()"), '微信登录回传小程序 AppID 供后端白名单校验');
assert(js.includes('auth.myPosts()') && js.includes("posts[0].postId"), '登录成功后拉取任职并保存当前岗位');
assert(js.includes('onUnload(){if(this.data.captchaTimer)clearInterval(this.data.captchaTimer);'), '页面卸载清理验证码倒计时定时器');

// 3) API 层：短信接口与请求头契约
assert(auth.includes("'/platform/auth/sms/send'") && auth.includes("'/platform/auth/sms/login'"), 'auth.js 定义短信发送与短信登录接口');
assert(auth.includes('getAccountInfoSync().miniProgram.appId'), 'auth.js 提供 appId 读取');
assert(http.includes("'tenant-id': tenantId()"), 'http 层所有请求带 tenant-id 头');
assert(http.includes('zs-api-tenant-v1'), 'tenant-id 支持通过 storage 覆盖（默认 1）');

// 4) 样式：微信绿主按钮与验证码按钮
assert(wxss.includes('.wechat-login-btn{background:#07c160}'), '微信一键登录按钮使用微信绿');
assert(wxss.includes('.captcha-btn'), '验证码按钮有独立样式');

console.log('verify-login-dual-channel: 全部断言通过（双通道登录 UI/逻辑/API 契约完整）');
