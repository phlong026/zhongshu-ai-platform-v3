require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
const { createDashboard } = require('../../utils/dashboard');
const customers = [];
const definition=createDashboard(1, {
data: { panel: null, keyword: '', customers, filteredCustomers: customers,
creation: [{ title: '爆款二创', icon: 'fire', action: 'remix' }, { title: '数字人口播', icon: 'person-filled', action: 'digital' }, { title: 'AI视频', icon: 'video-filled', action: 'video' }]
},
search(e) {
const keyword = e.detail.value.trim();
this.setData({keyword});this.filterCustomers();
}
});
const dashboardShow=definition.onShow;
definition.onShow=function(){if(require('../../utils/access').context().role==='hq-design-admin'){wx.redirectTo({url:'/pages/workflow/commission/index?view=admin'});return;}const keyword=this.data.keyword||'';dashboardShow.call(this);this.setData({...require('../../utils/sales-workspace').home(),keyword});this.filterCustomers();if(this.data.isSalesHome)this.refreshTodos();};
// Home to-do board: tab and expand state live on the page, rows come from the shared projection.
definition.refreshTodos=function(tab=this.data.todoTab,expanded=!!this.data.todoExpanded){this.setData(require('../../utils/sales-workspace').todoBoard(this.data.flowPending,this.data.flowWaiting,this.data.staffTodos||[],tab,expanded));};
definition.selectTodoTab=function(e){this.refreshTodos(e.currentTarget.dataset.tab,false);};
definition.toggleTodos=function(){this.refreshTodos(this.data.todoTab,!this.data.todoExpanded);};
definition.filterCustomers=function(){const keyword=this.data.keyword||'';this.setData({filteredCustomers:(this.data.customers||[]).filter(c=>(c.name+c.desc).includes(keyword))});};
// AI design and quick budget start in the explore page, like the 方案 tab; a customer is chosen when saving.
definition.openSalesShortcut=function(e){const url=e.currentTarget.dataset.url;if(url)return require('../../utils/page-navigation').open(url);this.openAction(e);};
definition.openSalesMetric=function(e){const focus=e.currentTarget.dataset.focus;if(focus==='commission')return require('../../utils/page-navigation').open('/pages/account/index?view=personal-performance');if(['all','due','appointments'].includes(focus))require('../../utils/page-navigation').open('/pages/customers/index'+(focus==='all'?'':'?focus='+focus));};
definition.continueSalesCustomer=function(){if(this.data.recentCustomer)require('../../utils/page-navigation').open(this.data.recentCustomer.hasDesign?require('../../utils/sales-flow').url(this.data.recentCustomer.id):'/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.recentCustomer.id));};
definition.continueSalesCreation=function(){require('../../utils/page-navigation').open('/pages/creation/index');};
definition.openFlowTask=function(e){const t=[...(this.data.flowPending||[]),...(this.data.flowWaiting||[])].find(t=>t.id===e.currentTarget.dataset.id);if(t)require('../../utils/page-navigation').open(t.url);};
Page(definition);
