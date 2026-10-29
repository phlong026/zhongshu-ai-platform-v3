const assert = require('node:assert/strict');
const fs = require('node:fs');

/*
Cleanup plan:
1. Lock three existing submit controls so styling cleanup cannot remove their tap or disabled semantics.
2. Move only repeated primary/disabled and tab appearance into styles/controls.wxss.
3. Keep layout in page styles and keep finance/task/payment-provider controls outside the shared selectors.
*/

const read = file => fs.readFileSync(file, 'utf8');
const has = (source, pattern, message) => assert(pattern.test(source), message);

const account = read('miniprogram/pages/account/index.wxml');
has(account, /class="solid bottom-inline[^\"]*"[^>]*disabled="\{\{!form\.address\|\|!form\.phone\|\|!form\.photos\.length\}\}"[^>]*bindtap="submitRequest"/, '需求提交保留点击与禁用语义');

const engineering = read('miniprogram/pages/build/materials/index.wxml');
has(engineering, /class="eng-primary eng-submit"[^>]*disabled="\{\{!pendingCount\|\|busy\}\}"[^>]*bindtap="submit"/, '工程提交保留点击与禁用语义');

const commission = read('miniprogram/pages/workflow/commission/index.wxml');
has(commission, /class="commission-primary"[^>]*disabled="\{\{busy\}\}"[^>]*bindtap="submit"/, '委托提交保留点击与禁用语义');
has(commission, /class="cashier-confirm"[^>]*bindtap="confirmCashier"[^>]*disabled="\{\{busy\}\}"/, '微信/支付宝收银台按钮保留独立品牌与禁用语义');

const app = read('miniprogram/app.wxss');
assert(app.includes('@import "./styles/controls.wxss";'), 'app.wxss 须全局引入控件规格');

const controls = read('miniprogram/styles/controls.wxss');
for (const selector of ['.primary', '.solid', '.eng-primary', '.commission-primary']) {
  assert(controls.includes(selector), `共享主按钮须覆盖 ${selector}`);
}
has(controls, /var\(--zs-brand\)/, '主按钮必须使用品牌令牌');
has(controls, /var\(--zs-card\)/, '主按钮文字必须使用卡片令牌');
has(controls, /\[disabled\]|\.is-disabled/, '共享控件必须定义禁用态');
assert(!/pointer-events\s*:\s*none|display\s*:\s*none/.test(controls), '外观层不得替代原生 disabled 或隐藏控件');

for (const selector of ['.tabs', '.seg', '.eng-filters']) {
  assert(controls.includes(selector), `共享 Tab 规格须覆盖 ${selector}`);
}
has(controls, /border-radius\s*:\s*999rpx/, '横向筛选使用胶囊规格');
has(controls, /flex\s*:\s*1/, '等宽分段 Tab 必须均分可用宽度');

for (const forbidden of ['.task-page', '.owner-primary', '.fin-', '.cashier', '.wechat', '.alipay', '.custom-tab', '.owner-nav']) {
  assert(!controls.includes(forbidden), `共享规格不得重染专用控件 ${forbidden}`);
}

const simplified = {
  'miniprogram/styles/unified.wxss': ['.account .solid:not(.is-disabled)', '.tabs.tabs>button'],
  'miniprogram/styles/project-flow.wxss': ['.commission-primary,.commission-secondary', '.admin .solid,.admin .outline'],
  'miniprogram/styles/workbench.wxss': ['.page.workbench-page .primary,.page.workbench-page .outline'],
  'miniprogram/styles/sales.wxss': ['.sales-page.sales-page .primary,.sales-page.sales-page .outline'],
  'miniprogram/pages/studio/index.wxss': ['.studio-tabs {']
};
for (const [file, duplicates] of Object.entries(simplified)) {
  const source = read(file);
  for (const duplicate of duplicates) assert(!source.includes(duplicate), `${file} 应删除已上提的重复外观 ${duplicate}`);
}

console.log('PASS 主按钮、禁用态与两类 Tab 规格共享，交互语义保持不变');
