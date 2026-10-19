from pathlib import Path
r=Path(__file__).resolve().parents[1]
def replace(name,old,new):
 p=r/name;s=p.read_text(encoding='utf-8');assert old in s,(name,old[:100]);p.write_text(s.replace(old,new),encoding='utf-8')
p=r/'miniprogram/utils/business.js';s=p.read_text(encoding='utf-8').replace("const D=require('./demo-catalog');","const D=require('./demo-catalog'),Access=require('./access');")
a=s.index('function scope()');b=s.index('function latest(',a)
s=s[:a]+'''function scope(){return Access.context();}
function normalize(c){const n=clone(c);n.companyId=n.companyId||'quanzhou';if(!n.ownerId)n.ownerId=Access.ownerId(n);n.drafts=n.drafts||{};return n;}
function projectCustomer(c){const n=normalize(c),a=scope();if(a.role==='manager'||a.role.startsWith('hq-')||a.role==='reviewer'||a.role==='customer'){n.receipts=[];n.budgets=[];n.quotes=[];n.serviceOrders=[];for(const key of ['firstPayment','tailPayment'])if(n[key])n[key]={confirmed:true,date:n[key].date};if(n.contract)n.contract={version:n.contract.version,file:n.contract.file,date:n.contract.date};if(a.role!=='customer'){n.phone=a.role==='manager'?n.phone:'';n.wechat='';}if(!n.release){n.deliverables=[];n.deliverableFile=null;}else n.deliverables=(n.deliverables||[]).filter(x=>x.version===n.release.version);if(a.role.startsWith('hq-')||a.role==='reviewer'){n.deliverables=(c.deliverables||[]).map(x=>{const row=clone(x);if(a.role!=='hq-designer'&&a.role!=='hq-release'&&!c.release)delete row.file;return row;});}}
 const key=scope().staffId+':'+scope().role+':';n.drafts=Object.fromEntries(Object.entries(n.drafts||{}).filter(([k])=>k.startsWith(key)));return n;}
function load(){const s=loadAll();s.customers=s.customers.map(normalize).filter(c=>Access.customer(c)).map(projectCustomer);return s;}
function commitRows(rows,extra={}){const all=loadAll();for(const row of rows){const i=all.customers.findIndex(c=>c.id===row.id);if(i<0)all.customers.push(clone(row));else all.customers[i]=clone(row);}wx.setStorageSync(KEY,Object.assign({},all,extra));}
function persist(state){Access.assert(scope().active,'请先登录并选择已授权工作身份');for(const c of state.customers){const old=loadAll().customers.find(x=>x.id===c.id);Access.assert(old&&Access.customer(old));Access.businessChanges(normalize(old),c,'edit');}commitRows(state.customers,{selected:state.selected,caseId:state.caseId});}
function customer(id){return load().customers.find(c=>c.id===id);}
function draftKey(view){const a=scope();return a.staffId+':'+a.role+':'+view;}
function write(id,change,event,action='edit'){
 const raw=loadAll().customers.find(c=>c.id===id);if(!raw||!Access.customer(raw))throw Error('客户不存在或未获授权');const before=normalize(raw),next=clone(before);change(next);Access.businessChanges(before,next,action);
 const a=scope(),actor=Access.actor();if(next.owner!==before.owner||next.ownerId!==before.ownerId){const id=next.ownerId!==before.ownerId?next.ownerId:Access.idFor(next.owner),person=(Access.rawBook(a.companyId)||{}).staff?.find(p=>p.id===id&&p.active&&(p.roleId==='sales'||p.role==='业务员'));Access.assert(person,'请选择当前公司有效业务员');next.ownerId=person.id;next.owner=person.name;next.responsibilityHistory=(next.responsibilityHistory||[]).concat({from:before.ownerId,to:person.id,...actor});}
 for(const [key,rows]of Object.entries({follows:next.follows||[],receipts:next.receipts||[],returns:next.returns||[]})){const offset=(before[key]||[]).length;for(const row of rows.slice(offset)){Object.assign(row,{actorId:a.staffId,userId:a.userId,role:a.role});if(key==='follows'){row.authorId=a.staffId;row.author=a.name;}if(key==='receipts'){row.id=row.id||'receipt-'+Date.now()+'-'+offset;row.registrarId=a.staffId;row.registrar=a.name;row.status='待核算';row.version=1;}}}
 if(next.request&&JSON.stringify(next.request)!==JSON.stringify(before.request)){Object.assign(next.request,{...actor,applicantId:a.staffId});next.designOrganizationId=next.designOrganizationId||'headquarters';if(!Object.keys(next.hqAssignees||{}).length){next.hqAssignees={};for(const p of require('./demo-users').people.filter(p=>p.companyId==='headquarters'))next.hqAssignees[p.roles[0]]=[p.staffId];}}
 if(next.tailApplication&&!before.tailApplication)Object.assign(next.tailApplication,actor,{applicantId:a.staffId});
 if(next.handover&&JSON.stringify(next.handover)!==JSON.stringify(before.handover)){const id=Access.idFor(next.handover.manager),person=(Access.rawBook(a.companyId)||{}).staff?.find(p=>p.id===id&&p.active&&p.role==='项目经理');Access.assert(person,'接收人必须为本公司在岗项目经理');next.handover.managerId=person.id;next.handover.submittedById=a.staffId;}
 if(next.acceptance&&!before.acceptance)Object.assign(next.acceptance,actor,{managerId:a.staffId});if(next.confirmation&&!before.confirmation)Object.assign(next.confirmation,actor);
 if(event)(next.events||(next.events=[])).push({id:'event-'+Date.now()+'-'+next.events.length,title:event,time:actor.at,...actor,objectId:id,projectId:next.projectId});
 commitRows([next]);return projectCustomer(next);
}
function add(values){const a=scope();Access.assert(a.active&&['sales','sales-lead'].includes(a.role),'请由业务员或业务负责人登记客户');const c=Object.assign(sample(),values,{id:'customer-'+Date.now()+'-'+Math.random().toString(36).slice(2,7),companyId:a.companyId,ownerId:a.staffId,createdById:a.staffId,owner:a.name,ownership:'private',follows:[],events:[],status:'已登记'});c.projectId='project-'+c.id;c.acquisitionChannel=c.source;commitRows([c],{selected:c.id});return c;}
''' + s[b:]
a=s.index('function referenceUsed(');b=s.index('function validDate',a)
s=s[:a]+'''function referenceUsed(direction,value,except={}){const a=scope(),ref=String(value||'').trim(),match=x=>String(x.transaction||'').trim()===ref;if(!ref)return false;const all=loadAll().customers.filter(c=>(c.companyId||'quanzhou')===a.companyId);if(all.some(c=>direction==='income'?(c.receipts||[]).some((x,i)=>!(except.customerId===c.id&&(except.recordId===x.id||except.index===i))&&match(x)):['firstPayment','tailPayment'].some(k=>c[k]&&match(c[k]))))return true;const e=wx.getStorageSync('zhongshu-engineering-v1')||{};if((e.projects||[]).filter(p=>(p.companyId||'quanzhou')===a.companyId).some(p=>(direction==='income'?p.collections||[]:p.payments||[]).some(x=>!(except.projectId===p.id&&except.recordId===x.id)&&match(x))))return true;const book=Access.rawBook(a.companyId);return !!(book&&(direction==='income'?book.receipts||[]:[...(book.payments||[]),...(book.refunds||[])]).some(row=>{const x=row.source||{};const own=x.module==='engineering'&&x.id===except.projectId&&x.recordId===except.recordId||x.module==='business'&&x.id===except.customerId&&(x.recordId===except.recordId||x.index===except.index);return !own&&match(row);}));}
''' +s[b:]
s=s.replace('module.exports={ KEY,','module.exports={ KEY,Access,draftKey,');p.write_text(s,encoding='utf-8')
# Engineering readers and writes share the same project membership policy.
p=r/'miniprogram/pages/build/model.js';s=p.read_text(encoding='utf-8').replace("const B = require('../../utils/business');","const B = require('../../utils/business');\nconst Access=require('../../utils/access');")
a=s.index('function load(){');b=s.index('function get(',a)
s=s[:a]+'''function load(){const s=loadAll();s.projects=s.projects.filter(p=>Access.project(p)).map(p=>Object.assign(p,{companyId:p.companyId||'quanzhou',managerId:Access.managerId(p)}));return s;}
function save(s){Access.assert(Access.context().active,'请先登录并选择已授权工作身份');const all=loadAll();for(const p of s.projects){Access.assert(Access.project(p));const i=all.projects.findIndex(x=>x.id===p.id);if(i>=0)all.projects[i]=clone(p);else all.projects.push(clone(p));}wx.setStorageSync(KEY,all);}
''' +s[b:]
s=s.replace("const p=clone(s.projects[i]);change(p);if(title)","const p=clone(s.projects[i]);Access.assert(Access.context().role==='manager'&&Access.managerId(p)===Access.context().staffId,'工程办理仅限当前被指派的项目经理');change(p);if(title)")
s=s.replace("title,time:new Date().toISOString()}","title,time:new Date().toISOString(),...Access.actor(),objectId:id,projectId:p.projectId}")
s=s.replace("function add(f){required", "function add(f){Access.assert(Access.context().active&&Access.context().role==='manager','请由项目经理新增工程');required")
s=s.replace("const p=project({id:uid('project'),name:","const p=project({id:uid('project'),managerId:Access.context().staffId,manager:Access.context().name,companyId:Access.context().companyId,name:")
s=s.replace("function apply(id,action,f={},ctx={}){let next='';", "function apply(id,action,f={},ctx={}){Access.assert(!['pay','collection-review','review'].includes(action),'付款和专业核对须由独立授权岗位办理，请返回对应工作台');let next='';")
s=s.replace("const record={id:existing?existing.id:uid('SK'),cents:","const record={id:existing?existing.id:uid('SK'),registrarId:existing?existing.registrarId:Access.context().staffId,registrar:existing?existing.registrar:Access.context().name,...Access.actor(),cents:")
s=s.replace("manager:c.acceptance.manager,accepted:","managerId:c.acceptance.managerId||Access.idFor(c.acceptance.manager),companyId:c.companyId,manager:c.acceptance.manager,accepted:")
s=s.replace("const meta={projectId:","const meta={managerId:c.acceptance.managerId||Access.idFor(c.acceptance.manager),projectId:")
p.write_text(s,encoding='utf-8')
print('Business and engineering authorization changes applied')
