const B=require('../../utils/business'),Access=require('../../utils/access'),Job=require('../../utils/organization');
require('../../utils/load-local-fonts')(require('../../utils/dashboard-font-sources'),'dashboard');
Page({
 data:{keyword:'',filter:'全部',filters:['全部','待跟进','跟进中','已到店'],customers:[],filteredCustomers:[]},
 onShow(){const actor=Access.context();if(!actor.loggedIn){this.setData({customers:[],filteredCustomers:[]});return wx.redirectTo({url:'/pages/account/index?view=login'});}if(!actor.active||!Job.has(actor.role,'sales')){this.setData({customers:[],filteredCustomers:[]});return wx.switchTab({url:'/pages/'+Access.home()+'/index'});}this.setData({customers:B.load().customers.filter(c=>Access.customer(c))});this.filterCustomers();},
 filterCustomers(){const keyword=this.data.keyword.trim();this.setData({filteredCustomers:this.data.customers.filter(c=>(this.data.filter==='全部'||c.status===this.data.filter)&&[c.name,c.phone,c.wechat,c.city].filter(Boolean).join(' ').includes(keyword))});},
 search(e){this.setData({keyword:e.detail.value});this.filterCustomers();},
 setFilter(e){this.setData({filter:e.currentTarget.dataset.value});this.filterCustomers();},
 register(){if(Access.context().active&&Job.has(Access.context().role,'sales'))wx.navigateTo({url:'/pages/workflow/index?view=customers&modal=register'});},
 open(e){const id=e.currentTarget.dataset.id;if(!this.data.customers.some(c=>c.id===id)||!Access.customer(B.customer(id)))return;wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(id)});}
});
