const assert = require('node:assert/strict');
const path = require('node:path');

async function main() {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const { render } = require('./role-page-runtime.cjs');
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  A.mutate(account => A.loginDemo(account, 'sales-dual'));
  const customer = B.add({ name: '客户工作区验收', phone: '13900008824', source: '朋友介绍', province: '湖北省', city: '黄冈市', summary: '两层乡墅，首层老人房' });
  const page = () => getCurrentPages().at(-1);
  const open = () => runtime.callWxMethod('reLaunch', { url: '/pages/workflow/index?view=customer&id=' + customer.id });
  const tap = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '可点击控件 ' + selector);
    await element.tap();
  };
  const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children)]);
  const text = node => (node.text || '') + node.children.map(text).join('');
  const nodes = () => flatten(render(path.resolve('miniprogram/pages/workflow/index.wxml'), page().data));
  const headings = () => nodes().filter(node => String(node.attrs?.class || '').split(' ').includes('heading')).map(node => text(node).replace(/\s/g, ''));
  const followButtons = () => nodes().filter(node => node.tag === 'button' && node.attrs['data-modal'] === 'follow');
  const checks = [];

  await open();
  assert.equal(text(followButtons()[0]), '记录首次跟进');
  assert.equal(followButtons().length, 1);
  assert.deepEqual(headings(), ['客户资料编辑']);
  await tap('[data-modal="follow"]');
  assert.equal(page().data.modal, 'follow');
  assert(nodes().some(node => node.attrs?.class === 'sheet-head' && text(node).trim() === '记录首次跟进'));
  page().closeModal();
  checks.push('概况展示客户资料与唯一的记录首次跟进按钮，弹窗名称一致');

  await tap('.customer-tabs [data-index="1"]');
  assert.deepEqual(headings(), ['客户资料编辑', '沟通记录', '业务办理']);
  assert.equal(followButtons().length, 1);
  assert.equal(followButtons()[0].parent.attrs.class, 'page-actions customer-follow-actions');
  const actions = nodes().filter(node => node.tag === 'button' && node.parent?.attrs.class === 'tool-grid');
  assert.deepEqual(actions.map(text), ['检索户型', 'AI设计', '快速预算', '委托设计']);
  checks.push('跟进页按客户资料、沟通记录、四项业务办理、底部记录按钮排列');

  await tap('[data-modal="edit"]');
  page().setData({ 'form.summary': '已核对本客户资料，增加二层露台' });
  await tap('.sheet-save');
  assert.equal(page().data.activeCustomerTab, 1, '编辑客户后保留跟进栏目');
  assert.equal(B.customer(customer.id).summary, '已核对本客户资料，增加二层露台');
  await tap('[data-modal="follow"]');
  page().setData({ 'form.method': '微信', 'form.followContent': '已确认二层露台需求', 'form.appointment': A.localDate() + ' 16:30', 'form.next': '电话联系' });
  await tap('.sheet-save');
  assert(!page().data.error, page().data.error);
  assert.equal(page().data.activeCustomerTab, 1, '保存跟进后保留跟进栏目');
  assert.equal(B.customer(customer.id).follows.length, 1);
  assert.equal(B.customer(customer.id).follows[0].content, '已确认二层露台需求');
  assert.equal(text(followButtons()[0]), '记录跟进');
  assert.equal(followButtons().length, 1);
  checks.push('编辑和跟进写回同一客户并保留跟进栏目，历史记录与按钮即时更新');

  for (const view of ['search', 'ai-config', 'budget', 'request']) {
    await open();
    await tap('.customer-tabs [data-index="1"]');
    await tap('.tool-grid [data-view="' + view + '"]');
    assert.equal(page().id, customer.id, view + ' 保留客户身份');
    if (view === 'request') assert.equal(page().route, 'pages/workflow/commission/index');
    else assert.equal(page().data.view, view);
  }
  checks.push('四项业务办理均可点击并保留原客户，委托进入现有委托流程');

  A.mutate(account => A.loginDemo(account, 'boss'));
  await open();
  assert.equal(page().data.canFollow, false);
  assert.equal(followButtons().length, 0, '只读岗位不能写跟进');
  checks.push('只读岗位保留原权限边界');
  console.log(JSON.stringify({ result: 'passed', evidence: '真实 Page/WXML 集成', checks }, null, 2));
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
