const Data=require('./fixtures.js'),Demo=require('../../utils/demo-session'),O=require('../../utils/organization');
Page({data:{filter:'全部',filters:['全部','内容创作','获客客户','设计业务','财务','工程','其他模块'],scenes:[],active:false,selected:-1,error:''},
onLoad(){this.refresh();},onShow(){this.refresh();},
refresh(){const s=Demo.active(),scenes=Data.scenes.map((r,index)=>({index,label:r.label,group:r.group,actorName:r.actorName,role:O.roles[r.actor.role],organization:r.actor.companyId==='headquarters'?'总部':'湖北加盟商',next:r.next||'当前流程完成',summary:r.summary,inputs:r.inputs,executable:r.executable,branch:r.branch||''}));this.setData({active:!!s,selected:s?.index??-1,scenes:scenes.filter(r=>this.data.filter==='全部'||r.group===this.data.filter),total:Data.scenes.length,generatedAt:Data.generatedAt});},
filter(e){this.setData({filter:e.currentTarget.dataset.value});this.refresh();},
live(){wx.navigateTo({url:'/pages/demo/live'});},
open(e){try{const index=Number(e.currentTarget.dataset.index),after=e.currentTarget.dataset.after===true||e.currentTarget.dataset.after==='true',scene=Demo.load(Data,index,after);this.setData({error:''});const url=after?(scene.resultUrl||scene.url):scene.url;if(/^\/pages\/(home|business|engineering|management|profile)\/index$/.test(url))wx.switchTab({url});else wx.reLaunch({url});}catch(e){this.setData({error:e.message});}},
restore(){try{Demo.restore();this.refresh();wx.showToast({title:'演示前数据已恢复',icon:'none'});wx.switchTab({url:'/pages/profile/index'});}catch(e){this.setData({error:e.message});}},
home(){wx.switchTab({url:'/pages/profile/index'});}
});
