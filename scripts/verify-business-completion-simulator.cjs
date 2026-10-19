const fs=require('fs'),assert=require('assert/strict'),a=require('miniprogram-automator');
const {stateAt,demoFile}=require('./business-fixtures.cjs');
const out='qa/business-completion-20261018';fs.mkdirSync(out+'/screenshots',{recursive:true});const captures=[];
const wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{const mp=await a.connect({wsEndpoint:process.env.WECHAT_AUTOMATION_ENDPOINT||'ws://127.0.0.1:9420'});const errors=[],checks=[];mp.on('exception',e=>errors.push(e));const previous=await mp.evaluate(()=>wx.getStorageSync('zhongshu-business-v3'));fs.writeFileSync(out+'/before-run.json',JSON.stringify(previous,null,2));let id='customer-chen',currentStep=0,base=null,startValues=null,startChecks=null,changeValues=null;
const retry=async fn=>{for(let attempt=0;attempt<3;attempt++){try{return await fn();}catch(e){if(!/timeout waiting/.test(e.message)||attempt===2)throw e;console.log('RETRY transient RPC read');await wait(350);}}};
const data=async key=>retry(()=>mp.evaluate(key=>getCurrentPages().slice(-1)[0].data[key],key));const invoke=async(method,...args)=>mp.evaluate(({method,args})=>getCurrentPages().slice(-1)[0][method](...args),{method,args});
const open=async (view,record='')=>{console.log('OPEN '+view);await mp.callWxMethod('reLaunch',{url:'/pages/workflow/index?view='+view+'&id='+id+(record?'&record='+record:'')});await wait(1300);};
const fill=async values=>mp.evaluate(values=>{const p=getCurrentPages().slice(-1)[0];for(const[key,value]of Object.entries(values))p.input({currentTarget:{dataset:{key}},detail:{value}});},values);
const tap=async selector=>{let el;for(let i=0;i<35;i++){const p=await retry(()=>mp.currentPage());el=await retry(()=>p.$(selector));if(el)break;await wait(200);}assert(el,'Missing rendered control: '+selector);await el.tap();await wait(1400);};
const fillNative=async values=>{const p=await retry(()=>mp.currentPage());for(const[key,value]of Object.entries(values)){console.log('FILL '+key);const el=await retry(()=>p.$('[data-key="'+key+'"]'));assert(el,'Native input '+key);await el.input(value);await wait(80);}};
const capture=async name=>{await mp.pageScrollTo(0);await wait(250);const file=name+'.png';await mp.screenshot({path:out+'/screenshots/'+file});const title=await data('title'),view=await data('view');await mp.pageScrollTo(4000);await wait(150);const scroll=await(await mp.currentPage()).scrollTop();if(scroll>40)await mp.screenshot({path:out+'/screenshots/'+name+'-bottom.png'});await mp.pageScrollTo(0);captures.push({file,view,title,scroll});fs.writeFileSync(out+'/capture.json',JSON.stringify(captures,null,2));console.log('CAPTURE '+name);};
const state=()=>mp.evaluate(()=>wx.getStorageSync('zhongshu-business-v3'));const c=async()=>(await state()).customers.find(x=>x.id===id);
const pass=async name=>{checks.push({name,result:'passed'});const checkpoint={nextStep:currentStep+1,id,state:await state(),captures,checks,shared:{base,startValues,startChecks,changeValues}};fs.writeFileSync(out+'/checkpoint.json',JSON.stringify(checkpoint,null,2));fs.writeFileSync(out+'/simulator-progress.json',JSON.stringify({checks,errors},null,2));console.log('PASS '+name);};
const resume=process.env.RESUME_BUSINESS==='1'?JSON.parse(fs.readFileSync(out+'/checkpoint.json','utf8')):null;
const step=async(index,fn)=>{if(resume&&index<resume.nextStep)return;currentStep=index;await fn();};
try{
 if(resume){id=resume.id;checks.push(...resume.checks);captures.push(...resume.captures);({base,startValues,startChecks,changeValues}=resume.shared||{});}await mp.evaluate(s=>wx.setStorageSync('zhongshu-business-v3',s),resume?resume.state:stateAt(0));
 await step(0,async()=>{
 await open('customers');await tap('[data-modal="register"]');await tap('.sheet-save');assert(await data('error'));
 for(const [key,value]of Object.entries({name:'模拟器回归客户',phone:'13812340628',summary:'计划建设二层乡墅'})){const input=await (await mp.currentPage()).$('.sheet [data-key="'+key+'"]');assert(input);await input.input(value);}
 await tap('.sheet-save');await wait(1200);id=(await data('customer')).id;assert.equal((await c()).name,'模拟器回归客户');await pass('原生登记表单输入、必填校验、保存并打开新客户');
 });
 await step(1,async()=>{

 await tap('[data-modal="follow"]');await fill({followContent:'电话确认需求，下次到店',appointment:'2026-10-19 15:00'});await tap('.sheet-save');assert.equal((await c()).follows.length,1);assert.equal((await c()).status,'跟进中');await pass('记录跟进并保留未完成预约');
 });
 await step(2,async()=>{

 await open('ai-config');await tap('.bottom-inline');await tap('.sheet-save');await wait(500);assert.equal(await data('view'),'ai-results');await tap('.footer .primary-action');assert.equal((await c()).plans.length,1);await pass('平面示例生成确认、候选选用及方案保存');
 });
 await step(3,async()=>{

 await open('facade-config');await tap('.bottom-inline');await tap('.sheet-save');await wait(500);assert.equal(await data('view'),'facade-candidates');await tap('.footer .primary-action');assert.equal((await c()).facades.length,1);await pass('立面配置、候选选用、结果保存');
 });
 await step(4,async()=>{

 await open('budget');await tap('.bottom-inline');assert.equal((await c()).budgets.length,1);await tap('.footer .primary-action');assert.equal(await data('view'),'progress');await pass('预算测算与回存客户进度');
 });
 await step(5,async()=>{

 await open('measurement');await tap('.footer .primary-action');assert(await data('error'));await invoke('setFile','measurementFile',demoFile('实测资料.pdf'));await tap('.footer .primary-action');assert.equal((await c()).quotes[0].total,680000);await pass('实测缺件阻断及有效报价生成');
 });
 await step(6,async()=>{

 await tap('.footer .primary-action');await invoke('setFile','signedFile',demoFile('已签合同.pdf'));await tap('[data-key="verified"]');await tap('.footer .primary-action');assert((await c()).contract);await pass('合同签署登记与报价版本关联');
 });
 await step(7,async()=>{

 await open('funds');await tap('.footer .primary-action');await fill({receiptAmount:'5000',receiptReceiver:'张敏',receiptTransaction:'SIM-RECEIPT'});await invoke('setFile','receiptFile',demoFile('收款凭证.jpg'));await tap('.sheet-save');assert.equal((await c()).receipts.length,1);await pass('客户收款备案独立保存');
 });
 await step(8,async()=>{

 await open('request');await tap('.footer .primary-action');assert((await c()).request);assert.equal((await c()).drawings.length,0);await pass('施工图后补不阻断电子设计需求');
 });
 await step(9,async()=>{

 await open('service-prepare');await fill({serviceTotal:'12000',serviceBasis:'双方约定平面、立面与配套设计',serviceVerifier:'财务专员',serviceChecked:true});await invoke('setFile','serviceFile',demoFile('设计服务约定.pdf'));await capture('01-service-prepare');await tap('.footer .primary-action');assert.equal((await c()).serviceOrders[0].first,6000);await capture('02-service-order');await pass('设计服务费核定后关联首尾款节点');
 });
 await step(10,async()=>{

 await open('payment-first');await fill({amount:'6000',transaction:'SIM-FIRST'});await invoke('setFile','paymentFile',demoFile('首款凭证.pdf'));await tap('[data-key="verified"]');await tap('.footer .primary-action');assert((await c()).firstPayment);await pass('总部设计首款线下付款登记');
 });
 await step(11,async()=>{

 await tap('[data-key="intakeChecked"]');await tap('.footer .primary-action');assert.equal(await data('modal'),'assign');await tap('.sheet-save');assert((await c()).assignment);await pass('总部受理与同页安排设计师');
 });
 await step(12,async()=>{

 await invoke('setFile','deliverableFile',demoFile('正式成果.pdf'));await tap('.footer .primary-action');assert.equal((await c()).deliverables.length,1);await open('formal-progress');assert(await (await mp.currentPage()).$('.timeline'));await pass('设计成果提交及业务员查看正式设计进度');
 });
 await step(13,async()=>{

 await open('tail-apply');await tap('.footer .primary-action');assert((await c()).tailApplication);await open('payment-tail');await fill({amount:'6000',transaction:'SIM-TAIL',verified:true});await invoke('setFile','paymentFile',demoFile('尾款凭证.pdf'));await tap('.footer .primary-action');assert((await c()).tailPayment);await pass('业务尾款申请进入财务办理');
 });
 await step(14,async()=>{

 await tap('.footer .primary-action');assert((await c()).release);assert.equal((await c()).confirmation,null);await invoke('setFile','confirmationFile',demoFile('客户确认.pdf'));await tap('[data-key="confirmationChecked"]');await tap('.footer .primary-action');assert((await c()).confirmation);await pass('总部正式下发、客户确认分步办理');
 });
 await step(15,async()=>{

 await open('documents');const signed=(await c()).signedFile;await invoke('setFile','drawingFile',demoFile('施工图v1.pdf'));await tap('.footer .primary-action');assert.equal((await c()).copies.length,1);assert.deepEqual((await c()).signedFile,signed);await pass('施工图补传及模拟抄送保留签约附件');
 });
 await step(16,async()=>{


 await open('review-submit');await fillNative({reviewer:'施工图专业审核员',reviewSubmitNote:'核对建筑及配套节点，绑定当前施工图版本。'});await tap('[data-key="reviewReady"]');await capture('03-review-submit');await tap('.primary-action');assert.equal((await c()).reviews[0].status,'pending');await capture('04-drawing-review-pending');
 await tap('.primary-action');await tap('[data-key="reviewResult"][data-value="退回修改"]');await fillNative({reviewOpinion:'补充露台排水与门洞节点详图后再送审。'});await invoke('setFile','reviewFile',demoFile('专业审核退回记录.pdf'));await tap('[data-key="reviewChecked"]');await capture('05-review-record');await tap('.primary-action');assert.equal((await c()).reviews[0].status,'returned');await capture('06-drawing-review-returned');await pass('施工图送审、专业审核退回及原因留存');
 });
 await step(17,async()=>{

 await tap('.primary-action');await fillNative({drawingVersion:'v2'});await invoke('setFile','drawingFile',demoFile('施工图修订v2.pdf'));await tap('.primary-action');await open('review-submit');assert.equal((await data('form')).reviewReady,false);await fillNative({reviewer:'施工图专业审核员',reviewSubmitNote:'修订版v2已补充门洞和排水节点。'});await tap('[data-key="reviewReady"]');await tap('.primary-action');await tap('.primary-action');assert.equal((await data('form')).reviewChecked,false);await fillNative({reviewOpinion:'修订节点已核对，本次专业审核通过。'});await invoke('setFile','reviewFile',demoFile('专业审核通过记录_v2.pdf'));await tap('[data-key="reviewChecked"]');await tap('.primary-action');assert.equal((await c()).reviews[1].status,'passed');await capture('07-drawing-review-passed');await pass('新版本重新送审，旧审核意见和附件保持留存');
 });
 await step(18,async()=>{


 await open('handover');await tap('.footer .primary-action');assert((await c()).handover);for(const key of ['receiveContract','receiveDrawing','receiveBrief'])await tap('[data-key="'+key+'"]');await tap('.footer .primary-action');assert.equal((await c()).acceptance.stage,'待开工');await pass('工程移交、项目经理逐项核对和接收回执');
 });
 await step(19,async()=>{


 startValues={startChecker:'李经理',startNote:'现场场地、施工人员、图纸与约定节点均已逐项核对。'};
 startChecks=['startSiteChecked','startTeamChecked','startDrawingChecked','startMilestoneChecked','startBriefChecked'];
 await tap('.primary-action');assert.equal(await data('view'),'start-check');await tap('.primary-action');assert(await data('error'));await fillNative(startValues);await invoke('setFile','startFile',demoFile('现场开工条件核查.pdf'));for(const key of startChecks)await tap('[data-key="'+key+'"]');await capture('08-start-check');await tap('.primary-action');assert.equal(await data('view'),'start-receipt');assert.equal((await c()).startChecks.length,1);assert.equal((await c()).acceptance.stage,'待开工');await capture('09-start-receipt');await pass('本版图纸审核、工程接收后逐项核查开工条件，保存回执仍待开工');
 });
 await step(20,async()=>{

 base=JSON.parse(JSON.stringify({contract:(await c()).contract,signedFile:(await c()).signedFile,acceptance:(await c()).acceptance,confirmation:(await c()).confirmation}));
 await open('change-list');await tap('.primary-action');await tap('.primary-action');assert(await data('error'));changeValues={changeReason:'客户提出调整露台与局部节点',changeScope:'增加露台防水排水节点；合同增加5000元，工期增加3天。',changeDelta:'5000',changeDays:'3',changeApplicant:'业务员张敏'};await fillNative(changeValues);await invoke('setFile','changeFile',demoFile('客户变更申请.pdf'));await tap('[data-key="changeChecked"]');await capture('10-change-new');await tap('.primary-action');assert.equal((await c()).changes[0].status,'submitted');await capture('11-change-detail');await open('start-receipt');assert(await data('blocked'));await pass('变更申请冻结原合同、原接收与图纸基准，并使原开工核查失效');
 });
 await step(21,async()=>{

 await open('change-approval');await tap('[data-key="changeDecision"][data-value="退回补充"]');await fillNative({changeApprover:'项目负责人',changeOpinion:'请补齐露台节点与工期影响说明。'});await invoke('setFile','changeApprovalFile',demoFile('变更退回依据.pdf'));await tap('[data-key="changeApprovalChecked"]');await tap('.primary-action');assert.equal((await c()).changes[0].status,'returned');await pass('设计变更审批可退回，原因和原申请保留');
 });
 await step(22,async()=>{

 await open('change-list');await tap('.primary-action');assert.equal((await data('form')).changeChecked,false);await fillNative({...changeValues,changeReason:'补全节点说明后重新提出变更'});await invoke('setFile','changeFile',demoFile('补全后的变更申请.pdf'));await tap('[data-key="changeChecked"]');await tap('.primary-action');assert.equal((await c()).changes.length,2);await tap('.primary-action');assert.equal((await data('form')).changeApprovalChecked,false);await fillNative({changeApprover:'项目负责人',changeOpinion:'范围、金额和工期影响已核对，同意按本次申请办理。'});await invoke('setFile','changeApprovalFile',demoFile('变更批准依据.pdf'));await tap('[data-key="changeApprovalChecked"]');await capture('12-change-approval');await tap('.primary-action');assert.equal((await c()).changes[1].status,'approved');await pass('重新申请生成独立变更版本并完成审批');
 });
 await step(23,async()=>{

 await tap('.primary-action');assert.equal(await data('view'),'amendment');await fillNative({agreementNo:'BC-SIM-002'});await invoke('setFile','agreementFile',demoFile('双方已签补充协议.pdf'));await tap('[data-key="agreementChecked"]');await capture('13-amendment');await tap('.primary-action');assert.equal((await c()).agreements[0].total,685000);assert.deepEqual((await c()).contract,base.contract);await open('amendment');await capture('14-amendment-signed');await pass('补充协议双方签署独立归档，原合同保持680000元');
 });
 await step(24,async()=>{

 await open('change-confirmation');await invoke('setFile','changeResultFile',demoFile('变更正式成果.pdf'));await invoke('setFile','changeConfirmationFile',demoFile('客户确认变更成果.pdf'));await fillNative({changeConfirmationNote:'客户已核对露台节点、补充协议金额及工期。'});await tap('[data-key="changeConfirmationChecked"]');await capture('15-change-confirmation');await tap('.primary-action');assert.equal((await c()).changes[1].status,'confirmed');assert.deepEqual((await c()).confirmation,base.confirmation);await fillNative({drawingVersion:'v3'});await invoke('setFile','drawingFile',demoFile('变更施工图_v3.pdf'));await tap('.primary-action');assert.equal((await c()).drawings[2].changeId,'change-2');await pass('变更成果重新确认并补传关联新图，原成果客户确认保留');
 });
 await step(25,async()=>{

 await open('review-submit');await fillNative({reviewer:'施工图专业审核员',reviewSubmitNote:'变更v3对应补充协议BC-SIM-002，核对露台节点。'});await tap('[data-key="reviewReady"]');await tap('.primary-action');await tap('.primary-action');await fillNative({reviewOpinion:'变更节点与本版图纸已核对，审核通过。'});await invoke('setFile','reviewFile',demoFile('变更v3专业审核通过.pdf'));await tap('[data-key="reviewChecked"]');await tap('.primary-action');assert.equal((await c()).reviews[2].status,'passed');
 await open('supplement-handover');await fillNative({supplementManager:'李经理',supplementNote:'核对补充协议、变更v3施工图、审核记录及露台交底。'});await capture('16-supplement-handover');await tap('.primary-action');assert.equal((await c()).supplements.length,1);assert.equal(await data('view'),'supplement-receive');await tap('.primary-action');assert(await data('error'));for(const key of ['supplementDrawingChecked','supplementAgreementChecked','supplementBriefChecked'])await tap('[data-key="'+key+'"]');await capture('17-supplement-receive');await tap('.primary-action');assert.equal((await c()).changes[1].status,'completed');assert.deepEqual((await c()).acceptance,base.acceptance);assert.deepEqual((await c()).signedFile,base.signedFile);await pass('变更图纸重新审核、补充交底和接收，原工程接收回执不覆盖');
 });
 await step(26,async()=>{

 assert.equal(await data('view'),'start-check');assert.equal((await data('form')).startDrawingChecked,false);await fillNative({...startValues,startNote:'重新核对变更v3施工图、补充协议及现场条件。'});await invoke('setFile','startFile',demoFile('变更后重新核查开工条件.pdf'));for(const key of startChecks)await tap('[data-key="'+key+'"]');await tap('.primary-action');assert.equal((await c()).startChecks.length,2);assert.equal((await c()).startChecks[1].drawingVersion,'v3');await capture('18-start-receipt-after-change');await pass('变更完成后按新版本重新核查，原核查历史保留');
 });
 await step(27,async()=>{

 await open('change-list');await capture('19-change-list');await tap('[data-record="change-1"]');assert.equal(await data('badge'),'已退回');assert.equal(await mp.evaluate(()=>getCurrentPages().slice(-1)[0].options.record),'change-1');await capture('20-historical-change');await pass('变更历史入口打开原退回单，不串到当前变更');
 });
 await step(28,async()=>{

 await open('hub');await capture('21-project-hub');
 fs.writeFileSync(out+'/completed-business-state.json',JSON.stringify(await c(),null,2));

 await open('customer');await tap('[data-index="1"]');assert(await (await mp.currentPage()).$('.follow-record'));await open('customers');const search=await (await mp.currentPage()).$('.search input');await search.input('不存在的客户');await wait(300);assert.equal((await data('filteredCustomers')).length,0);await pass('客户跟进标签、搜索无结果状态');
 });
 assert.equal(errors.length,0);fs.writeFileSync(out+'/simulator-results.json',JSON.stringify({result:'passed',runAt:new Date().toISOString(),system:await mp.systemInfo(),checks,errors},null,2));
}catch(error){console.log('FAIL',error.message);fs.writeFileSync(out+'/failed-results.json',JSON.stringify({result:'failed',message:error.message,checks,errors},null,2));try{console.log('FAIL VIEW',await data('view'),'ERROR',await data('error'));await mp.screenshot({path:out+'/failed-interaction.png'});}catch(captureError){console.log('Capture unavailable:',captureError.message);}throw error;}finally{await mp.evaluate(value=>{if(value)wx.setStorageSync('zhongshu-business-v3',value);else wx.removeStorageSync('zhongshu-business-v3');},previous);fs.writeFileSync(out+'/restored.json',JSON.stringify({restored:true,at:new Date().toISOString()}));await mp.callWxMethod('reLaunch',{url:'/pages/business/index'});await mp.disconnect();}
})().catch(e=>{console.error(e);process.exit(1)});
