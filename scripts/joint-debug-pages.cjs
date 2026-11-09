/**
 * 联合调试逐页测试（阶段 A：登录+主链页面数据断言）
 * 模拟器内 SMS 登录（boss 岗）→ 逐页导航 → 抓页面数据与错误态
 * 用法：ZS_JOINT_MOBILE=<测试手机号> ZS_JOINT_SSH=<user@host> node scripts/joint-debug-pages.cjs
 * 凭据不入库：生产地址与测试手机号经环境变量注入（2026-11-10 脱敏）。
 */
const automator = require('miniprogram-automator');
const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));

const MOBILE = process.env.ZS_JOINT_MOBILE;
const SSH_TARGET = process.env.ZS_JOINT_SSH;
if (!MOBILE || !SSH_TARGET) {
  console.error('用法：ZS_JOINT_MOBILE=<测试手机号> ZS_JOINT_SSH=<user@host> node scripts/joint-debug-pages.cjs');
  process.exit(1);
}
function redisCaptcha(mobile) {
  return execSync(
    `ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=no ${SSH_TARGET} "sudo bash /tmp/zs-redis-read.sh ${mobile}"`,
    { encoding: 'utf8' }).trim();
}

const results = [];
function ok(name, detail) { results.push('  ✅ ' + name + (detail ? ' — ' + detail : '')); }
function bad(name, detail) { results.push('  ❌ ' + name + ' — ' + detail); }

