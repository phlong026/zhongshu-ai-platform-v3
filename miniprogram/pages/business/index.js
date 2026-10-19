require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
const { createDashboard } = require('../../utils/dashboard');
const customers = [];
const definition=createDashboard(1, {
data: { panel: null, keyword: '', customers, filteredCustomers: customers,
creation: [{ title: '爆款二创', icon: 'fire', action: 'remix' }, { title: '数字人口播', icon: 'person-filled', action: 'digital' }, { title: 'AI视频', icon: 'video-filled', action: 'video' }]
},
search(e) {
const keyword = e.detail.value.trim();
this.setData({ keyword, filteredCustomers: this.data.customers.filter(c => (c.name + c.desc).includes(keyword)) });
}
});
const dashboardShow=definition.onShow;
definition.onShow=function(){dashboardShow.call(this);this.setData(require('../../utils/sales-workspace').home());};
definition.openSalesTask=function(e){const row=this.data.salesTodos.find(r=>r.id===e.currentTarget.dataset.id);if(row)wx.navigateTo({url:row.url});};
definition.openSalesShortcut=function(e){const action=e.currentTarget.dataset.action;if(action==='salesDesign')return wx.navigateTo({url:'/pages/design/index'});this.openAction(e);};
definition.allSalesCustomers=function(){wx.navigateTo({url:'/pages/customers/index'});};
definition.continueSalesCustomer=function(){if(this.data.recentCustomer)wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.recentCustomer.id)});};
definition.continueSalesCreation=function(){wx.navigateTo({url:'/pages/creation/index'});};
Page(definition);
