const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const pageFile = path.resolve(__dirname, '../miniprogram/pages/workflow/commission/index.js');
const real = createRequire(pageFile);

function fixture(role = 'sales', loggedIn = true) {
  let page, generation = 1;
  const calls = [], routes = [], guards = {};
  const post = { postId:'91', userId:'92', orgId:'93', orgType:role.startsWith('hq-')?'headquarters':'store', roleCode:role };
  const navigation = { open:(url,options)=>routes.push({url,options}), home:()=>routes.push({home:true}) };
  const http = {
    token:()=>loggedIn?'session':'', sessionGeneration:()=>generation,
    sessionGuard:()=>{const current=generation;return()=>loggedIn&&generation===current;},
    requestGuard:key=>{const request=guards[key]={},current=generation;return()=>loggedIn&&generation===current&&guards[key]===request;},
    get:async(url,query)=>{calls.push({method:'GET',url,query});return response(url);},
    post:async(url,body,headers)=>{calls.push({method:'POST',url,body,headers});return {};}
  };
  let response = () => ({customerId:'90071992547409971',customerName:'历史客户',commissionId:null,commission:null});
  function load(file, requireFn, globals={}) {
    const module={exports:{}};
    vm.runInNewContext(fs.readFileSync(file,'utf8'),{module,require:requireFn,...globals},{filename:file});
    return module.exports;
  }
  const api=load(path.resolve(__dirname,'../miniprogram/api/franchise/commission.js'),()=>http);
  const redirect=load(path.join(path.dirname(pageFile),'external-entry.js'),()=>navigation);
  load(pageFile,id=>id.endsWith('/api/http')?http:id.endsWith('/api/auth')?{currentApiPost:()=>post}
    :id.endsWith('/api/franchise/commission')?api:id.endsWith('/page-navigation')?navigation:id==='./external-entry'?redirect:real(id),
    {Page:value=>page=value,wx:{showToast(){},pageScrollTo(){}}});
  page={...page,data:structuredClone(page.data),setData(patch){
    for(const [key,value] of Object.entries(patch)){
      const parts=key.split('.');let target=this.data;
      for(const part of parts.slice(0,-1))target=target[part]||={};
      target[parts.at(-1)]=value;
    }
  }};
  const nodes=()=>render(pageFile.replace(/\.js$/,'.wxml'),page.data);
  const text=nodes=>nodes.map(node=>(node.text||'')+text(node.children||[])).join('');
  return {page,calls,routes,nodes,text:()=>text(nodes()),reply:fn=>response=fn,login(){loggedIn=true;generation++;},
    async open(options){page.onLoad(options);await page.onShow();}};
}

for(const role of ['sales','sales-lead','boss'])test(role+' old submit links use external registration without reading legacy pricing or drafts',async()=>{
  for(const options of [{view:'submit',customerId:'90071992547409971'}, {view:'submit',id:'90071992547409971'}, {view:'submit',commissionId:'90071992547409972'}]){
    const h=fixture(role);await h.open(options);
    assert.equal(h.calls.length,0);assert.equal(h.routes.length,1);
    assert.equal(h.routes[0].url,'/pages/external-entrust/index?'+(options.commissionId?'commissionId=90071992547409972':'customerId=90071992547409971'));
    assert.equal(h.page.data.fields.length,0);assert.equal(h.page.data.primary,'');
  }
});

for(const view of ['admin','projects'])test('retired '+view+' workbench has a current-home fallback and no role can activate it',async()=>{
  for(const role of ['sales','sales-lead','boss','engineer','finance','finance-lead','hq-design-admin','hq-finance']){
    const h=fixture(role);await h.open({view});
    assert.match(h.text(),/旧设计工作台已停用/);assert.match(h.text(),/返回当前工作台/);
    assert.doesNotMatch(h.text(),/今日待办|最近办理|项目加载中|补充设计需求/);
    assert.equal(h.calls.length,0);h.page.goHome();assert.deepEqual(h.routes,[{home:true}]);
  }
});

