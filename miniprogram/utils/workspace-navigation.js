const nav=require('./workbench-nav');
const http=require('../api/http'),auth=require('../api/auth');
const signature=()=>{const p=auth.currentApiPost()||{};return JSON.stringify([http.sessionGeneration(),p.tenantId,p.orgId,p.postId,p.roleCode,p.orgType,nav().tabs.map(t=>t.url)]);};
function openTab(tab,nested,bar){
 const app=getApp(),page=getCurrentPages().slice(-1)[0];
 if(page?.switchWorkspaceTab?.(tab)){bar?.setData({selected:tab.index});return;}
 const [route,query='']=tab.url.split('?');
 const atTarget=route==='/'+(page?.route||'')&&query.split('&').filter(Boolean).every(part=>{const [key,value='']=part.split('=');return String(page?.options?.[decodeURIComponent(key)]||'')===decodeURIComponent(value);});
 if(atTarget&&!page?.hasWorkspaceSubpage?.())return;
 app.globalData.workspaceNavigating=true;
 const options={url:tab.url,fail:()=>wx.showToast({title:'页面切换失败，请重试',icon:'none'}),complete:()=>{
  app.globalData.workspaceNavigating=false;
  const pending=app.globalData.workspacePending;delete app.globalData.workspacePending;
  if(!pending||pending.signature!==signature())return;
  const current=getCurrentPages().slice(-1)[0],currentBar=current?.getTabBar?.();
  if(currentBar?.switchTab)return currentBar.switchTab({currentTarget:{dataset:{index:pending.index}}});
  const next=nav().tabs.find(t=>t.index===pending.index);
  if(next)openTab(next,true);
 }};
 if(tab.native)return wx.switchTab(options);
 if(nested)return wx.redirectTo(options);
 return wx.navigateTo(options);
}
function select(index,nested,selected,bar){
 const tab=nav().tabs.find(t=>t.index===index);if(!tab)return;
 const app=getApp();
 if(app.globalData.workspaceNavigating){app.globalData.workspacePending={index,signature:signature()};return;}
 if(index===selected&&!getCurrentPages().slice(-1)[0]?.hasWorkspaceSubpage?.())return;
 return openTab(tab,nested,bar);
}
module.exports={select};
