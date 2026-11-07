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
const profile = read('miniprogram/utils/platform-profile.js');

// 1) 登录按钮：官方绿底 + 官方图标 + 手机号快捷授权保留（员工账号匹配需手机号）
assert(wxml.includes('open-type="getPhoneNumber"') && wxml.includes('bindgetphonenumber="wechatLogin"'), '微信一键登录保留手机号快捷授权（员工账号匹配）');
assert(wxml.includes('class="wechat-login-icon" src="/pages/account/assets/wechat-logo.png"'), '一键登录按钮使用微信官方双气泡图标');
assert(fs.existsSync(path.join(root, 'miniprogram/pages/account/assets/wechat-logo.png')), '微信官方图标资产存在');
assert(wxss.includes('.login-card .solid.wechat-login-btn{display:flex;align-items:center;justify-content:center;gap:12rpx;background:#07c160;color:#fff}'), '一键登录按钮为微信官方绿（特异性高于品牌渐变）');
assert(wxss.includes('.wechat-login-icon{width:44rpx;height:44rpx;flex-shrink:0}'), '官方图标尺寸样式存在');

// 2) 资料页：微信官方头像昵称填写能力（2022-10 后 getUserProfile 已废，官方唯一正道）
assert(wxml.includes('open-type="chooseAvatar"') && wxml.includes('bind:chooseavatar="onChooseAvatar"'), '头像按钮走官方 chooseAvatar 能力');
assert(wxml.includes('type="nickname"'), '姓名输入启用官方昵称快捷填写（type=nickname）');
assert(js.includes('onChooseAvatar(e)'), 'onChooseAvatar 处理函数已接线');
assert(js.includes('e.detail&&e.detail.avatarUrl'), '从官方回调取头像临时路径');

// 2.5) 登录页品牌化与文案精简（对标同行：logo+品牌名+按钮+协议，说明文字动态化）
assert(wxml.includes('class="login-brand" src="/assets/brand.png"'), '登录页品牌区使用品牌字标 logo');
assert(wxml.includes('<text class="large">众墅之家</text>'), '大标题为品牌名（导航栏「登录」不再重复）');
assert(!wxml.includes('wechat-login-tip') && !wxml.includes('使用微信绑定的手机号一键登录'), '微信 tab 静态说明文字已精简删除');
assert(!wxml.includes('无法授权手机号时'), '静态 fallback 提示已删（退路改为失败时动态提示）');
assert(wxml.includes('<text>微信一键登录</text>'), '按钮文案与 tab 同名（精简）');
assert(js.includes("return this.fail('已取消手机号授权，可改用验证码登录')"), '拒绝授权零网络请求早退并动态提示验证码退路');
assert(wxss.includes('.login-brand{display:block;margin:0 auto 24rpx;width:440rpx;height:120rpx}'), '品牌字标横条样式存在');

// 3) 上传链：http.upload 契约 + 头像落库（PUT avatar URL，后端 08 文档 avatar 键语义）
assert(http.includes("name: 'file'") && http.includes('formData'), 'http.upload 为 multipart file 字段封装');
assert(http.includes("function upload(path, filePath, formData)"), 'http.upload 已导出');
assert(http.includes("handleUnauthorized(requestSessionGen, null, reject)"), '上传 401 与 request 同契约处理');
assert(js.includes("http.upload('/infra/file/upload'"), '头像经底座上传通道获取 URL');
assert(js.includes("{nickname:name,avatar:url}"), '头像 URL 随姓名一并 PUT /platform/profile/mine');
assert(js.includes('saveAvatarLocal(temp)'), '未登录时头像仅本机保存（不静默丢弃）');
assert(js.includes("if(!/^https?:\\/\\//.test(String(url||'')))"), '上传返回 URL 校验，非 URL 显式报错');

// 4) mergeProfile：后端 avatar URL 为真源；后端 null 保留本地
assert(profile.includes("if (remote && typeof remote.avatar === 'string' && remote.avatar) next.avatar = remote.avatar;"), '后端头像 URL 非空即合入（真源口径）');

console.log('verify-wechat-login-official ✔  全部断言通过（官方图标登录 + 头像昵称官方能力 + 上传链契约完整）');
