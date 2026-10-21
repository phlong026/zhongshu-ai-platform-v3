const nav=require('../utils/workbench-nav');
Component({
 properties:{activeIndex:{type:Number,value:-1,observer(){this.refresh();}},nested:{type:Boolean,value:false}},
 data:{selected:0,hidden:false,tabs:[]},
 lifetimes:{attached(){this.refresh();}},pageLifetimes:{show(){this.refresh();}},
 methods:{
  refresh(){const tabs=nav().tabs,pages=getCurrentPages(),route='/'+(pages[pages.length-1]?.route||''),current=tabs.find(t=>t.url===route);this.setData({tabs,selected:current?current.index:this.properties.activeIndex>=0?this.properties.activeIndex:0});},
  switchTab(e){const index=Number(e.currentTarget.dataset.index),tab=nav().tabs.find(t=>t.index===index);if(!tab||index===this.data.selected)return;const page=getCurrentPages().slice(-1)[0];if(page?.switchWorkspaceTab?.(tab)){this.setData({selected:index});return;}if(tab.native)return wx.switchTab({url:tab.url});if(this.properties.nested)return wx.redirectTo({url:tab.url});wx.navigateTo({url:tab.url});}
 }
});
