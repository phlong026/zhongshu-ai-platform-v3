const panels = {
plan: { title: '户型设计', kind: 'detail', image: '/assets/floor-plan.png', description: '平面 → 立面 → 保存方案', fields: [{ key: 'width', label: '宅基地面宽（米）', placeholder: '12', type: 'digit' }, { key: 'depth', label: '宅基地进深（米）', placeholder: '15', type: 'digit' }, { key: 'need', label: '家庭需求', placeholder: '卧室数量、层数、老人房等' }], confirm: '保存设计需求' },
partnership: { title: '加盟咨询', kind: 'form', description: '与众墅之家一起，共建更好的乡墅事业。', fields: [{ key: 'name', label: '您的称呼', placeholder: '请输入姓名' }, { key: 'phone', label: '联系电话', placeholder: '请输入联系电话', type: 'number' }, { key: 'village', label: '意向区域', placeholder: '请输入意向加盟区域' }], confirm: '保存咨询意向' }
};
function createDashboard(index, extra) {
return Object.assign({
data: { panel: null },
onShow() {
const nav=require('./workbench-nav')();this.setData({...nav,panel:null});
if(index===0)this.setData({tools:[{title:'业务工作台',tab:'business',icon:'home-video-icon'},{title:'工程工作台',tab:'engineering',icon:'home-engineer-icon'},{title:'管理工作台',tab:'management',icon:'home-chart-icon'}]});
if (typeof this.getTabBar === 'function' && this.getTabBar()) {this.getTabBar().refresh?.();this.getTabBar().setData({ hidden: !!this.data.panel });}
},
goAdmin(e){const d=e.currentTarget.dataset;wx.navigateTo({url:'/pages/'+(d.area==='workflow'?'workflow':'admin')+'/index?view='+d.view+'&id='+encodeURIComponent(d.id)+(d.record?'&record='+encodeURIComponent(d.record):'')});},
openAction(e) {
const key = e.currentTarget.dataset.action;
const businessRoutes = { customer: 'customers&modal=register', customers: 'customers', customerDetail: 'customer', autonomous: 'ai-config', plan: 'ai-config', planSearch: 'search', facade: 'ai-facade', budget: 'budget' };
const businessView = index === 1 ? businessRoutes[key] : index === 2 && key === 'projects' ? 'customers&destination=hub' : '';
if (businessView) {
if(!require('../api/http').token()){wx.navigateTo({url:'/pages/account/index?view=login'});return;}let contextId = '';
const todo = this.data.customers && this.data.customers.find(item => String(item.id) === String(e.currentTarget.dataset.customer));
if(todo)contextId=todo.id;
const chooseCustomer = index === 1 && !contextId && ['search','ai-config','ai-facade','budget'].includes(businessView);
const destination=contextId&&key==='customerDetail'?(todo.view||'customer'):chooseCustomer?'customers&destination='+businessView:businessView;
wx.navigateTo({ url: '/pages/workflow/index?view=' + destination + (contextId ? '&id=' + encodeURIComponent(contextId) : '') }); return;
}
if(key==='growth'){wx.navigateTo({url:'/pages/growth/index'});return;}if(key==='login'){wx.navigateTo({url:'/pages/account/index?view=login'});return;}const creationRoutes = { serviceOrders:'/pages/account/index?view=services', remix: '/pages/creation/index', digital: '/pages/studio/index?view=digital', video: '/pages/ai-video/index', works: '/pages/studio/index?view=works' };
if (creationRoutes[key]) { require('./page-navigation').open(creationRoutes[key]); return; }
const panel = panels[key];
if (panel) {
this.setData({ panel: Object.assign({ key }, panel) });
if (this.getTabBar()) this.getTabBar().setData({ hidden: true });
}
},
closePanel() {
this.setData({ panel: null });
if (this.getTabBar()) this.getTabBar().setData({ hidden: false });
},
submitPanel(e) {
const { panel } = this.data;
if (!panel) return;
if (panel.fields) {
const values = e.detail.values || {};
const first = panel.fields[0];
if (!String(values[first.key] || '').trim()) {
wx.showToast({ title: '请填写' + first.label, icon: 'none' });
return;
}
if (values.phone && !/^1\d{10}$/.test(values.phone)) {
wx.showToast({ title: '请输入正确的手机号', icon: 'none' });
return;
}
wx.showToast({ title: '客户登记请登录后在客户页办理', icon: 'none' });
return;
wx.showToast({ title: '已保存', icon: 'success' });
} else if (panel.key === 'continueCourse') {
this.setData({ learned: Math.min(6, (this.data.learned || 2) + 1) });
wx.showToast({ title: '学习进度已更新', icon: 'success' });
} else if (panel.key === 'facade') {
wx.showToast({ title: '方案保存请登录后使用设计工作台', icon: 'none' });
}
this.closePanel();
},
goTab(e) { wx.switchTab({ url: '/pages/' + e.currentTarget.dataset.tab + '/index' }); }
}, extra || {});
}
module.exports = { createDashboard };
