const R=require('../../../utils/engineering-records'),X=require('../../../utils/access'),O=require('../../../utils/organization'),Files=require('../../../utils/sample-files'),SI=require('../schedule-import');
const P=require('../../../utils/platform-engineering'); // 工程域纯函数+在线编排（阶段 2）
const Nav=require('../../../utils/page-navigation');
const money=require('../../../utils/money').money;
const {makeFormHandlers}=require('../../../utils/view-container');
const baseHandlers=makeFormHandlers();
const titles={overview:'工程项目',prepare:'开工准备',start:'实际开工记录',collection:'工程收款',project:'项目与施工计划',materials:'材料计划',material:'材料计划详情',node:'施工节点记录',funding:'节点资金计划',payments:'项目款项',documents:'项目资料',delivery:'工程交付',settlement:'项目结算',archive:'交付档案'};
const field=(key,label,type='text')=>({key,label,type});
Page({
 ...baseHandlers,
 // M07-T02：在线 prepare 三期金额输入即校验（四项金额齐后 Σ≠总额即时报差额；无保存入口也保持口径提示可达——R2 P2）
 input(e){baseHandlers.input.call(this,e);if(this.data.view==='prepare'&&this.data.platEng&&['total','signAfter','baseDone','capDone'].every(k=>String(this.data.form[k]==null?'':this.data.form[k]).trim()!=='')){const err=P.threeStageSumError(this.data.form);if(err)this.setData({error:err.msg});}},
 data:{view:'overview',title:'工程项目',p:null,form:{},fields:[],actions:[],pending:[],waiting:[],plans:[],error:'',busy:false,showPlan:false,samplePreview:null},
 onLoad(o){this.id=o.id;this.planId=o.planId||'';this.nodeId=o.nodeId||'';this.stage=o.stage==='topping'?'topping':o.stage==='base'?'base':'start';this.setData({view:titles[o.view]?o.view:'overview'});this.refresh(true);},
 onShow(){if(this.id)this.refresh();
  this.setData && this.setData({ showDemoBadge: require('../../../utils/demo-badge').isDemoMode(this) });
  },
 refresh(reset=false){if(P.online()&&!this.__platDown)return this.refreshOnline(reset);try{
  const p=R.get(this.id);if(!p)throw Error('项目不存在或当前身份无权查看');const a=X.context(),own=R.owner(p),canPay=O.has(a.role,'finance'),finance=canPay||X.isBoss(a),view=this.data.view,ar=R.funds(p),tasks=R.tasks(p),plans=R.plans(p).map(r=>({...r,totalText:r.known?money(r.total):'待记录',paidText:money(r.paid),remainingText:r.known?money(r.remaining):'待记录',url:R.link(p,'material',{planId:r.id}),lines:r.lines.map(l=>({...l,totalText:money(l.total),paidText:money(l.paid)}))})),plan=plans.find(r=>r.id===this.planId),node=p.plan.nodes.find(n=>n.id===(plan?.nodeId||this.nodeId))||p.plan.nodes.find(n=>n.status!=='已完成')||p.plan.nodes.at(-1),engineers=(X.rawBook(p.companyId)?.staff||[]).filter(s=>s.active!==false&&O.staffHas(s,'engineer')).map(s=>({id:s.id,name:s.name}));
  const part=ar.installments.find(r=>r.stage===this.stage),contract=p.recordFlow.contract;let fields=[],actions=[],form=this.data.form;
  if(reset||!this.initialized){form={date:require('../../../utils/account').localDate(),note:'',file:null,files:[],nodeId:node?.id,engineerId:X.managerId(p),nodes:p.plan.nodes.map(n=>({...n})),lines:[{name:'',spec:'',quantity:'',unit:'包'}],due:require('../../../utils/account').localDate(),stage:this.stage,receiver:'本公司',transaction:'',amount:part?String(part.remaining/100):'',total:contract?String(contract.total/100):'',first:contract?String(contract.first/100):'',recipient:'',noMaterials:false,adjustReason:'',correctId:'',correctActual:'',correctReason:'',siteId:'',siteNote:'',siteFile:null,siteReason:'',nodeDate:node?.completedAt||require('../../../utils/account').localDate(),nodeNote:'',nodeFile:null,nodeReason:''};if(view==='node'&&node?.status==='已完成'){const done=p.siteRecords.filter(r=>r.nodeId===node.id&&r.completion).at(-1),check=p.inspections.filter(r=>r.nodeId===node.id).at(-1);Object.assign(form,{nodeNote:done?.note||'',nodeFile:check?.file||done?.file||null});}if(view==='prepare')Object.assign(form,{note:p.recordFlow.prepared?.note||'',file:contract?.file||null});if(view==='material'&&plan)Object.assign(form,{planId:plan.id,supplier:'',termsFile:p.supplies.find(s=>s.planId===plan.id)?.basis||null,lines:plan.lines.map(l=>({id:l.id,name:l.name,quantity:l.quantity,unit:l.unit,supplier:l.supplier,unitPrice:l.unitPrice?String(l.unitPrice):'',freight:String(l.freight||0),actual:'',adjust:'',received:l.received,cancelled:l.cancelled,locked:l.locked}))});this.initialized=true;}
  if(view==='funding'&&node){const nf=R.nodeFunds(p,node.id);if(reset||!this.fundingInitialized){form={...form,nodeId:node.id,amount:nf.planned===null?'':String(nf.planned/100),due:nf.funding?.due||node.start,note:nf.funding?.note||''};this.fundingInitialized=true;}fields=[field('amount','本节点预计用款（元）','digit'),field('due','计划用款日期','date'),field('note','用途或调整说明（选填）','textarea')];if(own&&!p.delivery)actions=[{action:'funding-plan',label:'保存本节点资金计划'}];}
  if(view==='prepare'){fields=[field('total','已签工程合同总额（元）','digit'),field('first','开工款（元，须小于合同总额；余款为封顶款）','digit'),field('file','双方已签合同','file'),field('note','现场、班组和开工准备记录','textarea')];if(own&&!p.actualStart)actions=[{action:'prepare',label:'保存开工准备'}];}
  if(view==='start'){fields=[field('date','实际开工日期','date'),field('note','现场情况','textarea'),field('file','开工凭据','file')];if(own&&!p.actualStart)actions=[{action:'start',label:'记录实际开工'}];}
  if(view==='collection'){const canCollect=O.has(a.role,'finance')&&a.orgType==='store'&&part?.remaining&&(this.stage==='start'||p.recordFlow.topping);fields=[field('amount','本次实际收款金额（元）','digit'),field('date','收款日期','date'),field('receiver','实际收款方'),field('transaction','收款流水或凭据编号'),field('file','实际收款凭据','file')];if(canCollect)actions=[{action:'collect',label:'登记实际收款'}];else{fields=[field('promiseDate','业主承诺付款日（选填）','date'),field('note','催收情况说明（选填）','textarea')];if(own&&part?.remaining&&(this.stage==='start'||p.recordFlow.topping))actions=[{action:'dunning',label:'登记已催收'}];}}
  if(view==='project'){fields=[field('note','调整或补充说明','textarea')];if(own&&!p.archived)actions=[{action:'project',label:'保存原项目记录'}];}
  if(view==='materials'){fields=[field('due','需到场日期','date'),field('note','材料说明（选填）','textarea')];if(own&&!p.delivery&&(!this.nodeId||node?.status!=='已完成'))actions=[{action:'material-plan',label:'提交材料计划'}];}
  if(view==='material'&&plan&&!['已验收齐全','已取消'].includes(plan.arrival)){fields=[field('date','本次实际到货日期','date'),field('promiseDate','供应商承诺到货日（催办时选填）','date'),field('note','到货说明或本次催到货联系说明（催办必填）','textarea'),field('file','到货照片或送货凭据（确认到货时必填）','file')];if(own&&!p.delivery&&plan.arrival!=='已验收齐全')actions.push({action:'arrival',label:'确认本次到货'},{action:'material-followup',label:'登记今日已催到货'});}
  if(view==='node'){fields=[field('date','记录日期','date'),field('note','施工情况与验收记录','textarea'),field('file','现场及验收凭据','file')];if(!p.materials.some(m=>m.nodeId===node?.id&&m.quantity>0))fields.push(field('noMaterials','本节点无需新增材料，现场材料已核对','check'));if(own&&node?.status!=='已完成'&&!p.delivery)actions=[{action:'node',label:node?.milestone==='topping'?'记录主体封顶完成':'记录本节点完成'},{action:'site',label:'先保存现场记录'}];}
  if(view==='node'&&node?.status==='已完成'||view==='funding'&&p.delivery)fields=[];
  if(view==='delivery'){fields=[field('date','实际交付日期','date'),field('recipient','业主或授权签收人'),field('note','交付记录（选填）','textarea'),field('file','业主签收凭据','file')];if(own&&!p.delivery)actions=[{action:'deliver',label:'记录工程交付'}];}
  if(view==='settlement'){fields=[field('date','结算确认日期','date'),field('note','结算说明（收付款与成本核对情况）','textarea'),field('file','结算确认凭据（选填）','file')];if(own&&p.delivery&&!p.settlement)actions=[{action:'settle',label:'确认项目结算'}];}
  if(view==='archive'&&own&&p.delivery&&p.settlement&&!p.archived)actions=[{action:'archive',label:'归档项目资料'}];
  const links=[['项目记录与计划','project'],['项目资料','documents'],['材料计划','materials'],['项目收付款','payments'],['交付与归档',p.delivery?'archive':'delivery'],...(p.delivery?[['项目结算','settlement']]:[])].map(([label,v])=>({label,url:R.link(p,v)}));
  const installments=['start','topping'].map(stage=>{const row=ar.installments.find(i=>i.stage===stage),expected=row?.amount??(stage==='start'?contract?.first:contract?contract.total-contract.first:0),confirmed=row?.paid||0,pending=p.collections.filter(r=>r.receiptStage===stage&&!['已核算','已备案','退回补充'].includes(r.status)).reduce((n,r)=>n+r.cents,0);return {stage,name:stage==='start'?'开工款':'封顶款',expected:contract?money(expected):'待约定',confirmed:money(confirmed),pending:money(pending),remaining:contract?money(Math.max(0,expected-confirmed)):'待约定',status:!contract?'待记录合同':!ar.plan?'收款约定待生成':confirmed>=expected?'已收齐':pending?'历史收款待线下核对':'待财务登记收款',url:R.link(p,'collection',{stage})};});
  const docs=p.documents.map(d=>({name:d.name,file:Files.displayFile(d.file)}));if(contract)docs.push({name:'已签建造合同',file:Files.displayFile(contract.file)});
  const nodeMoney=node?R.nodeFunds(p,node.id):null,nodePlans=plans.filter(r=>r.nodeId===node?.id),paceRows=R.progress(p).rows,nodeRows=p.plan.nodes.map(n=>({...n,url:R.link(p,'node',{nodeId:n.id}),pace:paceRows.find(r=>r.id===n.id)?.text||''})),paceDone=p.plan.nodes.filter(n=>n.status==='已完成').length,paceTotal=p.plan.nodes.length;
  const current=p.plan.nodes.find(n=>n.status!=='已完成'),next=tasks.pending[0]||tasks.waiting[0];this.setData({p,nodeId:this.nodeId,title:titles[view],form,fileNames:Files.displayNames(form),fields,actions,pending:tasks.pending,waiting:tasks.waiting,own,canPay,finance,demo:a.demo&&!Files.release(),templates:R.scheduleTemplates(p.companyId),templateIndex:Math.max(0,(this.data.templates||R.scheduleTemplates(p.companyId)).findIndex(t=>t.id===this.data.selectedTemplateId)),paceDone,paceTotal,paceOverdue:R.progress(p).overdue,plans:this.nodeId&&view==='materials'?nodePlans:plans,nodePlans,nodeRows,nodeMaterialUrl:node?R.link(p,'materials',{nodeId:node.id}):'',nodeFundingUrl:node?R.link(p,'funding',{nodeId:node.id}):'',nodeRecordUrl:node?R.link(p,'node',{nodeId:node.id}):'',nodeMoney:nodeMoney?{...nodeMoney,plannedText:nodeMoney.planned===null?'待记录':money(nodeMoney.planned),committedText:money(nodeMoney.committed),paidText:money(nodeMoney.paid),remainingText:money(nodeMoney.remaining),expenses:nodeMoney.expenses.map(e=>({...e,amountText:money(e.amount)}))}:null,inspections:p.inspections.filter(r=>r.nodeId===node?.id).map(r=>({...r,file:Files.displayFile(r.file)})),plan:plan||null,node:node||null,nodeIndex:Math.max(0,p.plan.nodes.findIndex(n=>n.id===form.nodeId)),arrivals:p.receipts.filter(r=>r.planId===this.planId).map(r=>({...r,file:Files.displayFile(r.file),materialName:p.materials.find(m=>p.supplies.some(s=>s.id===r.supplyId&&s.materialId===m.id))?.name||'材料',unit:p.materials.find(m=>p.supplies.some(s=>s.id===r.supplyId&&s.materialId===m.id))?.unit||'',canCorrect:own&&!p.delivery})),materialFollowups:(p.recordFlow.materialFollowups||[]).filter(r=>r.planId===this.planId).slice().reverse(),engineers,engineerIndex:Math.max(0,engineers.findIndex(s=>s.id===form.engineerId)),links,installments,docs,stage:this.stage,partLabel:this.stage==='start'?'开工款':'封顶款',canTerms:finance&&!!plan&&!['已付清','已取消'].includes(plan.payment),termsLocked:plan?.payment==='已付清',settlementUrl:R.link(p,'settlement'),settle:(()=>{const sum=R.settleSummary(p);return {materialsTotalText:money(sum.materialsTotal),materialsPaidText:money(sum.materialsPaid),materialsRemainingText:money(sum.materialsRemaining),expensesTotalText:money(sum.expensesTotal),expensesPaidText:money(sum.expensesPaid),expensesRemainingText:money(sum.expensesRemaining)};})(),stageText:p.archived?'已归档':p.delivery?'已交付':!p.actualStart?'开工准备':current?.name||'待交付',next:next||null,nodeUrl:current?R.link(p,'node',{nodeId:current.id}):R.link(p,'delivery'),events:p.events.slice(-20).reverse().map(r=>({...r,title:Files.displayText(r.title)})),dunning:(p.recordFlow.dunning||[]).filter(r=>r.stage===this.stage).slice().reverse(),collections:p.collections.map(r=>({...r,amountText:money(r.cents),url:'/pages/admin/index?view='+(r.status==='退回补充'?'receipt-supplement':'receipt')+'&id='+encodeURIComponent('engineering-receipt:'+p.id+':'+r.id)})),siteRecords:p.siteRecords.filter(r=>r.nodeId===node?.id).map(r=>{const files=(r.files&&r.files.length?r.files:r.file?[r.file]:[]).map(Files.displayFile);return {...r,file:files[0]||null,files,images:files.filter(f=>f.path&&/\.(png|jpe?g|webp|gif)$/i.test(f.name||'')),canCorrect:own&&!p.delivery&&!R.isCompletionRecord(p,r)};}).reverse(),canAdjust:own&&!p.delivery&&!!plan,canCorrectNode:own&&!p.delivery&&node?.status==='已完成',canReopen:own&&!p.delivery&&node?.status==='已完成'&&!R.reopenBlock(p,node.id),error:''});
 }catch(e){this.setData({p:null,error:e.message,actions:[]});}},
 jumpNodeSection(e){const selector=e.currentTarget.dataset.section==='inspection'?'.node-inspection':this.data.fields.length?'.record-fields':'.node-site-records',info=getApp().globalData.windowInfo||wx.getWindowInfo(),header=(info.statusBarHeight||20)+(info.windowWidth||375)*87/750+12;const q=wx.createSelectorQuery();q.select(selector).boundingClientRect();q.selectViewport().scrollOffset();q.exec(r=>{if(r[0])wx.pageScrollTo({scrollTop:Math.max(0,r[0].top+(r[1]?.scrollTop||0)-header),duration:150});});},
 togglePlan(){this.setData({showPlan:!this.data.showPlan});},
 toggle(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:!this.data.form[key]});},
 lineInput(e){const d=e.currentTarget.dataset;this.setData({['form.lines['+d.index+'].'+d.key]:e.detail.value,error:''});},
 nodeInput(e){const d=e.currentTarget.dataset;this.setData({['form.nodes['+d.index+'].'+d.key]:e.detail.value,error:''});},
 pickEngineer(e){const s=this.data.engineers[Number(e.detail.value)];if(s)this.setData({'form.engineerId':s.id,engineerIndex:Number(e.detail.value)});},
 pickNode(e){const nodeIndex=Number(e.detail.value),n=this.data.p.plan.nodes[nodeIndex];if(n)this.setData({'form.nodeId':n.id,nodeIndex});},
 pickTemplate(e){const i=Number(e.detail.value);if(this.data.templates[i])this.setData({templateIndex:i,selectedTemplateId:this.data.templates[i].id});},
 applyTemplate(e){try{const nodes=R.applyTemplate(this.data.p,this.data.templates[this.data.templateIndex]);this.setData({'form.nodes':nodes,error:''});wx.showToast({title:'已恢复标准节点清单，请逐项填写日期',icon:'none',duration:2500});}catch(err){this.fail(err.message);}},
 importPlan(){wx.chooseMessageFile({count:1,type:'file',extension:['xlsx'],success:r=>{try{const f=r.tempFiles[0],res=SI.importNodes(wx.getFileSystemManager().readFileSync(f.path));this.setData({'form.nodes':res.nodes,error:''});wx.showToast({title:res.notice||'已导入'+res.nodes.length+'个节点，请核对日期',icon:'none',duration:2500});}catch(err){this.fail(err.message);}}});},
 addLine(){this.setData({'form.lines':this.data.form.lines.concat({name:'',spec:'',quantity:'',unit:'包'})});},
 upload(e){const key=e.currentTarget.dataset.key||'file',multi=this.data.view==='node'&&key==='file',demo=!Files.release()&&X.context().demo,itemList=['拍照或从相册选择','选择聊天文件'];if(demo)itemList.push('使用参考凭据');wx.showActionSheet({itemList,success:r=>{if(r.tapIndex===2)return this.useSampleFile({currentTarget:{dataset:{sampleKey:key}}});if(r.tapIndex===0)return wx.chooseMedia({count:multi?9:1,mediaType:['image'],sourceType:['album','camera'],success:r=>{const files=r.tempFiles.map((f,i)=>({name:'现场照片-'+(i+1)+'.jpg',path:f.tempFilePath}));let saved=0;files.forEach(f=>wx.saveFile({tempFilePath:f.path,success:res=>{f.path=res.savedFilePath;if(++saved===files.length)this.setPhotos(key,files,multi);},fail:()=>this.fail('照片保存失败，请重新选择')}));}});wx.chooseMessageFile({count:1,type:'all',success:r=>{const f=r.tempFiles[0];wx.saveFile({tempFilePath:f.path,success:r=>this.setFile(key,{name:f.name,path:r.savedFilePath}),fail:()=>this.fail('凭据保存失败，请重新选择')});}});}});},
 useSampleFile(e){try{const key=e.currentTarget.dataset.sampleKey||e.currentTarget.dataset.key||'file',p=this.data.p,form=this.data.form,node=this.data.node,plan=this.data.plan,purpose={termsFile:'实际采购凭据',nodeFile:'节点验收更正凭据',siteFile:'现场记录更正凭据'}[key]||({prepare:'双方已签工程合同',start:'实际开工凭据',collection:'实际收款凭据',material:'实际到货凭据',node:'现场及验收凭据',delivery:'业主签收凭据',settlement:'结算确认凭据'}[this.data.view]||titles[this.data.view]+'办理凭据'),details=['工程阶段：'+titles[this.data.view],node?'施工节点：'+node.name+'（'+node.id+'）':'',form.date?'记录日期：'+form.date:'',form.total?'合同总额：'+form.total+'元':'',form.first?'开工款：'+form.first+'元':'',form.amount?'本次金额：'+form.amount+'元':'',form.transaction?'流水编号：'+form.transaction:'',form.receiver?'实际收款方：'+form.receiver:'',form.recipient?'签收人：'+form.recipient:'',plan?'材料计划：'+plan.id+' · '+plan.lines.map(line=>line.name).join('、'):'',form.note?'记录说明：'+form.note:''].filter(Boolean),sourceParty=form.receiver||form.recipient||X.context().name,confirmationParty=this.data.view==='delivery'?form.recipient:'',flowVersion=p.recordFlow?.version||1,version=['prepare','start','collection'].includes(this.data.view)?'工程合同V'+(p.recordFlow?.contract?.version||flowVersion):plan?'材料计划 '+plan.id:node?'施工计划V'+(p.plan?.version||flowVersion)+' · 节点'+node.id:p.drawingVersion||'工程资料V'+flowVersion;this.setFile(key,Files.reference({scope:'project',name:purpose+'参考凭据.pdf',customer:p.customer,customerId:p.sourceCustomerId,project:p.name,projectId:p.projectId||p.id,version,sourceParty,confirmationParty,purpose,details}));}catch(error){this.fail(error.message);}},
 setFile(key,file){this.setData({['form.'+key]:file,['fileNames.'+key]:Files.displayFile(file).name});},
 setPhotos(key,files,multi){if(!files.length)return;this.setData({['form.'+key]:files[0],...(multi?{['form.files']:files}:{}),['fileNames.'+key]:files.length>1?'已选'+files.length+'张照片':Files.displayFile(files[0]).name});},
 preview(e){const f=Files.displayFile(e.currentTarget.dataset.file);if(!f)return;if(f.path){if(/\.(png|jpe?g|webp|gif)$/i.test(f.name||''))return wx.previewImage({urls:[f.path]});wx.openDocument({filePath:f.path,showMenu:true,fail:()=>this.fail('文件暂时无法打开')});}else this.setData({samplePreview:f.previewKind?{...f,previewImage:({result:'/pages/build/assets/commission-result.jpg',drawing:'/pages/build/assets/commission-plan.jpg'})[f.previewKind]||'',projectName:this.data.p?.name||'',projectId:this.data.p?.projectId||''}:f});},
 zoomSampleImage(){const image=this.data.samplePreview?.previewImage;if(image)wx.previewImage({current:image,urls:[image]});},
 previewAsset(e){ // M07-T04 批次#36/R1 P1-5：在线凭据授权查看——downloadTicket 票据→图片预览或文档打开
  const id=e.currentTarget.dataset.assetId;if(!id)return;
  const engApi=require('../../../api/franchise/engineering');
  engApi.assets.downloadTicket(id).then(t=>{
    const url=t&&t.downloadUrl||'';
    if(!url||url.indexOf('local://')===0)return wx.showToast({title:'本地通道无在线预览，随 COS 批开放',icon:'none'});
    if(/\.(png|jpe?g|webp|gif)$/i.test(url.split('?')[0]))return wx.previewImage({urls:[url]}); // R2 P1-7：按 pathname 分流（COS 签名参数不破坏扩展名判断）
    wx.downloadFile({url,success:r=>wx.openDocument({filePath:r.tempFilePath,fail:()=>this.fail('文档打开失败')}),fail:()=>this.fail('下载失败，请重试')});
  }).catch(err=>this.fail((err&&err.msg)||'授权查看失败，请重试'));
 },
 closeSamplePreview(){this.setData({samplePreview:null});},
  async refreshOnline(reset){ // API 态：详情聚合+recordView 同构视图（fields/actions/form/各区块键）；失败降级本地演示
   try{
    const bundle=await P.loadProject(this.id);
    const data=P.recordView(bundle,{view:this.data.view,stage:this.stage,planId:this.planId,nodeId:this.nodeId,
      form:this.data.form,initialized:this.initialized&&!reset,
      today:require('../../../utils/account').localDate(),
      writeRole:['engineer','project-manager'].includes(X.context().role),
      financeRole:O.has(X.context().role,'finance')}); // 856 write 口径（后端另校验负责人本人）+collect 岗（批次#36）
    this.initialized=true;
    this.setData({...data,view:this.data.view,title:titles[this.data.view],nodeId:this.nodeId,
      error:'',platEng:true,busy:false});
   }catch(e){ // 失败降级：回退离线本地渲染（不白屏），平台错误以 toast/横幅保留
    const msg=P.errorText(e&&e.data?e.data:e);
    this.__platDown=true;
    this.refresh(reset);
    if(!this.data.p)this.setData({error:msg}); // 本地无演示数据可降级时，横幅保留平台错误文案
    wx.showToast({title:msg,icon:'none'});
   }
  },
  async performOnline(action,f,node){ // API 态动作映射（凭据类前置拦截随 COS 批；busy 防重+一次性幂等键）
   if(this.data.busy)return;
   this.setData({busy:true,error:''});
   if(!this.__idemKeys)this.__idemKeys={};
   if(!this.__idemKeys[action])this.__idemKeys[action]='eng-'+action+'-'+Date.now()+'-'+Math.floor(Math.random()*1e8);
   try{
    await P.perform(action,String(this.id),f||this.data.form,node||this.data.node,this.__idemKeys[action]);
    delete this.__idemKeys[action]; // 成功后释放；失败保留，重试同一动作复用同键
    wx.showToast({title:'已在线保存',icon:'success'});
    await this.refreshOnline(true);
   }catch(e){const msg=P.errorText(e&&e.data?e.data.data||e.data:e);
    this.setData({error:msg});wx.showToast({title:msg,icon:'none'});}
   finally{this.setData({busy:false});}
  },
  perform(e){if(this.data.busy)return;
  if(P.online()&&!this.__platDown){const action=e.currentTarget.dataset.action,f={...this.data.form};
   if(['node','site','node-correct','node-reopen'].includes(action))f.nodeId=this.data.node&&this.data.node.id;
   if(action==='material-terms')f.file=f.termsFile;
   if(action==='material-adjust')Object.assign(f,{reason:f.adjustReason,date:undefined});
   if(action==='arrival-correct')Object.assign(f,{receiptId:f.correctId,actual:f.correctActual,reason:f.correctReason,date:undefined});
   if(action==='site-correct')Object.assign(f,{recordId:f.siteId,note:f.siteNote,file:f.siteFile,reason:f.siteReason,date:undefined});
   if(action==='node-correct')Object.assign(f,{date:f.nodeDate,note:f.nodeNote,file:f.nodeFile,reason:f.nodeReason});
   if(action==='node-reopen')Object.assign(f,{reason:f.nodeReason,date:undefined});
   return this.performOnline(action,f,this.data.node);}const action=e.currentTarget.dataset.action;this.setData({busy:true,error:''});try{const f={...this.data.form};if(['node','site','funding-plan','node-correct','node-reopen'].includes(action))f.nodeId=this.data.node.id;if(action==='material-terms')f.file=f.termsFile;if(action==='material-adjust')Object.assign(f,{reason:f.adjustReason,date:undefined});if(action==='arrival-correct')Object.assign(f,{receiptId:f.correctId,actual:f.correctActual,reason:f.correctReason,date:undefined});if(action==='site-correct')Object.assign(f,{recordId:f.siteId,note:f.siteNote,file:f.siteFile,reason:f.siteReason,date:undefined});if(action==='node-correct')Object.assign(f,{date:f.nodeDate,note:f.nodeNote,file:f.nodeFile,reason:f.nodeReason});if(action==='node-reopen')Object.assign(f,{reason:f.nodeReason,date:undefined});const p=R.run(this.id,action,f,this.data.p.revision||0);if(!p){Nav.open('/pages/build/projects/index',{replace:true});return;}if(action==='material-plan'){this.planId=p.recordFlow.materialPlans.at(-1).id;this.setData({view:'material'});}else if(action==='collect'){this.setData({view:'payments'});}else if(!['arrival','material-terms','material-followup','settle','site','material-adjust','arrival-correct','site-correct','node-correct','node-reopen','dunning'].includes(action))this.setData({view:action==='deliver'?'settlement':action==='settle'?'archive':action==='funding-plan'?'funding':'overview'});this.fundingInitialized=false;this.refresh(true);wx.showToast({title:'记录已保存',icon:'success'});}catch(e){this.fail(e.message);}finally{this.setData({busy:false});}},
 pickArrival(e){const r=this.data.arrivals.find(r=>r.id===e.currentTarget.dataset.id);if(r)this.setData({'form.correctId':r.id,'form.correctActual':String(r.actual),'form.correctReason':''});},
 supplyPick(e){ // R4 P1-2/R5：到货供应事项显式选定（picker index→id+label 预计算——WXML 只读字段）
  const idx=Number(e.detail.value),opts=(this.data.form&&this.data.form.supplyOptions)||[];
  if(opts[idx])this.setData({'form.supplyId':opts[idx].id,'form.supplyLabel':opts[idx].label});
 },
 pickSite(e){const r=this.data.siteRecords.find(r=>r.id===e.currentTarget.dataset.id);if(r)this.setData({'form.siteId':r.id,'form.siteNote':r.note,'form.siteFile':r.file||null,'form.siteReason':''});},
 cancelCorrection(){this.setData({'form.correctId':'','form.siteId':''});},
 go(e){const d=e.currentTarget.dataset;if(d.url)Nav.open(d.url);},
 operating(e){const p=this.data.p;Nav.open('/pages/admin/operations/index?view='+e.currentTarget.dataset.view+'&id='+encodeURIComponent(p.projectId)+((this.data.view==='funding'||this.data.view==='node')&&this.data.node?'&nodeId='+encodeURIComponent(this.data.node.id):''));},
 home(){Nav.open('/pages/build/home/index');},back(){Nav.back();},
 fail(error){this.setData({error});wx.showToast({title:error,icon:'none'});}
});
