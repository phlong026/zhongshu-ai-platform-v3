const Job=require('../../utils/organization');
const G=require('../../utils/growth'),X=require('../../utils/access'),S=require('../../utils/studio');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：降级文案）
const contentApi=require('../../api/franchise/content');
const crm=require('../../api/franchise/crm');
const http=require('../../api/http');
const {makeFormHandlers}=require('../../utils/view-container');
const displayContent=content=>content?{...content,status:content.status==='演示已发布'?'已登记发布':content.status}:content;
Page({...makeFormHandlers(),data:{view:'home',title:'内容与客资',contents:[],leads:[],form:{source:'直接来访'},error:'',primary:'',id:'',staff:[],published:[]},
onLoad(o){this.id=o.id||'';this.setData({view:['content','capture','lead'].includes(o.view)?o.view:'home'});this.refresh();if(this.data.view==='capture'&&o.contentId){const index=this.data.published.findIndex(c=>c.id===o.contentId);if(index>=0)this.setData({'form.contentId':o.contentId,publishedIndex:index+1});}},onShow(){this.refresh();},
refresh(){const a=X.context();if(!a.active||a.orgType!=='store'||!['sales','sales-lead','boss','owner'].includes(a.role))return wx.redirectTo({url:'/pages/account/index?view=access&content='+encodeURIComponent('加盟商内容与线索')});const s=G.load(),v=this.data.view,c=s.contents.find(c=>c.id===this.id),l=s.leads.find(l=>l.id===this.id);let primary='',action='';if(v==='content'&&c){if(Job.has(a.role,'sales')&&c.authorId===a.staffId&&c.status!=='演示已发布'){primary='前往发布设置';action='publish';}}if(v==='capture'){if(!Job.has(a.role,'sales'))return wx.redirectTo({url:'/pages/account/index?view=access'});primary='登记客资';action='capture';}if(v==='lead'&&l){if(l.status==='跟进中'&&l.ownerId===a.staffId){primary='保存跟进';action='follow';}}
const existing=l&&G.existingCustomer(l);this.setData({existingCustomer:existing?{id:existing.id,name:existing.name,owner:existing.owner}:null,title:v==='content'?'内容与发布':v==='capture'?'登记客资':v==='lead'?'客资跟进':'内容与客资',actorRole:a.role,canHandleLead:!!l&&Job.has(a.role,'sales')&&l.ownerId===a.staffId,canEditContent:!!c&&Job.has(a.role,'sales')&&c.authorId===a.staffId,canCapture:Job.has(a.role,'sales'),canManage:['sales-lead','boss','owner'].includes(a.role),contents:s.contents.map(displayContent),leads:s.leads,content:displayContent(c),lead:l||null,missing:['content','lead'].includes(v)&&!(c||l),published:s.contents.filter(c=>c.status==='演示已发布'),publishedNames:['直接来访／转介绍',...s.contents.filter(c=>c.status==='演示已发布').map(c=>c.pack.title)],primary,action});if(http.token())this.syncGrowth();},
/** 在线获客总览：growth.summary 出 {publications,leads,converted,perPublication}；失败静默保留本地展示 */
syncGrowth(){
contentApi.growth.summary().then(summary=>{
if(!summary||typeof summary!=='object')return;
const per=Array.isArray(summary.perPublication)?summary.perPublication:[];
const patch={growthStats:{publications:summary.publications||0,leads:summary.leads||0,converted:summary.converted||0}};
// 登记客资来源选择改为平台发布维度；capture 页进行中不换列表（防选择索引错位）
if(this.data.view!=='capture'){
const rows=per.filter(row=>row&&row.publicationId!=null).map(row=>({id:String(row.publicationId),publicationId:String(row.publicationId),title:String(row.title||row.workTitle||('发布作品 '+row.publicationId)),leads:row.leads||0,converted:row.converted||0}));
if(rows.length){patch.published=rows;patch.publishedNames=['直接来访／转介绍',...rows.map(r=>r.title)];}
}
this.setData(patch);
}).catch(()=>{});
},
open(e){wx.navigateTo({url:'/pages/growth/index?view='+e.currentTarget.dataset.view+(e.currentTarget.dataset.id?'&id='+encodeURIComponent(e.currentTarget.dataset.id):'')});},
registerConsultation(){const c=this.data.content;if(this.data.canCapture&&c?.publishedUrl)wx.navigateTo({url:'/pages/growth/index?view=capture&contentId='+encodeURIComponent(c.id)});},
pick(e){const key=e.currentTarget.dataset.key,n=Number(e.detail.value);if(key==='contentId')this.setData({'form.contentId':n?this.data.published[n-1]?.id:'',publishedIndex:n});if(key==='reviewResult')this.setData({'form.reviewResult':['通过','退回修改'][n]});},
perform(e){const action=e?.currentTarget?.dataset?.action||this.data.action,f=this.data.form;if(action==='capture'&&http.token())return this.captureOnline(f);try{if(action==='capture'){const res=G.capture(f);wx.showToast({title:res.customer.linked?'已关联原客户，来源已保留':'客资已登记为本人客户',icon:'none'});this.setData({view:'home',form:{source:'直接来访'}});return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(res.customer.id)});}else if(action==='publish'){return this.editContent();}else if(action==='link-existing'){const c=G.linkExisting(this.id,this.data.lead.revision);return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});}else if(action==='convert'){const c=G.convert(this.id,f,this.data.lead.revision);return wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});}else{G.change(this.id,action,f,this.data.lead.revision);}this.setData({'form.note':'',error:''});this.refresh();}catch(e){if(action==='convert'&&/已有该客户/.test(e.message))this.setData({requestExistingLink:true});this.fail(e.message);}},
/** 在线登记客资：走 /franchise/leads capture；选中的来源为平台发布时 source=内容发布:{publicationId} */
captureOnline(f){
const name=String(f.name||'').trim(),phone=String(f.phone||'').trim(),need=String(f.need||'').trim();
if(!name)return this.fail('请填写称呼、有效手机号及咨询需求');
if(!/^1\d{10}$/.test(phone))return this.fail('请填写称呼、有效手机号及咨询需求');
if(!need)return this.fail('请填写称呼、有效手机号及咨询需求');
const selected=(this.data.published||[]).find(row=>row.id===f.contentId);
const source=selected&&selected.publicationId?'内容发布:'+selected.publicationId:(String(f.source||'').trim()||'直接来访');
if(!this.__captureKey)this.__captureKey='crm-capture-'+Date.now()+'-'+Math.floor(Math.random()*1e8); // 幂等键失败保留重试复用
crm.leads.capture({name,phone,need,source},this.__captureKey).then(res=>{
this.__captureKey='';
const result=res&&res.result;
wx.showToast({title:result==='LINKED'?'已关联原客户，来源已保留':result==='LINK_REQUIRED'?'已登记，待负责人关联原客户':'客资已登记',icon:'none'});
this.setData({view:'home',form:{source:'直接来访'}});
this.refresh();
}).catch(error=>{this.fail(P.degradeMessage(error));});
},
editContent(){const c=this.data.content;if(c)wx.navigateTo({url:'/pages/studio/index?view=publish&work='+encodeURIComponent(c.workId)+'&content='+encodeURIComponent(c.id)});},
goCustomer(){if(this.data.lead?.customerId)wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(this.data.lead.customerId)});},home(){wx.switchTab({url:'/pages/business/index'});},fail(error){this.setData({error});wx.showToast({title:error,icon:'none'});}
});

