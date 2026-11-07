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

/** 登录成功后保存令牌与首个岗位（有多个岗位时由用户在身份页切换）。
 * M02-T02/I03：token 落地瞬间先清上一账号的岗位上下文（postId 与缓存任职列表）——
 * 新 token 绝不搭配旧岗位缓存；任职列表由 afterRealLogin 拉取后重建。 */
function saveLogin(result) {
  http.saveSession({ token: result.accessToken, postId: '' });
  wx.removeStorageSync(POSTS_KEY);
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

/** 保存本人任职列表（导航接管与身份切换的数据源）；过滤 posts/mine 对组织停用任职返回的 null 元素 */
function savePosts(posts) {
  wx.setStorageSync(POSTS_KEY, (posts || []).filter(Boolean));
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

/**
 * 登录后任职加载守卫（M02-T02）：afterRealLogin 发起 myPosts 前调用，返回是否仍同会话——
 * 重复登录/换账号/退出使代次前移后，迟到的任职结果不得更新缓存或触发跳页。
 */
function postLoadGuard() {
  return http.sessionGuard();
}

/**
 * 岗位失效恢复（M02-T01/I05）：业务请求返回 1_103_000_002（岗位被撤/组织停用而 token 仍有效）后，
 * 拉取服务端最新有效任职并切换到与失效岗不同的首个有效岗位——服务端真源覆盖缓存，禁止循环切回已撤岗位；
 * 无替代（仅剩失效岗/全部撤销/组织停用唯一岗）或刷新失败则登出并复用 401 登录引导链。成功与失败两路都复位 http 去抖标志。
 * 会话代次护栏（Codex R1 P1）：恢复发起时捕获代次，写缓存/切岗/登出前校验——恢复期间的手动切岗、
 * 401 清场、换号登录都会使代次前移，迟到的恢复结果一律不触碰新会话。
 * @param invalidPostId 刚失效的 postId（http.onPostInvalid 回调传入）
 * @returns Promise<{status:'switched'|'none'|'stale', post?}>
 */
function recoverPostInvalid(invalidPostId) {
  const gen = http.sessionGeneration();
  return myPosts()
    .then(posts => {
      if (http.sessionGeneration() !== gen) return { status: 'stale' };
      const list = (posts || []).filter(Boolean);
      savePosts(list);
      const next = list.find(p => String(p.postId) !== String(invalidPostId));
      if (next) {
        http.saveSession({ postId: next.postId });
        return { status: 'switched', post: next };
      }
      logout();
      http.notifySessionExpired(); // 无替代岗位：复用 401 登录引导链（Codex R1 P2）
      return { status: 'none' };
    })
    .then(
      result => { http.endPostInvalidRecovery(); return result; },
      err => {
        http.endPostInvalidRecovery();
        if (http.sessionGeneration() !== gen) {
          return { status: 'stale' }; // 会话已被换走（如期间登录了新账号）：新会话自行处理，不登出、不按约定抛错
        }
        logout();
        http.notifySessionExpired();
        throw err;
      }
    );
}

module.exports = { login, saveLogin, myPosts, appId, sendSmsCode, smsLogin, savePosts, apiPosts, currentApiPost, switchPost, logout, recoverPostInvalid, postLoadGuard };

// M02-T01/I02：账号失效统一引导——清会话后跳转登录页（account 页 onShow 因会话清空展示登录态）。
// 自带 handle401 四组合处理的页面（customers/workflow 等，09 阶段1 契约：401→回 guest 渲染）豁免全局跳转，
// 由页面自身的失效处理接管（M04 批回归：verify-crm-pages AC9）。
http.onUnauthorized(() => {
  try {
    const pages = getCurrentPages();
    const top = pages[pages.length - 1];
    if (top && typeof top.handle401 === 'function') return; // 页面自理，不全局跳转
  } catch (e) { /* getCurrentPages 不可用时按全局跳转处理 */ }
  wx.reLaunch({ url: '/pages/account/index' });
});

// M02-T01/I05：岗位失效恢复流注册（模块级单例；恢复期间并发失效由 http 层去抖）
http.onPostInvalid(postId => { recoverPostInvalid(postId).catch(() => {}); });
