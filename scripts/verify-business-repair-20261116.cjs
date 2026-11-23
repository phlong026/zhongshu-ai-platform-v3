const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const Result=require('../miniprogram/pages/studio/content-task-result');
const Brief=require('../miniprogram/pages/workflow/commission/design-brief');
const Commission=require('../miniprogram/pages/workflow/commission/presentation');

function tabDefinition(tabs,globals){
 let definition;const module={exports:{}};
 vm.runInNewContext(fs.readFileSync(path.resolve('miniprogram/utils/workspace-navigation.js'),'utf8'),{...globals,module,require:id=>id.endsWith('/workbench-nav')?()=>({tabs}):id.endsWith('/auth')?{currentApiPost:()=>({postId:'fixture'})}:{sessionGeneration:()=>1}});
 vm.runInNewContext(fs.readFileSync(path.resolve('miniprogram/custom-tab-bar/index.js'),'utf8'),{...globals,Component:d=>definition=d,require:id=>id.endsWith('/workspace-navigation')?module.exports:()=>({tabs})});
 return definition;
}


test('real returned packet restores a usable date, return reason and frozen original files',()=>{
 const dto={commission:{id:'200',customerId:'100',submissionVersion:1,dispatchSeq:1},submissions:[{version:1}],progress:[{kind:'COMPLETE',createTime:1794844650153},{kind:'DISPATCH',createTime:1794844691686,note:'交付清单'}],dispatches:[{dispatchSeq:1,submissionVersion:1,drawingVersion:'QA-V1',resultAssetId:'301',drawingAssetId:'302',reviewAssetId:'303',createTime:1794844691686}],handovers:[{version:1,dispatchSeq:1,status:'RETURNED',decisionNote:'补充现场交通',confirmationDate:[2026,11,16],confirmationFile:'{"assetId":"304"}',managerUserId:158}]};
 const c=Commission.normalize(dto),f=Commission.formInit(c,'handover'),rows=Commission.rowsFor(c,Commission.stateOf(c));
 assert.equal(f.confirmationDate,'2026-11-16');assert.equal(f.drawingVersion,'QA-V1');assert.equal(f.confirmationFile.assetId,'304');assert.equal(f.managerUserId,'158');
 assert.equal(rows[0].label,'当前进度');assert.equal(rows[1].label,'当前办理');assert(rows.some(r=>r.label==='待补充'&&r.value==='补充现场交通'));assert(rows.some(r=>r.label==='设计完成'&&r.value));assert(rows.some(r=>r.label==='交付记录'&&r.value.includes('交付清单')));
 const accepted=Commission.normalize({...dto,handovers:[{...dto.handovers[0],version:2,status:'ACCEPTED'},{...dto.handovers[0],status:'RETURNED'}]});assert.equal(accepted.returns.length,0);assert.equal(accepted.handoverHistory.length,1);assert.equal(accepted.packet.version,2);
});
test('rapid Tab taps retain the final destination while only one native switch is in flight',()=>{
 let definition;const app={globalData:{}},flights=[];const tabs=[1,2,3].map(index=>({index,native:true,url:'/tab/'+index}));
 definition=tabDefinition(tabs,{getApp:()=>app,getCurrentPages:()=>[{getTabBar:()=>bar}],wx:{switchTab:o=>flights.push(o),showToast(){}}});
 const bar={...definition.methods,data:{selected:1},properties:{},setData(p){Object.assign(this.data,p)}};
 const tap=index=>bar.switchTab({currentTarget:{dataset:{index}}});
 tap(2);tap(3);assert.equal(flights.length,1);bar.data.selected=2;flights[0].complete();assert.equal(flights[1].url,'/tab/3');bar.data.selected=3;flights[1].complete();assert.equal(app.globalData.workspaceNavigating,false);
});

