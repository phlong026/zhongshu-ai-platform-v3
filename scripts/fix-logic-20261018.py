from pathlib import Path
r=Path.cwd();m=r/'miniprogram'
def edit(name,a,b):
 p=m/name;s=p.read_text(encoding='utf8');assert a in s,(name,a);p.write_text(s.replace(a,b),encoding='utf8')
# Procurement persists seed mutations and isolates project choices.
edit('pages/account/procurement.js',"const projects=[","const projects=[")
edit('pages/account/procurement.js',"function initial()", "projects.forEach(p=>p.companyId='quanzhou');\nfunction visibleProjects(s){if(!A.permitted(s,'account'))return [];const rows=projects.filter(p=>p.companyId===s.session.companyId);const b=wx.getStorageSync('zhongshu-business-v3');for(const c of b&&b.customers||[]){if((c.companyId||'quanzhou')===s.session.companyId&&(c.contract||c.acceptance)&&!rows.some(p=>p.id===c.projectId))rows.push({id:c.projectId,name:c.name+' · '+c.city+'乡墅',manager:c.acceptance&&c.acceptance.manager||c.owner,address:c.city,companyId:s.session.companyId});}return rows;}\nfunction initial()")
edit('pages/account/procurement.js',"return A.scoped(s,s.procurements||initial());","return A.permitted(s,'account')?A.scoped(s,s.procurements||initial()):[];")
edit('pages/account/procurement.js',"project=projects.find(x=>x.id===f.projectId)","project=visibleProjects(s).find(x=>x.id===f.projectId)")
edit('pages/account/procurement.js',"Number(f.quantity)<=0","Number(f.quantity)<=0||Number(f.quantity)>10000000")
edit('pages/account/procurement.js',"if(!f.address||!f.contact)","if(!(f.address||'').trim()||!(f.contact||'').trim())")
edit('pages/account/procurement.js',"const id='purchase-'+Date.now();","const id='purchase-'+Date.now()+'-'+Math.random().toString(36).slice(2,9);")
edit('pages/account/procurement.js',"function confirm(s,id,version){const p=get(s,id);","function confirm(s,id,version){s.procurements=s.procurements||initial();const p=get(s,id);")
edit('pages/account/procurement.js',"p.status='待安排供货';", "const q=p.quote;const cents=n=>Math.round(Number(n)*100);if(![q.unitPrice,q.material,q.freight,q.total].every(n=>Number.isFinite(Number(n))&&Number(n)>=0)||cents(p.quantity*q.unitPrice)!==cents(q.material)||cents(q.material)+cents(q.freight)!==cents(q.total))throw Error('报价金额与数量不一致，请联系供应方重新报价');p.status='待安排供货';")
edit('pages/account/procurement.js',"projects,initial","projects,visibleProjects,initial")
# Service visibility and drafts.
edit('utils/account.js',"function order(s,id){return scoped(s,s.orders).find(x=>x.id===id)||null;}","function visibleOrders(s){if(!permitted(s,'account'))return [];return s.orders.filter(x=>x.companyId===s.session.companyId&&(x.userId===s.session.userId||s.session.role==='owner'));}\nfunction order(s,id){return visibleOrders(s).find(x=>x.id===id)||null;}\nfunction draftKey(s,id){return ['request',s.session.companyId,s.session.userId,id].join(':');}")
edit('utils/account.js',"delete s.drafts['request:'+service.id]","delete s.drafts[draftKey(s,service.id)]")
edit('utils/account.js',"image:planImage,name:'店招设计方案 V'+((o.plan&&o.plan.version||0)+1)","image:o.service==='store'?planImage:services.find(x=>x.id===o.service).image,name:(o.service==='store'?'店招设计方案':o.name+'方案')+' V'+((o.plan&&o.plan.version||0)+1)")
edit('utils/account.js',"name:'店招设计定稿_V'+o.plan.version+'.pdf'","name:(o.service==='store'?'店招设计':o.name)+'定稿_V'+o.plan.version+'.pdf'")
edit('utils/account.js',"scoped,order,progress","scoped,visibleOrders,order,draftKey,progress")
edit('pages/account/index.js',"s.drafts['request:'+this.serviceId]","s.drafts[A.draftKey(s,this.serviceId)]")
edit('pages/account/index.js',"orders:A.scoped(s,s.orders)","orders:A.visibleOrders(s)")
edit('pages/account/index.js',"projects:Q.projects,","projects:Q.visibleProjects(s),")
edit('pages/account/index.js',"const p=Q.projects[Number(e.detail.value)];","const p=this.data.projects[Number(e.detail.value)];if(!p)return;")
edit('pages/account/index.js',"(x.project.name+x.product.name)","((x.project&&x.project.name||'关联项目')+(x.product&&x.product.name||'材料'))")
# Financial arithmetic, immutable references and same-tick record identifiers.
edit('pages/admin/model.js',"function seed()", "let sequence=0;const uid=prefix=>prefix+'-'+Date.now()+'-'+(++sequence);\nfunction seed()")
edit('pages/admin/model.js',"function apply(s,action,id,f={}){requireRole", "function apply(s,action,id,f={}){f=Object.assign({},f,{transaction:String(f.transaction||'').trim()});requireRole")
for x in ['payment','correction','refund','transfer','appeal']:
 edit('pages/admin/model.js',"'"+x+"-'+Date.now()","uid('"+x+"')")