test('logged-out historical links keep the exact original document through login',async()=>{
  const h=fixture('engineer',false),id='90071992547409972';
  const options={view:'receive',commissionId:id,history:'1'};
  await h.open(options);assert.match(h.text(),/登录后查看/);assert.equal(h.calls.length,0);
  h.page.goHome();
  assert.equal(h.routes[0].url,'/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/commission/index?view=receive&commissionId='+id+'&history=1'));
  h.login();h.reply(()=>({commission:{id,customerId:'90071992547409971',customerName:'历史客户',workflowMode:'LEGACY',status:'ACCEPTED'},submissions:[],handovers:[],progress:[],dispatches:[],allowedActions:[]}));
  await h.page.onShow();
  assert.equal(h.calls[0].url,'/franchise/commissions/'+id);assert.equal(h.page.data.customer.name,'历史客户');
  assert.equal(h.page.data.primary,'');assert.equal(h.page.data.canReturn,false);
});

test('historical originals retain the frozen brief, budget, fee and attachment without reopening new submission',async()=>{
  const h=fixture('finance'),id='90071992547409972',brief={projectName:'原委托项目',specialNeeds:'保留历史需求',customer:{name:'原客户',address:'原建房地址'}};
  h.reply(()=>({commission:{id,customerId:'90071992547409971',customerName:'历史客户',workflowMode:'LEGACY',status:'DESIGNING',submissionVersion:2},
    submissions:[{version:2,mode:'AREA_FULL',schemeSnapshot:JSON.stringify({designBrief:brief}),budgetVersion:3,width:12,depth:10,floors:2,area:240,totalCents:240000,rateCents:1000,pricingVersion:4,scope:'原交付范围',attachmentAssetId:'90071992547409973'}],
    handovers:[],progress:[],dispatches:[],allowedActions:['commission-submit']}));
  await h.open({view:'progress',commissionId:id,history:'1'});
  assert.equal(h.page.data.hasOrderSheet,true);assert.equal(h.page.data.orderSheet.title,'原委托项目');
  assert.equal(h.page.data.orderSheet.version,2);assert.equal(h.page.data.orderSheet.budgetVersion,3);
  assert.ok(h.page.data.rows.some(row=>row.file?.assetId==='90071992547409973'));
  assert.match(h.text(),/保留历史需求/);assert.match(h.text(),/保存委托单图片/);assert.doesNotMatch(h.text(),/预览委托单|补充设计需求/);
  h.page.historyOnly=false;h.page.action='commission-submit';await h.page.submit();
  assert.match(h.page.data.error,/旧委托新增已停用/);assert.equal(h.calls.filter(call=>call.method==='POST').length,0);
});

test('an old submit link outside business roles has a safe original-document fallback when no legacy record exists',async()=>{
  const h=fixture('engineer');await h.open({view:'submit',customerId:'90071992547409971'});
  assert.match(h.text(),/暂无可查看的历史委托/);assert.match(h.text(),/返回当前工作台/);
  assert.equal(h.page.data.customer,null);assert.equal(h.page.data.fields.length,0);assert.equal(h.page.data.primary,'');
  assert.equal(h.calls.length,1);assert.equal(h.calls[0].method,'GET');
});

test('customer-scoped historical originals keep the existing authorized customer information fallback',async()=>{
  const h=fixture('finance'),id='90071992547409972';
  h.reply(()=>({customerId:'90071992547409971',commissionId:id,customerName:'原客户',customerInfo:{name:'原客户',address:'已授权的原客户地址'},
    commission:{commission:{id,customerId:'90071992547409971',status:'DESIGNING',submissionVersion:1},
      submissions:[{version:1,text:'早期文字需求'}],handovers:[],progress:[],dispatches:[],allowedActions:[]}}));
  await h.open({view:'progress',customerId:'90071992547409971',history:'1'});
  assert.match(h.text(),/早期文字需求/);assert.match(h.text(),/已授权的原客户地址/);
  assert.equal(h.page.data.hasOrderSheet,true);assert.equal(h.page.data.designBriefEnabled,false);
});

