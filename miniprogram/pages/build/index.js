const P=require('../../utils/platform-engineering');
// Keep old links, including their original project, installment and evidence context.
Page({
 data:{failed:false},
 onLoad(o={}){this.entry=o;this.openDestination();},
 openDestination(){
  const o=this.entry||{};
  let target;
  if(!o.id){
   const destination=({materials:'materials',schedule:'schedule',prepare:'prepare',documents:'documents',payments:'payments',site:'site',inspection:'inspection'})[o.view];
   target='/pages/build/projects/index'+(destination?'?destination='+destination:'');
  }else{
   const view=({schedule:'project',site:'node',inspection:'node',projects:'overview'})[o.view]||o.view||'overview';
   const context=['nodeId','stage','planId','homeSupply'].filter(key=>o[key]).map(key=>'&'+key+'='+encodeURIComponent(o[key])).join('');
   target='/pages/build/record/index?view='+encodeURIComponent(view)+'&id='+encodeURIComponent(o.id)+context;
  }
  const url=P.online()?target:'/pages/account/index?view=login&return='+encodeURIComponent(target);
  this.setData({failed:false});
  wx.redirectTo({url,fail:()=>this.setData({failed:true})});
 },
 home(){require('../../utils/page-navigation').home();}
});
