const Access=require('../../utils/access'),Job=require('../../utils/organization'),B=require('../../utils/business'),cases=require('../../utils/design-cases');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{cases:cases.slice(0,4),imagesReady:false,imageFailed:false,entries:[{title:'灵感库',view:'inspiration',icon:'home-design-icon',hint:'从优秀方案找灵感'},{title:'AI平面',view:'ai-config',icon:'plan-file',hint:'按家庭需求做布局'},{title:'AI立面',view:'ai-facade',icon:'house',hint:'探索建筑外观方案'},{title:'快速预算',view:'budget',icon:'contract',hint:'快速估算建造投入'}]},
 loadImages(){if(!wx.loadSubpackage)return this.setData({imagesReady:true});this.setData({imageFailed:false});wx.loadSubpackage({name:'business-workflow',success:()=>this.setData({imagesReady:true}),fail:()=>this.setData({imageFailed:true})});},
 onShow(){const actor=Access.context();if(!actor.loggedIn)return wx.redirectTo({url:'/pages/account/index?view=login'});if(!actor.active||!Job.has(actor.role,'sales'))return wx.switchTab({url:'/pages/'+Access.home()+'/index'});if(!this.data.imagesReady&&!this.data.imageFailed)this.loadImages();},
 open(e){const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return;const view=e.currentTarget.dataset.view;if(!this.data.entries.some(x=>x.view===view))return;wx.navigateTo({url:'/pages/workflow/explore/index?view='+view});},
 openCase(e){const actor=Access.context(),id=e.currentTarget.dataset.id;if(!actor.active||!Job.has(actor.role,'sales')||!cases.some(c=>c.id===id))return;wx.navigateTo({url:'/pages/workflow/explore/index?view=case&case='+encodeURIComponent(id)});}
});