test('old design edit links read the original record without reviving retired actions from stale server hints',async()=>{
  const id='90071992547409972';
  for(const view of ['feedback','complete','dispatch']){
    const h=fixture(view==='feedback'?'sales':'hq-design-admin');
    h.reply(()=>({commission:{id,customerId:'90071992547409971',customerName:'历史客户',workflowMode:'LEGACY',status:'DESIGNING',submissionVersion:2},
      submissions:[{version:2,text:'原需求'}],handovers:[],dispatches:[],progress:[{seq:1,kind:'FEEDBACK',note:'原修改意见'}],
      allowedActions:['commission-feedback','design-complete','design-dispatch','fee-decide']}));
    await h.open({view,commissionId:id});
    assert.equal(h.page.data.view,'progress');assert.equal(h.page.data.fields.length,0);assert.equal(h.page.data.primary,'');
    assert.match(h.text(),/原修改意见/);assert.doesNotMatch(h.text(),/批准本版费用|登记客户修改意见|上传并下发成果|线下完成说明/);
    assert.equal(h.page.decideFee,undefined);assert.equal(h.page.openFeedback,undefined);
    await h.page.submit();assert.equal(h.calls.filter(call=>call.method==='POST').length,0);
    for(const action of ['commission-feedback','design-complete','design-dispatch','fee-decide'])assert.throws(()=>h.page.commandPayload(action),/旧设计办理已停用/);
  }
});

test('the existing authorized rule entries retain original versions and amounts as read-only records',async()=>{
  for(const [view,role,url] of [['policy','boss','/franchise/design-policies/current'],['pricing','hq-design-admin','/franchise/design-pricing-policies/current']]){
    const h=fixture(role);
    h.reply(()=>({version:4,totalCents:123400,firstCents:50000,rateCents:1000,scope:'原交付范围',recipient:'原收款主体'}));
    await h.open({view});
    assert.equal(h.calls.length,1);assert.equal(h.calls[0].method,'GET');assert.equal(h.calls[0].url,url);
    assert.equal(h.page.data.fields.length,0);assert.equal(h.page.data.primary,'');
    assert.match(h.text(),/V4/);assert.match(h.text(),/原交付范围/);assert.match(h.text(),/原收款主体/);assert.match(h.text(),/原规则仅供查阅/);
    assert.match(h.text(),view==='policy'?/1,234.00/:/10.00/);
    assert.doesNotMatch(h.text(),/保存计价规则|保存历史授权规则|批准本版费用/);
    h.page.data.form={total:'9999',rate:'200'};await h.page.submit();
    assert.equal(h.calls.filter(call=>call.method==='POST').length,0);
  }
});

test('rule reads keep their original role restriction',async()=>{
  for(const view of ['policy','pricing'])for(const role of ['sales','engineer','finance','hq-finance']){
    const h=fixture(role);await h.open({view});
    assert.equal(h.calls.length,0);assert.equal(h.page.data.fields.length,0);assert.equal(h.page.data.primary,'');
    assert.equal(h.page.data.platformStatus,'degraded');
  }
});

test('old pay and paid links preserve the original historical receipt and never create another payment',async()=>{
  const id='90071992547409972',order={commissionId:id,submissionVersion:2,number:'原模拟记录',status:'PAID',amountCents:123400};
  for(const view of ['pay','paid']){
    const h=fixture('finance');
    h.reply(url=>url.endsWith('/simulated-payment-receipt')?order:{commission:{id,customerId:'90071992547409971',customerName:'原客户',workflowMode:'LEGACY',status:'DESIGNING'},
      submissions:[],handovers:[],progress:[],dispatches:[],simulatedPayment:order,allowedActions:[]});
    await h.open({view,commissionId:id});
    assert.equal(h.page.data.paymentOrder.status,'PAID');assert.equal(h.page.data.paymentOrder.number,'原模拟记录');
    assert.equal(h.page.data.primary,'');assert.equal(h.page.data.fields.length,0);
    assert.match(h.text(),/不计入实际收支/);assert.doesNotMatch(h.text(),/登记付款|确认支付|选择模拟登记方式/);
    await h.page.submit();assert.equal(h.calls.filter(call=>call.method==='POST').length,0);
    assert.ok(h.calls.every(call=>call.url.startsWith('/franchise/commissions/'+id)));
  }
});
