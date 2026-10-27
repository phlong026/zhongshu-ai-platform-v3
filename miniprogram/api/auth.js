/**
 * 员工账号与岗位（M2.7 第一步接入：微信登录 + 我的任职）。
 * 对应后端 /platform/auth/wechat-login 与 /platform/posts/mine。
 */
const http = require('./http');

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

/** 切换当前岗位 */
function switchPost(postId) {
  http.saveSession({ postId });
}

function logout() {
  http.clearSession();
}

module.exports = { login, saveLogin, myPosts, switchPost, logout };
