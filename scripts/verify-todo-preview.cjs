const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
function component(rows,scope='engineer',appearance='',ownerMode=false){
 let definition;
 vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/components/todo-preview/index.js'),'utf8'),{Component:v=>definition=v,require:require('node:module').createRequire(path.join(root,'miniprogram/components/todo-preview/index.js'))});
 const events=[];
 const c={data:{...definition.data,rows,scope,appearance,ownerMode},setData(v){Object.assign(this.data,v);},triggerEvent(name,detail){events.push({name,detail});},...definition.methods};
 c.update=(items,identity=c.data.scope)=>{Object.assign(c.data,{rows:items,scope:identity});definition.observers['rows, scope'].call(c,items,identity);};
 c.update(rows);return {c,events};
}
const rows=n=>Array.from({length:n},(_,i)=>({key:'task-'+i,title:'工程 '+i,clickable:true,path:'/pages/build/record/index?id='+i}));
test('首页待办显示前三条和剩余条数，展开后第四条仍指向原单',()=>{
 const {render}=require('./role-page-runtime.cjs');const file=path.join(root,'miniprogram/components/todo-preview/index.wxml');
 const list=rows(7),{c,events}=component(list,'finance','home');
 const flatten=ns=>ns.flatMap(n=>[n,...flatten(n.children)]),text=n=>n.text||n.children.map(text).join('');
 function screen(){const nodes=flatten(render(file,c.data));return {button:nodes.find(n=>n.attrs?.bindtap==='toggle'),rows:nodes.filter(n=>n.attrs?.bindtap==='activate')};}
 let view=screen();assert.equal(view.rows.length,3);assert.equal(text(view.button),'另 4 项');c.toggle();view=screen();assert.equal(view.rows.length,7);assert.equal(text(view.button),'收起');
 c.activate({currentTarget:{dataset:{index:view.rows[3].attrs['data-index']}}});assert.equal(events[0].detail.item.path,list[3].path);c.toggle();assert.equal(screen().rows.length,3);
 c.update(list.slice(0,3));assert.equal(screen().button,undefined);
});
for(const n of [0,1,3,4,20])test(n+' tasks show at most three with an exact remaining count',()=>{
 const {c}=component(rows(n));assert.equal(c.data.visibleRows.length,Math.min(n,3));assert.equal(c.data.remaining,Math.max(0,n-3));
 c.toggle();assert.equal(c.data.visibleRows.length,n);c.toggle();assert.equal(c.data.visibleRows.length,Math.min(n,3));
});
test('expanded fourth task preserves its original destination and disabled tasks cannot navigate',()=>{
 const list=rows(5),{c,events}=component(list);list[4].clickable=false;c.toggle();
 c.activate({currentTarget:{dataset:{index:3}}});assert.strictEqual(events[0].detail.item,list[3]);
 c.activate({currentTarget:{dataset:{index:4}}});assert.equal(events.length,1);
});
test('role change collapses the list; completing tasks removes a stale expanded state',()=>{
 const {c}=component(rows(8));c.toggle();c.update(rows(9));assert.equal(c.data.visibleRows.length,9);
 c.update(rows(7),'finance');assert.equal(c.data.visibleRows.length,3);c.toggle();c.update(rows(1));assert.equal(c.data.expanded,false);c.update(rows(8));assert.equal(c.data.visibleRows.length,3);
});
test('finance and design fields retain business context, status and amount',()=>{
 const {c}=component([{key:'fin',company:'测试公司',name:'材料采购',status:'部分付款',amount:'¥123,456.78'},{id:'design',name:'设计项目',detail:'施工图待下发',actionUrl:'/pages/workflow/commission/index?id=1'}]);
 assert.equal(c.data.visibleRows[0].title,'测试公司 · 材料采购');assert.equal(c.data.visibleRows[0].amount,'¥123,456.78');assert.equal(c.data.visibleRows[0].meta,'部分付款');assert.equal(c.data.visibleRows[1].meta,'施工图待下发');
});

