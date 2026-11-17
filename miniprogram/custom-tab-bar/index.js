const nav=require('../utils/workbench-nav');
const signature=()=>nav().tabs.map(t=>t.url).join('|');
function openTab(tab,nested,bar){
 const app=getApp(),page=getCurrentPages().slice(-1)[0];
 if(page?.switchWorkspaceTab?.(tab)){bar?.setData({selected:tab.index});return;}
 app.globalData.workspaceNavigating=true;
 const options={url:tab.url,fail:()=>wx.showToast({title:'页面切换失败，请重试',icon:'none'}),complete:()=>{
  app.globalData.workspaceNavigating=false;
  const pending=app.globalData.workspacePending;delete app.globalData.workspacePending;
  if(!pending||pending.signature!==signature())return;
  const current=getCurrentPages().slice(-1)[0],currentBar=current?.getTabBar?.();
  if(currentBar?.switchTab)return currentBar.switchTab({currentTarget:{dataset:{index:pending.index}}});
  const next=nav().tabs.find(t=>t.index===pending.index);
  // Dedicated workbenches use a nested bar. Their page handler keeps local
  // tabs in place; otherwise continue from the current page, not a detached bar.
  if(next)openTab(next,true);
 }};
 if(tab.native)return wx.switchTab(options);
 if(nested)return wx.redirectTo(options);
 return wx.navigateTo(options);
}
Component({
 properties:{activeIndex:{type:Number,value:-1,observer(){this.refresh();}},nested:{type:Boolean,value:false}},
 data:{selected:0,hidden:false,tabs:[]},
 lifetimes:{attached(){this.refresh();}},pageLifetimes:{show(){this.refresh();}},
 methods:{
  refresh(){const tabs=nav().tabs,pages=getCurrentPages(),route='/'+(pages[pages.length-1]?.route||''),current=tabs.find(t=>t.url===route);this.setData({tabs,selected:this.properties.activeIndex>=0?this.properties.activeIndex:current?current.index:0});},
  switchTab(e){
   const index=Number(e.currentTarget.dataset.index),tab=nav().tabs.find(t=>t.index===index);
   if(!tab)return;
   const app=getApp();
   if(app.globalData.workspaceNavigating){app.globalData.workspacePending={index,signature:signature()};return;}
   if(index===this.data.selected&&!getCurrentPages().slice(-1)[0]?.hasWorkspaceSubpage?.())return;
   return openTab(tab,this.properties.nested,this);
  }
 }
});
