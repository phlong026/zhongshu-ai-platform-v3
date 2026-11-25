const R=require('../../../utils/engineering-records'),X=require('../../../utils/access'),O=require('../../../utils/organization'),Files=require('../../../utils/sample-files'),SI=require('../schedule-import');
const P=require('../../../utils/platform-engineering'); // 工程域纯函数+在线编排（阶段 2）
const EH=require('../external-handover');
const Nav=require('../../../utils/page-navigation');
const {makeFormHandlers}=require('../../../utils/view-container');
const baseHandlers=makeFormHandlers();
const titles={cameras:'工地实景',delay:'进度偏差处理',overview:'工程项目',prepare:'开工准备',start:'正式开工记录',collection:'工程收款',project:'项目与施工计划',materials:'材料计划',material:'材料计划详情',node:'施工节点记录',funding:'节点资金计划',payments:'项目款项',expenses:'项目费用',documents:'项目资料',delivery:'工程交付',settlement:'项目结算',archive:'交付档案'};
const field=(key,label,type='text')=>({key,label,type});
Page({
 ...baseHandlers,
 // M07-T02：在线 prepare 三期金额输入即校验（四项金额齐后 Σ≠总额即时报差额；无保存入口也保持口径提示可达——R2 P2）
 input(e){baseHandlers.input.call(this,e);if(this.data.view==='prepare'&&this.data.platEng&&['total','signAfter','baseDone','capDone'].every(k=>String(this.data.form[k]==null?'':this.data.form[k]).trim()!=='')){const err=P.threeStageSumError(this.data.form);if(err)this.setData({error:err.msg});}},
 data:{view:'overview',title:'工程项目',p:null,form:{},fields:[],actions:[],pending:[],waiting:[],plans:[],error:'',busy:false,showPlan:false,expanded:{},inspectionHistoryId:'',planEditorId:'',samplePreview:null,expenseMode:'register',externalHandoverUrl:'',externalHandoverVersion:0},
 onLoad(o){this.id=o.id;this.planId=o.planId||'';this.homeSupply=o.homeSupply||'';this.nodeId=o.nodeId||'';this.stage=['base','topping','tail'].includes(o.stage)?o.stage:'start';this.setData({view:titles[o.view]?o.view:'overview'});this.refresh(true);},
 onShow(){this.__disposed=false;this.__inactive=false;this.__previewClosed=false;const h=require('../../../api/http'),identity=String(h.currentPostId())+':'+h.sessionGeneration();if(this.__identity!==identity){this.__accessEpoch=(this.__accessEpoch||0)+1;this.__externalPreview=null;this.__identity=identity;this.initialized=false;this.__idemKeys={};this.setData({playbackUrl:'',playingCameraId:'',cameras:[],cameraFormOpen:false,expanded:{},inspectionHistoryId:'',planEditorId:'',p:null,form:{},fields:[],actions:[],docs:[],fileNames:{},samplePreview:null,externalHandoverUrl:'',externalHandoverVersion:0,busy:false,error:''});}if(this.id)this.refresh();
  },
 refresh(reset=false){if(P.online())return this.refreshOnline(reset);this.setData({p:null,error:'请先登录后端账号'});}, // 本地数据分支已下线：在线走 API，离线只留登录提示与空态
 jumpNodeSection(e){const selector=e.currentTarget.dataset.section==='inspection'?'.node-inspection':this.data.fields.length&&(this.data.node?.status!=='已完成'||this.data.expanded.nodeEdit)?'.record-fields':'.node-site-records',info=getApp().globalData.windowInfo||wx.getWindowInfo(),header=(info.statusBarHeight||20)+(info.windowWidth||375)*87/750+12;const q=wx.createSelectorQuery();q.select(selector).boundingClientRect();q.selectViewport().scrollOffset();q.exec(r=>{if(r[0])wx.pageScrollTo({scrollTop:Math.max(0,r[0].top+(r[1]?.scrollTop||0)-header),duration:150});});},
 togglePlan(){this.setData({showPlan:!this.data.showPlan});},
 toggleSection(e){const key=e.currentTarget.dataset.section;if(!['progress','events','arrivals','collections','dunning','followups','site','inspections','delay','expenses','adjust','terms','nodeEdit','help'].includes(key))return;this.setData({['expanded.'+key]:!this.data.expanded[key]});},
 togglePlanNode(e){const id=String(e.currentTarget.dataset.id);this.setData({planEditorId:this.data.planEditorId===id?'':id});},
 toggle(e){const key=e.currentTarget.dataset.key;this.setData({['form.'+key]:!this.data.form[key]});},
 lineInput(e){const d=e.currentTarget.dataset;this.setData({['form.lines['+d.index+'].'+d.key]:e.detail.value,error:''});},
 nodeInput(e){const d=e.currentTarget.dataset,patch={['form.nodes['+d.index+'].'+d.key]:e.detail.value,error:''};if(d.key==='start'&&this.data.form.nodes[d.index]?.milestone==='start')patch['form.nodes['+d.index+'].end']=e.detail.value;this.setData(patch);},
 pickEngineer(e){const s=this.data.engineers[Number(e.detail.value)];if(s)this.setData({'form.engineerId':s.id,engineerIndex:Number(e.detail.value)});},
 pickNode(e){const nodeIndex=Number(e.detail.value),n=(this.data.materialNodes||this.data.p.plan.nodes)[nodeIndex];if(n)this.setData({'form.nodeId':n.id,nodeIndex});},
 pickTemplate(e){const i=Number(e.detail.value);if(this.data.templates[i])this.setData({templateIndex:i,selectedTemplateId:this.data.templates[i].id});},
 applyTemplate(e){try{const nodes=R.applyTemplate(this.data.p,this.data.templates[this.data.templateIndex]);this.setData({'form.nodes':nodes,error:''});wx.showToast({title:'已恢复标准节点清单，请逐项填写日期',icon:'none',duration:2500});}catch(err){this.fail(err.message);}},
 importPlan(){wx.chooseMessageFile({count:1,type:'file',extension:['xlsx'],success:r=>{try{const f=r.tempFiles[0];if(!f)return;if(Number(f.size)>SI.MAX_FILE_BYTES)return this.fail('进度表文件不能超过2MB');const res=SI.importNodes(wx.getFileSystemManager().readFileSync(f.path));this.setData({'form.nodes':res.nodes,error:''});wx.showToast({title:res.notice||'已导入'+res.nodes.length+'个节点，请核对日期',icon:'none',duration:2500});}catch(err){this.fail(err.message);}},fail:e=>{if(!/cancel/i.test(String(e&&e.errMsg||'')))this.fail('选择进度表失败，请重试');}});},
 addLine(){this.setData({'form.lines':this.data.form.lines.concat({name:'',spec:'',quantity:'',unit:'包'})});},
 fileSelectionGuard(){const http=require('../../../api/http'),current=http.sessionGuard(),post=http.currentPostId(),id=this.id,view=this.data.view;return()=>current()&&!this.__disposed&&id===this.id&&view===this.data.view&&String(http.currentPostId())===String(post);},
  selectNodeReplacement(e){
   if(this.data.busy)return;const index=Number(e.detail.value),selected=(this.data.nodeEvidenceOptions||[])[index];if(!selected)return;
   this.__fileSelectionGeneration=(this.__fileSelectionGeneration||0)+1;
   this.setData({nodeEvidenceIndex:index,'form.nodeRecordId':selected.recordId,'form.nodeReplaceAssetId':selected.assetId,'form.nodeFile':null,'fileNames.nodeFile':'',error:''});
  },
  toggleInspectionHistory(e){const id=String(e.currentTarget.dataset.id);this.setData({inspectionHistoryId:this.data.inspectionHistoryId===id?'':id});},
  upload(e){
   const generation=this.__fileSelectionGeneration=(this.__fileSelectionGeneration||0)+1,current=this.fileSelectionGuard(),alive=()=>current()&&generation===this.__fileSelectionGeneration;
   const key=e.currentTarget.dataset.key||'file',multi=this.data.view==='node'&&key==='file',demo=!Files.release()&&X.context().demo,itemList=['拍照或从相册选择','选择聊天文件'];
  if(demo)itemList.push('使用参考凭据');
  wx.showActionSheet({itemList,success:r=>{
   if(!alive())return;
   if(r.tapIndex===2)return this.useSampleFile({currentTarget:{dataset:{sampleKey:key}}});
   if(r.tapIndex===0)return wx.chooseMedia({count:multi?9:1,mediaType:['image'],sourceType:['album','camera'],success:r=>{
    if(!alive())return;const files=r.tempFiles.map((f,i)=>({name:'现场照片-'+(i+1)+'.jpg',path:f.tempFilePath}));let saved=0;
    files.forEach(f=>wx.saveFile({tempFilePath:f.path,success:res=>{if(!alive())return;f.path=res.savedFilePath;if(++saved===files.length)this.setPhotos(key,files,multi);},fail:()=>{if(alive())this.fail('照片保存失败，请重新选择');}}));
   }});
   wx.chooseMessageFile({count:1,type:'all',success:r=>{if(!alive())return;const f=r.tempFiles[0];if(!f)return;wx.saveFile({tempFilePath:f.path,success:r=>{if(alive())this.setFile(key,{name:f.name,path:r.savedFilePath});},fail:()=>{if(alive())this.fail('凭据保存失败，请重新选择');}});}});
  }});
 },
 useSampleFile(e){try{const key=e.currentTarget.dataset.sampleKey||e.currentTarget.dataset.key||'file',p=this.data.p,form=this.data.form,node=this.data.node,plan=this.data.plan,purpose={termsFile:'实际采购凭据',nodeFile:'节点验收更正凭据',siteFile:'现场记录更正凭据'}[key]||({prepare:'双方已签工程合同',start:'实际开工凭据',collection:'实际收款凭据',material:'实际到货凭据',node:'现场及验收凭据',delivery:'业主签收凭据',settlement:'结算确认凭据'}[this.data.view]||titles[this.data.view]+'办理凭据'),details=['工程阶段：'+titles[this.data.view],node?'施工节点：'+node.name+'（'+node.id+'）':'',form.date?'记录日期：'+form.date:'',form.total?'合同总额：'+form.total+'元':'',form.first?'开工款：'+form.first+'元':'',form.amount?'本次金额：'+form.amount+'元':'',form.transaction?'流水编号：'+form.transaction:'',form.receiver?'实际收款方：'+form.receiver:'',form.recipient?'签收人：'+form.recipient:'',plan?'材料计划：'+plan.id+' · '+plan.lines.map(line=>line.name).join('、'):'',form.note?'记录说明：'+form.note:''].filter(Boolean),sourceParty=form.receiver||form.recipient||X.context().name,confirmationParty=this.data.view==='delivery'?form.recipient:'',flowVersion=p.recordFlow?.version||1,version=['prepare','start','collection'].includes(this.data.view)?'工程合同V'+(p.recordFlow?.contract?.version||flowVersion):plan?'材料计划 '+plan.id:node?'施工计划V'+(p.plan?.version||flowVersion)+' · 节点'+node.id:p.drawingVersion||'工程资料V'+flowVersion;this.setFile(key,Files.reference({scope:'project',name:purpose+'参考凭据.pdf',customer:p.customer,customerId:p.sourceCustomerId,project:p.name,projectId:p.projectId||p.id,version,sourceParty,confirmationParty,purpose,details}));}catch(error){this.fail(error.message);}},
 setFile(key,file){this.setData({['form.'+key]:file,...(this.data.view==='node'&&key==='file'?{'form.files':[file]}:{}),['fileNames.'+key]:Files.displayFile(file).name});},
 setPhotos(key,files,multi){if(!files.length)return;this.setData({['form.'+key]:files[0],...(multi?{['form.files']:files}:{}),['fileNames.'+key]:files.length>1?'已选'+files.length+'张照片':Files.displayFile(files[0]).name});},
 preview(e){const raw=e.currentTarget.dataset.file;if(raw&&raw.text&&!raw.assetId){this.setData({samplePreview:{name:raw.name||'项目资料',text:raw.text}});return;}if(raw&&raw.assetId)return this.previewAsset({currentTarget:{dataset:raw}});const f=Files.displayFile(raw);if(!f)return;if(f.path){if(/\.(png|jpe?g|webp|gif)$/i.test(f.name||''))return wx.previewImage({urls:[f.path]});wx.openDocument({filePath:f.path,showMenu:true,fail:()=>this.fail('文件暂时无法打开')});}else if(P.online())this.fail('资料尚未关联有效文件，请联系业务人员补充');else this.setData({samplePreview:f.previewKind?{...f,previewImage:({result:'/pages/build/assets/commission-result.jpg',drawing:'/pages/build/assets/commission-plan.jpg'})[f.previewKind]||'',projectName:this.data.p?.name||'',projectId:this.data.p?.projectId||''}:f});},
 zoomSampleImage(){const image=this.data.samplePreview?.previewImage;if(image)wx.previewImage({current:image,urls:[image]});},
 async previewAsset(e){
  const d=e.currentTarget.dataset,id=d.assetId;if(d.externalHandover||d.externalVersion||(this.data.docs||[]).some(row=>row.file?.externalHandover&&row.file.assetId===id))return EH.preview(this,d);if(!id)return;
  const http=require('../../../api/http'),sessionAlive=http.sessionGuard(),postId=http.currentPostId(),epoch=this.__accessEpoch||0;
  const alive=()=>!this.__previewClosed&&sessionAlive()&&String(http.currentPostId())===String(postId)&&(this.__accessEpoch||0)===epoch;
  try{
   const t=d.commissionId&&d.dispatchSeq&&d.slot
    ?await http.post('/franchise/commissions/'+encodeURIComponent(d.commissionId)+'/dispatches/'+encodeURIComponent(d.dispatchSeq)+'/files/'+encodeURIComponent(d.slot)+'/download-tickets')
    :await require('../../../api/franchise/engineering').assets.downloadTicket(id);
   if(!alive())return;
   const file=await require('../../../utils/b-asset-download').downloadTicketFile({http,downloadUrl:t&&t.downloadUrl,alive,label:'工程资料'});
   if(!alive())return;
   if(/^image\//i.test(file.contentType))return wx.previewImage({urls:[file.tempFilePath]});
   wx.openDocument({filePath:file.tempFilePath,...(/application\/pdf/i.test(file.contentType)?{fileType:'pdf'}:{}),showMenu:true,fail:()=>{if(alive())this.fail('文档打开失败');}});
  }catch(err){if(alive())this.fail((err&&err.msg)||(err&&err.message)||'授权查看失败，请重试');}
 },
 onHide(){this.__inactive=true;this.__previewClosed=true;this.__externalPreview=null;this.setData({playbackUrl:'',playingCameraId:''});},
 onUnload(){this.__disposed=true;this.__previewClosed=true;this.__externalPreview=null;},
 closeSamplePreview(){this.setData({samplePreview:null});},
  async refreshOnline(reset){ // API 态：详情聚合+recordView 同构视图（fields/actions/form/各区块键）；失败只显示错误态
   const http=require('../../../api/http'),current=http.requestGuard('engineering-record:'+this.id+':'+this.data.view),id=this.id,view=this.data.view,post=http.currentPostId(),epoch=this.__accessEpoch||0;
   const alive=()=>current()&&!this.__disposed&&!this.__inactive&&id===this.id&&view===this.data.view&&String(http.currentPostId())===String(post)&&(this.__accessEpoch||0)===epoch;
   this.__externalPreview=null;
   try{
    // 费用视图并行预拉清单（失败不阻断详情渲染，清单区显示空）
    const expensePull=this.data.view==='expenses'
      ? require('../../../api/franchise/engineering').materials.expenseList(this.id).catch(()=>null)
      : null;
    const api=require('../../../api/franchise/engineering');
    const cameraPull=['overview','cameras'].includes(view)?api.cameras.list(id).then(rows=>({rows})).catch(e=>({rows:[],error:P.errorText(e)})):Promise.resolve({rows:[]});
    const handlersPull=view==='delay'?api.progress.handlers(id):Promise.resolve([]);
    const [bundle,cameraData,handlers]=await Promise.all([P.loadProject(this.id),cameraPull,handlersPull]);
    const expenses=expensePull?await expensePull:null;
    if(!alive())return;
    if(view==='material'&&this.homeSupply){
      const plan=(bundle.materials?.plans||[]).find(p=>(p.lines||[]).some(l=>String(l.supplyId)===String(this.homeSupply)));
      if(!plan)throw Error('原供货事项已变化或无权查看，请返回指标明细刷新');
      this.planId=String(plan.materialPlanId);
    }
    const apiPost=(()=>{try{const http=require('../../../api/http');return http.token()?require('../../../api/auth').currentApiPost():null;}catch(e){return null;}})();
    const apiRole=apiPost&&apiPost.roleCode||'';
    const data=P.recordView(bundle,{view:this.data.view,stage:this.stage,planId:this.planId,nodeId:this.nodeId,
      form:this.data.form,initialized:this.initialized&&!reset,userId:apiPost?.userId,
      cameras:cameraData.rows,cameraError:cameraData.error||'',handlers,
      today:require('../../../utils/account').localDate(),
      writeRole:['engineer','project-manager'].includes(apiRole),
      financeRole:O.has(apiRole,'finance'),
      termsRole:apiPost?.orgType==='store'&&(O.has(apiRole,'finance')||['boss','owner'].includes(apiRole)),
      expenses:expenses||undefined,expenseMode:this.data.expenseMode||'register'}); // 856 write 口径（后端另校验负责人本人）+collect 岗（批次#36）
    this.__externalProject=bundle.detail?.handoverMode==='EXTERNAL';
    if(this.__externalProject){await EH.bind(bundle.detail,data,alive);if(!alive())return;}
    else{data.externalHandoverUrl='';data.externalHandoverVersion=0;}
    if(!this.initialized&&this.homeSupply){const selected=data.form?.supplyOptions?.find(s=>String(s.id)===String(this.homeSupply));if(selected){data.form.supplyId=selected.id;data.form.supplyLabel=selected.label;}}
    this.initialized=true;
    const ledgerRole=apiPost?.orgType==='store'&&['boss','owner','finance','finance-lead'].includes(apiRole);
    data.collections=(data.collections||[]).map(r=>({...r,canPreviewFile:ledgerRole,url:ledgerRole?'/pages/admin/finance-desk/index?view=project&id='+encodeURIComponent(String(this.id)):''}));
    // 展示层只控制密度；完整记录、表单和命令仍使用原始数据。
    const rows=data.nodeRows||[],firstOpen=rows.findIndex(n=>n.status!=='已完成'),from=firstOpen<0?Math.max(0,rows.length-3):Math.min(firstOpen,Math.max(0,rows.length-3));
    data.nodeRows=rows.map((n,i)=>({...n,preview:i>=from&&i<from+3}));
    data.progressPercent=rows.length?Math.round((data.paceDone||0)*100/rows.length):0;
    data.events=[...(data.events||[])].reverse();
    data.delayHistory=[...(data.delayHistory||[])].reverse();
    const toolNames=['进度计划','项目资料','材料计划','项目款项','交付归档','项目结算'];
    const toolIcons=['/pages/engineering/assets/calendar.png','/pages/engineering/assets/folder.png','/pages/engineering/assets/material.png','/assets/money.png','/pages/engineering/assets/inspection.png','/assets/money.png'];
    data.projectTools=(data.links||[]).map((l,i)=>({...l,label:toolNames[i]||l.label,icon:toolIcons[i]}));
    data.projectTools.splice(3,0,{label:'工地实景',url:data.cameraUrl,icon:'/assets/home-video-icon.png',count:(data.cameras||[]).length});
    this.setData({...data,view:this.data.view,title:titles[this.data.view],nodeId:this.nodeId,
      error:'',platEng:true,busy:false});
   }catch(e){if(!alive())return; // 失败显示错误态（不回落本地演示数据），平台错误以 toast 保留
    const msg=P.errorText(e&&e.data?e.data:e);
    if(this.__externalProject)EH.clear(this,msg);else this.setData({p:null,error:msg});
    wx.showToast({title:msg,icon:'none'});
   }
  },
  async performOnline(action,f,node){ // API 态动作映射（凭据类前置拦截随 COS 批；busy 防重+一次性幂等键）
   if(this.data.busy)return;
   const http=require('../../../api/http'),current=http.sessionGuard(),id=this.id,view=this.data.view,post=http.currentPostId(),epoch=this.__accessEpoch||0;
   const alive=()=>current()&&!this.__disposed&&!this.__inactive&&id===this.id&&view===this.data.view&&String(http.currentPostId())===String(post)&&(this.__accessEpoch||0)===epoch;
   this.setData({busy:true,error:''});
   // 幂等键复合（Codex R1 P1-2/R2 P1/R3 P1/R4 P1）：按 action 显式选目标字段——节点系（含 material-plan）
   // 绑表单 nodeId、期次系绑 stage、对象系绑各自 ID；未列动作（交付/结算/归档等）一律项目级，不回落活动节点。
   // 键复用判定加命令指纹（R4 P1-2）：同目标同内容=失败重试复用同键（后端幂等回放原命令）；
   // 同目标改内容=新命令新键（响应丢失后改填新费用再提交不得复用旧键——防回放旧命令吞掉新登记）
   const IDEM={'expense-terms':'expenseId','expense-revise':'expenseId','expense-cancel':'expenseId',
    'arrival':'supplyId','material-terms':'supplyId','arrival-correct':'correctId','site-correct':'siteId',
    'delay':'nodeId','camera-save':'cameraId','node':'nodeId','site':'nodeId','node-correct':'nodeId','node-reopen':'nodeId','material-plan':'nodeId',
    'collect':'stage','dunning':'stage'};
   const f0=f||this.data.form,tk=IDEM[action],tv=tk?String(f0[tk]||''):'';
   const idemId=action+(tv?'-'+tk+tv:'');
   // 命令指纹（Codex R4-R6）：按 action 投影实际提交字段（platform-engineering.idemFingerprint 纯函数）——
   // 排除服务端回流（余量行/展示标签），保留行内用户输入（adjust/材料行/节点输入）
   const fp=P.idemFingerprint(action,f0);
   if(!this.__idemKeys)this.__idemKeys={};
   let cached=this.__idemKeys[idemId];
   if(!cached||cached.fp!==fp){ // 无缓存或内容变了→新键；同目标同内容失败重试复用
    cached={key:'eng-'+idemId+'-'+Date.now()+'-'+Math.floor(Math.random()*1e8),fp};
    this.__idemKeys[idemId]=cached;
   }
   try{
    const result=await P.perform(action,String(this.id),f0,node||this.data.node,cached.key);
    if(!alive())return;
    delete this.__idemKeys[idemId]; // 成功后释放；失败保留，同目标同内容重试复用同键（指纹匹配时）
    wx.showToast({title:result&&result.stageStatus==='PENDING_CONFIRMATION'?'已提交，待财务核实':'已在线保存',icon:result&&result.stageStatus==='PENDING_CONFIRMATION'?'none':'success'});
    // Codex R1 P2-1：费用登记成功后清空表单（防旧名称/金额残留一键重复计成本）
    if(action==='camera-save')this.setData({cameraFormOpen:false,playbackUrl:'',playingCameraId:''});
    if(action==='expense-register')this.setData({'form.expenseName':'','form.expenseAmount':'','form.note':''});
    await this.refreshOnline(true);
   }catch(e){if(!alive())return;const msg=P.errorText(e&&e.data?e.data.data||e.data:e);
    this.setData({error:msg});wx.showToast({title:msg,icon:'none'});}
   finally{if(alive())this.setData({busy:false});}
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
 go(e){const d=e.detail&&e.detail.item||e.currentTarget.dataset;if(d.url)Nav.open(d.url);},
 operating(e){
  const p=this.data.p;
  if(P.online()){
   const http=require('../../../api/http'),alive=http.sessionGuard(),postId=String(http.currentPostId()),post=require('../../../api/auth').currentApiPost();
   const identity=postId+':'+http.sessionGeneration();
   if(!alive()||this.__disposed||this.__inactive||this.__identity!==identity||!p||String(p.id)!==String(this.id)||String(p.projectId)!==String(this.id))
    return this.fail('项目或身份已更新，请刷新后重新进入项目账');
   // .3 原付款入口保留工程归属；B 使用已有项目账，不进入丢失项目上下文的老板首页。
   if(post?.orgType==='store'&&['boss','owner','finance','finance-lead'].includes(post.roleCode))
    return Nav.open('/pages/admin/finance-desk/index?view=project&id='+encodeURIComponent(String(this.id)));
   if(['engineer','engineer-lead'].includes(post?.roleCode))
    return Nav.open('/pages/build/record/index?id='+encodeURIComponent(String(this.id))+'&view='+(e.currentTarget.dataset.view==='payables'?'materials':'settlement'));
  }
  Nav.open('/pages/admin/home/index?view=operating&id='+encodeURIComponent(p.projectId)+((this.data.view==='funding'||this.data.view==='node')&&this.data.node?'&nodeId='+encodeURIComponent(this.data.node.id):''));
 },
 pickDelayOwner(e){const p=(this.data.handlers||[])[Number(e.detail.value)];if(p)this.setData({'form.delayOwnerUserId':String(p.id),'form.delayOwnerName':p.name});},
 editCamera(e){if(!this.data.canManageCameras)return;const c=this.data.cameras.find(c=>String(c.id)===String(e.currentTarget.dataset.id));this.setData({cameraFormOpen:true,playbackUrl:'',playingCameraId:'','form.cameraId':c?.id||'','form.cameraName':c?.name||'','form.cameraLocation':c?.location||'','form.cameraUrl':'','form.cameraEnabled':c?c.enabled:true});},
 closeCameraForm(){this.setData({cameraFormOpen:false,'form.cameraUrl':''});},
 toggleCameraEnabled(){this.setData({'form.cameraEnabled':!this.data.form.cameraEnabled});},
 async playCamera(e){
  const cameraId=String(e.currentTarget.dataset.id),http=require('../../../api/http'),guard=http.requestGuard('camera-playback'),post=http.currentPostId(),id=this.id;
  const alive=()=>guard()&&!this.__disposed&&!this.__inactive&&id===this.id&&String(http.currentPostId())===String(post);
  this.setData({playingCameraId:cameraId,playbackUrl:'',playbackError:'',playbackLoading:true});
  try{const r=await require('../../../api/franchise/engineering').cameras.playback(id,cameraId);if(alive())this.setData({playbackUrl:r.url,playbackLoading:false});}
  catch(e){if(alive())this.setData({playbackLoading:false,playbackError:P.errorText(e)||'暂时无法加载画面'});}
 },
 cameraVideoError(){this.setData({playbackError:'画面暂时不可用，请核对摄像头网络或更新播放地址',playbackLoading:false});},
 cameraVideoReady(){this.setData({playbackError:'',playbackLoading:false});},
 reloadCameras(){this.refreshOnline(false);},
 reloadProject(){this.__previewClosed=false;return this.refreshOnline(true);},
 home(){Nav.open('/pages/build/home/index');},back(){Nav.back();},
 fail(error){this.setData({error});wx.showToast({title:error,icon:'none'});}
});