edit('pages/admin/model.js',"x.transaction===f.transaction","String(x.transaction||'').trim()===f.transaction")
edit('pages/admin/model.js',"x.verifiedAt=now();", "const q=s.payables.find(p=>p.id===x.payableId);assert(!q||paid(s,q.id)+x.amount<=q.total,'核实后累计付款超过应付总额，请先核对原付款');x.verifiedAt=now();")
edit('pages/admin/model.js',"payable.status=f.result==='原笔已付款'?'已付清':'待办理'", "payable.status=s.payments.some(v=>v.payableId===payable.id&&v.status==='待核实')?'结果待核实':paid(s,payable.id)===payable.total?'已付清':paid(s,payable.id)>0?'部分已付':'待办理'")
# Retain actual payments when deliverable versions change.
edit('pages/workflow/index.js',"x.tailPayment=null;x.tailApplication=null;x.release=null;", "if(!x.tailPayment)x.tailApplication=null;x.release=null;")
edit('pages/workflow/index.js',"transaction:f.transaction,file:f.paymentFile", "transaction:f.transaction.trim(),file:f.paymentFile")
# Engineering checks and project identity.
edit('pages/build/model.js',"let p=s.projects.find(x=>x.sourceCustomerId===c.id);if(!p){", "let p=s.projects.find(x=>x.sourceCustomerId===c.id);if(!p){p=s.projects.find(x=>x.projectId===c.projectId);if(p){assert(!p.sourceCustomerId||p.sourceCustomerId===c.id,'同一项目编号已关联其他业务客户，请先核对项目身份。');p.sourceCustomerId=c.id;changed=true;}}if(!p){")
edit('pages/build/model.js',"if(ctx.collectionId)assert", "if(ctx.inspectionId)assert(p.inspections.some(x=>x.id===ctx.inspectionId&&(!ctx.nodeId||x.nodeId===ctx.nodeId)),'验收记录不存在或不属于当前节点。');if(ctx.collectionId)assert")
edit('pages/build/model.js',"x.transaction===f.transaction", "String(x.transaction||'').trim()===String(f.transaction||'').trim()")
edit('pages/build/model.js',"assert(date(f.date),'请选择有效收货日期。');", "assert(date(f.date),'请选择有效收货日期。');assert(['合格','不合格'].includes(f.quality),'请选择有效质量结论。');")
edit('pages/build/model.js',"assert(f.verified,'请核对财务依据。');", "assert(f.verified,'请核对财务依据。');assert(['核算通过','退回补充'].includes(f.result),'请选择有效核算结论。');")
print('Applied procurement, service, payment, design-version and engineering fixes')
