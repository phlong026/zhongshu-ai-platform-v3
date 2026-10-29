const Job=require('./organization');
const X=require('./access');
const KEY=require('./storage-keys').growth,copy=x=>JSON.parse(JSON.stringify(x)),uid=t=>t+'-'+Date.now()+'-'+Math.random().toString(36).slice(2,8);
function all(){const s=wx.getStorageSync(KEY);return s&&s.version===1?copy(s):{version:1,contents:[],leads:[],events:[]};}
function actor(){const a=X.context();X.assert(a.active&&a.orgType==='store','本轮获客办理仅向加盟商授权岗位开放');return a;}
function team(id,a){return X.rawBook(a.companyId)?.staff.find(p=>p.id===id)?.teamIds?.sales||'sales';}
function manages(r,a){return r.companyId===a.companyId&&(X.isBoss(a)||a.role==='sales-lead'&&a.managedTeamIds.includes(r.teamId||team(r.authorId||r.registrarId,a)));}
function canContent(r,a){return r.companyId===a.companyId&&(Job.has(a.role,'sales')&&r.authorId===a.staffId||manages(r,a));}
function canLead(r,a){return r.companyId===a.companyId&&(Job.has(a.role,'sales')&&r.ownerId===a.staffId||manages(r,a));}
function load(){const a=X.context();if(!a.active||a.orgType!=='store'||!['sales','sales-lead','boss','owner'].includes(a.role))return {contents:[],leads:[]};const s=all();return {contents:s.contents.filter(r=>canContent(r,a)),leads:s.leads.filter(r=>canLead(r,a))};}
function commit(s,type,id){s.events.push({type,id,...X.actor()});wx.setStorageSync(KEY,s);}
function policy(a){return X.rawBook(a.companyId)?.contentPolicy||{accounts:[{id:'douyin-adviser',operators:['zhang','liu','sales-dual']},{id:'channels-design',operators:['zhang','liu','sales-dual']}]};}
function accountAllowed(accountId,a){const p=X.rawBook(a.companyId)?.staff.find(p=>p.id===a.staffId),q=policy(a).accounts.find(q=>q.id===accountId);return Job.has(a.role,'sales')&&q&&(p?.publishAccountIds?p.publishAccountIds.includes(accountId):q.operators.includes(a.staffId));}
function signature(pack){return JSON.stringify(pack);}
function prepare(workId,f,account){
 const a=actor();X.assert(accountAllowed(account.id,a),'当前业务员未获该组织账号发布授权');
 const work=require('./studio').read().works.find(w=>w.id===workId);X.assert(work,'只能发布本人实际保存的作品');
 const pack={work:copy(work),title:String(f.title||'').trim(),publishText:String(f.publishText||'').trim(),tags:String(f.tags||''),cover:f.cover||work.image,accountId:account.id,platform:account.platform,scheduled:String(f.scheduled||'')};
 X.assert(pack.title&&pack.publishText,'请补齐标题和发布内容');const sig=signature(pack),s=all();
 let r=s.contents.find(r=>r.companyId===a.companyId&&r.authorId===a.staffId&&r.workId===workId&&r.pack.accountId===account.id);
 if(r&&r.signature===sig){if(r.publicationFlow!=='direct-v1'&&!['发布中','演示已发布'].includes(r.status)){if(r.review)r.reviewHistory=(r.reviewHistory||[]).concat(copy(r.review));Object.assign(r,{publicationFlow:'direct-v1',status:'待发布',review:null,revision:r.revision+1});commit(s,'content-direct-publish',r.id);}return copy(r);}
 X.assert(!r||!['发布中','演示已发布'].includes(r.status),'已提交发布的作品不能覆盖原发布包，请创建新作品');
 if(!r){r={id:uid('content'),companyId:a.companyId,teamId:a.teamId,authorId:a.staffId,authorPersonId:a.personId,workId,versions:[],revision:0};s.contents.push(r);}
 if(r.review)r.reviewHistory=(r.reviewHistory||[]).concat(copy(r.review));r.versions.push({version:r.versions.length+1,pack,sig,...X.actor()});
 Object.assign(r,{pack,signature:sig,status:'待发布',review:null,publicationFlow:'direct-v1',revision:r.revision+1});commit(s,'content-direct-publish',r.id);return copy(r);
}
function assertPublish(r,a){X.assert(r&&r.companyId===a.companyId&&r.authorId===a.staffId&&accountAllowed(r.pack.accountId,a),'原作品或发布账号授权已失效');X.assert(r.signature===signature(r.pack),'发布内容已变化，请重新打开原作品');if(r.publicationFlow==='direct-v1'){X.assert(r.versions.at(-1)?.sig===r.signature&&['待发布','发布中','演示已发布'].includes(r.status),'发布内容版本已变化，请重新打开');return;}X.assert(r.review?.result==='通过'&&r.review.signature===r.signature,'历史发布记录未完成原流程，请从发布设置重新提交');}
function publishing(id,recordId){const a=actor(),s=all(),r=s.contents.find(r=>r.id===id);assertPublish(r,a);r.publicationId=recordId;r.status='发布中';r.revision++;commit(s,'publication-submit',id);}
function published(recordId){const a=actor(),s=all(),r=s.contents.find(r=>r.publicationId===recordId),actual=require('./studio').read().records.find(r=>r.id===recordId);X.assert(actual?.status==='published'&&actual.contentId===r?.id&&actual.contentSignature===r?.signature,'须先取得原作品的发布结果');assertPublish(r,a);const url=require('./studio').normalizePublishedUrl(actual.publishedUrl);X.assert(!s.contents.some(c=>c.companyId===a.companyId&&c.id!==r.id&&c.publishedUrl===url),'此发布链接已登记到其他作品，请核对后填写');if(r.status==='演示已发布'&&r.publishedUrl===url)return;Object.assign(r,{status:'演示已发布',publishedAt:actual.publishedAt,publishedUrl:url,collection:copy(actual.collection),linkRecordedAt:actual.linkRecordedAt,revision:r.revision+1});commit(s,'publication-link-recorded',r.id);}
function findStoreCustomer(a,phone){return ((wx.getStorageSync(require('./storage-keys').business)||{}).customers||[]).find(c=>(c.companyId||'quanzhou')===a.companyId&&c.phone===phone&&(X.isBoss(a)||X.teamAccess(c,a)))||null;}
function capture(f){
 const a=actor();X.assert(Job.has(a.role,'sales'),'请由业务员登记真实客资');
 X.assert(String(f.name||'').trim()&&/^1\d{10}$/.test(String(f.phone||''))&&String(f.need||'').trim(),'请填写称呼、有效手机号及咨询需求');
 const s=all(),content=f.contentId?s.contents.find(c=>c.id===f.contentId&&c.companyId===a.companyId&&c.status==='演示已发布'&&canContent(c,a)):null;
 X.assert(!f.contentId||content,'请关联有权查看且已确认发布的原内容');
 X.assert(!s.leads.some(r=>r.companyId===a.companyId&&r.phone===f.phone&&!['无效','已转客户'].includes(r.status)),'本门店已有该联系人客资，请在原客资关联的客户下继续跟进');
 const r={id:uid('lead'),companyId:a.companyId,teamId:a.teamId,name:f.name.trim(),phone:f.phone,need:f.need.trim(),source:content?'内容咨询':String(f.source||'直接来访'),contentId:content?.id||'',publicationId:content?.publicationId||'',publicationUrl:content?.publishedUrl||'',sourceSnapshot:content?{title:content.pack.title,platform:content.pack.platform,accountId:content.pack.accountId,workId:content.workId,publishedUrl:content.publishedUrl||''}:null,registrarId:a.staffId,registrarPersonId:a.personId,ownerId:a.staffId,status:'跟进中',follows:[{kind:'capture',note:'登记客资，保留作品与发布来源',...X.actor()}],history:[],revision:1,...X.actor()};
 const existing=findStoreCustomer(a,r.phone);
 if(existing){
  const before=wx.getStorageSync(require('./storage-keys').business),business=copy(before),c=business.customers.find(c=>c.id===existing.id);
  c.leadAssociations=c.leadAssociations||[];
  r.status='已转客户';r.conversionKind='existing-customer';r.customerId=c.id;
  if(!c.leadAssociations.some(l=>l.leadId===r.id))c.leadAssociations.push({leadId:r.id,contentId:r.contentId,publicationId:r.publicationId,source:r.source,sourceSnapshot:copy(r.sourceSnapshot),need:r.need,follows:copy(r.follows),originalLeadOwnerId:r.ownerId,customerOwnerId:X.ownerId(c),...X.actor()});
  c.revision=(c.revision||0)+1;
  r.history.push({action:'link-existing',customerId:c.id,preservedOwnerId:X.ownerId(c),...X.actor()});
  s.leads.push(r);
  try{wx.setStorageSync(require('./storage-keys').business,business);commit(s,'lead-capture',r.id);}catch(error){if(before)wx.setStorageSync(require('./storage-keys').business,before);throw error;}
  return {lead:copy(r),customer:{id:c.id,linked:true,name:c.name,ownerId:X.ownerId(c)}};
 }
 s.leads.push(r);
 commit(s,'lead-capture',r.id);
 const c=require('./business').fromLead(r.id,{});
 const s2=all(),r2=s2.leads.find(x=>x.id===r.id);
 r2.customerId=c.id;r2.status='已转客户';r2.conversionKind='direct-capture';r2.revision++;
 commit(s2,'lead-capture',r.id);
 return {lead:copy(r2),customer:c};
}
function change(id,action,f,expected){const a=actor(),s=all(),r=s.leads.find(r=>r.id===id);X.assert(r&&canLead(r,a),'线索不存在或无权访问');X.assert(expected===r.revision,'线索已更新，请刷新');X.assert(r.status!=='已转客户','已转客户的线索不能重新跟进');X.assert(Job.has(a.role,'sales')&&r.ownerId===a.staffId,'请由当前业务经办人办理');if(action==='request-link'){X.assert(['跟进中','待负责人关联'].includes(r.status),'请先跟进再请求关联');r.status='待负责人关联';r.linkRequest={note:f.note||'同手机号原客户需要负责人关联',...X.actor()};}else if(action==='follow'){X.assert(r.status==='跟进中'&&String(f.note||'').trim(),'请填写跟进内容');r.follows.push({note:f.note,...X.actor()});}else if(action==='invalid'){X.assert(String(f.note||'').trim(),'请说明无效原因');r.status='无效';}else throw Error('未知线索动作');r.revision++;commit(s,action,id);return copy(r);}
function convert(id,f,expected){const a=actor(),s=all(),r=s.leads.find(r=>r.id===id);X.assert(Job.has(a.role,'sales')&&r&&r.companyId===a.companyId&&r.ownerId===a.staffId,'请由线索当前业务员转客户');if(r.status==='已转客户')return require('./business').customer(r.customerId);X.assert(r.revision===expected&&r.status==='跟进中'&&r.follows.length,'请先跟进线索，刷新后转客户');const c=require('./business').fromLead(id,f);r.customerId=c.id;r.status='已转客户';r.revision++;commit(s,'lead-convert',id);return c;}
function currentLead(id){const a=actor(),r=all().leads.find(r=>r.id===id);X.assert(r&&r.companyId===a.companyId&&r.ownerId===a.staffId&&Job.has(a.role,'sales'),'线索未分配给本人');return r;}
function existingCustomer(lead){const a=actor();if(!lead||!manages(lead,a))return null;return ((wx.getStorageSync(require('./storage-keys').business)||{}).customers||[]).find(c=>(c.companyId||'quanzhou')===a.companyId&&c.phone===lead.phone&&(X.isBoss(a)||X.teamAccess(c,a)))||null;}
function linkExisting(id,expected){
 const a=actor(),s=all(),r=s.leads.find(l=>l.id===id);X.assert(r&&manages(r,a),'请由有权负责人关联原客户');
 if(r.status==='已转客户'&&r.customerId)return {id:r.customerId};
 X.assert(r.revision===expected&&r.status!=='无效','线索已变化，请刷新');
 const customer=existingCustomer(r);X.assert(customer,'未找到本人有权处理的同手机号原客户，请交对应团队负责人或老板核对');
 const before=wx.getStorageSync(require('./storage-keys').business),business=copy(before),c=business.customers.find(c=>c.id===customer.id);
 c.leadAssociations=c.leadAssociations||[];
 if(!c.leadAssociations.some(l=>l.leadId===id))c.leadAssociations.push({leadId:id,contentId:r.contentId,publicationId:r.publicationId,source:r.source,sourceSnapshot:copy(r.sourceSnapshot),need:r.need,follows:copy(r.follows),originalLeadOwnerId:r.ownerId,customerOwnerId:X.ownerId(c),...X.actor()});
 c.revision=(c.revision||0)+1;r.customerId=c.id;r.status='已转客户';r.conversionKind='existing-customer';r.revision++;
 r.history.push({action:'link-existing',customerId:c.id,preservedOwnerId:X.ownerId(c),...X.actor()});
 try{wx.setStorageSync(require('./storage-keys').business,business);commit(s,'lead-link-existing',id);}catch(error){if(before)wx.setStorageSync(require('./storage-keys').business,before);throw error;}
 return {id:c.id,ownerId:X.ownerId(c)};
}
module.exports={KEY,load,prepare,publishing,published,capture,change,convert,currentLead,existingCustomer,linkExisting,accountAllowed,assertPublish,signature};
