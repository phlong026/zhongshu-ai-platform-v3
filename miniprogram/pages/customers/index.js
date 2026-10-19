const B=require('../../utils/business'),Access=require('../../utils/access'),Job=require('../../utils/organization'),Workspace=require('../../utils/sales-workspace');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{keyword:'',filter:'全部',focus:'',focusLabel:'',filters:['全部','待跟进','跟进中','已到店'],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10},
 onLoad(o){const focus=['due','appointments'].includes(o.focus)?o.focus:'';this.setData({focus,focusLabel:focus==='due'?'今日待跟进':focus==='appointments'?'预约到店':''});},
 onShow(){const actor=Access.context();if(!actor.loggedIn){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.redirectTo({url:'/pages/account/index?view=login'});}if(!actor.active||!Job.has(actor.role,'sales')){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.switchTab({url:'/pages/'+Access.home()+'/index'});}const rows=Workspace.customerRows();this.setData({customers:rows,counts:Workspace.counts(rows),dueCount:Workspace.focusRows(rows,'due').length,appointmentCount:Workspace.focusRows(rows,'appointments').length,isLead:actor.role==='sales-lead',isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo});this.filterCustomers();},
 filterCustomers(){const keyword=this.data.keyword.trim(),rows=Workspace.focusRows(this.data.customers,this.data.focus).filter(c=>(this.data.filter==='全部'||c.status===this.data.filter)&&c.searchText.includes(keyword));this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),hasMore:rows.length>this.data.pageSize});},
 clearFocus(){this.setData({focus:'',focusLabel:'',filter:'全部',keyword:'',pageSize:10});this.filterCustomers();},
 selectFocus(e){const focus=e.currentTarget.dataset.focus;if(!['due','appointments'].includes(focus))return;if(this.data.focus===focus)return this.clearFocus();this.setData({focus,focusLabel:focus==='due'?'今日待跟进':'预约到店',filter:'全部',keyword:'',pageSize:10});this.filterCustomers();},
 search(e){this.setData({keyword:e.detail.value});this.filterCustomers();},
 setFilter(e){this.setData({filter:e.currentTarget.dataset.value,pageSize:10});this.filterCustomers();},
 more(){this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();},
 register(){if(Access.context().active&&Job.has(Access.context().role,'sales'))wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;if(!this.data.customers.some(c=>c.id===id)||!Access.customer(B.customer(id)))return;wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>c.id===e.currentTarget.dataset.id);if(row&&B.customer(row.id))wx.navigateTo({url:row.url});},
 manage(e){if(!Access.context().active||Access.context().role!=='sales-lead')return;const view=e.currentTarget.dataset.view;if(view==='business-team')return wx.navigateTo({url:'/pages/admin/index?view=business-team'});if(['allocation','transfer'].includes(view))wx.navigateTo({url:'/pages/workflow/index?view='+view});},
 demo(){if(Access.context().active&&Job.has(Access.context().role,'sales')&&wx.getStorageSync('zhongshu-account-v1')?.session?.demo)wx.navigateTo({url:'/pages/demo/index'});}
});
