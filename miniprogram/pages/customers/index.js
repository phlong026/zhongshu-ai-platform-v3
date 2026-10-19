const B=require('../../utils/business'),Access=require('../../utils/access'),Job=require('../../utils/organization'),Workspace=require('../../utils/sales-workspace');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{keyword:'',filter:'全部',filters:['全部','待跟进','跟进中','已到店'],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10},
 onShow(){const actor=Access.context();if(!actor.loggedIn){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.redirectTo({url:'/pages/account/index?view=login'});}if(!actor.active||!Job.has(actor.role,'sales')){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.switchTab({url:'/pages/'+Access.home()+'/index'});}const rows=Workspace.customerRows();this.setData({customers:rows,counts:Workspace.counts(rows),isLead:actor.role==='sales-lead',isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo});this.filterCustomers();},
 filterCustomers(){const keyword=this.data.keyword.trim(),rows=this.data.customers.filter(c=>(this.data.filter==='全部'||c.status===this.data.filter)&&c.searchText.includes(keyword));this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),hasMore:rows.length>this.data.pageSize});},
 search(e){this.setData({keyword:e.detail.value});this.filterCustomers();},
 setFilter(e){this.setData({filter:e.currentTarget.dataset.value,pageSize:10});this.filterCustomers();},
 more(){this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();},
 register(){if(Access.context().active&&Job.has(Access.context().role,'sales'))wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;if(!this.data.customers.some(c=>c.id===id)||!Access.customer(B.customer(id)))return;wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>c.id===e.currentTarget.dataset.id);if(row&&B.customer(row.id))wx.navigateTo({url:row.url});},
 manage(e){if(this.data.isLead&&['allocation','transfer'].includes(e.currentTarget.dataset.view))wx.navigateTo({url:'/pages/workflow/index?view='+e.currentTarget.dataset.view});}
});