// Exercise the production refresh helper with deferred API responses. Rendering below
// checks the real WXML branches; it does not substitute for a native device recording.
function platformHarness() {
 const context={post:{tenantId:1,orgId:10,postId:20,orgType:'store',roleCode:'boss'},session:1,token:'test-session'};
 const pending=[],updates=[],model=require('../miniprogram/utils/platform-todos');
 const deps={
  '../api/http':{sessionGeneration:()=>context.session,token:()=>context.token},
  '../api/auth':{currentApiPost:()=>context.post},
  '../api/todos':{list:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))},
  './platform-nav':{open(){}},'./platform-todos':model
 };
 const module={exports:{}};
 vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/utils/platform-todos-page.js'),'utf8'),
  {module,require:name=>{if(!(name in deps))throw Error(name);return deps[name];}});
 const page={data:{},setData(patch){Object.assign(this.data,patch);if(patch.platformTodos)updates.push(patch.platformTodos);}};
 const helper=module.exports;helper.init(page);
 return {context,pending,updates,page,helper};
}
const todoResponse=(n=5,prefix='task')=>({items:Array.from({length:n},(_,i)=>({key:prefix+'-'+i,title:'待办 '+i,actionable:true,appPath:'/pages/workflow/commission/index?id='+i})),actionableCount:n,failedDomains:[]});
const flushTodos=()=>new Promise(resolve=>setImmediate(resolve));
async function loadedTodos(n=5) {
 const h=platformHarness();h.helper.refresh(h.page,false);h.pending[0].resolve(todoResponse(n));await flushTodos();return h;
}

test('同会话后台刷新保留待办与展开状态，重复 onShow 合并请求',async()=>{
 const h=await loadedTodos(),before=h.page.data.platformTodos;
 const {c}=component(before.rows,before.sessionGen+':'+before.postKey);c.toggle();
 h.helper.refresh(h.page,false);h.helper.refresh(h.page,false);
 const state=h.page.data.platformTodos;
 assert.equal(h.pending.length,2,'刷新中的 onShow 共用一次请求');
 assert.equal(state.status,'ready','后台刷新不能切成整块 loading');
 assert.equal(state.refreshing,true);assert.strictEqual(state.rows,before.rows);
 c.update(state.rows,state.sessionGen+':'+state.postKey);assert.equal(c.data.expanded,true);
 h.pending[1].resolve(todoResponse(6));await flushTodos();
 c.update(h.page.data.platformTodos.rows,state.sessionGen+':'+state.postKey);
 assert.equal(c.data.visibleRows.length,6);assert.equal(c.data.expanded,true);
 assert.equal(h.page.data.platformTodos.refreshing,false);
});

test('已成功的空待办刷新时保留空态，首载失败仍显示失败而非旧内容',async()=>{
 const h=await loadedTodos(0);h.helper.refresh(h.page,false);
 assert.equal(h.page.data.platformTodos.status,'ready');
 h.pending[1].reject({message:'网络异常'});await flushTodos();
 assert.equal(h.page.data.platformTodos.status,'ready');
 assert.ok(h.page.data.platformTodos.refreshError);
 const first=platformHarness();first.helper.refresh(first.page,false);
 assert.equal(first.page.data.platformTodos.status,'loading');
 first.pending[0].reject({message:'网络异常'});await flushTodos();
 assert.equal(first.page.data.platformTodos.status,'degraded');
 assert.equal(first.page.data.platformTodos.rows.length,0);
});

test('同 scope 网络失败和非法响应保留已展示待办，重试成功后清除局部错误',async()=>{
 const h=await loadedTodos(),before=h.page.data.platformTodos;
 h.helper.refresh(h.page,false);h.pending[1].reject({message:'连接中断'});await flushTodos();
 assert.equal(h.page.data.platformTodos.status,'ready');assert.strictEqual(h.page.data.platformTodos.rows,before.rows);
 assert.equal(h.page.data.platformTodos.refreshing,false);assert.match(h.page.data.platformTodos.refreshError,/连接中断/);
 h.helper.retry(h.page);assert.strictEqual(h.page.data.platformTodos.rows,before.rows);
 h.pending[2].resolve({items:null});await flushTodos();
 assert.strictEqual(h.page.data.platformTodos.rows,before.rows);assert.ok(h.page.data.platformTodos.refreshError);
 h.helper.retry(h.page);h.pending[3].resolve(todoResponse(3));await flushTodos();
 assert.equal(h.page.data.platformTodos.refreshError,'');assert.equal(h.page.data.platformTodos.rows.length,3);
});

