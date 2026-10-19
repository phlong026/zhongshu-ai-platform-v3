const Access=require('../../utils/access'),Job=require('../../utils/organization');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{entries:[{title:'户型检索',description:'按尺寸寻找方案参考',image:'plan-search-cover.jpg',view:'search'},{title:'平面方案',description:'结合客户需求辅助设计',image:'floor-plan.png',view:'ai-config'},{title:'立面方案',description:'把建房想法变成效果',image:'facade.jpg',view:'ai-facade'},{title:'报价测算',description:'核对参数与参考预算',image:'quote-estimate-cover.jpg',view:'budget'}]},
 onShow(){const actor=Access.context();if(!actor.loggedIn)return wx.redirectTo({url:'/pages/account/index?view=login'});if(!actor.active||!Job.has(actor.role,'sales'))return wx.switchTab({url:'/pages/'+Access.home()+'/index'});},
 open(e){const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return;const view=e.currentTarget.dataset.view;if(!this.data.entries.some(x=>x.view===view))return;wx.navigateTo({url:'/pages/workflow/index?view=customers&destination='+view});}
});
