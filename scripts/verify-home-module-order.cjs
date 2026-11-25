const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');

const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children)]);
const hasClass = (node, name) => String(node.attrs?.class || '').split(/\s+/).includes(name);
const text = node => (node.text || '') + node.children.map(text).join('');
const flush = async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); };

test('门店首页保持问候概览待办，总部三岗位进入带品牌公司范围的只读工作台', async () => {
  const runtime = await connect();
  const auth = require('../miniprogram/api/auth');
  const organization = require('../miniprogram/utils/organization');
  const cases = [
    ['store', 'sales', 'sales-greeting', 'sales-stat', 'platform-todos-section'],
    ['store', 'sales-lead', 'sales-greeting', 'sales-stat', 'platform-todos-section'],
    ['store', 'engineer', 'eng-hello', 'stat-value', 'eng-section'],
    ['store', 'project-manager', 'eng-hello', 'stat-value', 'eng-section'],
    ['store', 'finance', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['store', 'finance-lead', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['store', 'boss', 'owner-hello', 'home-six-item', 'owner-card'],
    ['store', 'owner', 'owner-hello', 'home-six-item', 'owner-card'],
    ['headquarters', 'boss', 'owner-hello', 'home-six-item', 'owner-card'],
    ['headquarters', 'hq-admin'],
    ['headquarters', 'finance', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['headquarters', 'finance-lead', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['headquarters', 'hq-design-admin', 'admin-greeting', 'admin-metric', 'admin-section']
  ];
  let nickname;
  global.wx.request = options => {
    const url = new URL(options.url).pathname.replace('/admin-api', '');
    let data = [];
    if (url === '/platform/profile/mine') data = { nickname };
    else if (url === '/platform/headquarters/context') {
      const post=auth.currentApiPost();data={tenantId:'1',headquartersOrgId:post.orgId,postId:post.postId,roleCode:post.roleCode,readOnly:true,brands:[],companies:[]};
    }
    else if (url === '/platform/headquarters/summary') data={customers:0,projects:0,readOnly:true};
    else if (url.startsWith('/platform/headquarters/finance/')) data={list:[],total:0,pageNo:1,pageSize:20,readOnly:true};
    else if (url === '/platform/todos') data = { items: [], failedDomains: [], actionableCount: 0 };
    else if (url === '/franchise/commissions' || url === '/franchise/crm/customers' || url.includes('design-fees')) data = { list: [], total: 0 };
    else if (url === '/franchise/performance/commission/rows') data = []; // Separate commission read returns a list.
    else if (url.includes('performance')) data = { departments: {}, totals: {}, trend: [], company: {}, members: [], departmentsGoals: {} };
    else if (url.includes('growth')) data = {};
    setImmediate(() => options.success({ statusCode: 200, data: { code: 0, data } }));
  };
  for (const [orgType, roleCode, greetingClass, metricClass, todoClass] of cases) {
    nickname = orgType + '-' + roleCode;
    const post = { postId: nickname, userId:'701',tenantId:'1',orgId: nickname, orgName: '公司名称不能充当姓名', orgType, roleCode };
    auth.saveLogin({ accessToken: 'test-' + nickname,userId:'701' }); auth.savePosts([post]); auth.switchPost(post.postId);
    const home = organization.navigation(organization.apiContext(post)).tabs[0];
    await runtime.callWxMethod(home.native ? 'switchTab' : 'reLaunch', { url: home.url });
    await flush();
    const page = getCurrentPages().at(-1);
    const file = path.resolve(__dirname, '../miniprogram', page.route + '.wxml');
    const nodes = flatten(render(file, page.data));
    if(organization.headquartersReader(post)){
      assert.equal(page.route,'pages/headquarters/index');assert.equal(page.data.ready,true);
      assert.equal(page.data.section,roleCode==='hq-admin'?'overview':'finance');
      const heading=nodes.find(n=>hasClass(n,'hq-heading-row')),scope=nodes.find(n=>hasClass(n,'hq-scope')),sections=nodes.find(n=>hasClass(n,'hq-tabs'));
      assert.ok(heading&&scope&&sections,nickname+' headquarters scope controls');
      assert.ok(text(heading).includes('全品牌 · 只读'));assert.ok(text(scope).includes('全部品牌')&&text(scope).includes('全部公司'));
      assert.ok(nodes.indexOf(heading)<nodes.indexOf(scope)&&nodes.indexOf(scope)<nodes.indexOf(sections));
      assert.equal(nodes.some(n=>n.tag==='todo-preview'),false,'read-only headquarters has no actionable business todos');
      continue;
    }
    const greeting = nodes.find(n => hasClass(n, greetingClass));
    assert.ok(greeting, nickname + ' greeting exists');
    assert.ok(text(greeting).includes(nickname), nickname + ' shows authenticated profile name');
    const overview=nodes.find(n=>n.tag==='home-overview');
    const todo=nodes.find(n=>n.tag==='todo-preview' || (hasClass(n,todoClass) && /今日待办|待办事项|待办加载中/.test(text(n))));
    assert.ok(todo,nickname+' todo module');
    if (roleCode==='boss' || roleCode==='owner') {
      assert.equal(todo.attrs.title,'今日待办');
      assert.equal(todo.attrs['owner-mode'],true);
    }
    let first,last;
    if ((orgType==='headquarters' && roleCode.startsWith('finance')) || roleCode==='hq-design-admin') {
      const cards=nodes.filter(n=>hasClass(n,metricClass));assert.equal(cards.length,4,nickname+' headquarters cards');
      first=cards[0];last=cards.at(-1);
      if(roleCode.startsWith('finance'))assert.ok(!text(last).includes('¥'),'task count is not money');
    } else {
      assert.ok(overview,nickname+' shared overview');
      const expected=roleCode.startsWith('sales')?'business':['boss','owner'].includes(roleCode)?'owner':roleCode.startsWith('finance')?'finance':'engineering';
      assert.equal(overview.attrs.role,expected);first=last=overview;
    }
    assert.ok(nodes.indexOf(greeting)<nodes.indexOf(first) && nodes.indexOf(last)<nodes.indexOf(todo),nickname+' greeting → overview → todos');
  }
  // The owner's engineering tab uses a separate overview page rather than the engineer home.
  const boss={postId:'boss-engineering',orgId:'store',orgType:'store',roleCode:'boss'};
  nickname='老板工程总览';auth.saveLogin({accessToken:'boss-token'});auth.savePosts([boss]);auth.switchPost(boss.postId);
  await runtime.callWxMethod('switchTab',{url:'/pages/engineering/index'});await flush();
  const overview=getCurrentPages().at(-1);
  const nodes=flatten(render(path.resolve(__dirname,'../miniprogram/pages/engineering/index.wxml'),overview.data));
  const hello=nodes.find(n=>hasClass(n,'engineering-greeting'));
  const grid=nodes.find(n=>hasClass(n,'eng-shortcuts'));
  assert.ok(text(hello).includes(nickname));assert.equal(grid.children.filter(n=>n.tag==='button').length,4);
  const todo=nodes.find(n=>hasClass(n,'guest-todos'));
  assert.ok(nodes.indexOf(hello)<nodes.indexOf(grid)&&nodes.indexOf(grid)<nodes.indexOf(todo));
});

test('空待办、加载和失败分别显示，岗位概览始终可见', () => {
  const owner=require('../miniprogram/utils/platform-owner-home').view({name:'李明',period:'2026-11'});
  const cases=[['business/index',{isSalesHome:true,salesName:'李明',salesShortcuts:[]}],['admin/home/index',{ready:true,desk:owner}],['admin/finance-desk/index',{financeRefreshing:false,ready:true,view:'desk',deskHome:{name:'陈晨',hq:false,tasks:[]},platformFinance:{status:'ready'}}]];
  for(const [file,data] of cases)for(const status of ['loading','degraded','partial-empty','ready']){
    const nodes=flatten(render(path.resolve(__dirname,'../miniprogram/pages',file+'.wxml'),{...data,guest:false,platformTodos:{status,rows:[],count:0}}));
    const top=nodes.find(n=>n.tag==='home-overview');assert.ok(top,file+' overview');
    const preview=nodes.find(n=>n.tag==='todo-preview');
    if(status==='ready'){assert.ok(preview,file+' empty preview');assert.ok(nodes.indexOf(preview)>nodes.indexOf(top));}
    else {assert.equal(preview,undefined,file+' no false empty state in '+status);
      const msg=nodes.find(n=>n.tag==='#text'&&/待办加载中|待办暂时无法|已加载部分暂无待办/.test(n.text));
      assert.ok(msg && nodes.indexOf(msg)>nodes.indexOf(top),file+' '+status);
    }
  }
});
