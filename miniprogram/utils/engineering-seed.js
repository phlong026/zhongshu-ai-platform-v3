const D=require('./demo-catalog');
const asset = name => '/pages/build/assets/' + name + '.jpg';
const sampleFile = name => ({ name, sample: true });
function nodes() { return [{id:'n1',name:'基础工程',start:'2026-11-01',end:'2026-11-07',status:'待开工'},{id:'n2',name:'一层主体',start:'2026-11-08',end:'2026-11-21',status:'待开工'},{id:'n3',name:'二层主体',start:'2026-11-22',end:'2026-12-05',status:'待开工'}]; }
function project(values={}) { return Object.assign({id:'',name:'',customer:'',city:'',manager:'李经理',phone:'',area:400,floors:2,accepted:'',sample:false,sourceCustomerId:'',image:asset('house'),checks:[null,null,null,null,null,null],drawingVersion:'v1',documents:[],drawing:null,review:null,actualStart:null,plan:{version:1,nodes:nodes()},planSaved:false,planHistory:[],sends:[],materials:[],supplies:[],receipts:[],reships:[],inspections:[],rectifications:[],siteRecords:[],collections:[],payments:[],delivery:null,archived:false,events:[],drafts:{}},values); }
function initial() {
 const flow=D.acceptance,stage=flow.stages.engineering,p=project({id:flow.engineeringId,projectId:stage.projectId,sourceCustomerId:'',name:stage.label+' · 湖北乡墅',customer:stage.label,city:'武汉',manager:'李经理',accepted:flow.timeline.engineeringAccepted,sample:true,asOf:stage.asOf,documents:[{name:'签约资料',file:sampleFile('建造合同_双方已签.pdf'),date:flow.timeline.contractSigned},{name:'测量记录',file:sampleFile(D.design.measurement.name),date:'2026-10-19'},{name:'交底记录',file:sampleFile('电子交底记录.pdf'),date:flow.timeline.handoverSubmitted}],drawingVersion:flow.drawingVersion,drawing:sampleFile('施工图_'+flow.drawingVersion+'.pdf')});
 p.checks[3]={date:flow.timeline.planCompiled,person:'李经理',note:'班组及负责人已落实',file:sampleFile('班组安排.pdf')};p.checks[5]={date:flow.timeline.planCompiled,person:'李经理',note:'已关联电子交底记录',file:sampleFile('电子交底记录.pdf')};
 p.materials=[{id:'m1',name:'水泥 P.O 42.5',unit:'包',quantity:100,date:'2026-11-03',nodeId:'n1',submitted:true},{id:'m2',name:'砂 中砂',unit:'m³',quantity:10,date:'2026-11-03',nodeId:'n1',submitted:false}];
 p.supplies=[{id:'A001',materialId:'m1',quantity:60,supplier:'本地建材服务点',person:'陈师傅',date:'2026-11-03',unitPrice:30,payer:'',basis:null,status:'待完善'}];
 const w=project({id:'demo-wang',name:'王先生 · 麻城乡墅',customer:'王先生',city:'麻城',area:320,sample:true,accepted:'2026-10-20',image:asset('site'),actualStart:{date:'2026-10-25',time:'08:00',person:'李经理'},planSaved:true});w.plan.nodes[0].status='施工中';
 const l=project({id:'demo-lin',name:'林女士 · 武穴乡墅',customer:'林女士',city:'武穴',area:350,sample:true,accepted:'2026-10-01',image:asset('house-lin'),actualStart:{date:'2026-10-03',time:'08:00',person:'李经理'},planSaved:true});l.plan.nodes.forEach(n=>n.status='已完成');
 p.contractTotal=flow.contractYuan;p.plan.compiledAt=flow.timeline.planCompiled;p.planHistory=[];w.projectId='project-wang';l.projectId=D.projects.lin.id;return {version:1,projects:[p,w,l]};
}

module.exports=initial;Object.assign(module.exports,{project,asset,sampleFile});
