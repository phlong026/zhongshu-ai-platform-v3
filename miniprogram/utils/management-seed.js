const D=require('./demo-catalog');const file=name=>({name,sample:true});
function baseSeed(){
 const flow=D.acceptance,stage=flow.stages.finance;
 return {version:1,companyId:'quanzhou',snapshot:D.financialSnapshot,
  receipts:[
   {id:flow.receipts.advance.id,projectId:stage.projectId,customer:stage.label,name:'工程预收款',amount:flow.receipts.advance.cents,status:'已核算',date:'2026-10-18',receivedAt:'2026-10-18 10:00',registeredAt:'2026-10-18 10:05',registrar:'张业务',receiver:'王店长',method:'银行转账',file:file('工程收款凭据.jpg'),transaction:'SK-20261018-001',revisions:[]},
   {id:'receipt-design-5000',projectId:stage.projectId,customer:stage.label,name:'独立设计费',amount:550000,status:'已备案',date:'2026-10-18',receivedAt:'2026-10-18 10:15:27',registeredAt:'2026-10-18 10:20',registrar:'张业务',receiver:'王店长',method:'银行转账',file:file('独立设计费收款凭据.jpg'),transaction:'SK-20261018-002',revisions:[{id:'correction-design-001',originalAmount:550000,amount:flow.designFeeCents,note:'原登记录入金额有误，以收款凭证为准。',by:'李财务',at:'2026-10-18 10:40',file:file('独立设计费收款凭据.jpg')}]},
   {id:flow.receipts.first.id,projectId:stage.projectId,customer:stage.label,name:'工程一期预收款',amount:flow.receipts.first.cents,status:'待核算',date:'2026-10-18',receivedAt:'2026-10-18 14:00',registeredAt:'2026-10-18 14:10',registrar:'李经理',receiver:'王店长',method:'银行转账',file:file('银行收款凭据_待核算.jpg'),transaction:'SK-20261018-003',revisions:[]}
  ],
  payables:[
   {id:'payable-chen-design',projectId:stage.projectId,customer:stage.label,name:'总部设计服务',stage:'设计首款',total:600000,recipient:'总部设计服务中心',status:'部分已付',agreement:file('设计服务约定.pdf')},
   {id:'payable-lin-verify',projectId:'project-lin',customer:'林女士',name:'设计服务',stage:'设计首款',total:600000,recipient:'总部设计服务中心',status:'结果待核实',agreement:file('设计服务约定_林.pdf')}
  ],
  payments:[
   {id:'payment-wang-design',projectId:'project-wang',customer:'王先生',name:'总部设计服务',amount:600000,status:'已核实',date:'2026-10-17',transaction:'FK-20261017-001',file:file('付款凭据.pdf')},
   {id:'payment-zhang-labor',projectId:'project-zhang',customer:'张先生',name:'劳务结算',amount:1200000,status:'已核实',date:'2026-10-16',transaction:'FK-20261016-001',file:file('劳务凭据.pdf')},
   {id:'payment-zhao-freight',projectId:'project-zhao',customer:'赵女士',name:'运输费',amount:180000,status:'已核实',date:'2026-10-15',transaction:'FK-20261015-001',file:file('运费凭据.pdf')},
   {id:'payment-chen-partial',payableId:'payable-chen-design',projectId:stage.projectId,customer:stage.label,name:'总部设计服务',amount:200000,status:'已核实',date:'2026-10-18',verifiedAt:'2026-10-18 16:10',transaction:'FK-20261018-001',file:file('部分付款凭据.jpg')},
   {id:'payment-lin-unknown',payableId:'payable-lin-verify',projectId:'project-lin',customer:'林女士',name:'设计服务',amount:600000,status:'待核实',date:'2026-10-17',transaction:'FK-20261017-002',file:file('原付款凭据.jpg')}
  ],
  refunds:[{id:D.refund.id,receiptId:D.refund.receiptId,projectId:stage.projectId,amount:D.refund.amount*100,status:'已核实',date:'2026-10-18',transaction:'TK-20261018-001',reason:'双方协商退还部分款项',file:file('退款凭据.jpg')}],
  staff:[
   {id:'zhang',name:'张业务',role:'业务员',active:true},{id:'liu',name:'刘业务',role:'业务员',active:true},{id:'li',name:'李经理',role:'工程师',active:true},{id:'wang-manager',name:'王经理',role:'工程师',active:true},{id:'wang-finance',name:'王财务',role:'财务专员',active:true},{id:'chen-inactive',name:'陈业务',role:'业务员',active:false,disabledAt:'2026-10-18 09:00',disabledBy:'赵老板',reason:'人员离岗'}
  ],
  work:[
   {id:'work-huang',customer:'黄先生',name:'客户跟进',stage:'需求沟通',owner:'zhang',author:'zhang'},
   {id:'work-chen',customer:flow.customer,name:'方案沟通',owner:flow.actors.sales.staffId,author:flow.actors.sales.staffId},
   {id:'work-lin',customer:'林女士',name:'预约跟进',owner:'zhang',author:'zhang'},
   {id:'work-sun',customer:'孙先生',name:'客户跟进',owner:'chen-inactive',author:'chen-inactive'},
   {id:'work-content',customer:'秋季建房口播',name:'内容任务',owner:'chen-inactive',author:'chen-inactive'}
  ],
  transfers:[],drafts:{},
  rules:{version:2,period:'2026-10',locked:true,targets:[{name:'节点按期验收率',target:90,weight:60},{name:'一次验收通过率',target:95,weight:40}]},
  sources:[
   {id:'node-zhou',customer:'周先生',name:'主体验收',scheduled:'2026-10-12',accepted:'2026-10-12',onTime:true,first:true},
   {id:'node-wu',customer:'吴女士',name:'基础验收',scheduled:'2026-10-15',accepted:'2026-10-14',onTime:true,first:true},
   {id:'node-zhang',customer:'张先生',name:'主体验收',scheduled:'2026-10-16',accepted:null,onTime:false,first:false},
   {id:'node-li',customer:'李先生',name:'基础验收',scheduled:'2026-10-10',accepted:'2026-10-10',onTime:true,first:true},
   {id:'node-zhao',customer:'赵女士',name:'基础验收',scheduled:'2026-10-11',accepted:'2026-10-11',onTime:true,first:true}
  ],
  appeals:[],
  archives:[{id:'2026-09',person:'wang-manager',ruleVersion:1,status:'待复核',results:[90,100],date:null},{id:'2026-08',person:'wang-manager',ruleVersion:1,status:'已归档',results:[90,100],date:'2026-09-03'},{id:'2026-07',person:'wang-manager',ruleVersion:1,status:'已归档',results:[90,100],date:'2026-08-03'}],
  events:[],change:{id:D.projects.zhang.changeId,projectId:'project-zhang',customer:'张先生',project:D.projects.zhang.name,manager:'王经理',original:80000000,delta:3000000,days:3,ownerAgreed:true,confirmed:false,file:file('业主同意变更凭据.pdf')}
 };
}
function seed(){const s=baseSeed();for(const p of require('./demo-users').people.filter(p=>p.companyId==='quanzhou'&&p.roles[0]!=='customer')){const x=s.staff.find(x=>x.id===p.staffId);if(x){x.roleId=p.roles[0];x.roles=p.roles.slice();}else s.staff.push({id:p.staffId,name:p.name,role:require('./access').roles[p.roles[0]],roleId:p.roles[0],roles:p.roles.slice(),personId:p.staffId,teamId:require('./organization').teamForRole(p.roles[0]),active:true});}for(const w of s.work){if(w.id==='work-chen'){w.customerId=D.acceptance.customerId;w.projectId=D.acceptance.projectId;w.kind='customer-responsibility';}if(w.id==='work-lin'){w.customerId='customer-lin';w.projectId='project-lin';w.kind='customer-responsibility';}}return s;}
module.exports=seed;
