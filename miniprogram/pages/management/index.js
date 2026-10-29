// Guests preview the management module here; signed-in boss and finance roles
// redirect straight to their own workbench, keeping one tab entry per plan D1.
const X=require('../../utils/access');
const guestSearch=require('./guest-search');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({data:{allowed:false,loggedIn:false,role:'',guest:false,menu:[],companyId:''},
onShow(){const a=X.context();if(a.active&&X.isBoss())return wx.redirectTo({url:'/pages/admin/home/index'});if(a.active&&['finance','finance-lead'].includes(a.role))return wx.redirectTo({url:'/pages/admin/finance-desk/index'});const state=require('../../utils/workbench').management();this.setData({...state,...(state.guest?guestSearch(this.data.guestKeyword||''):{}),guestDetail:null});if(this.getTabBar()){this.getTabBar().refresh?.();this.getTabBar().setData({selected:3,hidden:false});}},
searchGuest(e){if(this.data.guest)this.setData(guestSearch(e.detail.value));},
clearGuestSearch(){if(this.data.guest)this.setData(guestSearch());},
preview(e){if(!this.data.guest)return;const key=e.currentTarget.dataset.key,item=(this.data.guestGroups||[]).flatMap(g=>g.items).find(x=>x.key===key);if(item){this.setData({guestDetail:item});this.getTabBar()?.setData({hidden:true});}},
closePreview(){this.setData({guestDetail:null});this.getTabBar()?.setData({hidden:false});},
loginPreview(){const detail=this.data.guestDetail;this.closePreview();const target=detail?'/pages/admin/index?view='+detail.target:'';wx.navigateTo({url:'/pages/account/index?view=login'+(target?'&content='+encodeURIComponent(detail.label)+'&return='+encodeURIComponent(target):'')});},
noop(){},
goHome(){wx.switchTab({url:'/pages/'+X.home()+'/index'});},
identity(){wx.navigateTo({url:'/pages/account/index?view='+(this.data.loggedIn?'identity':'login')});}
});