test('finance rapid taps retain a dedicated workbench target and drain from the live page',()=>{
 let definition,current;const app={globalData:{}},flights=[],local=[];
 const tabs=[{index:4,native:true,url:'/pages/profile/index'},{index:16,native:false,url:'/finance'},{index:17,native:false,url:'/finance?view=flows'}];
 definition=tabDefinition(tabs,{getApp:()=>app,getCurrentPages:()=>[current],wx:{switchTab:o=>flights.push(['native',o]),navigateTo:o=>flights.push(['push',o]),redirectTo:o=>flights.push(['replace',o]),showToast(){}}});
 const bar=selected=>({...definition.methods,data:{selected},properties:{nested:true},setData(p){Object.assign(this.data,p)}}),finance=bar(16),profile=bar(4),tap=(b,index)=>b.switchTab({currentTarget:{dataset:{index}}});
 current={switchWorkspaceTab:t=>{local.push(t.index);return t.index!==4;}};tap(finance,4);tap(finance,17);assert.equal(app.globalData.workspacePending.index,17);
 current={getTabBar:()=>profile};flights[0][1].complete();assert.equal(flights[1][1].url,'/finance?view=flows');tap(profile,16);
 current={switchWorkspaceTab:t=>{local.push(t.index);return true;}};flights[1][1].complete();assert.equal(local.at(-1),16);assert.equal(flights.length,2);assert.equal(app.globalData.workspaceNavigating,false);assert.equal(app.globalData.workspacePending,undefined);
});

test('finance home, books and shared profile switch inside one page and preserve the book filters',()=>{
 let definition,profileShows=0,coldLoads=0;const post={roleCode:'finance',orgType:'store'};
 vm.runInNewContext(fs.readFileSync(path.resolve('miniprogram/pages/admin/finance-desk/index.js'),'utf8'),{Page:d=>definition=d,require:id=>id==='../../../api/auth'?{currentApiPost:()=>post}:id==='../../profile/profile-page'?()=>({onShow(){profileShows++;},open(){},logout(){}}):{},wx:{pageScrollTo(){}}});
 const p={...definition,__platformActions:true,__financeIdentity:'159',__financeLoadedIdentity:'159',data:{...definition.data,platformApiMode:true,view:'projects',keyword:'验收账本',flowTab:'ledger',category:'材料'},setData(patch){Object.assign(this.data,patch)},refresh(){assert.equal(this.data.ready,true);},onShow(){coldLoads++;this.__financeLoadedIdentity=this.__financeIdentity;}};
 assert.equal(p.switchWorkspaceTab({key:'profile'}),true);assert.equal(p.data.view,'profile');assert.equal(profileShows,1);p.switchWorkspaceTab({key:'finance-home'});p.switchWorkspaceTab({key:'finance-books'});assert.equal(p.data.view,'projects');assert.equal(p.data.keyword,'验收账本');assert.equal(p.data.category,'材料');assert.equal(coldLoads,0);
 p.__financeLoadedIdentity='';p.switchWorkspaceTab({key:'finance-home'});assert.equal(coldLoads,1);post.roleCode='sales';assert.equal(p.switchWorkspaceTab({key:'profile'}),false);
 const tabs=require('../miniprogram/utils/organization').navigation({active:true,loggedIn:true,role:'finance',orgType:'store'}).tabs;assert.equal(tabs.length,3);assert(tabs.every(t=>!t.native&&t.url.startsWith('/pages/admin/finance-desk/index')));
});
test('requirements inherit recorded scheme fields without confusing entrance, house, image orientation or population',()=>{
 const inherited=Brief.inherit({customerName:'王先生',latestBudget:{payload:JSON.stringify({configSnapshot:{orientation:'PORTRAIT',entranceOrientation:'SOUTH',rooms:{bedroom:5,living:3,bath:2},style:'现代'}})}}).brief;
 assert.equal(inherited.entranceDirection,'南');assert.equal(inherited.orientation,'');assert.equal(inherited.entranceAligned,'');assert.equal(inherited.householdMembers,'');assert.equal(inherited.bedrooms,'5');assert.deepEqual(inherited.rooms,['卧室','客厅']);assert.deepEqual(inherited.styleRefs,['现代']);
 assert.equal(Brief.inherit({}, {...inherited,bedrooms:'4'}).brief.bedrooms,'4');
 assert.equal(Brief.inherit({latestBudget:{payload:'bad json'}}).brief.orientation,'');
});
test('completed text and parsed source tasks never become generated videos',()=>{
 for(const kind of ['copy','asr','link_parse']){
  const d=Result.describe({kind,status:'SUCCEEDED',result:{text:'真实转写',videoUrl:'https://source.test/video.mp4'}});
  assert.equal(d.videoReady,false);assert.equal(d.resultUrl,'');assert.equal(d.kindLabel==='数字人口播',false);
 }
 assert.equal(Result.describe({kind:'unknown'}),null);
 assert.equal(Result.describe({kind:'digital',status:'SUCCEEDED',resultUrl:'https://result.test/film.mp4'}).videoReady,true);
 assert.equal(Result.describe({kind:'digital',status:'SUCCEEDED',resultUrl:''}).videoReady,false);
});
function messageCenter(http){
 const module={exports:{}};
 vm.runInNewContext(fs.readFileSync(path.resolve('miniprogram/pages/account/message-center.js'),'utf8'),{module,exports:module.exports,require:id=>{
  if(id==='../../api/http')return http;
  if(id==='../../utils/platform-messages')return require('../miniprogram/utils/platform-messages');
  throw Error('Unmocked '+id);
 }});
 return module.exports;
}
function messages(http){
 let module={exports:{}};const opens=[];
 vm.runInNewContext(fs.readFileSync(path.resolve('miniprogram/pages/account/platform-messages-page.js'),'utf8'),{module,exports:module.exports,require:id=>{
  if(id==='../../api/http')return http;
  if(id==='./message-center')return messageCenter(http);
  if(id==='../../utils/page-navigation')return {open:url=>opens.push(url)};
  throw Error('Unmocked '+id);
 }});
 const p={data:{messageHasMore:false},apiMessages:[],setData(patch){Object.assign(this.data,patch)},filter(){this.data.rows=this.apiMessages;}};
 return {api:module.exports,p,opens};
}
const http=(get,post=async()=>({}))=>({token:()=>true,requestGuard:()=>()=>true,get,post});

