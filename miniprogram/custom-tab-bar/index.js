const nav=require('../utils/workbench-nav');
const Workspace=require('../utils/workspace-navigation');
Component({
 properties:{activeIndex:{type:Number,value:-1,observer(){this.refresh();}},nested:{type:Boolean,value:false}},
 data:{selected:0,hidden:false,tabs:[]},
 lifetimes:{attached(){this.refresh();}},pageLifetimes:{show(){this.refresh();}},
 methods:{
  refresh(){const tabs=nav().tabs,pages=getCurrentPages(),route='/'+(pages[pages.length-1]?.route||''),current=tabs.find(t=>t.url===route);this.setData({tabs,selected:this.properties.activeIndex>=0?this.properties.activeIndex:current?current.index:0});},
  switchTab(e){return Workspace.select(Number(e.currentTarget.dataset.index),this.properties.nested,this.data.selected,this);}
 }
});
