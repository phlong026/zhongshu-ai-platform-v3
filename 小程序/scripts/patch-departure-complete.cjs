const fs=require('fs');const edit=(p,fn)=>{let s=fs.readFileSync(p,'utf8');const r=(a,b)=>{if(!s.includes(a))throw Error('Missing '+p+' '+a.slice(0,65));s=s.replace(a,b);};fn(r,()=>s,v=>s=v);fs.writeFileSync(p,s);};
edit('miniprogram/pages/admin/finance-actions.js',r=>{
 r("const actions=['reconcile'", "const actions=['finance-transfer','reconcile'");
 r("if(['receipt-return','reconcile'].includes(action))", "if(action==='finance-transfer'){const row=payment||refund,to=s.staff.find(p=>p.id===f.assignee&&p.active&&(p.roles||[p.roleId]).includes('finance'));X.assert((X.isBoss(a)||a.role==='finance-lead')&&row&&['需补充','待复核','待核实'].includes(row.status),'仅负责人可转交待处理的原笔付款或退款');const from=row.custodianId||row.registrarId;X.assert(to&&to.id!==from&&f.checked&&String(f.note||'').trim(),'请选择不同的在岗财务专员并填写交接依据');row.transferHistory=(row.transferHistory||[]).concat({from,to:to.id,note:f.note,...actor});row.custodianId=to.id;row.custodian=to.name;audit(row);}\n else if(['receipt-return','reconcile'].includes(action))");
 r("payment.registrarId!==a.staffId,'不得核对", "payment.registrarId!==a.staffId&&payment.custodianId!==a.staffId,'不得核对");
 r("payment.registrarId===a.staffId&&payment.status==='需补充'", "(payment.custodianId||payment.registrarId)===a.staffId&&payment.status==='需补充'");
 r("refund.registrarId!==a.staffId,'请由另一", "refund.registrarId!==a.staffId&&refund.custodianId!==a.staffId,'请由另一");
 r("refund.registrarId===a.staffId&&refund.status==='需补充'", "(refund.custodianId||refund.registrarId)===a.staffId&&refund.status==='需补充'");
});
edit('miniprogram/pages/admin/model.js',r=>{
 r("return F.merge(s);}function save", "s=F.merge(s);s.work=(s.work||[]).filter(w=>w.kind!=='project-responsibility');for(const p of (wx.getStorageSync('zhongshu-engineering-v1')||{}).projects||[])if((p.companyId||'quanzhou')===companyId&&!p.archived)s.work.push({id:'engineering-work:'+p.id,kind:'project-responsibility',engineeringId:p.id,customerId:p.sourceCustomerId||'',projectId:p.projectId||p.id,customer:p.customer,name:'工程项目负责',stage:p.actualStart?'施工中':'待开工',owner:X.managerId(p),author:p.createdById||p.originalManagerId||X.managerId(p)});return s;}function save");
 r("assert((w.name==='内容任务'||w.name.includes('跟进')||w.name==='方案沟通')&&to.role==='业务员','接替人的职责不匹配');", "const engineering=w.kind==='project-responsibility';assert(engineering?X.isBoss()&&(to.roles||[to.roleId]).includes('manager'):(w.name==='内容任务'||w.name.includes('跟进')||w.name==='方案沟通')&&to.role==='业务员','接替人的职责不匹配或当前人员无项目交接权');");
 r("workId:w.id,customerId:w.customerId", "workId:w.id,engineeringId:w.engineeringId||'',customerId:w.customerId");
 r("s.transfers.push(transfer);if(w.kind", "s.transfers.push(transfer);if(engineering)s.pendingEngineeringTransfers=(s.pendingEngineeringTransfers||[]).concat(transfer);if(w.kind");
 r("n.work=n.work.filter(w=>w.customerId||w.author===c.staffId);", "n.work=n.work.filter(w=>w.kind!=='project-responsibility'&&(w.customerId||w.author===c.staffId));");
});
edit('miniprogram/pages/admin/finance-save.js',r=>{
 r(" for(const r of s.receipts||[])", " for(const t of s.pendingEngineeringTransfers||[]){const p=e.projects.find(p=>p.id===t.engineeringId&&(p.companyId||'quanzhou')===s.companyId),to=s.staff.find(p=>p.id===t.to&&p.active&&(p.roles||[p.roleId]).includes('manager'));if(!p||!to||X.managerId(p)!==t.from||p.projectId!==t.projectId||p.archived)throw Error('原工程责任或接替人已变化，请刷新后交接');p.originalManagerId=p.originalManagerId||t.from;p.managerId=to.id;p.manager=to.name;p.responsibilityHistory=(p.responsibilityHistory||[]).concat(clone(t));p.events=(p.events||[]).concat({title:'工程责任交接',...clone(t)});changedE=true;if(p.sourceCustomerId){const c=b.customers.find(c=>c.id===p.sourceCustomerId&&c.projectId===p.projectId&&(c.companyId||'quanzhou')===s.companyId);if(!c||X.managerId(c)!==t.from)throw Error('业务原接收或项目编号已变化');c.managerId=to.id;c.currentManagerName=to.name;c.engineeringResponsibilityHistory=(c.engineeringResponsibilityHistory||[]).concat(clone(t));c.events=(c.events||[]).concat({title:'工程责任交接',...clone(t)});changedB=true;}}\n for(const r of s.receipts||[])");
 r("approvalId:r.approvalId};", "approvalId:r.approvalId,custodianId:r.custodianId,transferHistory:r.transferHistory||[],supplements:r.supplements||[],history:r.history||[],events:r.events||[],verifications:r.verifications||[]};");
 r("reviewedAt:r.reviewedAt});changedE=true;", "reviewedAt:r.reviewedAt,custodianId:r.custodianId,transferHistory:r.transferHistory||[],supplements:r.supplements||[],history:r.history||[],events:r.events||[],verifications:r.verifications||[]});changedE=true;");
 r("delete value.pendingTransfers;", "delete value.pendingTransfers;delete value.pendingEngineeringTransfers;");
});
edit('miniprogram/utils/access.js',r=>{
 r("'companyId','projectId','createdById'", "'companyId','projectId','managerId','currentManagerName','createdById'");
 r("r&&r.registrarId===c.staffId", "r&&(r.custodianId||r.registrarId)===c.staffId");
 r("r&&r.registrarId===c.staffId", "r&&(r.custodianId||r.registrarId)===c.staffId");
 r("if(view==='refund-supplement')", "if(view==='finance-transfer')return (isBoss(c)||c.role==='finance-lead')&&!!r&&rowAccess(r,c);if(view==='refund-supplement')");
});
edit('miniprogram/utils/finance-ledger.js',r=>{
 r("for(const p of s.payments){const q=", "const needsHandover=r=>{const id=r.custodianId||r.registrarId;return (s.staff||[]).some(p=>p.id===id&&p.active===false);};for(const p of s.payments){if(['需补充','待复核','待核实'].includes(p.status)&&needsHandover(p))add(p,'finance-transfer','原付款经办离岗交接',p.status,p.amount);const q=");
 r("for(const r of s.refunds||[]){if", "for(const r of s.refunds||[]){if(['需补充','待复核','待核实'].includes(r.status)&&needsHandover(r))add(r,'finance-transfer','原退款经办离岗交接',r.status,r.amount);if");
});
edit('miniprogram/pages/admin/views.js',r=>{r("x.role==='业务员').map", "x.role===(work.kind==='project-responsibility'?'项目经理':'业务员')).map");r("name:x.name+' [在岗 · 业务员]'", "name:x.name+' [在岗 · '+x.role+']'");});
edit('miniprogram/pages/admin/finance-views.js',r=>{
 r("const titles={", "const titles={'finance-transfer':'原笔财务经办交接',");
 r(" if(view==='todos')", " if(view==='finance-transfer'){const record=p||refund;if(!record)return {...out,missing:true};out.hero={title:'原笔经办交接',amount:money(record.amount),desc:record.status};out.sections=[section('原记录与责任',[row('原记录编号',record.id),row('原登记者',person(record.registrarId)),row('当前经办人',person(record.custodianId||record.registrarId)),row('原流水',record.transaction),notice('仅交接后续补证责任，原金额、原流水和原登记者保留。'),{type:'picker',label:'接替财务专员',key:'assignee',options:(X.rawBook(X.context().companyId)?.staff||[]).filter(p=>p.active&&(p.roles||[p.roleId]).includes('finance')&&p.id!==(record.custodianId||record.registrarId)).map(p=>({id:p.id,name:p.name}))},area('note','请填写经办交接原因'),check('已核对原笔、接替人员与交接依据')])];out.primary='确认原笔经办交接';out.action='finance-transfer';return out;}\n if(view==='todos')");
 r("row('付款登记者',person(p.registrarId))", "row('付款登记者',person(p.registrarId)),row('当前补证经办',person(p.custodianId||p.registrarId))");
 r("p.registrarId===X.context().staffId", "(p.custodianId||p.registrarId)===X.context().staffId");
 r("X.context().staffId===refund.registrarId", "X.context().staffId===(refund.custodianId||refund.registrarId)");
 r("if(p.returnReason)", "if(['待复核','需补充','待核实'].includes(p.status)&&(X.isBoss()||X.context().role==='finance-lead'))out.sections.push(section('原笔后续责任',[link('转交本笔经办','finance-transfer',p.id,'由另一在岗财务补证；原登记者保留')]));if(p.returnReason)");
 r("if(refund.returnReason)", "if(['待复核','需补充','待核实'].includes(refund.status)&&(X.isBoss()||X.context().role==='finance-lead'))out.sections.push(section('原笔后续责任',[link('转交本笔经办','finance-transfer',refund.id,'经办离岗时按原笔授权交接')]));if(refund.returnReason)");
});
edit('miniprogram/pages/admin/index.js',r=>{r("'refund-return','refund-supplement'", "'refund-return','refund-supplement','finance-transfer'");});
edit('miniprogram/pages/build/model.js',(r,get,set)=>{set(get().replaceAll("managerId:c.acceptance.managerId||Access.idFor(c.acceptance.manager)","managerId:Access.managerId(c)").replaceAll("manager:c.acceptance.manager", "manager:c.currentManagerName||c.acceptance.manager"));});
edit('miniprogram/pages/workflow/extra-business.js',r=>{r("transfer.acceptance = { manager: transfer.manager, date:", "const actual=B.Access.context();transfer.acceptance = { manager: actual.active?actual.name:transfer.manager, managerId:actual.active?actual.staffId:transfer.managerId, date:");});
edit('miniprogram/pages/workflow/extra-views.js',r=>{r("kv('接收经理', transfer ? transfer.manager : '—')", "kv('接收经理',c.currentManagerName||(transfer?transfer.manager:'—'))");});
