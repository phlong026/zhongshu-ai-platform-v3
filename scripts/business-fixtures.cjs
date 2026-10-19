const B=require('../miniprogram/utils/business');
const demoFile=name=>({name,sample:true});
function stateAt(stage=0){
 const state=B.initial(),c=state.customers[0];c.owner='我';
 if(stage>=1){c.status='已到店';c.summary='现代简约，两层乡墅，一楼老人房，客餐厅通透。';c.follows=[{method:'到店',result:'已到店',content:'已确认建房尺寸与家庭需求，下一步一起查看参考方案。',appointment:'15:00',date:'2026-10-18'}];}
 if(stage>=2){c.status='跟进中';c.plans=[{version:1,width:'12.5',depth:'16',floors:'2',id:'plan-chen-v1',images:['/pages/workflow/assets/biz-plan-1.jpg','/pages/workflow/assets/biz-plan-2.jpg'],date:'2026-10-18',sample:true}];}
 if(stage>=3)c.budgets=[{version:1,total:600000,main:480000,exterior:120000,area:400,planVersion:1,date:'2026-10-18'}];
 if(stage>=4){c.measurementFile=demoFile('正式测量资料.pdf');c.measurements=[{version:1,width:'12.5',depth:'16',floors:'2',area:400,date:'2026-10-19',fileId:'measurement-chen-v1',file:c.measurementFile}];c.quotes=[{version:1,total:680000,unitPrice:1050,main:420000,exterior:160000,other:100000,area:400,measurementVersion:1,date:'2026-10-19'}];}
 if(stage>=5){c.signedFile=demoFile('建造合同_双方已签.pdf');c.contract={version:1,total:680000,quoteVersion:1,date:'2026-10-20',file:c.signedFile};}
 if(stage>=6)c.request={version:1,text:'现代简约立面；一层老人房、客餐厅与厨房；二层三间卧室，保留南向采光。',date:'2026-10-21'};
 if(stage>=7)c.firstPayment={amount:6000,date:'2026-10-22',transaction:'DEMO-FIRST',file:demoFile('首款凭证.pdf')};
 if(stage>=8)c.intake={date:'2026-10-23'};
 if(stage>=9)c.assignment={designer:'林设计师',date:'2026-10-25'};
 if(stage>=10){c.deliverableFile=demoFile('正式设计成果_v1.pdf');c.deliverables=[{version:1,date:'2026-10-25',file:c.deliverableFile}];c.tailApplication={version:1,date:'2026-10-25'};}
 if(stage>=11)c.tailPayment={amount:6000,date:'2026-10-26',transaction:'DEMO-TAIL',file:demoFile('尾款凭证.pdf')};
 if(stage>=12)c.release={version:1,date:'2026-10-27'};
 if(stage>=13){c.confirmation={version:1,date:'2026-10-27'};c.drawingFile=demoFile('施工图_v1.pdf');c.drawings=[{version:'v1',date:'2026-10-27',file:c.drawingFile}];c.copies=[{version:'v1',date:'2026-10-28',status:'simulated',recipient:c.name}];}
 if(stage>=14)c.handover={version:1,date:'2026-10-29',manager:'李经理',note:'按补传施工图 v1 交底；核对宅基地南侧高差及一楼老人房门洞位置。',drawingVersion:'v1',releaseVersion:1,status:'pending'};
 if(stage>=15)c.acceptance={manager:'李经理',date:'2026-10-30',stage:'待开工'};
 return state;
}
const targets=[
 ['01_登记客户.jpg','customers',0,'register',{name:'陈先生',phone:'138****0628',summary:'已有宅基地，计划建两层乡墅，想先了解方案与费用。'}],
 ['02_保存后的客户工作区.jpg','customer',0],['03_记录首次跟进.jpg','customer',0,'follow',{followContent:'已介绍服务，客户计划建两层乡墅，一楼需要老人房，约明日下午到店沟通。',appointment:'明日15:00'}],['03B_到店沟通完成.jpg','arrival',1],
 ['04A_灵感库_案例推荐.jpg','inspiration',1],['04B_案例详情_立面效果.jpg','case',1],['04C_按尺寸检索平面.jpg','search',1],['04D_案例详情_平面参考.jpg','case-plan',1],['05A_AI 平面设计_配置.jpg','ai-config',1],['05B_AI 平面设计_选用保存.jpg','ai-results',1],['06_已选用平面方案结果.jpg','result',2],['07_预算工作台：核定参数.jpg','budget',2],['08_预算工作台：参考结果.jpg','budget-result',3],['09_方案与预算回存客户.jpg','progress',3],['10_测量报价：实测资料.jpg','measurement',3,null,{measurementDate:'2026-10-19',measurementFile:demoFile('正式测量资料.pdf')}],['11_测量报价：正式报价.jpg','quote',4],['12_合同签署登记：施工图后补.jpg','contract',5],['13_电子设计需求：施工图可后补.jpg','request',5],['14_总部设计首款办理.jpg','payment-first',6,null,{paymentDate:'2026-10-22'}],['15A_总部资料受理.jpg','intake',7],['15B_同页安排设计师.jpg','assign',8,'assign',{dueDate:'2026-10-25'}],['16_设计师提交成果.jpg','deliverable',9,null,{deliverableFile:demoFile('正式设计成果_v1.pdf')}],['17_设计尾款财务办理.jpg','payment-tail',10,null,{paymentDate:'2026-10-26'}],['18A_总部正式下发.jpg','release',11],['18B_项目资料：补传施工图.jpg','documents',12,null,{drawingFile:demoFile('施工图_v1.pdf'),uploadDate:'2026-10-27'}],['19_施工图补图抄送记录.jpg','copies',13],['20_补图后的工程移交.jpg','handover',13],['21_补图后的项目经理接收.jpg','receive',14,null,{receiveContract:true,receiveDrawing:true,receiveBrief:true,receiveDate:'2026-10-30'}],
 ['01B_公有客资分配.jpg','allocation',0],['02B_责任转交.jpg','transfer',5],['05C_立面设计配置.jpg','facade-config',1],['05D_立面候选选用.jpg','facade-candidates',1],['06B_立面方案结果.jpg','facade-result',2],['12B_客户收款备案.jpg','funds',5],['15C_业务员正式设计进度.jpg','formal-progress',9],['16B_业务员申请设计尾款.jpg','tail-apply',10]
];
module.exports={stateAt,targets,demoFile};
