const Access=require('../../utils/access'),Job=require('../../utils/organization'),B=require('../../utils/business'),cases=require('../../utils/design-cases');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{cases,imagesReady:false,imageFailed:false,entries:[{title:'户型检索',view:'search'},{title:'平面方案',view:'ai-config'},{title:'立面方案',view:'ai-facade'},{title:'报价测算',view:'budget'},{title:'灵感库',view:'inspiration'}]},
 loadImages(){if(!wx.loadSubpackage)return this.setData({imagesReady:true});this.setData({imageFailed:false});wx.loadSubpackage({name:'business-workflow',success:()=>this.setData({imagesReady:true}),fail:()=>this.setData({imageFailed:true})});},
 onShow(){const actor=Access.context();if(!actor.loggedIn)return wx.redirectTo({url:'/pages/account/index?view=login'});if(!actor.active||!Job.has(actor.role,'sales'))return wx.switchTab({url:'/pages/'+Access.home()+'/index'});if(!this.data.imagesReady&&!this.data.imageFailed)this.loadImages();},
 open(e){const actor=Access.context();if(!actor.active||!Job.has(actor.role,'sales'))return;const view=e.currentTarget.dataset.view;if(!this.data.entries.some(x=>x.view===view))return;wx.navigateTo({url:'/pages/workflow/index?view=customers&destination='+view});},
 openCase(e){const actor=Access.context(),id=e.currentTarget.dataset.id;if(!actor.active||!Job.has(actor.role,'sales')||!cases.some(c=>c.id===id))return;const state=B.load();state.caseId=id;B.persist(state);wx.navigateTo({url:'/pages/workflow/index?view=customers&destination=case'});}
});
