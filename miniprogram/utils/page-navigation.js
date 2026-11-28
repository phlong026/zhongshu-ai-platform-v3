// Native stack first; retain the displaced page when a deep flow must redirect.
const copy=require('./clone');
const current=()=>getCurrentPages().slice(-1)[0];
const tabs=['home','business','engineering','management','profile'].map(v=>'/pages/'+v+'/index');
const salesTabs={creation:'/pages/home/index',customers:'/pages/management/index'};
let pendingTabEntry=null;
const stateKeys=["form","keyword","filter","versionFilter","flowTab","statusFilter","sort","due","nodeFilter","materialFilter","nodeId","mode","destination","category","tab","orderTab","messageTab","financeTab","dataTab","activeCustomerTab","caseTab","floor","selectedPlan","searchWidth","searchDepth","pickingRef","caseItem","filteredCases","taskFilter","recordFilter","editingText","optimizeRequirement","expanded","courseTab"];
function apiSession(){try{const auth=require('../api/auth'),http=require('../api/http');const token=http.token();const post=token?auth.currentApiPost():null;return post?{post,gen:http.sessionGeneration()}:null;}catch(e){return null;}}
function scope(){const api=apiSession();if(api){const p=api.post;return ['api',p.tenantId||'',p.orgId||'',p.postId||'',p.orgType||'',p.roleCode||'',api.gen].join(':');}const a=require('./access').context();return [a.companyId,a.staffId,a.role,a.active].join(':');}
// Resolve old sales entry paths before opening a page. Native switchTab cannot
// carry query parameters; the destination consumes this scoped intent once.
function salesDestination(destination){
 const [path,query='']=destination.split('?'),kind=['creation','design','customers'].find(key=>path==='/pages/'+key+'/index');
 if(!kind)return null;
 const api=apiSession();if(!api||!['sales','sales-lead'].includes(api.post.roleCode))return null;
 // The retired sales scheme Tab is now the inspiration tool. Keep other roles' routes intact.
 if(kind==='design')return {url:'/design-migration/pages/library/index'};
 const options={};
 for(const pair of query.split('&')){
  const split=pair.indexOf('='),key=split<0?pair:pair.slice(0,split);
  if(!['focus','intent','editor','section'].includes(key))continue;
  try{options[key]=decodeURIComponent(split<0?'':pair.slice(split+1));}catch(_){/* Ignore malformed legacy options. */}
 }
 if(kind==='creation'&&['works','digital'].includes(options.section))return {url:'/pages/studio/index?view='+options.section};
 return {route:salesTabs[kind],options};
}
function takeTabEntry(route){
 const entry=pendingTabEntry;
 if(!entry)return null;
 if(entry.scope!==scope()){pendingTabEntry=null;return null;}
 if(entry.route!==route)return null;
 pendingTabEntry=null;return entry.options;
}
function clearTabEntry(route,entryScope,page){if(pendingTabEntry?.route===route&&pendingTabEntry.scope===entryScope&&pendingTabEntry.replacingPage!==page)pendingTabEntry=null;}
function openSalesTab(target){
 const entry=pendingTabEntry={...target,scope:scope()},page=current();
 const samePage=page&&'/'+page.route===target.route;
 if(samePage&&page.acceptNavigationEntry?.())return;
 if(samePage)entry.replacingPage=page; // Its unload must not erase the replacement's intent.
 const options={url:target.route,fail(){if(pendingTabEntry===entry)pendingTabEntry=null;wx.showToast({title:'页面打开失败，请重试',icon:'none'});}};
 // A changed actor or failed authorization must initialize a fresh page.
 return samePage?wx.reLaunch(options):wx.switchTab(options);
}
function url(page){const opts={...(page.options||{})};if(page.data?.view)opts.view=page.data.view;if(page.id)opts.id=page.id;return '/'+page.route+Object.keys(opts).filter(k=>opts[k]!==undefined&&opts[k]!=='').map((k,i)=>(i?'&':'?')+encodeURIComponent(k)+'='+encodeURIComponent(opts[k])).join('');}
function homeUrl(){return '/pages/'+(require('./access').home()||'home')+'/index';}
function home(){current()?.persistEditor?.();const api=apiSession();if(api){const O=require('./organization');const dest=O.navigation(O.apiContext(api.post)).tabs[0];const options={url:dest.url,fail:()=>wx.showToast({title:'工作台打开失败，请重试',icon:'none'})};// 专属工作台一次换根直达，避免 switchTab 落在中转 tab 等 onShow 二次导航互相等待（与登录/切岗 openApiHome 同型，2026-11-10）。
return dest.native?wx.switchTab(options):wx.reLaunch(options);}return wx.switchTab({url:homeUrl()});}
function target(route,view,id,extra={}){return route+'?view='+view+(id?'&id='+encodeURIComponent(id):'')+Object.keys(extra).filter(k=>extra[k]).map(k=>'&'+k+'='+encodeURIComponent(extra[k])).join('');}
function parent(page=current()){
 const route=page?.route||'',d=page?.data||{},o=page?.options||{},v=d.view||o.view,id=page?.id||o.id||d.id||'';
 if(route==='pages/workflow/index'&&apiSession()){// API 态父级：仅本批视图；demo map 不动
  const map={customers:'',customer:'customers',arrival:'customers',transfer:'customer',requirement:'customer',budget:'customer','budget-result':'budget',contract:'customer',delivery:'customer',progress:'customer'};
  const next=Object.prototype.hasOwnProperty.call(map,v)?map[v]:'';
  return next?target('/pages/workflow/index',next,id):homeUrl();
 }
 if(route==='pages/workflow/index'){
  const map={'budget-result':'budget',budget:'customer',customers:'',customer:'customers',arrival:'customer',progress:'customer',history:'customer',delivery:'customer',hub:'customer','case-plan':'search',case:'inspiration','ai-results':'ai-config',result:'ai-config','facade-result':'facade-config','facade-candidates':'facade-config'};
  const next=Object.prototype.hasOwnProperty.call(map,v)?map[v]:'delivery',c=d.customer;
  return next&&(!c||require('./access').workflow(next,c,false))?target('/pages/workflow/index',next,id):homeUrl();
 }
 if(route==='pages/workflow/explore/index'){const api=apiSession();return api&&['sales','sales-lead'].includes(api.post.roleCode)?'/pages/business/index':'/pages/design/index';}
 if(route==='pages/admin/finance-desk/index'){const D=require('./finance-desk');return ['statement','partner'].includes(v)?D.url(require('./access').context().orgType==='headquarters'?'partners':'projects'):v==='project'?D.url('projects'):homeUrl();}
 if(route==='pages/workflow/quote-export/index')return target('/pages/workflow/index','budget-result',id);
 if(route==='pages/build/index')return v==='projects'?homeUrl():target('/pages/build/index',v==='overview'?'projects':'overview',id);
 if(route==='pages/account/announcements/detail')return '/pages/account/announcements/index';
 if(route==='pages/account/announcements/index'){
  const session=apiSession(),O=require('./organization');
  const profile=session&&O.navigation(O.apiContext(session.post)).tabs.find(tab=>tab.index===4);
  return profile?profile.url:'/pages/profile/index';
 }
 if(route==='pages/account/index'){
  const map={course:'learning',resource:'learning',service:'services',request:'service',product:'selection',procure:'product',purchase:'purchases',order:'orders',plan:'order',delivery:'order',cancel:'order',feedback:'help',binding:'privacy',devices:'privacy',permissions:'privacy',policy:apiSession()?'privacy':'',agreement:'policy'};
  return map[v]?target('/pages/account/index',map[v],id,{service:o.service}):'/pages/profile/index';
 }
 if(route==='pages/studio/index'){
  const map={detail:'records',result:'works',publish:'result',receipt:'records',library:'remix'};
  return map[v]?target('/pages/studio/index',map[v],'',{task:o.task}):'/pages/creation/index';
 }
 return homeUrl();
}
function restore(page,entry){
 if(!page||entry.scope!==scope()||page.route!==entry.url.split('?')[0].slice(1))return;
 page.__navigationTrail=entry.trail;page.setData(entry.state);page.filterCustomers?.();if(page.filter?.length===0)page.filter();page.restoreNavigationState?.();
 if(entry.scrollTop)wx.pageScrollTo({scrollTop:entry.scrollTop,duration:0});
}
function open(destination,{replace=false}={}){
 const sales=salesDestination(destination);
 if(sales?.route)return openSalesTab(sales);
 pendingTabEntry=null;
 if(sales)destination=sales.url;
 const pages=getCurrentPages(),p=current();if(!p)return wx.redirectTo({url:destination});
 const existing=pages.findIndex(other=>url(other)===destination);
 if(existing>=0&&existing<pages.length-1&&wx.navigateBack)return wx.navigateBack({delta:pages.length-1-existing});
 if(url(p)===destination)return;
 if(tabs.includes(destination))return wx.switchTab({url:destination});
 if(!replace&&pages.length<8)return wx.navigateTo({url:destination});
 const trail=replace?(p.__navigationTrail||[]):[{url:url(p),scope:scope(),state:Object.fromEntries(stateKeys.filter(k=>p.data[k]!==undefined).map(k=>[k,copy(p.data[k])])),scrollTop:p.__navigationScrollTop||0,trail:p.__navigationTrail||[]}];
 return wx.redirectTo({url:destination,success(){const next=current();if(next&&next.route===destination.split('?')[0].slice(1))next.__navigationTrail=trail;}});
}
function back({fallback,skipLocal=false}={}){
 const p=current();if(!p)return home();
 if(!skipLocal){
  if(p.data?.preview&&p.closePreview)return p.closePreview();
  if(p.data?.modal&&p.closeModal)return p.closeModal();
  if(p.data?.sheet){if(p.closeSheet)return p.closeSheet();return p.setData({sheet:false});}
  if(p.navigationBack)return p.navigationBack();
 }
 p.persistEditor?.();
 const entry=(p.__navigationTrail||[])[0];
 if(entry&&entry.scope===scope())return wx.redirectTo({url:entry.url,success(){restore(current(),entry);},fail:home});
 if(getCurrentPages().length>1&&wx.navigateBack)return wx.navigateBack({delta:1,fail:()=>open(fallback||parent(p),{replace:true})});
 const dest=fallback||parent(p);return dest===url(p)?home():open(dest,{replace:true});
}
module.exports={back,home,open,parent,url,homeUrl,scope,salesTabs,takeTabEntry,clearTabEntry};
