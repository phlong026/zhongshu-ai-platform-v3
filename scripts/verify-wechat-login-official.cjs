// 微信官方登录与头像昵称能力静态验证（对标同行做法）：
// 1) 一键登录按钮=微信官方绿 + 官方双气泡图标；2) 资料页=官方头像昵称填写能力（chooseAvatar/type=nickname）
// 3) 头像上传链（http.upload → /infra/file/upload → PUT /platform/profile/mine avatar URL）。
// 用法：node scripts/verify-wechat-login-official.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

const wxml = read('miniprogram/pages/account/index.wxml');
const js = read('miniprogram/pages/account/index.js');
const http = read('miniprogram/api/http.js');
const wxss = read('miniprogram/pages/account/index.wxss');
const profile = require('../miniprogram/utils/platform-profile');

// 1) 登录按钮：官方绿底 + 官方图标；绑定由登录后的同账号接口完成
assert(wxml.includes('bindtap="wechatLogin"') && wxml.includes('open-type="getPhoneNumber"'), '首次微信登录使用官方手机号授权');
assert(wxml.includes('class="wechat-login-icon" src="/pages/account/assets/wechat-logo.png"'), '一键登录按钮使用微信官方双气泡图标');
assert(fs.existsSync(path.join(root, 'miniprogram/pages/account/assets/wechat-logo.png')), '微信官方图标资产存在');
assert(wxss.includes('.login-card .solid.wechat-login-btn{display:flex;align-items:center;justify-content:center;gap:12rpx;background:#07c160!important;color:#fff!important}'), '一键登录按钮为微信官方绿（!important 穿透品牌渐变）');
assert(wxss.includes('.wechat-login-icon{width:44rpx;height:44rpx;flex-shrink:0}'), '官方图标尺寸样式存在');

// 2) 资料页：微信官方头像昵称填写能力（2022-10 后 getUserProfile 已废，官方唯一正道）
assert(wxml.includes('open-type="chooseAvatar"') && wxml.includes('bind:chooseavatar="onChooseAvatar"'), '头像按钮走官方 chooseAvatar 能力');
assert(wxml.includes('type="nickname"'), '姓名输入启用官方昵称快捷填写（type=nickname）');
assert(js.includes('onChooseAvatar(e)'), 'onChooseAvatar 处理函数已接线');
assert(js.includes('e.detail&&e.detail.avatarUrl'), '从官方回调取头像临时路径');

// 2.5) 登录页品牌化与文案精简（对标同行：logo 即标题+按钮+协议，说明文字动态化）
assert(wxml.includes('class="login-brand" src="/assets/brand.png"'), '登录页品牌区使用品牌字标 logo');
assert(!wxml.includes('<text class="large">众墅之家</text>'), '大标题已删（logo 自含品牌名，不再重复）');
assert(!wxml.includes('wechat-login-tip') && !wxml.includes('使用微信绑定的手机号一键登录'), '微信 tab 静态说明文字已精简删除');
assert(!wxml.includes('无法授权手机号时'), '静态 fallback 提示已删（退路改为失败时动态提示）');
assert(wxml.includes('<text>微信一键登录</text>'), '按钮文案与 tab 同名（精简）');
assert(js.includes('wechatPhoneRequired:true') && !js.includes('bindWechatAfterLogin'), '未识别微信时授权手机号，不要求手动绑定');
assert(js.includes('phoneCode:phoneCode||undefined'), '微信授权 code 提交后端自动匹配');
assert(wxss.includes('.login-brand{display:block;margin:0 auto 24rpx;width:440rpx;height:120rpx}'), '品牌字标横条样式存在');
assert(wxss.includes('background:#07c160!important;color:#fff!important}'), '一键登录按钮官方绿以 !important 穿透（实测普通声明被品牌渐变压过）');
assert(wxss.includes('.wechat-actions{margin-top:40rpx}'), '微信 tab seg 与按钮拉开呼吸间距（40rpx）');
const layout = read('miniprogram/pages/account/layout.wxss');
assert(!layout.includes('.login-card>.solid{margin:32rpx 0 15rpx}'), '按钮老结构死规则已清（page-actions 包裹后无目标）');

// 3) 上传链：http.upload 契约 + 头像落库（PUT avatar URL，后端 08 文档 avatar 键语义）
assert(http.includes("name: 'file'") && http.includes('formData'), 'http.upload 为 multipart file 字段封装');
assert.equal(typeof require('../miniprogram/api/http').upload, 'function', 'http.upload 已导出');
const uploadSource = http.slice(http.indexOf('function upload('), http.indexOf('module.exports'));
const requestSource = http.slice(http.indexOf('function request('), http.indexOf('function upload('));
const sharedUnauthorized = 'retryUnauthorized(requestSessionGen, sentToken, opts,';
assert(uploadSource.includes(sharedUnauthorized) && requestSource.includes(sharedUnauthorized)
  && uploadSource.includes('res.statusCode === 401') && uploadSource.includes('body && body.code === 401'),
  '上传 HTTP/body 401 与 request 复用同一续期及失效处理');
assert(js.includes("http.upload('/infra/file/upload'"), '头像经底座上传通道获取 URL');
assert(js.includes("{nickname:name,avatar:url}"), '头像 URL 随姓名一并 PUT /platform/profile/mine');
assert(js.includes('saveAvatarLocal(temp)'), '未登录时头像仅本机保存（不静默丢弃）');
assert(js.includes('String(url).length>512') && js.includes('头像地址无效'), '头像 URL 非法或超长时拒绝保存');

// 4) mergeProfile：完整后端资料为真源；null 表示未设置，恢复默认头像。
const cached = { apiUserId: '7', name: '当前用户', phone: '13800000000', avatar: 'https://cdn/old.png' };
const remote = { id: 7, nickname: '当前用户', mobile: '13800000000', avatar: 'https://cdn/new.png' };
assert.equal(profile.mergeProfile(cached, remote, 7).avatar, remote.avatar, '后端头像 URL 合入同用户资料');
assert.equal(profile.mergeProfile(cached, { ...remote, avatar: null }, 7).avatar, '/assets/account-avatar.jpg', '后端空头像不保留旧头像');

console.log('verify-wechat-login-official ✔  全部断言通过（官方图标登录 + 头像昵称官方能力 + 上传链契约完整）');
assert(wxml.includes('login-consent-panel') && !wxml.includes('login-agreement'), '协议改为两通道共用弹窗');
assert(wxss.includes('.login-password-input{letter-spacing:0!important'), '密码输入恢复正常字距');
