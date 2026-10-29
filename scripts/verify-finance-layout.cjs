const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const read = file => fs.readFileSync(path.resolve(file), 'utf8');

async function main() {
  const { A } = await require('./verify-owner-workbench.cjs').fixture();
  const D = require('../miniprogram/utils/finance-desk');
  const homeTemplate = read('miniprogram/pages/admin/finance-desk/finance-home.wxml');
  const deskTemplate = read('miniprogram/pages/admin/finance-desk/index.wxml');
  const deskStyles = read('miniprogram/pages/admin/finance-desk/index.wxss');
  const deskController = read('miniprogram/pages/admin/finance-desk/index.js');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };

  assert(homeTemplate.includes('class="fin-home-intro"'));
  assert(homeTemplate.includes('bindchange="financeMonth"'));
  assert(homeTemplate.includes('class="fin-home-metric {{item.tone}}"'));
  assert(homeTemplate.includes('data-url="{{item.url}}" bindtap="go"'));
  assert(homeTemplate.indexOf('fin-home-summary') < homeTemplate.indexOf('fin-home-list'));
  assert(homeTemplate.indexOf('fin-home-list') < homeTemplate.indexOf('fin-home-shortcuts'), '财务首页顺序应为指标、待办、常用功能、项目进度');
  assert(homeTemplate.indexOf('fin-home-shortcuts') < homeTemplate.indexOf('fin-home-progress'));
  for (const title of ['待办事项', '常用快捷办理', '正在办理']) assert(homeTemplate.includes('>' + title + '<'));
  assert(!homeTemplate.includes('fin-identity'), '首页第五区后不应追加身份或说明板块');
  assert(homeTemplate.includes('wx:if="{{!desk.previewTasks.length}}"'));
  assert(homeTemplate.includes('wx:if="{{!desk.focus.length}}"'));
  assert(deskTemplate.includes('<import src="./finance-home.wxml"/>'));
  assert(deskTemplate.includes('<template is="financeHome" data="{{desk:deskHome}}"/>'));
  assert(!fs.existsSync(path.resolve('miniprogram/pages/management/finance-home.wxml')), '旧管理页财务模板应已删除');
  assert(!/linear-gradient|#164f3b|#0d392d/.test(deskStyles));
  assert(!/常用原流程|真实记录|视图|派生|口径|事实/.test(homeTemplate));
  assert(deskController.includes('financeMonth(e){this.month(e);}'), '独立财务桌面必须代理首页月份交互');
  pass('独立财务桌面承载首页布局，保留月份、指标和待办交互');

  assert(deskTemplate.includes('class="fin-desk-tools"'));
  assert(deskTemplate.includes('bindinput="search"'));
  assert(deskTemplate.includes('bindtap="segment"'));
  assert(deskTemplate.includes('bindchange="pick"'));
  assert(deskTemplate.includes('bindtap="go"'));
  assert(deskTemplate.includes('id="finance-desk-nav"'));
  assert(deskTemplate.includes('fin-list-empty'));
  assert(!/linear-gradient|#164f3b|#0d392d/.test(deskStyles));
  assert(!/按原单核对每笔金额、状态与办理入口|真实记录|系统自动更新行政待办/.test(deskTemplate + deskController));
  for (const method of ['search(e)', 'clear()', 'segment(e)', 'filter(e)', 'pick(e)', 'month(e)', 'go(e)']) assert(deskController.includes(method), method + ' 必须保留');
  pass('财务明细保留搜索筛选、原单操作和底部导航并提供明确空态');

  A.mutate(account => A.loginDemo(account, 'finance-wang'));
  const stored = wx.getStorageSync('zhongshu-management-v1');
  const book = stored.companyId === 'quanzhou' ? stored : stored.books.quanzhou;
  book.managementSettings = { ...(book.managementSettings || {}), company: { ...(book.managementSettings?.company || {}), displayName: '财务布局测试门店' } };
  wx.setStorageSync('zhongshu-management-v1', stored);
  const desk = D.home(D.load());
  assert.equal(desk.company, '财务布局测试门店');
  assert.equal(desk.layout, undefined);
  assert(desk.metrics.every(item => item.url), '汇总指标必须沿用真实页面入口');
  assert(desk.shortcuts.every(item => item.url), '快捷办理必须沿用真实流程入口');
  assert.equal(desk.previewTasks.length, Math.min(3, desk.tasks.length));
  pass('财务首页数据、待办和入口均沿用现有业务数据');

  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
