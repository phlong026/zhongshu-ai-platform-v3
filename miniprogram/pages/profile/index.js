const A=require('../../utils/account');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
const profileDefinition={
data:{loggedIn:true,name:'张业务',role:'业务员',company:'湖北加盟商',avatar:'/assets/account-avatar.jpg',unread:3,progress:{completed:3,total:8,percent:37.5},courseImage:A.courseImage},
onShow(){const s=A.load(),c=A.company(s),a=A.access.context(s),team={'sales-lead':['业务团队','business-team'],'project-manager':['工程团队','engineering-team'],'hq-design-lead':['设计团队','design-team'],'finance-lead':['财务管理','finance-team'],boss:['人员岗位与交接','staff'],owner:['门店团队与交接','staff']}[a.role]||[];this.setData({loggedIn:s.session.loggedIn,showStoreServices:!s.session.loggedIn||a.orgType==='store',teamTitle:a.active?team[0]:'',teamView:team[1]||'',name:s.profile.name,avatar:s.profile.avatar,role:A.roles[s.session.role],company:c?c.name:'未选择公司',unread:A.scoped(s,s.messages).filter(x=>!x.read).length,progress:A.progress(s)});if(this.getTabBar()){this.getTabBar().refresh?.();this.getTabBar().setData({selected:4,hidden:false});}},
openTeam(){if(this.data.teamView)wx.navigateTo({url:'/pages/admin/index?view='+this.data.teamView});},
open(e){const view=e.currentTarget.dataset.view;if(view==='procurement')return wx.navigateTo({url:'/pages/account/index?view=selection'});const target='/pages/account/index?view='+view;if(['profile','messages','orders','personal-performance'].includes(view)&&!this.data.loggedIn)return wx.navigateTo({url:'/pages/account/index?view=login&return='+encodeURIComponent(target)});wx.navigateTo({url:target});},
openAction(e){const routes={profile:'profile',courses:'learning',resources:'learning&tab=资料',continueCourse:'course',feedback:'help',privacy:'privacy',settings:'privacy',service:'help'};this.open({currentTarget:{dataset:{view:routes[e.currentTarget.dataset.action]||'help'}}});}
 };
const baseShow=profileDefinition.onShow;
profileDefinition.onShow=function(){const a=A.access.context();if(a.active&&require('../../utils/organization').has(a.role,'sales')){this.getTabBar()?.refresh?.();return wx.redirectTo({url:'/pages/account/sales/index'});}return baseShow.call(this);};
Page(profileDefinition);
