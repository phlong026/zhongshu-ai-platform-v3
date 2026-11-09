/**
 * 联合调试登录（连接已就绪版本——工具已由 CLI auto 打开）
 * 用法：ZS_JOINT_MOBILE=<测试手机号> ZS_JOINT_SSH=<user@host> node scripts/joint-debug-login.cjs
 * 凭据不入库：生产地址与测试手机号经环境变量注入（2026-11-10 脱敏）。
 */
const automator = require('miniprogram-automator');
const { execSync } = require('child_process');

const MOBILE = process.env.ZS_JOINT_MOBILE;
const SSH_TARGET = process.env.ZS_JOINT_SSH;
if (!MOBILE || !SSH_TARGET) {
  console.error('用法：ZS_JOINT_MOBILE=<测试手机号> ZS_JOINT_SSH=<user@host> node scripts/joint-debug-login.cjs');
  process.exit(1);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

function redisCaptcha() {
  return execSync(
    `ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=no ${SSH_TARGET} "sudo redis-cli -h 127.0.0.1 -a \\"\\$(grep '^ZS_REDIS_PASSWORD=' /opt/zhongshu/env.merged | cut -d= -f2)\\" --no-auth-warning GET 'platform:sms:captcha:${MOBILE}'"`,
    { encoding: 'utf8' }).trim();
}

(async () => {
  const mini = await automator.connect({ wsEndpoint: 'ws://localhost:9420' });
  console.log('已连接');

  await mini.reLaunch('/pages/account/index?view=login');
  await sleep(3000);
  let page = await mini.currentPage();
  console.log('页面:', page.path);

  // 协议勾选（登录前必须）
  const check = await page.$('.check-row');
  if (check) { await check.tap(); await sleep(400); console.log('协议已勾'); }

  // 手机号输入（第一个 input）
  const inputs = await page.$$('input');
  console.log('input 数:', inputs.length);
  for (const inp of inputs) {
    const ph = await inp.attribute('placeholder').catch(() => '');
    if (String(ph).includes('手机号')) { await inp.input(MOBILE); console.log('手机号已输'); break; }
  }
  await sleep(300);

  // 获取验证码（data-handler sendCaptcha）
  const cap = await page.$('.captcha-btn');
  if (cap) { await cap.tap(); console.log('已点获取验证码'); }
  await sleep(3500);

  const code = redisCaptcha();
  console.log('Redis 验证码:', code ? 'OK(6位)' : '空');
  if (!code) throw new Error('Redis 无码');

  // 验证码输入（第二个 number input）
  const inputs2 = await page.$$('input');
  for (const inp of inputs2) {
    const ph = await inp.attribute('placeholder').catch(() => '');
    if (String(ph).includes('验证码')) { await inp.input(code); console.log('验证码已填'); break; }
  }
  await sleep(300);

  // 登录按钮（smsLogin handler）
  const btns = await page.$$('button');
  for (const b of btns) {
    const h = await b.attribute('data-handler').catch(() => '');
    if (h === 'smsLogin') { await b.tap(); console.log('已点登录'); break; }
  }
  await sleep(4500);

  page = await mini.currentPage();
  console.log('登录后页面:', page.path);
  const data = await page.data();
  console.log('数据键:', Object.keys(data || {}).filter(k => ['loggedIn','view','identity','apiLoggedIn','selectedRole'].includes(k)).join(','));
  console.log(data && data.loggedIn !== undefined ? 'loggedIn=' + data.loggedIn : '');

  console.log('\n✅ 登录链路联调完成');
  await mini.disconnect();
})().catch(e => { console.error('❌', e.message); process.exit(1); });
