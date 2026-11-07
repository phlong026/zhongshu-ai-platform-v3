// 接收建档与工程分包共享原项目资料映射，主包不依赖工程页面。
const X=require('./access'),Job=require('./organization'),B=require('./business');
const clone=B.clone,assert=X.assert;
// 工程档案空模板（演示 seed 已随生产剔除移除；plan 节点由空数组起步）
function project(values={}){return Object.assign({id:'',name:'',customer:'',city:'',manager:'',phone:'',area:'',floors:'',accepted:'',sample:false,sourceCustomerId:'',image:'',checks:[null,null,null,null,null,null],drawingVersion:'',documents:[],drawing:null,review:null,actualStart:null,plan:{version:1,nodes:[]},planSaved:false,planHistory:[],sends:[],materials:[],supplies:[],receipts:[],reships:[],inspections:[],rectifications:[],siteRecords:[],collections:[],payments:[],delivery:null,archived:false,events:[],drafts:{}},values);}
const KEY=require('./storage-keys').engineering,latest=rows=>rows?.at(-1);
function load(){
 const stored=wx.getStorageSync(KEY);
 if(!stored||stored.version!==1||!Array.isArray(stored.projects))return {version:1,projects:[]};
 const state=clone(stored);
 for(const p of state.projects)if(!p.projectId){
  const source=p.sourceCustomerId&&B.customer(p.sourceCustomerId);
  p.projectId=source?.projectId||p.id;
 }
 return state;
}
function find(c,projects){
 const matches=projects.filter(p=>p.sourceCustomerId===c.id||p.projectId===c.projectId);
 assert(matches.length<=1,'同一业务项目关联了多个工程，请先核对项目身份');
 const p=matches[0];
 if(p){
  assert((p.companyId||'quanzhou')===c.companyId,'工程与业务项目公司归属不一致');
  assert(!p.sourceCustomerId||p.sourceCustomerId===c.id,'同一项目编号已关联其他业务客户，请先核对项目身份');
  assert(p.projectId===c.projectId,'业务客户与原工程项目编号不一致');
 }
 return p;
}
function merge(c,existing){
 const basis=require('./project-records').basis(c),managerId=X.managerId(c);
 const p=existing?clone(existing):project({id:'biz-'+c.id,projectId:c.projectId,sourceCustomerId:c.id,companyId:c.companyId,managerId,manager:c.currentManagerName||c.acceptance.manager});
 // 工程经办人、已签施工合同和现场记录由工程维护，资料刷新只带入业务交底。
 Object.assign(p,{sourceCustomerId:c.id,name:c.name+' · '+c.city.replace(/市$/,'')+'乡墅',customer:c.name,city:c.city,phone:c.phone,area:basis.area,floors:basis.floors,accepted:p.accepted||c.acceptance.date});
 if(!p.recordFlow?.contract)p.contractTotal=c.agreements?.at(-1)?.total??c.contract?.total??p.contractTotal??0;
 const SF=require('./sales-flow'),offline=SF.is(c),packet=c.businessFlow?.packet;
 p.acceptedAt=p.acceptedAt||c.acceptance.at||c.businessFlow?.events?.filter(e=>e.action==='packet-receive').at(-1)?.at||c.acceptance.date;
 if(offline)require('./engineering-records').init(p);
 const d=offline?{version:packet?.drawingVersion,file:packet?.drawingFile}:latest(c.drawings);
 p.documents=clone(offline?SF.packageRows(c).map(r=>({name:r.label,file:r.file,date:c.handover.date})):[{name:'签约资料',file:c.contract&&c.contract.file,date:c.contract&&c.contract.date},{name:'交底记录',file:c.handover&&{name:'电子交底记录',text:c.handover.note},date:c.handover&&c.handover.date}].filter(x=>x.file));
 p.drawing=d?clone(d.file):null;p.drawingVersion=d?.version||'';
 return p;
}
function receive(customerId){
 const c=B.customer(customerId),a=X.context();
 assert(c?.acceptance&&Job.has(a.role,'engineer')&&X.sameCompany(c,a)&&c.acceptance.managerId===a.staffId&&X.managerId(c)===a.staffId,'请由本次资料接收的工程人员建立原项目');
 const state=load(),old=find(c,state.projects);
 assert(!old||X.managerId(old)===a.staffId,'该项目已有工程负责人，请先完成工程责任交接后再接收');
 assert(!old||X.project(old),'原工程没有授权，不能通过接收取得权限');
 const p=merge(c,old);
 if(old)state.projects[state.projects.findIndex(row=>row.id===old.id)]=p;
 else{assert(!state.projects.some(row=>row.id===p.id),'工程编号已关联其他项目');state.projects.push(p);}
 wx.setStorageSync(KEY,state);
 return p;
}
module.exports={load,find,merge,receive};
