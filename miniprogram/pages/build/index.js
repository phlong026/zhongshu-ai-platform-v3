const P=require('../../utils/platform-engineering'); // 旧工程详情壳：页面路径保留（旧分享链接兜底），本地视图逻辑已下线
Page({
 data:{view:'projects',title:'我的项目',p:null,sections:[],form:{},missing:false,error:''},
 onLoad(o){
  // API 态：一律转跳 record 页承接（view/id/nodeId 透传；旧 view 由 record 页兜底映射为 overview）
  if(P.online()){wx.redirectTo({url:'/pages/build/record/index?view='+(o.view||'overview')+'&id='+encodeURIComponent(o.id||'')+(o.nodeId?'&nodeId='+encodeURIComponent(o.nodeId):'')});return;}
  // 非 API 态：本地数据依赖已下线，提示登录（不再回落本地渲染）
  this.setData({missing:true,error:'请先登录后端账号'});
 }
});
