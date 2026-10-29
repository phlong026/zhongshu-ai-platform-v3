// Native stack first; retain the displaced page when a deep flow must redirect.
const copy=require('./clone');
const current=()=>getCurrentPages().slice(-1)[0];
const tabs=['home','business','engineering','management','profile'].map(v=>'/pages/'+v+'/index');
const stateKeys=["form","keyword","filter","versionFilter","flowTab","statusFilter","sort","due","nodeFilter","materialFilter","nodeId","mode","destination","category","tab","orderTab","messageTab","financeTab","dataTab","activeCustomerTab","caseTab","floor","selectedPlan","searchWidth","searchDepth","pickingRef","caseItem","filteredCases","taskFilter","recordFilter","editingText","optimizeRequirement","expanded","courseTab"];
function scope(){const a=require('./access').context();return [a.companyId,a.staffId,a.role,a.active].join(':');}
function url(page){const opts={...(page.options||{})};if(page.data?.view)opts.view=page.data.view;if(page.id)opts.id=page.id;return '/'+page.route+Object.keys(opts).filter(k=>opts[k]!==undefined&&opts[k]!=='').map((k,i)=>(i?'&':'?')+encodeURIComponent(k)+'='+encodeURIComponent(opts[k])).join('');}
function homeUrl(){return '/pages/'+(require('./access').home()||'home')+'/index';}
function home(){current()?.persistEditor?.();return wx.switchTab({url:homeUrl()});}
function target(route,view,id,extra={}){return route+'?view='+view+(id?'&id='+encodeURIComponent(id):'')+Object.keys(extra).filter(k=>extra[k]).map(k=>'&'+k+'='+encodeURIComponent(extra[k])).join('');}
function parent(page=current()){
 const route=page?.route||'',d=page?.data||{},o=page?.options||{},v=d.view||o.view,id=page?.id||o.id||d.id||'';
 if(route==='pages/workflow/index'){
  const map={'budget-result':'budget',budget:'customer',customers:'',customer:'customers',arrival:'customer',progress:'customer',history:'customer',delivery:'customer',hub:'customer','case-plan':'search',case:'inspiration','ai-results':'ai-config',result:'ai-config','facade-result':'facade-config','facade-candidates':'facade-config'};
  const next=Object.prototype.hasOwnProperty.call(map,v)?map[v]:'delivery',c=d.customer;
  return next&&(!c||require('./access').workflow(next,c,false))?target('/pages/workflow/index',next,id):homeUrl();
 }
 if(route==='pages/workflow/explore/index')return '/pages/design/index';
 if(route==='pages/admin/finance-desk/index'){const D=require('./finance-desk');return ['statement','partner'].includes(v)?D.url(require('./access').context().orgType==='headquarters'?'partners':'projects'):v==='project'?D.url('projects'):homeUrl();}
 if(route==='pages/workflow/quote-export/index')return target('/pages/workflow/index','budget-result',id);
 if(route==='pages/build/index')return v==='projects'?homeUrl():target('/pages/build/index',v==='overview'?'projects':'overview',id);
 if(route==='pages/account/index'){
  const map={course:'learning',resource:'learning',service:'services',request:'service',product:'selection',procure:'product',purchase:'purchases',order:'orders',plan:'order',delivery:'order',cancel:'order',feedback:'help',binding:'privacy',devices:'privacy',permissions:'privacy',policy:'privacy',agreement:'privacy'};
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
module.exports={back,home,open,parent,url,homeUrl};