test('message refresh and reads update the visible unread count independently of category or pagination',()=>{
 let loggedIn=true;const transport={token:()=>loggedIn},center=messageCenter(transport);
 const method=fs.readFileSync(path.resolve('miniprogram/pages/account/index.js'),'utf8').split('\n').find(line=>line.trim().startsWith('filter(){'));
 const definition=vm.runInNewContext('({'+method+'})',{http:transport,require:id=>{assert.equal(id,'./message-center');return center;},A:{scoped:(_s,rows)=>rows,visibleOrders:()=>[],services:[]},Q:{products:[]},Learning:{coursesFor:()=>[],resourcesFor:()=>[]}});
 const p={...definition,studyRole:()=>'',data:{view:'messages',state:{messages:[{id:'local',type:'系统',read:false}],favorites:[]},category:'全部',messageTab:'全部'},apiMessages:[{id:'123',type:'业务',read:false}],apiUnread:52,setData(patch){Object.assign(this.data,patch)}};
 p.filter();assert.equal(p.data.unread,52);p.data.messageTab='系统';p.filter();assert.equal(p.data.unread,52);assert.equal(p.data.messages[0].id,'123');
 p.apiUnread=0;p.apiMessages[0].read=true;p.filter();assert.equal(p.data.unread,0);
 loggedIn=false;p.filter();assert.equal(p.data.unread,0);assert.equal(p.data.messages.length,0,'signed-out inbox does not revive demo notifications');p.data.state.messages[0].read=true;p.filter();assert.equal(p.data.unread,0);
});
test('message failure is an error state, not a successful empty list',async()=>{
 const {api,p}=messages(http(async()=>{throw Error('服务不可用')}));await api.sync.call(p);
 assert.equal(p.data.messageStatus,'error');assert.match(p.data.messageError,/服务不可用/);
});
test('message pagination preserves the server cursor and snapshot, and appends without duplicates',async()=>{
 const requests=[];const rows=Array.from({length:30},(_,i)=>({id:String(900-i),type:'BUSINESS',title:'业务通知',read:false}));
 const {api,p}=messages(http(async(url,query)=>{assert.equal(url,'/platform/messages/center');requests.push(query);return requests.length===1?{items:rows,unread:52,pending:2,hasMore:true,nextCursor:'9007199254740999:opaque',snapshotAt:'first'}:{items:[rows[29],{id:'849',type:'BUSINESS'}],unread:52,pending:2,hasMore:false,snapshotAt:'second'};}));
 await api.sync.call(p);assert.equal(requests[0].limit,30);assert.equal(p.apiMessages.length,30);assert.equal(p.data.messageHasMore,true);assert.equal(p.apiUnread,52);
 await api.sync.call(p,true);assert.equal(requests[1].beforeId,'9007199254740999:opaque');assert.equal(p.apiMessages.length,31);assert.equal(p.data.messageHasMore,false);assert.equal(p.__messagesSnapshot,'first');
});
test('server read failure neither marks local message read nor navigates',async()=>{
 const {api,p,opens}=messages(http(async()=>({count:0}),async()=>{throw Error('保存失败')}));p.apiMessages=[{id:'1',read:false}];
 await api.read.call(p,{id:'1',route:'customer',objectId:'9007199254740999'});
 assert.equal(opens.length,0);assert.equal(p.apiMessages[0].read,false);assert.match(p.data.messageError,/保存失败/);
});
test('message opening uses the fresh server action path and rejects unsupported destinations',async()=>{
 const actionPath='/pages/workflow/commission/index?view=progress&commissionId=9007199254740999';
 const {api,p,opens}=messages(http(async()=>({}),async(url,body)=>{assert.equal(url,'/platform/messages/center/open');assert.equal(body.id,'1');return {id:'1',read:true,actionPath};}));p.apiMessages=[{id:'1',read:false}];p.apiUnread=1;
 await api.read.call(p,{id:'1',route:'commission',objectId:'9007199254740999'});
 assert.equal(opens[0],actionPath);assert.equal(p.apiMessages[0].read,true);assert.equal(p.apiUnread,0);
 assert.equal(api.target({actionPath:'/pages/studio/index?view=result&task=999'}),'/pages/studio/index?view=result&task=999');
 assert.throws(()=>api.target({actionPath:'https://external.test'}));assert.throws(()=>api.target({route:'commission',objectId:'999'}));
});
test('an unloaded message page ignores late responses',async()=>{
 let resolve;const waiting=new Promise(r=>resolve=r);const {api,p}=messages(http(async()=>waiting));
 const pending=api.sync.call(p);p.__messagesGen++;resolve({items:[{id:'1',title:'late'}],unread:1});await pending;assert.equal(p.apiMessages.length,0);
});
test('sales navigation has exactly five native destinations, while engineer keeps its workbench',()=>{
 const O=require('../miniprogram/utils/organization');
 const n=O.navigation({active:true,loggedIn:true,role:'sales',orgType:'store'});
 assert.equal(n.tabs.length,5);assert(n.tabs.every(t=>t.native));assert.equal(new Set(n.tabs.map(t=>t.url)).size,5);
 assert.equal(n.tabs.find(t=>t.key==='creation').url,'/pages/home/index');
 const eng=O.navigation({active:true,loggedIn:true,role:'engineer',orgType:'store'});assert(eng.tabs.some(t=>t.url==='/pages/build/home/index'));
});
function commission(prepare,contextOverride={}){
 let definition;const file=path.resolve('miniprogram/pages/workflow/commission/index.js');const nativeRequire=require('node:module').createRequire(file);let opened;
 const api={commissions:{context:async()=>({customerId:'100',customerName:'验收客户',city:'武汉',canPrepareBudget:prepare,allowedActions:[],latestBudget:null,commission:null,...contextOverride})}};
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:d=>definition=d,require:id=>id.endsWith('api/http')?{sessionGeneration:()=>1,requestGuard:()=>()=>true,token:()=>true}:id.endsWith('api/auth')?{currentApiPost:()=>({postId:'1',userId:'10',orgId:'1',orgType:'store',roleCode:'sales'})}:id.endsWith('api/franchise/commission')?api:id.endsWith('utils/page-navigation')?{open:url=>opened=url}:nativeRequire(id),console,wx:{showToast(){}}});
 const p={...definition,data:structuredClone(definition.data),setData(patch){Object.assign(this.data,patch)}};p.onLoad({view:'submit',customerId:'100'});
 return {p,getOpened:()=>opened};
}
test('missing budget exposes preparation without granting commission submission permission',async()=>{
 const {p,getOpened}=commission(true);await p.onShow();assert.equal(p.data.customer.name,'验收客户');assert.equal(p.data.budgetCard,true);assert.equal(p.data.canPrepareBudget,true);assert.equal(p.data.primary,'');assert.equal(p.action,'');p.completeBudget();assert.equal(getOpened(),'/pages/workflow/index?view=budget&id=100');
});
test('read-only customer cannot navigate through the budget write entry',async()=>{
 const {p,getOpened}=commission(false);await p.onShow();p.completeBudget();assert.equal(getOpened(),undefined);assert.equal(p.data.canPrepareBudget,false);assert(p.data.submitBlockedReason);
});
test('returning from budget to an existing pending commission resumes its original payment',async()=>{
 const detail={commission:{id:'200',customerId:'100',customerName:'验收客户',status:'DESIGNING',submissionVersion:1},submissions:[],allowedActions:['commission-simulate-pay'],simulatedPayment:{status:'PENDING',amountCents:240000}};
 const {p}=commission(true,{commissionId:'200',commission:detail});await p.onShow();assert.equal(p.id,'200');assert.equal(p.data.view,'pay');assert.equal(p.action,'commission-simulate-pay');assert.equal(p.data.primary,'登记付款');
});
