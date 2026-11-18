const Job=require('../../utils/organization');
const G=require('../../utils/growth');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：降级文案）
const contentApi=require('../../api/franchise/content');
const crm=require('../../api/franchise/crm');
const http=require('../../api/http');
const {makeFormHandlers}=require('../../utils/view-container');
const DIRECT_SOURCE='直接来访／转介绍';
const currentPost=()=>{try{return http.token()?require('../../api/auth').currentApiPost():null;}catch(e){return null;}};
const displayContent=content=>content?{...content,status:content.status==='演示已发布'?'已登记发布':content.status}:content;
const totalOf=value=>{
if(typeof value==='number'&&Number.isFinite(value))return value;
if(!value||typeof value!=='object'||Array.isArray(value))return 0;
return Object.values(value).reduce((total,count)=>total+(Number.isFinite(Number(count))?Number(count):0),0);
};
Page({...makeFormHandlers(),data:{view:'home',title:'内容与客资',contents:[],leads:[],form:{source:'直接来访'},error:'',primary:'',id:'',staff:[],published:[],pendingLeads:[]},
onLoad(o){
this.__captureUnloaded=false;this.id=o.id||'';
const view=['content','capture','lead'].includes(o.view)?o.view:'home';
this.__publicationEntry=view==='capture'?String(o.publicationId||o.contentId||''):'';
this.setData({view});
},onShow(){this.refresh();},
onUnload(){this.__captureUnloaded=true;this.__captureSeq=(this.__captureSeq||0)+1;},
refresh(){const post=currentPost();if(!(post&&post.orgType==='store'&&['sales','sales-lead','boss','owner'].includes(post.roleCode)))return wx.redirectTo({url:'/pages/account/index?view=access&content='+encodeURIComponent('加盟商内容与线索')});const a={active:true,orgType:'store',role:post.roleCode,staffId:'',name:post.name||''};const s=G.load(),v=this.data.view,c=s.contents.find(c=>c.id===this.id),l=s.leads.find(l=>l.id===this.id);let primary='',action='';if(v==='content'&&c){if(Job.has(a.role,'sales')&&c.authorId===a.staffId&&c.status!=='演示已发布'){primary='前往发布设置';action='publish';}}if(v==='capture'){if(!Job.has(a.role,'sales'))return wx.redirectTo({url:'/pages/account/index?view=access'});primary='登记客资';action='capture';}if(v==='lead'&&l){if(l.status==='跟进中'&&l.ownerId===a.staffId){primary='保存跟进';action='follow';}}
const identity=http.sessionGeneration()+':'+post.postId;
if(this.__growthIdentity!==identity){
  this.__captureSeq=(this.__captureSeq||0)+1;
  this.__captureKey='';this.__linkedCustomerRetry=null;
  const publicationId=this.__growthIdentity?'':this.__publicationEntry;
  this.__growthIdentity=identity;this.__publicationEntry='';
  this.setData({form:{source:'直接来访',contentId:publicationId},published:[],publishedNames:[DIRECT_SOURCE],
    publishedIndex:0,publicationError:'',pendingLeads:[],growthStats:null,error:''});
}
const existing=l&&G.existingCustomer(l);this.setData({existingCustomer:existing?{id:existing.id,name:existing.name,owner:existing.owner}:null,title:v==='content'?'内容与发布':v==='capture'?'登记客资':v==='lead'?'客资跟进':'内容与客资',actorRole:a.role,canHandleLead:!!l&&Job.has(a.role,'sales')&&l.ownerId===a.staffId,canEditContent:!!c&&Job.has(a.role,'sales')&&c.authorId===a.staffId,canCapture:Job.has(a.role,'sales'),canManage:['sales-lead','boss','owner'].includes(a.role),contents:s.contents.map(displayContent),leads:s.leads,content:displayContent(c)||null,lead:l||null,missing:['content','lead'].includes(v)&&!(c||l),primary,action});if(http.token()){this.syncGrowth();if(this.data.canManage)this.loadPendingLeads();}},
syncGrowth(){
const request=http.requestGuard('growth-summary'),identity=this.__growthIdentity;
const current=()=>!this.__captureUnloaded&&request()&&identity===this.__growthIdentity;
return contentApi.growth.summary().then(summary=>{
if(!current())return;
if(!summary||!Array.isArray(summary.perPublication))throw Error('发布作品读取失败');
const rows=summary.perPublication.filter(row=>row&&/^\d+$/.test(String(row.publicationId??row.id))).map(row=>{
  const publicationId=String(row.publicationId??row.id);
  return {id:publicationId,publicationId,title:String(row.title||row.workTitle||('发布作品 '+publicationId)),
    leads:totalOf(row.leads),converted:totalOf(row.converted)};
});
const selected=this.data.form.contentId;
const index=selected?rows.findIndex(row=>row.id===selected)+1:0;
this.setData({growthStats:{publications:totalOf(summary.publications),leads:totalOf(summary.leads),converted:totalOf(summary.converted)},
  published:rows,publishedNames:[DIRECT_SOURCE,...rows.map(row=>row.title)],publishedIndex:index,
  publicationError:selected&&!index?'原发布作品当前不可用，请重新选择客资来源。':''});
}).catch(()=>{
  if(current()&&this.data.view==='capture')this.setData({publicationError:'发布作品暂时无法读取，请重试加载。'});
});
},
open(e){wx.navigateTo({url:'/pages/growth/index?view='+e.currentTarget.dataset.view+(e.currentTarget.dataset.id?'&id='+encodeURIComponent(e.currentTarget.dataset.id):'')});},
registerConsultation(){const c=this.data.content;if(this.data.canCapture&&c?.publishedUrl)wx.navigateTo({url:'/pages/growth/index?view=capture&contentId='+encodeURIComponent(c.id)});},
chooseDirectSource(){this.setData({'form.contentId':'',publishedIndex:0,publicationError:''});},
pick(e){const key=e.currentTarget.dataset.key,n=Number(e.detail.value);if(key==='contentId')this.setData({'form.contentId':n?this.data.published[n-1]?.id:'',publishedIndex:n,publicationError:''});if(key==='reviewResult')this.setData({'form.reviewResult':['通过','退回修改'][n]});},
perform(e){const action=e?.currentTarget?.dataset?.action||this.data.action,f=this.data.form;if(action==='capture'&&http.token())return this.captureOnline(f);try{if(action==='capture'){const res=G.capture(f);wx.showToast({title:res.customer.linked?'已关联原客户，来源已保留':'客资已登记为本人客户',icon:'none'});this.setData({view:'home',form:{source:'直接来访'}});return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(res.customer.id)});}else if(action==='publish'){return this.editContent();}else if(action==='link-existing'){const c=G.linkExisting(this.id,this.data.lead.revision);return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});}else if(action==='convert'){const c=G.convert(this.id,f,this.data.lead.revision);return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});}else{G.change(this.id,action,f,this.data.lead.revision);}this.setData({'form.note':'',error:''});this.refresh();}catch(e){if(action==='convert'&&/已有该客户/.test(e.message))this.setData({requestExistingLink:true});this.fail(e.message);}},
/** 在线登记客资：走 /franchise/leads capture；选中的来源为平台发布时 source=内容发布:{publicationId} */
captureOnline(f){
if(this.__linkedCustomerRetry)return this.retryLinkedCustomer();
const name=String(f.name||'').trim(),phone=String(f.phone||'').trim(),need=String(f.need||'').trim();
if(!name)return this.fail('请填写称呼、有效手机号及咨询需求');
if(!/^1\d{10}$/.test(phone))return this.fail('请填写称呼、有效手机号及咨询需求');
if(!need)return this.fail('请填写称呼、有效手机号及咨询需求');
const context=this.captureContext();
if(!context || (this.__growthIdentity && this.__growthIdentity!==context.sessionGeneration+':'+context.postId))
  return this.fail('当前岗位已变化，请重新进入登记页');
const alive=()=>this.captureAlive(context);
const selected=(this.data.published||[]).find(row=>row.id===f.contentId);
if(f.contentId&&!selected)return this.fail('请先核对发布作品，或重新选择客资来源');
const source=selected&&selected.publicationId?'内容发布:'+selected.publicationId:(String(f.source||'').trim()||'直接来访');
if(!this.__captureKey)this.__captureKey='crm-capture-'+Date.now()+'-'+Math.floor(Math.random()*1e8); // 幂等键失败保留重试复用
crm.leads.capture({name,phone,need,source},this.__captureKey).then(res=>{
if(!alive())return;
const result=res&&res.result,customerId=res&&res.customerId!=null?String(res.customerId):'';
if(result==='LINK_REQUIRED'){ // M04-T03：待负责人关联原客户——保存 leadId 供负责人在线处置
  this.__captureKey='';
  this.setData({pendingLead:{leadId:res.leadId||res.id||'',name:name,phone:phone,revision:res.revision||1}});
  wx.showToast({title:'已登记，待负责人关联原客户',icon:'none'});
  this.setData({view:'home',form:{source:'直接来访'}});
  return this.refresh();
}
if(result==='CREATED'&&customerId){
  this.__captureKey='';
  wx.showToast({title:'客资已登记',icon:'none'});
  this.setData({view:'home',form:{source:'直接来访'}});
  return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(customerId)});
}
if(result==='LINKED'&&customerId){
  this.__linkedCustomerRetry={customerId,sessionGeneration:context.sessionGeneration,postId:context.postId};
  return this.inspectLinkedCustomer(context,customerId);
}
this.__captureKey='';
this.fail('客资状态已变化，请刷新');
}).catch(error=>{if(alive())this.fail(P.degradeMessage(error));});
},
captureContext(){
const post=currentPost(),postId=String(post&&post.postId||'');
if(!(postId&&post.orgType==='store'&&['sales','sales-lead','boss','owner'].includes(post.roleCode)))return null;
return{seq:this.__captureSeq=(this.__captureSeq||0)+1,sessionGeneration:http.sessionGeneration(),postId};
},
captureAlive(context){
const post=currentPost();
return!this.__captureUnloaded&&context.seq===this.__captureSeq&&context.sessionGeneration===http.sessionGeneration()&&!!post&&String(post.postId)===context.postId;
},
retryLinkedCustomer(){
const pending=this.__linkedCustomerRetry,post=currentPost();
if(!pending||!post||pending.sessionGeneration!==http.sessionGeneration()||pending.postId!==String(post.postId)){
  this.__linkedCustomerRetry=null;this.__captureKey='';this.setData({form:{source:'直接来访'}});
  return this.fail('工作身份已变化，请重新登记');
}
const context=this.captureContext();
if(!context)return this.fail('当前岗位已变化，请重新进入登记页');
return this.inspectLinkedCustomer(context,pending.customerId);
},
inspectLinkedCustomer(context,customerId){
const alive=()=>this.captureAlive(context);
return crm.customers.get(customerId).then(()=>{
  if(!alive())return;
  this.__linkedCustomerRetry=null;this.__captureKey='';
  wx.showToast({title:'已关联原客户，来源已保留',icon:'none'});
  this.setData({view:'home',form:{source:'直接来访'},error:''});
  wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(customerId)});
}).catch(error=>{
  if(!alive())return;
  if(error&&(error.code===1110000000||error.code===403)){
    this.__linkedCustomerRetry=null;this.__captureKey='';this.setData({view:'home',form:{source:'直接来访'}});
    return this.fail('该客户由同事负责，已关联到其名下，请联系负责人查看');
  }
  this.fail('客资已关联，查看客户失败，请点击登记客资重试查看');
});
},
editContent(){const c=this.data.content;if(c)wx.navigateTo({url:'/pages/studio/index?view=publish&work='+encodeURIComponent(c.workId)+'&content='+encodeURIComponent(c.id)});},
goCustomer(){if(this.data.lead?.customerId)wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.lead.customerId)});},
onlineLinkLead(e){ // M04-T03：负责人在线关联原客户——幂等键失败保留重试复用（Codex R1 P2-1）
  const idx=Number(e.currentTarget.dataset.idx);
  const item=(this.data.pendingLeads||[])[idx];
  if(!item)return this.fail('请选择待关联客资');
  if(!item.__linkKey)item.__linkKey='crm-link-'+item.leadId+'-'+Date.now();
  crm.leads.link(item.leadId,item.revision,item.__linkKey).then(()=>{
    wx.showToast({title:'已关联原客户',icon:'none'});
    this.loadPendingLeads(); // 回读状态
  }).catch(err=>this.fail(P.degradeMessage(err)));
},
onlineInvalidateLead(e){ // M04-T03：负责人在线作废——幂等键同上
  const idx=Number(e.currentTarget.dataset.idx);
  const item=(this.data.pendingLeads||[])[idx];
  if(!item)return this.fail('请选择待作废客资');
  if(!item.__invKey)item.__invKey='crm-inv-'+item.leadId+'-'+Date.now();
  crm.leads.invalidate(item.leadId,'负责人作废',item.revision,item.__invKey).then(()=>{
    wx.showToast({title:'已作废',icon:'none'});
    this.loadPendingLeads(); // 回读状态
  }).catch(err=>this.fail(P.degradeMessage(err)));
},
loadPendingLeads(){ // M04-T03：负责人查 pending
  if(!http.token())return;
  const current=http.requestGuard('growth-pending');
  crm.leads.pending().then(rows=>{
    if(!current()||this.__captureUnloaded)return;
    const items=(rows||[]).filter(r=>r.status==='LINK_REQUIRED').map(r=>({
      leadId:r.id,name:r.name,phone:r.phone,revision:r.revision||1,source:r.source||''
    }));
    this.setData({pendingLeads:items});
  }).catch(()=>{});
},
home(){wx.switchTab({url:'/pages/business/index'});},fail(error){this.setData({error});wx.showToast({title:error,icon:'none'});}
});

