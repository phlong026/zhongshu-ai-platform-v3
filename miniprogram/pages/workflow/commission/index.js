const B=require('../../../utils/business'),X=require('../../../utils/access'),SF=require('../../../utils/sales-flow'),Nav=require('../../../utils/page-navigation'),Admin=require('../../../utils/design-admin-workbench');
const copy=B.clone;
Page({
 data:{title:'设计委托',view:'progress',customer:null,fields:[],form:{},rows:[],tasks:[],taskCount:0,metrics:[],recent:[],projectRows:[],projectFilters:[],projectCounts:{},projectTotal:0,projectHasMore:false,keyword:'',projectFilter:'全部',error:'',busy:false,primary:'',canDraft:false,canReturn:false,canFeedback:false,preview:null},
 onLoad(options){this.options=options||{};this.id=options.id||'';this.view=options.view||'progress';if(!['admin','projects'].includes(this.view))this.refresh(true);},
 onShow(){this.refresh(false);},
 refresh(reset=false){
  try{
   const a=X.context();X.assert(a.active,'请先登录有效岗位');
   if(['admin','projects'].includes(this.view)){
    X.assert(a.role==='hq-design-admin','当前岗位无设计行政权限');
    const desk=Admin.load(a),keyword=this.data.keyword||'',projectFilter=this.data.projectFilter||'全部';this.adminDesk=desk;this.projectLimit=20;
    this.setData({title:this.view==='projects'?'项目':'首页',view:this.view,tasks:desk.todos,taskCount:desk.taskCount,metrics:desk.metrics,recent:desk.recent,projectFilters:desk.filters,projectCounts:desk.counts,adminName:require('../../../utils/organization').roles[a.role],adminDate:desk.date,keyword,projectFilter,error:'',primary:''});
    return this.renderAdminProjects(true);
   }
   if(this.view==='policy'){
    X.assert(a.role==='boss'&&a.orgType==='store','标准费用由加盟商老板配置');const p=SF.policy(a.companyId);
    const form=reset||!this.initialized?{scope:p?.scope||'',total:p?.total||'',first:p?.first||'',recipient:p?.recipient||'',active:p?p.active:true}:this.data.form;this.initialized=true;
    return this.setData({title:'标准设计费用',view:'policy',form,primary:'保存并授权标准规则',fields:[{key:'scope',label:'适用交付范围',kind:'textarea'},{key:'total',label:'服务总额（元）',kind:'money'},{key:'first',label:'首款（元，尾款自动计算）',kind:'money'},{key:'recipient',label:'总部实际收款方',kind:'input'},{key:'active',label:'启用本版标准规则',kind:'check'}],rows:[{label:'授权说明',value:'范围、金额、首尾款和收款方全部匹配时直接办理；不匹配则交老板审批。历史委托保留原规则版本。'}],error:''});
   }
   const c=B.customer(this.id);X.assert(c,'客户不存在或当前岗位无权查看');
   if(SF.legacy(c)){wx.redirectTo({url:'/pages/workflow/index?view=delivery&id='+encodeURIComponent(c.id)});return;}
   const existing=SF.is(c),n=SF.next(c),p=SF.policy(c.companyId),packet=c.businessFlow?.packet;
   // Submitted packets supersede drafts saved against an earlier handover.
   const savedDraft=c.drafts?.[B.draftKey('offline:'+this.view)],draft=savedDraft?.__requestVersion===(c.request?.version||0)&&(this.view!=='handover'||savedDraft.dispatchVersion===c.businessFlow?.dispatch?.version&&(savedDraft.__packetVersion||0)===(packet?.version||0))?savedDraft:null;
   const initial=this.view==='submit'?{text:c.request?.text||c.summary||'',scope:c.request?.scope||p?.scope||'',total:c.serviceOrders?.at(-1)?.total||p?.total||'',first:c.serviceOrders?.at(-1)?.first||p?.first||'',recipient:c.serviceOrders?.at(-1)?.recipient||p?.recipient||'',attachment:c.request?.attachment||null,checked:false}:this.view==='handover'?{resultFile:packet?.resultFile||null,drawingFile:packet?.drawingFile||null,reviewFile:packet?.reviewFile||null,confirmationFile:packet?.confirmationFile||null,drawingVersion:packet?.drawingVersion||'',confirmationDate:packet?.confirmationDate||B.date(),note:packet?.note||'',managerId:c.handover?.managerId||'',received:false,confirmed:false}:{recipient:c.owner||'',note:'',checked:false};
   initial.__requestVersion=c.request?.version||0;if(this.view==='handover'){initial.dispatchVersion=c.businessFlow?.dispatch?.version;initial.__packetVersion=packet?.version||0;}
   const form=reset||!this.initialized?{...initial,...draft}:this.data.form;this.initialized=true;
   const map={submit:'commission-submit',feedback:'commission-feedback',complete:'design-complete',dispatch:'design-dispatch',handover:'packet-submit',receive:'packet-receive'},action=map[this.view];
   const can=!!(action&&SF.allowed(action,c)&&((this.view==='submit'&&(!existing||n.view==='submit'))||this.view==='feedback'&&existing&&c.businessFlow.dispatch&&!c.handover&&!c.acceptance||n.view===this.view&&n.actionable));
   const fields=[];const add=(key,label,kind='input')=>fields.push({key,label,kind});
   if(this.view==='submit'&&can){add('text','客户设计需求','textarea');add('scope','本次交付范围','textarea');add('total','设计服务总额（元）','money');add('first','首款金额（元，余款为尾款）','money');add('recipient','总部实际收款方');add('attachment','需求附件（选填）','file');add('checked','已核对需求、尺寸来源、范围及首尾款约定','check');}
   if(this.view==='complete'&&can){add('note','线下完成说明（选填）','textarea');add('checked','本次设计及内部专业审核已完成','check');}
   if(this.view==='feedback'&&can){add('note','需线下沟通的修改意见','textarea');add('attachment','客户修改意见附件（选填）','file');}
   if(this.view==='dispatch'&&can){add('recipient','实际成果接收人');add('note','实际交付清单、渠道及说明','textarea');add('checked','本次正式成果已经实际下发','check');}
   if(this.view==='handover'&&can){add('resultFile','正式设计成果','file');add('drawingFile','本版施工图','file');add('drawingVersion','本次施工图版本');add('reviewFile','对应版本的线下专业审核依据','file');add('confirmationFile','客户对本版成果的确认凭据','file');add('confirmationDate','客户确认日期','date');add('note','现场重点与电子交底','textarea');add('managerId','项目工程人员','manager');add('received','正式文件已收齐且可读取','check');add('confirmed','客户已确认本次采用的成果版本','check');}
   if(this.view==='receive'&&can){add('note','退回原因（退回时必填）','textarea');add('checked','本版图纸、客户确认、审核依据及交底已核对','check');}
   const rows=[{label:'当前进度',value:n.state},{label:'当前办理',value:n.owner},{label:'交付范围',value:c.request?.scope||'随委托确认'}];
   if(a.role==='hq-design-admin'&&c.paymentConditions)for(const stage of ['first','tail'])rows.push({label:stage==='first'?'首款到账':'尾款到账',value:c.paymentConditions[stage]?.text||'待财务确认'});
   if(c.request){rows.push({label:'设计需求',value:c.request.text});const basis=c.request.basis;if(basis)rows.push({label:'尺寸依据',value:`预算 V${basis.version} · ${basis.width}m × ${basis.depth}m · ${basis.floors}层 · ${basis.area}㎡`});if(c.request.attachment)rows.push({label:'需求附件',value:c.request.attachment.name,file:c.request.attachment});}const order=c.serviceOrders?.at(-1);if(order){rows.push({label:'设计服务总额',value:'￥'+B.money(order.total)});for(const stage of ['first','tail']){const pay=require('../../../utils/intercompany').condition(c,stage);rows.push({label:stage==='first'?'首款':'尾款',value:'应付 ￥'+B.money(order[stage])+' · '+pay.text});}}
   if(c.businessFlow?.completed)rows.push({label:'设计完成',value:c.businessFlow.completed.date+' · '+c.businessFlow.completed.by});
   if(c.businessFlow?.dispatch)rows.push({label:'交付记录',value:c.businessFlow.dispatch.date+' · '+c.businessFlow.dispatch.note});
   if(c.businessFlow?.feedbacks?.at(-1))rows.push({label:'本次修改意见',value:c.businessFlow.feedbacks.at(-1).note});
   if(this.view==='receive'||packet)for(const r of SF.packageRows(c))rows.push({label:r.label,value:r.file?.name,file:r.file});
   if(c.handover?.note)rows.push({label:'电子交底',value:c.handover.note});
   for(const r of c.returns||[])if(r.status==='pending')rows.unshift({label:'待补充',value:r.reason});
   const labels={submit:'提交设计委托',feedback:'提交修改意见',complete:'设计完成',dispatch:'成果已下发',handover:c.handover?'补充后重新提交':'提交资料移交',receive:'确认接收'};
   const nextAllowed=n.actionable&&map[n.view]&&SF.allowed(map[n.view],c);
   this.action=can?action:'';this.revision=c.revision;this.nextView=!can&&nextAllowed?n.view:'';
   this.setData({title:({submit:'设计委托',feedback:'登记修改意见',progress:'委托与交付',handover:'成果与移交',complete:'登记设计完成',dispatch:'登记成果下发',receive:'业务资料接收'})[this.view]||'委托与交付',view:this.view,customer:{id:c.id,name:c.name,city:c.city,revision:c.revision},form,fields,rows,error:'',primary:can?labels[this.view]:this.nextView?'继续办理':'',canDraft:can&&['submit','handover'].includes(this.view),canReturn:can&&this.view==='receive',canFeedback:can&&this.view==='handover'&&!c.handover,managerOptions:(X.rawBook(c.companyId)?.staff||[]).filter(p=>p.active!==false&&require('../../../utils/organization').staffHas(p,'engineer')).map(p=>({id:p.id,name:p.name})),selectedManager:(X.rawBook(c.companyId)?.staff||[]).find(p=>p.id===form.managerId)?.name||'',isComplete:!!c.acceptance});
  }catch(e){this.action='';this.nextView='';this.revision=undefined;this.initialized=false;this.adminDesk=null;this.setData({error:e.message,customer:null,rows:[],tasks:[],taskCount:0,metrics:[],recent:[],projectRows:[],projectFilters:[],projectCounts:{},projectTotal:0,projectHasMore:false,form:{},fields:[],preview:null,primary:'',canDraft:false,canReturn:false,canFeedback:false,managerOptions:[],selectedManager:'',isComplete:false});}
 },
 input(e){this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value,error:''});},
 renderAdminProjects(reset=false){if(!this.adminDesk)return;if(reset)this.projectLimit=20;const page=Admin.paginate(this.adminDesk.rows,this.data.keyword,this.data.projectFilter,this.projectLimit);this.setData({projectRows:page.rows,projectTotal:page.total,projectHasMore:page.hasMore});},
 searchProjects(e){this.setData({keyword:e.detail.value});this.renderAdminProjects(true);},
 selectProjectFilter(e){this.setData({projectFilter:e.currentTarget.dataset.filter});this.renderAdminProjects(true);},
 loadMoreProjects(){if(!this.data.projectHasMore)return;this.projectLimit+=20;this.renderAdminProjects(false);},
 onReachBottom(){if(this.view==='projects')this.loadMoreProjects();},
 openMetric(e){this.tabStates=this.tabStates||{};this.tabStates.admin={keyword:this.data.keyword,projectFilter:this.data.projectFilter};this.view='projects';this.setData({title:'项目',view:'projects',keyword:'',projectFilter:e.currentTarget.dataset.filter||'全部'});this.renderAdminProjects(true);wx.pageScrollTo({scrollTop:0,duration:0});},
 switchWorkspaceTab(tab){const home=tab.key==='business'&&tab.index===19,projects=tab.key==='design-admin-projects'&&tab.index===20;if(!home&&!projects)return false;this.tabStates=this.tabStates||{};this.tabStates[this.view]={keyword:this.data.keyword,projectFilter:this.data.projectFilter};const next=projects?'projects':'admin';this.view=next;this.setData({title:next==='projects'?'项目':'首页',view:next,keyword:'',projectFilter:'全部',...(this.tabStates[next]||{})});this.renderAdminProjects(true);wx.pageScrollTo({scrollTop:0,duration:0});return true;},
 check(e){this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value.length>0,error:''});},
 chooseManager(e){const p=this.data.managerOptions[Number(e.detail.value)];if(p)this.setData({'form.managerId':p.id,selectedManager:p.name});},
 chooseFile(e){const key=e.currentTarget.dataset.key;wx.chooseMessageFile({count:1,type:'file',success:async r=>{try{const f=r.tempFiles[0],saved=await require('../../../utils/creation').persistMedia(f.path);this.setData({['form.'+key]:{name:f.name,path:saved,size:f.size},error:''});}catch(err){this.setData({error:err.message});}},fail:err=>{if(!/cancel/i.test(err.errMsg||''))this.setData({error:'文件选择失败，请重新选择'});}});},
 preview(e){const f=this.data.form[e.currentTarget.dataset.key]||this.data.rows[Number(e.currentTarget.dataset.index)]?.file;if(!f)return;if(f.text||f.sample)return this.setData({preview:{name:f.name,text:f.text||'该文件暂无预览内容。'}});if(f.path)wx.openDocument({filePath:f.path,showMenu:true,fail:()=>this.setData({error:'此格式无法直接预览，请核对原文件后继续'})});},
 closePreview(){this.setData({preview:null});},
 openTask(e){Nav.open(e.currentTarget.dataset.url);},
 openFeedback(){Nav.open(SF.url(this.id,'feedback'));},
 submit(){
  if(this.data.busy)return;
  if(this.nextView)return Nav.open(SF.url(this.id,this.nextView));
  this.setData({busy:true,error:''});try{if(this.view==='policy'){SF.savePolicy(this.data.form);wx.showToast({title:'标准规则已保存',icon:'success'});this.refresh(true);}else{X.assert(this.action,'当前没有可提交的办理动作');SF.run(this.id,this.action,this.data.form,this.revision);this.view='progress';this.initialized=false;this.refresh(true);wx.showToast({title:'已保存并更新待办',icon:'success'});}}catch(e){this.setData({error:e.message});}finally{this.setData({busy:false});}
 },
 saveDraft(){try{const action=this.view==='submit'?'commission-submit':'packet-submit';B.write(this.id,n=>{n.drafts[B.draftKey('offline:'+this.view)]=copy(this.data.form);},'暂存业务资料','draft:'+action,this.revision);this.refresh(false);wx.showToast({title:'已暂存',icon:'success'});}catch(e){this.setData({error:e.message});}},
 returnPacket(){try{SF.run(this.id,'packet-return',this.data.form,this.revision);this.view='progress';this.refresh(true);}catch(e){this.setData({error:e.message});}},
});