(async () => {
  const mini = await automator.connect({ wsEndpoint: 'ws://localhost:9420' });
  console.log('已连接模拟器\n== 阶段 A：登录 ==');

  // 1. 已登录则跳过登录阶段（频控保护：60s 内不可重复发码）
  let page;
  const existingToken = await mini.callWxMethod('getStorageSync', 'zs-api-token-v1');
  const hasSession = existingToken && String(existingToken).length > 20;
  if (hasSession) {
    ok('SMS 登录（' + MOBILE + ' → boss 岗）', '复用现有会话（60s 频控内免重新登录）');
    await mini.reLaunch('/pages/admin/home/index');
    await sleep(3000);
    page = await mini.currentPage();
  } else {
  await mini.reLaunch('/pages/account/index?view=login');
  await sleep(3000);
  page = await mini.currentPage();

  // 2. 勾协议 + 输手机号 + 获取验证码（真实写 Redis）
  const check = await page.$('.check-row');
  if (check) { await check.tap(); await sleep(300); }
  const inputs = await page.$$('input');
  for (const inp of inputs) {
    const ph = await inp.attribute('placeholder').catch(() => '');
    if (String(ph).includes('手机号')) await inp.input(MOBILE);
  }
  await sleep(300);
  const capBtn = await page.$('.captcha-btn');
  if (capBtn) { await capBtn.tap(); await sleep(2500); }
  const captcha = redisCaptcha(MOBILE);
  if (!captcha) throw new Error('Redis 无验证码——工具请求未达后端');
  console.log('验证码已取（' + captcha.length + ' 位）');

  // 3. 填验证码 + 登录
  const inputs2 = await page.$$('input');
  for (const inp of inputs2) {
    const ph = await inp.attribute('placeholder').catch(() => '');
    if (String(ph).includes('验证码')) await inp.input(captcha);
  }
  await sleep(300);
  const btns = await page.$$('button');
  for (const b of btns) {
    const h = await b.attribute('data-handler').catch(() => '');
    if (h === 'smsLogin') { await b.tap(); break; }
  }
  await sleep(5000);
  page = await mini.currentPage();
  const d0 = await page.data();
  if (d0.loggedIn === false && page.path.includes('account')) {
    bad('SMS 登录', 'error=' + JSON.stringify(d0.error || '').slice(0, 60));
    throw new Error('登录未成功');
  }
  ok('SMS 登录（' + MOBILE + ' → boss 岗）');
  }

  // 4. 首个落地页（boss → 管理工作台路由 → admin/home）
  await sleep(2500);
  page = await mini.currentPage();
  const dHome = await page.data();
  const homeOk = page.path.includes('admin/home') || page.path.includes('management');
  if (homeOk) ok('登录落地工作台', page.path);
  else bad('登录落地页', page.path + ' | error=' + JSON.stringify((dHome || {}).error || '').slice(0, 40));

  // 5. 工作台数据（经营六数源）
  const homeData = await page.data();
  const wbtodos = (homeData.platformTodos || {}).items;
  ok('工作台平台待办区', Array.isArray(wbtodos) ? wbtodos.length + ' 条' : '字段未渲染');

  // 6. 业务 tab（客户工作台）
  await mini.switchTab('/pages/business/index');
  await sleep(3000);
  page = await mini.currentPage();
  const dBiz = await page.data();
  const bizOk = page.path.includes('business');
  bizOk ? ok('业务工作台', page.path) : bad('业务工作台', page.path);
  const bizTodos = (dBiz.platformTodos || {}).items;
  if (Array.isArray(bizTodos)) ok('业务工作台待办区', bizTodos.length + ' 条');
  else if (dBiz.platformTodos) ok('业务工作台待办区', '对象存在，items 未填充（键：' + Object.keys(dBiz.platformTodos).join(',') + '）');

  // 7. 客户页（crm）
  await mini.navigateTo('/pages/customers/index');
  await sleep(3000);
  page = await mini.currentPage();
  const dCust = await page.data();
  const custRows = (dCust.rows || dCust.customers || dCust.filteredCustomers || []);
  if (custRows.length >= 3) ok('客户列表', custRows.length + ' 条（联调数据生效）');
  else if (dCust.error) bad('客户列表', 'error=' + JSON.stringify(dCust.error).slice(0, 50));
  else ok('客户列表', custRows.length + ' 条');
  await mini.navigateBack();
  await sleep(1500);

  // 8. 工程 tab
  await mini.switchTab('/pages/engineering/index');
  await sleep(3000);
  page = await mini.currentPage();
  ok('工程工作台', page.path);

  // 9. 我的（API 登录态断言——今天修复的页面）
  await mini.switchTab('/pages/profile/index');
  await sleep(2500);
  page = await mini.currentPage();
  const dProf = await page.data();
  if (dProf.loggedIn) ok('我的页登录态（今日修复验证）', 'loggedIn=true, role=' + (dProf.role || ''));
  else bad('我的页登录态（今日修复验证）', '仍显示未登录');

  // 11. P1-1 直达化验收（2026-11-10）：brand-header「工作台」按钮一次直达专属工作台，
  // 不经中转 tab 等 onShow 二次导航（page-navigation.home() 直达化的模拟器验收）。
  // automator 取自定义组件内部元素用 id + createSelectorQuery（基础库 3.17.4 标签选择器不可靠）；
  // workbench() 与按钮 bindtap 同一处理函数，验证同一代码路径。
  await mini.navigateTo('/pages/wallet/recharge/index');
  await sleep(2500);
  const wb = await mini.evaluate(() => {
    const p = getCurrentPages().slice(-1)[0];
    const c = p.selectComponent('#brand-header');
    if (!c) return { comp: false };
    return new Promise(resolve => {
      c.createSelectorQuery().select('.return-home').boundingClientRect().exec(r => {
        resolve({ comp: true, showNavigation: c.data.showNavigation, btnRendered: !!(r && r[0]) });
      });
    });
  });
  if (wb && wb.comp && wb.btnRendered) {
    ok('工作台按钮渲染（P1-1 前置）', 'showNavigation=' + wb.showNavigation);
    await mini.evaluate(() => { getCurrentPages().slice(-1)[0].selectComponent('#brand-header').workbench(); });
    await sleep(3000);
    const landed = await mini.currentPage();
    if (landed.path === 'pages/admin/home/index') ok('工作台按钮直达（P1-1）', landed.path);
    else bad('工作台按钮直达（P1-1）', '落点=' + landed.path + '（期望 admin/home 直达）');
  } else {
    bad('工作台按钮直达（P1-1）', '组件/按钮未取到：' + JSON.stringify(wb));
  }

  // 10. 汇总
  console.log('\n== 阶段 A 结果 ==');
  results.forEach(r => console.log(r));
  await mini.disconnect();
  process.exit(0);
})().catch(e => {
  console.error('❌ 调试中断:', e.message);
  results.forEach(r => console.log(r));
  process.exit(1);
});