test('后台刷新手动重载仍以最新请求为准，旧成功和旧失败均不覆盖',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);h.helper.retry(h.page);
 h.pending[2].resolve(todoResponse(4,'new'));await flushTodos();
 h.pending[1].reject({message:'旧请求失败'});await flushTodos();
 assert.equal(h.page.data.platformTodos.rows[0].key,'new-0');assert.equal(h.page.data.platformTodos.refreshError,'');
 h.helper.refresh(h.page,false);h.helper.retry(h.page);
 h.pending[4].resolve(todoResponse(4,'latest'));await flushTodos();
 h.pending[3].resolve(todoResponse(4,'old'));await flushTodos();
 assert.equal(h.page.data.platformTodos.rows[0].key,'latest-0');
});

for(const change of ['post','session','logout'])test(change+' 变化立即移除旧待办，迟到请求不能回填',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);
 if(change==='post')h.context.post={...h.context.post,postId:21};
 if(change==='session')h.context.session++;
 if(change==='logout'){h.context.token='';h.context.session++;}
 h.helper.refresh(h.page,false);
 assert.equal(h.page.data.platformTodos.rows.length,0);
 assert.equal(h.page.data.platformTodos.status,change==='logout'?'idle':'loading');
 h.pending[1].resolve(todoResponse(5,'old'));await flushTodos();
 assert.equal(h.page.data.platformTodos.rows.length,0);
});

for(const [field,value]of [['roleCode','sales'],['orgType','headquarters']])test('同 postId 同会话的 '+field+' 权限变化清场并隔离旧响应',async()=>{
 const h=await loadedTodos(),oldKey=h.page.data.platformTodos.postKey;
 h.helper.refresh(h.page,false);
 h.context.post={...h.context.post,[field]:value};
 h.helper.refresh(h.page,false);
 assert.equal(h.page.data.platformTodos.rows.length,0,'新权限不得沿用旧角色的待办');
 assert.equal(h.page.data.platformTodos.status,'loading');
 assert.notEqual(h.page.data.platformTodos.postKey,oldKey);
 assert.equal(h.pending.length,3,'不得与旧权限在途请求合并');
 h.pending[2].resolve(todoResponse(2,'new-role'));await flushTodos();
 h.pending[1].resolve(todoResponse(5,'old-role'));await flushTodos();
 assert.equal(h.page.data.platformTodos.rows.length,2);
 assert.equal(h.page.data.platformTodos.rows[0].key,'new-role-0');
});

for(const error of [{code:403},{code:1103000002,postInvalid:true}])test('权限失效 '+error.code+' 清除已展示待办',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);h.pending[1].reject(error);await flushTodos();
 assert.equal(h.page.data.platformTodos.rows.length,0);
 assert.equal(h.page.data.platformTodos.status,'degraded');
});

test('旧 401 不能清空新会话刚加载的待办，当前 401 清场',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);h.context.session++;
 h.helper.refresh(h.page,false);h.pending[2].resolve(todoResponse(4,'new-session'));await flushTodos();
 h.pending[1].reject({code:401});await flushTodos();
 assert.equal(h.page.data.platformTodos.rows[0]?.key,'new-session-0');
 h.helper.refresh(h.page,false);h.context.session++;h.context.token='';
 h.pending[3].reject({code:401});await flushTodos();
 assert.equal(h.page.data.platformTodos.status,'idle');assert.equal(h.page.data.platformTodos.rows.length,0);
});

test('并发请求任一当前 401 使会话失效时清场，较新请求的迟到成功也不回填',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);h.helper.retry(h.page);
 h.context.session++;h.context.token='';h.pending[1].reject({code:401});await flushTodos();
 assert.equal(h.page.data.platformTodos.status,'idle');assert.equal(h.page.data.platformTodos.rows.length,0);
 h.pending[2].resolve(todoResponse());await flushTodos();assert.equal(h.page.data.platformTodos.rows.length,0);
});

