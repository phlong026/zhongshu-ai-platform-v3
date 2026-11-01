const Access=require('../../utils/access'),Job=require('../../utils/organization');
// Entrance thumbnails travel with this page; the full catalog stays in the business workflow.
const cases=require('../../utils/design-cases').slice(0,4).map(c=>({id:c.id,name:c.name,area:c.area,floors:c.floors,style:c.style,image:c.image.replace('/pages/workflow/assets/','/pages/design/assets/')}));
Page({
 data:{cases,entries:[{title:'灵感库',view:'inspiration',icon:'home-design-icon',hint:'从优秀方案找灵感'},{title:'AI平面',view:'ai-config',icon:'plan-file',hint:'按家庭需求做布局'},{title:'AI立面',view:'ai-facade',icon:'house',hint:'探索建筑外观方案'},{title:'快速预算',view:'budget',icon:'contract',hint:'快速估算建造投入'}]},
 onShow(){
  const post=(()=>{try{const auth=require('../../api/auth');const http=require('../../api/http');return http.token()?auth.currentApiPost():null;}catch(e){return null;}})();
  if(post&&['sales','sales-lead','hq-design-admin'].includes(post.roleCode))return;// design:use 三岗 API 态直达
  const actor=Access.context();if(!actor.loggedIn)return wx.redirectTo({url:'/pages/account/index?view=login'});if(!actor.active||!Job.has(actor.role,'sales'))return wx.switchTab({url:'/pages/'+Access.home()+'/index'});},
 open(e){const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return;const view=e.currentTarget.dataset.view;if(!this.data.entries.some(x=>x.view===view))return;wx.navigateTo({url:'/pages/workflow/explore/index?view='+view});},
 openCase(e){const actor=Access.context(),id=e.currentTarget.dataset.id;if(!actor.active||!Job.has(actor.role,'sales')||!cases.some(c=>c.id===id))return;wx.navigateTo({url:'/pages/workflow/explore/index?view=case&case='+encodeURIComponent(id)});}
});
