// AI 设计入口（纯平台态）：design:use 三岗（sales/sales-lead/hq-design-admin）直达设计工作流；无平台岗位即停用入口，不再回落本地案例。
const ENTRY_ROLES=['sales','sales-lead','hq-design-admin'];
const currentPost=()=>{try{const auth=require('../../api/auth');const http=require('../../api/http');return http.token()?auth.currentApiPost():null;}catch(e){return null;}};
Page({
 data:{entries:[{title:'灵感库',view:'inspiration',icon:'home-design-icon',hint:'从优秀方案找灵感'},{title:'AI平面',view:'ai-config',icon:'plan-file',hint:'按家庭需求做布局'},{title:'AI立面',view:'ai-facade',icon:'house',hint:'探索建筑外观方案'},{title:'快速预算',view:'budget',icon:'contract',hint:'快速估算建造投入'}],entranceEnabled:false},
 onShow(){const post=currentPost();this.setData({entranceEnabled:!!post&&ENTRY_ROLES.includes(post.roleCode)});},
 open(e){
  const post=currentPost();
  if(!post||!ENTRY_ROLES.includes(post.roleCode))return;// 入口停用（wxml 仅在 entranceEnabled 时渲染入口）
  const view=e.currentTarget.dataset.view;if(!this.data.entries.some(x=>x.view===view))return;
  wx.navigateTo({url:'/pages/workflow/explore/index?view='+view});},
});
