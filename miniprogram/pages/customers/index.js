const B=require('../../utils/business'),Access=require('../../utils/access'),Job=require('../../utils/organization'),Workspace=require('../../utils/sales-workspace');
const TABS=[['due','今日待跟进'],['appointments','预约到店'],['all','全部'],['visited','已到店']];
const EMPTY={due:'今天的跟进都办完了',appointments:'暂无预约到店的客户',visited:'暂无已到店的客户',all:'当前没有可见客户，可先登记客户。'};
const tabRows=(rows,tab)=>tab==='visited'?rows.filter(r=>r.status==='已到店'):Workspace.focusRows(rows,tab==='all'?'':tab);
Page({
 data:{keyword:'',tab:'all',tabs:[],customers:[],filteredCustomers:[],visibleCustomers:[],pageSize:10},
 onLoad(o){if(['due','appointments'].includes(o.focus))this.setData({tab:o.focus});},
 onShow(){const actor=Access.context();if(!actor.loggedIn){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.redirectTo({url:'/pages/account/index?view=login'});}if(!actor.active||!Job.has(actor.role,'sales')){this.setData({customers:[],filteredCustomers:[],visibleCustomers:[]});return wx.switchTab({url:'/pages/'+Access.home()+'/index'});}const rows=Workspace.customerRows();this.setData({customers:rows,counts:Workspace.counts(rows),tabs:TABS.map(([key,label])=>({key,label,count:tabRows(rows,key).length,dot:key==='due'&&rows.some(r=>r.rank===0)})),isLead:actor.role==='sales-lead',isDemo:!!wx.getStorageSync('zhongshu-account-v1')?.session?.demo});this.filterCustomers();},
 filterCustomers(){const keyword=this.data.keyword.trim(),rows=Workspace.sortRows(tabRows(this.data.customers,this.data.tab).filter(c=>c.searchText.includes(keyword)));this.setData({filteredCustomers:rows,visibleCustomers:rows.slice(0,this.data.pageSize),hasMore:rows.length>this.data.pageSize,emptyText:keyword?'暂无符合条件的客户':EMPTY[this.data.tab]});},
 selectTab(e){const tab=e.currentTarget.dataset.tab;if(!TABS.some(([key])=>key===tab)||tab===this.data.tab)return;this.setData({tab,keyword:'',pageSize:10});this.filterCustomers();},
 search(e){this.setData({keyword:e.detail.value});this.filterCustomers();},
 more(){this.setData({pageSize:this.data.pageSize+10});this.filterCustomers();},
 register(){if(Access.context().active&&Job.has(Access.context().role,'sales'))wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;if(!this.data.customers.some(c=>c.id===id)||!Access.customer(B.customer(id)))return;wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});},
 act(e){const row=this.data.customers.find(c=>c.id===e.currentTarget.dataset.id);if(row&&B.customer(row.id))wx.navigateTo({url:row.url});},
 call(e){const phone=B.customer(e.currentTarget.dataset.id)?.phone||'';if(!/^1\d{10}$/.test(phone))return wx.showToast({title:'该号码不可拨打',icon:'none'});wx.makePhoneCall({phoneNumber:phone,fail(){}});},
});