test('财务待办失败重试按钮调用真实页面方法并重新请求',async()=>{
 const h=await loadedTodos();h.helper.refresh(h.page,false);h.pending[1].reject({message:'更新失败'});await flushTodos();
 const {render}=require('./role-page-runtime.cjs'),flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]);
 let definition;vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/pages/admin/finance-desk/index.js'),'utf8'),
  {Page:value=>definition=value,require:name=>name.endsWith('/platform-todos-page')?h.helper:{}});
 const nodes=flat(render(path.join(root,'miniprogram/pages/admin/finance-desk/index.wxml'),{
  view:'desk',ready:true,platformFinance:null,platformTodos:h.page.data.platformTodos,deskHome:{tasks:h.page.data.platformTodos.rows}
 }));
 const button=nodes.find(n=>n.tag==='button'&&n.children.some(c=>c.text==='重试'));
 assert.ok(button);assert.equal(typeof definition[button.attrs.bindtap],'function','重试必须连接已定义的页面方法');
 definition[button.attrs.bindtap].call(h.page);assert.equal(h.pending.length,3);assert.equal(h.page.data.platformTodos.rows.length,5);
});

test('全部真实工作台模板保留刷新中的 todo-preview，并提供局部失败重试',async()=>{
 const h=await loadedTodos(),states=[h.page.data.platformTodos];
 h.helper.refresh(h.page,false);states.push(h.page.data.platformTodos);
 h.pending[1].reject({message:'更新失败'});await flushTodos();states.push(h.page.data.platformTodos);
 const {render}=require('./role-page-runtime.cjs'),flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]);
 const surfaces=[
  ['pages/business/index.wxml',{guest:false,isSalesHome:true}],
  ['pages/engineering/index.wxml',{guest:false,canWork:true}],
  ['pages/build/home/index.wxml',{eng:{},homeMounted:true,workspaceView:'home'}],
  ['pages/admin/home/index.wxml',{desk:{}}],
  ['pages/admin/finance-desk/index.wxml',{view:'desk',ready:true,financeRefreshing:false}],
  ['pages/admin/finance-desk/index.wxml',{view:'tasks',ready:true}]
 ];
 for(const [file,data]of surfaces)for(const state of states){
  const nodes=flat(render(path.join(root,'miniprogram',file),{...data,platformFinance:null,platformTodos:state,rows:state.rows,deskHome:{tasks:state.rows}}));
  const preview=nodes.find(n=>n.tag==='todo-preview');
  assert.ok(preview,file+' retains preview in '+state.status);
  assert.equal(preview.attrs.scope,state.sessionGen+':'+state.postKey,file+' scopes expansion by session and post');
  const retry=file.includes('finance-desk')?'refreshFinanceTasks':'retryPlatformTodos';
  if(state.refreshError)assert.ok(nodes.some(n=>n.attrs?.bindtap===retry),file+' has local retry');
 }
});

test('老板今日待办仅预览三条，全部角标表示未完成总数，两个入口独立',()=>{
 const {render}=require('./role-page-runtime.cjs'),file=path.join(root,'miniprogram/components/todo-preview/index.wxml');
 const assignee=(key,title,typeLabel='待办理')=>({key,title,typeLabel,clickable:true,path:'/pages/workflow/index'});
 const decision=assignee('franchise:fee-approve:11','费用待处理','委托费用待审批');
 const list=[assignee('fr-sale:contract-sign:2','签施工合同'),assignee('fr-eng:delay:3','节点延期'),
  decision,assignee('fr-fin:bank-confirm:4','到账核实'),decision,
  assignee('fr-sale:stage-pending:2','常规应收'),{...assignee('franchise:fee-approve:non','等待他人'),clickable:false}];
 const {c,events}=component(list,'owner','home',true);c.data.title='今日待办';
 const flatten=ns=>ns.flatMap(n=>[n,...flatten(n.children)]),text=n=>n.text||n.children.map(text).join('');
 const nodes=flatten(render(file,c.data));
 const actions=nodes.filter(n=>n.attrs?.bindtap==='openOwnerList');
 assert.equal(c.data.total,4);assert.equal(c.data.decisionTotal,1);
 assert.equal(c.data.visibleRows.length,3);assert.equal(c.data.visibleRows[0].key,decision.key);
 assert.ok(c.data.visibleRows.every(r=>r.action==='去处理'));
 assert.equal(text(nodes.find(n=>n.attrs?.class==='home-todo-badge')),'4');
 assert.equal(actions.length,2);assert.ok(text(actions[0]).includes('待决策 1'));
 assert.equal(nodes.some(n=>n.attrs?.bindtap==='toggle'),false);
 c.activate({currentTarget:{dataset:{index:0}}});assert.equal(events[0].detail.item.key,decision.key);
 c.openOwnerList({currentTarget:{dataset:{mode:'decisions'}}});assert.equal(events[1].detail.decisions,true);
 c.update(list.filter(x=>x.key!==decision.key));assert.equal(c.data.total,3);assert.equal(c.data.decisionTotal,0);
});

