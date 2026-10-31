/**
 * 员工账号与岗位（M2.7 第一步接入：微信登录 + 我的任职）。
 * 对应后端 /platform/auth/wechat-login 与 /platform/posts/mine。
 */
const http = require('./http');

const POSTS_KEY = 'zs-api-posts-v1';

/** 微信登录：wx.login 拿 code；后端需要手机号授权时返回 needPhone，再带 phoneCode 重试 */
function login(options) {
  const opts = options || {};
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (!res.code) {
          reject({ code: -1, message: '微信登录失败：未获取到 code' });
          return;
        }
        http
          .post('/platform/auth/wechat-login', {
            appid: opts.appid,
            loginCode: res.code,
            phoneCode: opts.phoneCode || undefined,
            needPhone: opts.needPhone || false
          })
          .then(resolve)
          .catch(reject);
      },
      fail() {
        reject({ code: -1, message: '微信登录失败，请重试' });
      }
    });
  });
}

/** 登录成功后保存令牌与首个岗位（有多个岗位时由用户在身份页切换） */
function saveLogin(result) {
  http.saveSession({ token: result.accessToken });
  if (result.posts && result.posts.length) {
    http.saveSession({ postId: result.posts[0].postId });
  }
}

/** 本人全部有效任职；postId 用于 X-Post-Id 切换当前岗位 */
function myPosts() {
  return http.get('/platform/posts/mine');
}

/** 当前小程序 AppID（登录请求需带回给后端做接入白名单校验） */
function appId() {
  try {
    return wx.getAccountInfoSync().miniProgram.appId;
  } catch (e) {
    return '';
  }
}

/** 发送登录短信验证码（后端内置限流：60s 一条、每日上限） */
function sendSmsCode(mobile) {
  return http.post('/platform/auth/sms/send', { mobile });
}

/** 手机号 + 验证码登录；返回 {userId, accessToken, refreshToken, expiresTime} */
function smsLogin(mobile, captcha) {
  return http.post('/platform/auth/sms/login', { mobile, captcha });
}

/** 保存本人任职列表（导航接管与身份切换的数据源） */
function savePosts(posts) {
  wx.setStorageSync(POSTS_KEY, posts || []);
}

/** 已缓存的本人任职列表 */
function apiPosts() {
  return wx.getStorageSync(POSTS_KEY) || [];
}

/** 当前生效岗位：对齐 X-Post-Id；未指定时取首个（与 saveLogin 一致） */
function currentApiPost() {
  const list = apiPosts();
  if (!list.length) return null;
  const current = http.currentPostId();
  return list.find(p => String(p.postId) === String(current)) || list[0];
}

/** 切换当前岗位 */
function switchPost(postId) {
  http.saveSession({ postId });
}

function logout() {
  http.clearSession();
  wx.removeStorageSync(POSTS_KEY);
}

module.exports = { login, saveLogin, myPosts, appId, sendSmsCode, smsLogin, savePosts, apiPosts, currentApiPost, switchPost, logout };
