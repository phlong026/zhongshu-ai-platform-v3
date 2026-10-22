const Job=require('./organization');
const X=require('./access');
module.exports=function(b,n,action){const a=X.context(),actor=X.actor(),diff=k=>JSON.stringify(b[k])!==JSON.stringify(n[k]);
if(diff('owner')||diff('ownerId')){const id=n.ownerId!==b.ownerId?n.ownerId:X.staffIdFor(n.owner,a.companyId,'sales'),staff=X.rawBook(a.companyId).staff.find(p=>p.id===id&&p.active!==false&&Job.staffHas(p,'sales'));X.assert(staff,'新负责人必须具有效业务员岗位');n.ownerId=id;n.owner=staff.name;n.responsibilityHistory=(n.responsibilityHistory||[]).concat({from:X.ownerId(b),to:id,...actor});}
for(const [key,rows]of Object.entries({follows:n.follows||[],receipts:n.receipts||[],returns:n.returns||[]}))for(const row of rows.slice((b[key]||[]).length)){Object.assign(row,actor);if(key==='follows')Object.assign(row,{authorId:a.staffId,author:a.name});if(key==='receipts')Object.assign(row,{id:row.id||'receipt-'+Date.now()+'-'+Math.random().toString(36).slice(2,7),registrarId:a.staffId,registrarPersonId:a.personId,registrar:a.name,status:'待核算',version:1});}
for(const row of n.supplements||[])if(!(b.supplements||[]).some(x=>x.id===row.id)){X.assert(X.staffIdFor(row.manager,n.companyId||'quanzhou','engineer')===X.managerId(b),'补充资料必须交当前工程师');Object.assign(row,actor,{managerId:X.managerId(b)});}
for(const key of ['serviceOrders'])for(const row of (n[key]||[]).slice((b[key]||[]).length))Object.assign(row,actor);
for(const row of n.changes||[]){const old=(b.changes||[]).find(x=>x.id===row.id);if(!old)Object.assign(row,actor,{applicantId:a.staffId});else if(JSON.stringify(old)!==JSON.stringify(row)&&action==='change-approval')Object.assign(row.approval,actor);}
if(n.acceptance&&!b.acceptance)Object.assign(n.acceptance,actor,{managerId:a.staffId});require('./commercial-approval').enqueue(b,n);n.revision=(b.revision||0)+1;
};
