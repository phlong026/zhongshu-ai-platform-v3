// Role-specific queues use the same source IDs and authorization as their detail pages.
const A=require('../../utils/account'),X=require('../../utils/access'),F=require('../../utils/finance-source');
const guestSearch=require('./guest-search');
const staffSearch=require('./staff-search');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({data:{allowed:false,loggedIn:false,role:'',mode:'finance',financeTodos:[],financeCounts:{reconcile:0,pay:0,verify:0},summary:{income:0,paid:0},menu:[],companyId:'',receivable:'待配置',plannedPay:'待配置'},
onShow(){if(X.context().active&&X.isBoss())return wx.redirectTo({url:'/pages/admin/home/index'});const state=require('../../utils/workbench').management(),D=require('../../utils/finance-desk');let financeDesk=null,financeError='';if(D.allowed()){try{financeDesk=D.home(D.load());}catch(e){financeError=e.message;}}this.setData({...state,...(state.guest?guestSearch(this.data.guestKeyword||''):staffSearch(state,this.data.staffKeyword||'')),financeDesk,financeError,guestDetail:null});if(this.getTabBar()){this.getTabBar().refresh?.();this.getTabBar().setData({selected:3,hidden:false});}},
financeGo(e){if(require('../../utils/finance-desk').allowed()&&e.currentTarget.dataset.url)require('../../utils/page-navigation').open(e.currentTarget.dataset.url);},
financeMonth(e){try{require('../../utils/finance-desk').setPeriod(e.detail.value);this.onShow();}catch(error){wx.showToast({title:error.message,icon:'none'});}},
financeMetric(e){const D=require('../../utils/finance-desk'),d=this.data.financeDesk,i=Number(e.currentTarget.dataset.index);if(!d||!D.allowed())return;const targets=d.hq?(d.lead?[D.url('partners','',{filter:'debt'}),D.url('partners','',{filter:'advance'}),D.url('partners','',{filter:'overdue'}),d.reviewTasksUrl]:[D.url('flows','',{tab:'ledger'}),D.url('flows'),D.url('partners','',{filter:'advance'}),D.url('partners','',{filter:'pending'})]):d.lead?[D.url('flows'),D.url('flows','',{tab:'payables',due:'week'}),D.url('flows','',{filter:'overdue'}),d.reviewTasksUrl]:[D.url('flows','',{tab:'ledger',category:'实收'}),D.url('flows','',{tab:'ledger',category:'实付'}),D.url('flows'),D.url('flows','',{tab:'payables',due:'week'})];require('../../utils/page-navigation').open(targets[i]);},
searchStaff(e){if(!this.data.guest&&this.data.allowed)this.setData(staffSearch(this.data,e.detail.value));},
clearStaffSearch(){if(!this.data.guest&&this.data.allowed)this.setData(staffSearch(this.data));},
searchGuest(e){if(this.data.guest)this.setData(guestSearch(e.detail.value));},
clearGuestSearch(){if(this.data.guest)this.setData(guestSearch());},
go(e){const d=e.currentTarget.dataset;if(this.data.guest||!this.data.allowed)return this.identity();if(d.url)return wx.navigateTo({url:d.url});if(d.area==='operations')return wx.navigateTo({url:'/pages/admin/operations/index?view='+(d.view==='plan-approval'?'receivables':'expenses')+'&id='+encodeURIComponent(d.id)});wx.navigateTo({url:'/pages/'+(d.area==='workflow'?'workflow':'admin')+'/index?view='+d.view+(d.id?'&id='+encodeURIComponent(d.id):'')+(d.taskType?'&taskType='+encodeURIComponent(d.taskType):'')+(d.record?'&record='+encodeURIComponent(d.record):'')});},
preview(e){if(!this.data.guest)return;const key=e.currentTarget.dataset.key,item=(this.data.guestGroups||[]).flatMap(g=>g.items).find(x=>x.key===key);if(item){this.setData({guestDetail:item});this.getTabBar()?.setData({hidden:true});}},
closePreview(){this.setData({guestDetail:null});this.getTabBar()?.setData({hidden:false});},
loginPreview(){const detail=this.data.guestDetail;this.closePreview();const target=detail?'/pages/admin/index?view='+detail.target:'';wx.navigateTo({url:'/pages/account/index?view=login'+(target?'&content='+encodeURIComponent(detail.label)+'&return='+encodeURIComponent(target):'')});},
noop(){},
goHome(){wx.switchTab({url:'/pages/'+X.home()+'/index'});},
identity(){wx.navigateTo({url:'/pages/account/index?view='+(this.data.loggedIn?'identity':'login')});}
});
