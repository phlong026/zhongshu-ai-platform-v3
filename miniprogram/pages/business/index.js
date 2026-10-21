require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
const { createDashboard } = require('../../utils/dashboard');
const customers = [];
const definition=createDashboard(1, {
data: { panel: null, keyword: '', customers, filteredCustomers: customers,
creation: [{ title: '爆款二创', icon: 'fire', action: 'remix' }, { title: '数字人口播', icon: 'person-filled', action: 'digital' }, { title: 'AI视频', icon: 'video-filled', action: 'video' }]
},
search(e) {
const keyword = e.detail.value.trim();
this.setData({keyword});this.filterDesignTasks();
}
});
const dashboardShow=definition.onShow;
definition.onShow=function(){if(require('../../utils/access').context().role==='hq-design-admin'){wx.redirectTo({url:'/pages/workflow/commission/index?view=admin'});return;}const keyword=this.data.keyword||'';dashboardShow.call(this);this.setData({...require('../../utils/sales-workspace').home(),...require('../../utils/design-desk').home(),keyword});this.filterDesignTasks();};
definition.openDesignProjects=function(e){require('../../utils/page-navigation').open('/pages/workflow/design-desk/index?tab=projects&filter='+(e.currentTarget.dataset.filter||'all'));};
definition.openDesignDrawings=function(){require('../../utils/page-navigation').open('/pages/workflow/design-desk/index?tab=drawings');};
definition.continueDesign=function(e){const row=require('../../utils/design-desk').projects().find(p=>p.id===e.currentTarget.dataset.id);if(row)require('../../utils/page-navigation').open(e.currentTarget.dataset.detail?row.detailUrl:row.url);};
definition.filterDesignTasks=function(){const keyword=this.data.keyword||'',filter=this.data.designTaskFilter||'all';this.setData({filteredCustomers:(this.data.customers||[]).filter(c=>(c.name+c.desc).includes(keyword)&&(!this.data.isDesign||filter==='all'||c.kind===filter))});};
definition.filterDesign=function(e){this.setData({designTaskFilter:e.currentTarget.dataset.filter});this.filterDesignTasks();};
definition.openSalesShortcut=function(e){const action=e.currentTarget.dataset.action;if(action==='salesDesign')return require('../../utils/page-navigation').open('/pages/design/index');this.openAction(e);};
definition.openSalesMetric=function(e){const focus=e.currentTarget.dataset.focus;if(['all','due','appointments'].includes(focus))require('../../utils/page-navigation').open('/pages/customers/index'+(focus==='all'?'':'?focus='+focus));};
definition.continueSalesCustomer=function(){if(this.data.recentCustomer)require('../../utils/page-navigation').open('/pages/workflow/index?view='+(this.data.recentCustomer.hasDesign?'delivery':'customer')+'&id='+encodeURIComponent(this.data.recentCustomer.id));};
definition.continueSalesCreation=function(){require('../../utils/page-navigation').open('/pages/creation/index');};
definition.openFlowTask=function(e){const t=[...(this.data.flowPending||[]),...(this.data.flowWaiting||[])].find(t=>t.id===e.currentTarget.dataset.id);if(t)require('../../utils/page-navigation').open(t.url);};
Page(definition);
