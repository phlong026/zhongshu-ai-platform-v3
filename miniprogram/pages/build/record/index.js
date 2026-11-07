const R=require('../../../utils/engineering-records'),X=require('../../../utils/access'),O=require('../../../utils/organization'),Files=require('../../../utils/sample-files'),SI=require('../schedule-import');
const P=require('../../../utils/platform-engineering'); // 工程域纯函数+在线编排（阶段 2）
const Nav=require('../../../utils/page-navigation');
const {makeFormHandlers}=require('../../../utils/view-container');
const baseHandlers=makeFormHandlers();
const titles={overview:'工程项目',prepare:'开工准备',start:'实际开工记录',collection:'工程收款',project:'项目与施工计划',materials:'材料计划',material:'材料计划详情',node:'施工节点记录',funding:'节点资金计划',payments:'项目款项',expenses:'项目费用',documents:'项目资料',delivery:'工程交付',settlement:'项目结算',archive:'交付档案'};
const field=(key,label,type='text')=>({key,label,type});
Page({
 ...baseHandlers,
 // M07-T02：在线 prepare 三期金额输入即校验（四项金额齐后 Σ≠总额即时报差额；无保存入口也保持口径提示可达——R2 P2）
 input(e){baseHandlers.input.call(this,e);if(this.data.view==='prepare'&&this.data.platEng&&['total','signAfter','baseDone','capDone'].every(k=>String(this.data.form[k]==null?'':this.data.form[k]).trim()!=='')){const err=P.threeStageSumError(this.data.form);if(err)this.setData({error:err.msg});}},
 data:{view:'overview',title:'工程项目',p:null,form:{},fields:[],actions:[],pending:[],waiting:[],plans:[],error:'',busy:false,showPlan:false,samplePreview:null,expenseMode:'register'},
 onLoad(o){this.id=o.id;this.planId=o.planId||'';this.nodeId=o.nodeId||'';this.stage=o.stage==='topping'?'topping':o.stage==='base'?'base':'start';this.setData({view:titles[o.view]?o.view:'overview'});this.refresh(true);},
 onShow(){if(this.id)this.refresh();
  },
 refresh(reset=false){if(P.online())return this.refreshOnline(reset);this.setData({p:null,error:'请先登录后端账号'});}, // 本地数据分支已下线：在线走 API，离线只留登录提示与空态
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
    // 费用视图并行预拉清单（失败不阻断详情渲染，清单区显示空）
    const expensePull=this.data.view==='expenses'
      ? require('../../../api/franchise/engineering').materials.expenseList(this.id).catch(()=>null)
      : null;
    const bundle=await P.loadProject(this.id);
    const expenses=expensePull?await expensePull:null;
    const data=P.recordView(bundle,{view:this.data.view,stage:this.stage,planId:this.planId,nodeId:this.nodeId,
      form:this.data.form,initialized:this.initialized&&!reset,
      today:require('../../../utils/account').localDate(),
      writeRole:['engineer','project-manager'].includes(X.context().role),
      financeRole:O.has(X.context().role,'finance'),
      expenses:expenses||undefined,expenseMode:this.data.expenseMode||'register'}); // 856 write 口径（后端另校验负责人本人）+collect 岗（批次#36）
    this.initialized=true;
    // 收款登记承接：原收款记录行统一指财务台收支页（finance-desk 承接收款登记）
    data.collections=(data.collections||[]).map(r=>({...r,url:'/pages/admin/finance-desk/index?view=flows&tab=receivables'}));
    this.setData({...data,view:this.data.view,title:titles[this.data.view],nodeId:this.nodeId,
      error:'',platEng:true,busy:false});
   }catch(e){ // 失败显示错误态（不回落本地演示数据），平台错误以 toast 保留
    const msg=P.errorText(e&&e.data?e.data:e);
    this.setData({p:null,error:msg});
    wx.showToast({title:msg,icon:'none'});
   }
  },
  async performOnline(action,f,node){ // API 态动作映射（凭据类前置拦截随 COS 批；busy 防重+一次性幂等键）
   if(this.data.busy)return;
   this.setData({busy:true,error:''});
   // 幂等键复合（Codex R1 P1-2/R2 P1）：按 action 显式选目标字段——通用优先链会在材料页串键
   //（supplyId/receiptId 共存时 arrival-correct 取错维度）；同一目标的失败重试复用同键
   const IDEM={'expense-terms':'expenseId','expense-revise':'expenseId','expense-cancel':'expenseId',
    'arrival':'supplyId','material-terms':'supplyId','arrival-correct':'correctId','site-correct':'siteId'};
   const f0=f||this.data.form,tk=IDEM[action],tv=tk?(f0[tk]||''):'';
   const idemId=action+(tv?'-'+tk+tv:(node&&node.id?'-n'+node.id:''));
   if(!this.__idemKeys)this.__idemKeys={};
   if(!this.__idemKeys[idemId])this.__idemKeys[idemId]='eng-'+idemId+'-'+Date.now()+'-'+Math.floor(Math.random()*1e8);
   try{
    await P.perform(action,String(this.id),f0,node||this.data.node,this.__idemKeys[idemId]);
    delete this.__idemKeys[idemId]; // 成功后释放；失败保留，同一目标重试复用同键
    wx.showToast({title:'已在线保存',icon:'success'});
    // Codex R1 P2-1：费用登记成功后清空表单（防旧名称/金额残留一键重复计成本）
    if(action==='expense-register')this.setData({'form.expenseName':'','form.expenseAmount':'','form.note':''});
    await this.refreshOnline(true);
   }catch(e){const msg=P.errorText(e&&e.data?e.data.data||e.data:e);
    this.setData({error:msg});wx.showToast({title:msg,icon:'none'});}
   finally{this.setData({busy:false});}
  },
  perform(e){if(this.data.busy)return; // 本地 R.run 分支已下线：无条件在线；离线态 fields/actions 为空天然禁用
  const action=e.currentTarget.dataset.action,f={...this.data.form};
   if(['node','site','node-correct','node-reopen'].includes(action))f.nodeId=this.data.node&&this.data.node.id;
   if(action==='material-terms')f.file=f.termsFile;
   if(action==='material-adjust')Object.assign(f,{reason:f.adjustReason,date:undefined});
   if(action==='arrival-correct')Object.assign(f,{receiptId:f.correctId,actual:f.correctActual,reason:f.correctReason,date:undefined});
   if(action==='site-correct')Object.assign(f,{recordId:f.siteId,note:f.siteNote,file:f.siteFile,reason:f.siteReason,date:undefined});
   if(action==='node-correct')Object.assign(f,{date:f.nodeDate,note:f.nodeNote,file:f.nodeFile,reason:f.nodeReason});
   if(action==='node-reopen')Object.assign(f,{reason:f.nodeReason,date:undefined});
   return this.performOnline(action,f,this.data.node);},
 pickArrival(e){const r=this.data.arrivals.find(r=>r.id===e.currentTarget.dataset.id);if(r)this.setData({'form.correctId':r.id,'form.correctActual':String(r.actual),'form.correctReason':''});},
 supplyPick(e){ // R4 P1-2/R5：到货供应事项显式选定（picker index→id+label 预计算——WXML 只读字段）
  const idx=Number(e.detail.value),opts=(this.data.form&&this.data.form.supplyOptions)||[];
  if(opts[idx])this.setData({'form.supplyId':opts[idx].id,'form.supplyLabel':opts[idx].label});
 },
 pickPurpose(e){ // 费用用途枚举 picker（LABOR/TRAVEL/OTHER——Codex R2 P2：code 与显示 label 同步更新）
  const i=Number(e.detail.value),p=(this.data.purposes||[])[i];
  if(p)this.setData({'form.purposeCode':p.code,purposeLabel:p.label});
 },
 expenseMode(e){ // 费用四模式切换（register 默认/terms 挂应付/revise 修订/cancel 取消——行内选定目标后重建 fields）
  const d=e.currentTarget.dataset,mode=d.mode||'register',row=(this.data.expenseRows||[]).find(r=>r.id===d.id);
  this.setData({expenseMode:mode,error:'','form.expenseId':row?row.id:'','form.expenseName':row?row.name:'',
   'form.supplier':'','form.basis':'','form.newAmount':'','form.note':'','form.cancelReason':''});
  this.refreshOnline(false); // 不重置 form（保留目标 expenseId），仅按新 mode 重建 fields/actions
 },
 pickSite(e){const r=this.data.siteRecords.find(r=>r.id===e.currentTarget.dataset.id);if(r)this.setData({'form.siteId':r.id,'form.siteNote':r.note,'form.siteFile':r.file||null,'form.siteReason':''});},
 cancelCorrection(){this.setData({'form.correctId':'','form.siteId':''});},
 go(e){const d=e.currentTarget.dataset;if(d.url)Nav.open(d.url);},
 operating(e){const p=this.data.p;Nav.open('/pages/admin/home/index?view=operating&id='+encodeURIComponent(p.projectId)+((this.data.view==='funding'||this.data.view==='node')&&this.data.node?'&nodeId='+encodeURIComponent(this.data.node.id):''));}, // 经营视图由 owner-page 承接
 home(){Nav.open('/pages/build/home/index');},back(){Nav.back();},
 fail(error){this.setData({error});wx.showToast({title:error,icon:'none'});}
});
