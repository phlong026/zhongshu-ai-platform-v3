const Access=require('../../utils/access');
Page({
 data:{roles:[{name:'业务',description:'内容获客 · 客户跟进',icon:'team'},{name:'工程',description:'节点管理 · 项目交付',icon:'home-engineer-icon'},{name:'财务',description:'收支办理 · 账目协同',icon:'money'},{name:'门店负责人',description:'团队协作 · 经营掌握',icon:'home-chart-icon'}],steps:[{name:'创作获客',icon:'home-video-icon'},{name:'客户经营',icon:'team'},{name:'工程交付',icon:'home-engineer-icon'},{name:'财务协同',icon:'money'}]},
 onShow(){const actor=Access.context();if(actor.loggedIn){wx.switchTab({url:'/pages/'+Access.home()+'/index'});return;}if(this.getTabBar()){this.getTabBar().refresh?.();this.getTabBar().setData({selected:0,hidden:false});}},
 learnPlatform(){wx.showModal({title:'众墅之家AI赋能平台',content:'围绕乡墅门店经营，连接内容创作、方案设计、客户跟进、工程交付与财务协同。登录后，按已授权岗位进入自己的工作台。',showCancel:false,confirmText:'知道了',confirmColor:'#75451f'});},
 login(){wx.navigateTo({url:'/pages/account/index?view=login'});}
});
