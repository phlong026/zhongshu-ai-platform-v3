require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
const { createDashboard } = require('../../utils/dashboard');
const customers = [];
Page(createDashboard(1, {
data: { panel: null, keyword: '', customers, filteredCustomers: customers,
creation: [{ title: '爆款二创', icon: 'fire', action: 'remix' }, { title: '数字人口播', icon: 'person-filled', action: 'digital' }, { title: 'AI视频', icon: 'video-filled', action: 'video' }]
},
search(e) {
const keyword = e.detail.value.trim();
this.setData({ keyword, filteredCustomers: this.data.customers.filter(c => (c.name + c.desc).includes(keyword)) });
}
}));