test('老板待办完整页组合来源与待决策过滤，切换身份后旧单不打开',async()=>{
 const helper=require('../miniprogram/utils/owner-todo-view');
 const contract={key:'fr-sale:contract-sign:2',title:'合同面签',path:'/pages/workflow/index',clickable:true};
 const fee={...contract,key:'franchise:fee-approve:11',title:'委托费用待审批'};
 const eng={...contract,key:'fr-eng:delay:3',title:'节点延期'};
 const cash={...contract,key:'fr-fin:bank-confirm:4',title:'到账核实'};
 const engineeringMoney={...contract,key:'eng-collect:15:BASE_DONE',amount:'¥10.00'};
 const raw=[contract,eng,fee,cash,engineeringMoney];
 assert.deepEqual(helper.filter(helper.board(raw).rows,'engineering',false).map(x=>x.key),[eng.key,engineeringMoney.key]);
 assert.deepEqual(helper.filter(helper.board(raw).rows,'engineering',true),[]);
 let definition;const opens=[];let session=1;
 let post={orgType:'store',roleCode:'owner',orgId:'1',postId:'A'};
 const core=require('../miniprogram/utils/platform-todos');
 const pageHelper={
  init(p){p.__ptLoadGen=0;p.setData({platformTodos:core.initialState()})},
  refresh(p){return Promise.resolve().then(()=>p.setData({platformTodos:{rows:raw,postKey:core.postKeyOf(post),sessionGen:session,status:'ready'}}))},
  open(p,e){opens.push(e.detail.item.key)}
 };
 vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/pages/admin/todos/index.js'),'utf8'),{
  Page:v=>definition=v,require(n){if(n.endsWith('/platform-todos-page'))return pageHelper;if(n.endsWith('/owner-todo-view'))return helper;
   if(n.endsWith('/api/auth'))return {currentApiPost:()=>post};if(n.endsWith('/api/http'))return {token:()=>true,sessionGeneration:()=>session};return core}
 });
 const p={...definition,data:{...definition.data},setData(v){Object.assign(this.data,v)}};
 p.onLoad({decisions:'1'});await p.refresh();assert.deepEqual(p.data.rows.map(x=>x.key),[fee.key]);
 p.selectSource({currentTarget:{dataset:{source:'engineering'}}});assert.equal(p.data.rows.length,0);
 p.toggleDecisions({detail:{value:false}});assert.equal(p.data.rows.length,2);
 const tap={currentTarget:{dataset:{key:eng.key}}};p.open(tap);assert.deepEqual(opens,[eng.key]);
 session++;p.open(tap);assert.deepEqual(opens,[eng.key]);
 post={...post,roleCode:'finance'};await p.refresh();assert.equal(p.data.rows.length,0);
});

test('真实提成核对阶段可直达原单，关闭或等待补款时不伪造待决策',()=>{
 const H=require('../miniprogram/utils/owner-todo-view'),P=require('../miniprogram/utils/platform-performance');
 const projectId='9007199254740999';
 const raw={projectId,stages:[
  {stage:'SIGN_AFTER',reviewDue:true,eligible:true,paidCents:0},
  {stage:'BASE_DONE',reviewDue:true,eligible:false,paidCents:0},
  {stage:'CAP_DONE',reviewDue:false,eligible:true,paidCents:1}
 ]};
 const initial={items:[],actionableCount:0,failedDomains:[]};
 const reviewRows=[P.commissionRowView(raw,'boss','测试项目')];
 const result=H.mergeReviews(initial,reviewRows.concat(reviewRows));
 assert.equal(result.actionableCount,1);
 const item=require('../miniprogram/utils/platform-todos').rowOf(result.items[0]);
 const board=H.board([item]);
 assert.equal(board.decisionTotal,1);assert.equal(board.rows[0].source,'finance');
 assert.equal(item.path,'/pages/admin/commission/index?id='+projectId+'&reviewStage=SIGN_AFTER');
 assert.equal(H.mergeReviews(initial,[P.commissionRowView(raw,'owner')]).items.length,0);
 assert.deepEqual(H.mergeReviews(initial,null,true).failedDomains,['提成复核']);
});
