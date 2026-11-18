const P=require('../../utils/platform-engineering'); // 旧工程详情壳：页面路径保留（旧分享链接兜底），本地视图逻辑已下线
Page({
 data:{view:'projects',title:'我的项目',p:null,sections:[],form:{},missing:false,error:''},
 onLoad(o={}){
  if(P.online()){
   if(!o.id){
    const destination=({materials:'materials',schedule:'schedule',prepare:'prepare',documents:'documents',payments:'payments',site:'site',inspection:'inspection'})[o.view];
    wx.redirectTo({url:'/pages/build/projects/index'+(destination?'?destination='+destination:'')});return;
   }
   const view=({schedule:'project',site:'node',inspection:'node',projects:'overview'})[o.view]||o.view||'overview';
   wx.redirectTo({url:'/pages/build/record/index?view='+encodeURIComponent(view)+'&id='+encodeURIComponent(o.id)+(o.nodeId?'&nodeId='+encodeURIComponent(o.nodeId):'')});return;
  }
  // 非 API 态：本地数据依赖已下线，提示登录（不再回落本地渲染）
  this.setData({missing:true,error:'请先登录后端账号'});
 }
});
