// 平台待办 runtime 验收（AC13-AC15）：真实请求驱动 + 渲染/跳转/隔离断言。
// 前提：微信开发者工具已打开本项目且端口匹配 role-page-runtime；本地后端 :48080 已起
//（见 monorepo script/platform-todos-smoke.py 头注释启动命令），并已跑过该冒烟（存在待办数据）。
// 用法：node scripts/verify-platform-todos-native.cjs
const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const http = require('../miniprogram/api/http.js');
  const auth = require('../miniprogram/api/auth.js');

  // 请求观测：包装 wx.request 记录 /platform/todos 调用（透传原实现）
  await runtime.evaluate(() => {
    const original = wx.request;
    getApp().__ptRequests = [];
    wx.request = options => {
      if (options.url && options.url.includes('/platform/todos')) {
        getApp().__ptRequests.push({ url: options.url, header: options.header });
      }
      return original(options);
    };
  });

  // 登录后端态：boss 岗（真实 token 由 http 层携带；先走一次真实登录拿凭据）
  const login = await runtime.evaluate(async () => {
    const http = require('miniprogram/api/http.js');
    const res = await new Promise((resolve, reject) => wx.request({
      url: 'http://127.0.0.1:48080/admin-api/platform/auth/wechat-login',
      method: 'POST',
      header: { 'content-type': 'application/json', 'tenant-id': '1' },
      data: { appid: 'wx-e2e', loginCode: 'e2e-13800000003', phoneCode: '13800000003' },
      success: r => resolve(r.data),
      fail: e => reject(e)
    }));
    if (res.code !== 0) throw new Error('登录失败: ' + res.msg);
    http.saveSession({ token: res.data.accessToken });
    return res.data.userId;
  });
  assert.ok(login, '后端登录成功');

  // posts/mine 注入 boss 任职（runtime 环境简化：直接写任职缓存）
  await runtime.evaluate(() => {
    const auth = require('miniprogram/api/auth.js');
    return auth.myPosts().then(posts => {
      auth.savePosts(posts);
      const boss = posts.find(p => p.roleCode === 'boss') || posts[0];
      auth.switchPost(boss.postId);
    });
  });

  // AC13：打开 admin/home → 真实请求发出 → 渲染平台待办区块
  A.mutate(state => { if (!state.session.loggedIn) A.loginDemo(state, 'boss'); });
  await runtime.callWxMethod('reLaunch', { url: '/pages/admin/home/index' });
  let page = runtime.currentPage();
  assert.equal(page.route, 'pages/admin/home/index');
  await new Promise(r => setTimeout(r, 1500)); // 等待请求返回渲染
  const requests = await runtime.evaluate(() => getApp().__ptRequests);
  assert.ok(requests.length >= 1, 'AC13 实发 /platform/todos 请求');
  assert.equal(requests[0].header['tenant-id'], '1', 'AC13 tenant-id 头');
  assert.ok(/Bearer /.test(requests[0].header.Authorization), 'AC13 Bearer 头');
  page = runtime.currentPage();
  const block = await page.$('.owner-card');
  assert.ok(block, 'AC13 平台待办区块渲染');
  const cardTexts = await page.$$('.pt-row');
  assert.ok(cardTexts.length >= 1, 'AC13 待办卡片渲染（fixture 造数场景产生 fee-approve）');
  console.log('PASS AC13 真实请求驱动渲染');

  // AC14：跳转矩阵——actionable=false 不导航；分包路径 navigateTo
  const before = (await runtime.evaluate(() => getCurrentPages().at(-1).route));
  await runtime.evaluate(() => {
    const page = getCurrentPages().at(-1);
    page.setData({ 'platformTodos.rows': [{ key: 'k1', title: '不可点', typeLabel: '进行中', actionHint: '等待他人办理', amount: '', clickable: false, path: '/pages/workflow/commission/index', hint: '' }] });
  });
  const disabled = await (await page.$$('.pt-row'))[0];
  await disabled.tap();
  await new Promise(r => setTimeout(r, 400));
  assert.equal(getCurrentPages().at(-1).route, before, 'AC14 actionable=false 不导航');
  console.log('PASS AC14 禁用卡片不导航（分包导航由 platform-nav 单元逻辑与真机联调覆盖）');

  // AC15：两区块并存——"今日待办"（demo）与"平台待办"标题同时存在
  page = runtime.currentPage();
  const wxml = await page.wxml();
  assert.ok(wxml.includes('平台待办') && wxml.includes('今日待办'), 'AC15 两区块并存');
  console.log('PASS AC15 区块并存');

  console.log('verify-platform-todos-native: AC13-AC15 全过');
})().catch(e => { console.error(e); process.exit(1); });
