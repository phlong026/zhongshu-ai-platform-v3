const {routeValue,fail}=require('../../utils/view-container');
const auth=require('../../api/auth');
const AccountLogin=require('../../utils/account-login');
const http=require('../../api/http');
const orgApi=require('../../api/org');
const STAFF_MOBILE_AMBIGUOUS=1101000023;
const isOrgManager=post=>!!http.token()&&!!post&&['boss','owner'].includes(post.roleCode);
const O=require('../../utils/organization');
function openApiHome(post) {
 const home=O.navigation(O.apiContext(post)).tabs[0];
 const options={url:home.url,fail:()=>wx.showToast({title:'工作台打开失败，请重试',icon:'none'})};
 // 专属工作台直接换根，避免 switchTab 的 onShow 内再次导航而互相等待。
 return home.native?wx.switchTab(options):wx.reLaunch(options);
}
const Files=require('../../utils/sample-files');
const displayPurchase=p=>p?{...p,quote:p.quote?{...p.quote,file:Files.displayFile(p.quote.file)}:p.quote}:p;
const Q=require('./procurement');
const A=require('../../utils/account');
const M=require('../../utils/platform-messages');
const validPeriod=value=>/^\d{4}-(0[1-9]|1[0-2])$/.test(String(value||''));
const titles={selection:'众墅甄选',product:'商品详情',procure:'采购需求',purchases:'我的采购单',purchase:'采购单详情',profile:'个人资料',learning:'学习中心',course:'课程详情',messages:'消息中心',help:'帮助与反馈',privacy:'账号与隐私',services:'加盟商服务',service:'门店形象服务',request:'提交服务需求',orders:'我的服务订单',order:'服务订单详情',plan:'方案确认',delivery:'成果接收',cancel:'取消处理',login:'登录',identity:'公司与工作身份',access:'访问提示',resource:'资料预览',policy:'隐私政策',agreement:'用户协议',binding:'账号绑定',devices:'登录设备',permissions:'隐私授权管理','personal-performance':'我的绩效',feedback:'反馈详情','api-members':'成员与权限'};
const Learning=require('../../utils/learning-catalog');
const courses=Learning.courses,resources=Learning.resources;
Page({
 data:{view:'learning',title:'学习中心',tab:'课程',category:'全部',categories:['全部','自媒体运营','招商','业务销售','设计','工程','财务绩效','AI应用'],messageTab:'全部',messageTabs:['全部','业务','学习','系统'],messageStatus:'',messageError:'',messageHasMore:false,messageBusy:false,messageMode:'ALL',messagePending:0,messageModule:'',messageModuleIndex:0,messageModules:[],messageKeyword:'',messageDetail:null,messagePartial:'',orderTab:'全部',orderTabs:['全部','待沟通','待确认','进行中','已完成'],feedbackTab:'提交反馈',feedbackType:'使用问题',keyword:'',form:{},error:'',checked:false,expanded:false,courseTab:'课程目录',modal:null,paymentReceipt:null,busy:false,loginMode:'account',loginPolicy:null,consentOpen:false,consentChecked:false,consentDocument:'',passwordVisible:false,newPasswordVisible:false,confirmPasswordVisible:false,loginBusy:false,matchBusy:false,accountMatches:[],accountMatchesHasMore:false,selectedAccount:null,defaultAccount:null,rememberDefault:true,passwordChangeRequired:false,pendingAccountLogin:null,wechatPhoneRequired:false,wechatPhoneGeneration:null,staffAccountDelivery:null,services:A.services,chapters:A.chapters,apiLeavers:[],apiReceivers:[],apiPendingRows:[],handoverPendingError:false,handoverSource:null,handoverTargetUserId:null,handoverTargetIndex:-1,handoverBusy:false,handoverResult:null,reassignHint:''},
 onLoad(options){if(http.token()&&['learning','course','resource'].includes(options.view)){const params=options.view==='course'&&options.id?'?id='+encodeURIComponent(options.id):options.tab==='资料'||options.view==='resource'?'?tab='+encodeURIComponent('资料'):'';wx.redirectTo({url:'/pages/account/training/index'+(options.entry==='academy'&&!params?'?entry=academy':params)});return;}this.options=options||{};// 展陈设计与众墅甄选目前只做展示：下单、订单、采购单类链接统一回到展示页
  const showcase={request:'services',orders:'services',order:'services',plan:'services',delivery:'services',cancel:'services',procure:'selection',purchases:'selection',purchase:'selection'};const view=titles[showcase[options.view]||options.view]?showcase[options.view]||options.view:'learning';this.id=routeValue(options.id)||'';this.serviceId=routeValue(options.service)||'store';const preferred=view==='login'?AccountLogin.defaultAccount():null;this.setData({view,title:titles[view],tab:routeValue(options.tab||'课程'),category:routeValue(options.category||'全部'),statusHeight:(getApp().globalData.windowInfo||wx.getSystemInfoSync()).statusBarHeight||20,defaultAccount:preferred,selectedAccount:preferred,form:preferred?{accountQuery:preferred.loginName}:this.data.form});this.refresh(true);if(view==='login')this.loadLoginPolicy();},
 onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
 onShow(){if(this.options)this.refresh(false);if(this.data.view==='login'&&http.token()&&!this.data.loginBusy)this.resumeLogin();},
 resumeLogin(){
  const generation=this.cancelLoginFlow({clearSensitive:true,keepSession:true});
  this.setData({loginBusy:true,error:''});
  return auth.restoreSession().then(post=>{if(this.loginFlowCurrent(generation)&&post)this.goAfterLogin(generation);})
   .catch(error=>{if(this.loginFlowCurrent(generation)&&!error.silent&&error.code!==401)this.setData({error:'自动登录暂未完成，请检查网络后重试'});})
   .finally(()=>{if(this.loginFlowCurrent(generation))this.setData({loginBusy:false});});
 },
 refresh(reset=false){const s=A.load(),c=A.company(s),a=A.access.context(s),v=this.data.view,apiPost=(()=>{try{return http.token()?auth.currentApiPost():null;}catch(e){return null;}})(),apiLoggedIn=!!apiPost,loggedIn=s.session.loggedIn||apiLoggedIn;this.learningRole=apiLoggedIn?apiPost.roleCode:(a.active?a.role:'');const visibleCourses=Learning.coursesFor(this.learningRole),visibleResources=Learning.resourcesFor(this.learningRole);const key=apiLoggedIn?['api',apiPost.orgId,apiPost.postId].join(':'):[a.companyId,a.staffId,a.role].join(':');if(this.paymentActorKey&&this.paymentActorKey!==key)this.setData({paymentReceipt:null});if(this.paymentActorKey!==key){require('./platform-messages-page').invalidateAction(this);this.__messagesGen=(this.__messagesGen||0)+1;this.__messagesFlight=null;this.apiMessages=[];this.apiUnread=0;this.setData({messageStatus:'',messageError:'',messageHasMore:false,messageBusy:false,messageDetail:null,messagePending:0,messagePartial:''});}this.paymentActorKey=key;const guarded=['learning','course','resource','api-members','profile','messages','request','orders','order','plan','delivery','cancel','personal-performance','devices','permissions','binding','feedback','procure','purchases','purchase'],apiViews=['learning','course','resource','profile','messages','identity','devices','permissions','binding','personal-performance'],performancePeriod=v==='personal-performance'&&validPeriod(this.options.period)?this.options.period:'',localAllowed=v==='api-members'?false:v==='identity'?s.session.loggedIn:A.permitted(s,'account'),apiAllowed=apiLoggedIn&&(apiViews.includes(v)||v==='api-members'&&isOrgManager(apiPost));if((guarded.includes(v)||v==='identity')&&!localAllowed&&!apiAllowed){wx.redirectTo({url:'/pages/account/index?view='+(loggedIn?'access':'login')+'&return='+encodeURIComponent('/pages/account/index?view='+v+(this.id?'&id='+encodeURIComponent(this.id):'')+(performancePeriod?'&period='+performancePeriod:''))});return;}
 if((v==='course'&&!visibleCourses.some(course=>course.id===this.id))||(v==='resource'&&!visibleResources.some(resource=>resource.id===this.id))){this.setData({learningDenied:true,missing:false,currentCourse:null,resource:null,chapters:[],filteredCourses:[],filteredResources:[],error:''});return;}
 if(v==='personal-performance'){if(this.performanceRedirecting)return;const period=validPeriod(this.options.period)?this.options.period:validPeriod(this.id)?this.id:'';this.performanceRedirecting=true;wx.redirectTo({url:'/pages/admin/tasks/index?view=mine'+(period?'&period='+period:''),fail:()=>{this.performanceRedirecting=false;this.fail('绩效页面打开失败，请重试');}});return;}
 const service=A.services.find(x=>x.id===this.serviceId)||A.services[0],o=A.order(s,this.id),currentCourse=visibleCourses.find(x=>x.id===this.id)||visibleCourses[0]||{},progress=this.courseProgressView(currentCourse.id);const form=reset?(v==='profile'?{name:s.profile.name}:v==='request'?Object.assign({service:this.serviceId,token:'request-'+Date.now(),address:'',contact:s.profile.name,phone:'',note:'',photos:[],sizePhotos:[]},s.drafts[A.draftKey(s,this.serviceId)]||{}):v==='login'?this.data.form:{}):this.data.form;
 const resource=visibleResources.find(x=>x.id===this.id);this.setData({learningDenied:false,displayCompany:apiPost?apiPost.orgName||'':c?c.name:'',categories:['全部',...new Set(visibleCourses.map(course=>course.category))],resumeCourse:visibleCourses[0]||null,resourceName:(resources.find(row=>row.id===currentCourse.resourceId)||{}).name||'',state:A.visible(s),canPurchaseQuote:require('../../utils/organization').has(a.role,'engineer')||['boss','owner'].includes(a.role),canPurchaseEngineering:require('../../utils/organization').has(a.role,'engineer'),canConfirmService:!!o&&['boss','owner'].includes(s.session.role)&&o.companyId===s.session.companyId,loggedIn,profile:s.profile,company:c,title:v==='learning'&&this.options.entry==='academy'?'商学院':v==='service'&&service.id!=='store'?service.name:titles[v],role:this.displayRole(apiPost,s.session.role),accessLabel:routeValue(this.options.content)||((this.options.return||'').match(/view=([a-z-]+)/)||[])[1]||'企业业务',form,service,order:A.displayOrder(o),progress,currentCourse,chapters:Learning.chapters[currentCourse.id]||[],courseImage:currentCourse.image||'',resource:resource||null,resourceFavorite:A.permitted(s,'account')&&s.favorites.includes(this.id),unread:http.token()?(this.apiUnread||0):M.totalUnread(A.scoped(s,s.messages).filter(m=>!m.read).length,0),selectedRole:this.data.selectedRole||s.session.role,roleOptions:(c?c.roles:[]).map(id=>({id,name:A.roles[id],description:require('../../utils/organization').navigation({...a,role:id}).homeTitle+' · 按该岗位授权范围办理'})),apiLoggedIn,apiRoleOptions:this.apiPostOptions(),apiCurrentName:apiPost?(O.roles[apiPost.roleCode]||apiPost.roleCode)+'（'+(apiPost.orgType==='headquarters'?'总部':'门店')+'）':'',apiCanManage:!!apiPost&&['boss','owner'].includes(apiPost.roleCode),maskedPhone:s.profile.phone?s.profile.phone.slice(0,3)+'****'+s.profile.phone.slice(-4):'未绑定',feedbacks:A.scoped(s,s.feedback),missing:['order','plan','delivery','cancel'].includes(v)&&!o||['service','request'].includes(v)&&!A.services.some(x=>x.id===this.serviceId)});if(reset&&o&&o.plan)this.setData({planVersion:o.plan.version});this.refreshProcurement(reset);if(v==='messages'||v==='profile')this.syncApiMessages();if((v==='profile'||v==='identity')&&reset)this.syncApiProfile();if(v==='learning'||v==='course')this.syncCourseProgress();this.filter();if(v==='api-members')this.loadApiMembers();},
 displayRole(apiPost,legacyRole){return apiPost?(O.roles[apiPost.roleCode]||apiPost.roleCode):(A.roles[legacyRole]||'');},
 apiPostOptions(){try{const cur=auth.currentApiPost();return O.sortPostsForDisplay(auth.apiPosts()).map(p=>({postId:p.postId,name:O.roles[p.roleCode]||p.roleCode,orgName:p.orgName||'',enabled:O.enabled(p.orgType,p.roleCode),orgType:p.orgType==='headquarters'?'总部':'门店',lead:p.lead?'· 负责人':'',selected:!!cur&&String(p.postId)===String(cur.postId)}));}catch(e){return[];}},
 courseProgressView(courseId){ // 学习进度 API 视图（无 token/未加载=零进度，不回落本地——切生产口径）
  const rows=Learning.chapters[courseId]||[],total=rows.length;
  const done=((this.apiCourseDone||{})[courseId]||[]).filter(i=>Number.isInteger(i)&&i>=0&&i<total).sort((x,y)=>x-y);
  const completed=done.length,current=Math.min(completed,total-1);
  return {completed,total,current,percent:total?completed/total*100:0,chapter:rows[Math.min(current,Math.max(total-1,0))],done};
 },
 syncCourseProgress(){ // 后端课程进度（/franchise/content/course-progress 全量拉取→当前课程视图重建）
  // Codex R1 P2-2/R2 P2/R3 P2：代次守卫在 token 检查前递增（退出/换会话也失效旧响应）；
  // 缓存绑定 token 指纹——换账号即失效（退出后立即渲染零进度，换 B 拉取失败不残留 A 的进度）
  this.__courseGen=(this.__courseGen||0)+1;const gen=this.__courseGen;
  const tk=http.token(),currentCourse=Learning.coursesFor(this.studyRole()).find(x=>x.id===this.id)||Learning.coursesFor(this.studyRole())[0]||{};
  if(this.__courseToken!==tk){ // Codex R4 P2：换账号立即清缓存并同步渲染零进度（防后续拉取失败时页面残留旧账号进度）
    this.__courseToken=tk;this.apiCourseDone=null;
    if(['learning','course'].includes(this.data.view))this.setData({progress:this.courseProgressView(this.data.view==='course'?currentCourse.id:(this.data.resumeCourse||{}).id)});
  }
  if(!tk){this.apiCourseDone=null;
    if(['learning','course'].includes(this.data.view))this.setData({progress:this.courseProgressView(this.data.view==='course'?currentCourse.id:(this.data.resumeCourse||{}).id)});
    return Promise.resolve();}
  return require('../../api/franchise/content').drafts.courseProgress().then(rows=>{
    if(gen!==this.__courseGen)return;
    const map={};(rows||[]).filter(r=>Learning.canStudy(this.studyRole(),r.courseId)).forEach(r=>{(map[r.courseId]=map[r.courseId]||[]).push(Number(r.chapterIndex));});
    this.apiCourseDone=map;
    if(['learning','course'].includes(this.data.view))this.setData({progress:this.courseProgressView(this.data.view==='course'?currentCourse.id:(this.data.resumeCourse||{}).id)});
  }).catch(()=>{}); // 静默：进度区维持零进度，完成章节时再报错
 },
 refreshProcurement(reset){const s=this.data.state,v=this.data.view,product=Q.products.find(x=>x.id===(this.id||'tile-gray')),purchase=displayPurchase(Q.get(s,this.id||'purchase-zhang'));const currentProduct=v==='purchase'&&purchase?Q.products.find(x=>x.id===purchase.productId):product;this.setData({products:Q.products,product:currentProduct||null,projects:Q.visibleProjects(s),purchase:purchase||null,purchaseProject:purchase&&Q.visibleProjects(s).find(x=>x.id===purchase.projectId)||null,purchases:Q.list(s).map(x=>Object.assign(displayPurchase(x),{product:Q.products.find(p=>p.id===x.productId),project:Q.visibleProjects(s).find(p=>p.id===x.projectId)}))});if(['procure','product'].includes(v)&&!product)this.setData({missing:true});if(v==='procure'&&reset&&product){const selected=Q.visibleProjects(s).find(p=>p.id===this.options.projectId)||(!this.options.projectId?Q.visibleProjects(s)[0]:null);this.setData({selectedProjectName:selected?.name||'',form:{productId:product.id,projectId:selected?.id||'',quantity:'',date:A.localDate(),address:selected?.address||'',contact:selected?.manager||'',token:'procure-'+Date.now(),note:''}});}},
 pickProject(e){const p=this.data.projects[Number(e.detail.value)];if(!p)return;this.setData({selectedProjectName:p.name,'form.projectId':p.id,'form.address':p.address,'form.contact':p.manager});},openPurchaseProject(){const p=this.data.purchaseProject;if(p?.engineeringId)require('../../utils/page-navigation').open('/pages/build/materials/index?id='+encodeURIComponent(p.engineeringId)+'&purchaseId='+encodeURIComponent(this.data.purchase.id));},
 submitProcure(){try{const id=A.mutate(s=>Q.submit(s,this.data.form));this.navigate('purchase',id);}catch(e){this.fail(e.message);}},
 confirmPurchase(){if(!this.data.checked)return this.fail('请核对当前报价、数量与配送安排');try{A.mutate(s=>Q.confirm(s,this.data.purchase.id,this.data.purchase.quote.version));this.refresh();}catch(e){this.fail(e.message);}},
 filter(){const s=this.data.state,kw=this.data.keyword||'',cat=this.data.category;this.setData({unread:http.token()?(this.apiUnread||0):this.data.view==='messages'?0:A.scoped(s,s.messages).filter(m=>!m.read).length,filteredProducts:Q.products.filter(x=>(cat==='全部'||x.category===cat)&&(x.name+x.spec).includes(kw)),filteredPurchases:(this.data.purchases||[]).filter(x=>(this.data.orderTab==='全部'||this.data.orderTab==='供货中'&&['供货中','待安排供货'].includes(x.status)||this.data.orderTab===x.status)&&(((x.project&&x.project.name||'关联项目')+(x.product&&x.product.name||'材料')).includes(kw))),filteredCourses:Learning.coursesFor(this.studyRole()).filter(x=>(cat==='全部'||x.category===cat)&&(x.name+x.category).includes(kw)),filteredResources:Learning.resourcesFor(this.studyRole()).filter(x=>(cat==='全部'||x.category===cat)&&(x.name+x.category).includes(kw)&&(!this.data.favoritesOnly||s.favorites.includes(x.id))),messages:this.data.view==='messages'?require('./message-center').rows(this,A.scoped(s,s.messages)):[],orders:A.visibleOrders(s).filter(x=>(this.data.orderTab==='全部'||x.status===this.data.orderTab)&&x.name.includes(kw)).map(x=>Object.assign({},x,{image:(A.services.find(y=>y.id===x.service)||A.services[0]).image})),filteredServices:A.services.filter(x=>x.name.includes(kw))});},
 go(e){const d=e.currentTarget.dataset;if(d.view==='procurement')return this.navigate('selection');this.navigate(d.view,d.id,d.service);},
 navigate(view,id='',service=''){if(!titles[view])return;const url='/pages/account/index?view='+view+(id?'&id='+encodeURIComponent(id):'')+(service?'&service='+encodeURIComponent(service):'')+(this.options.projectId?'&projectId='+encodeURIComponent(this.options.projectId):'');require('../../utils/page-navigation').open(url);},
  input(e){const key=e.currentTarget.dataset.key,value=e.detail.value;const patch={['form.'+key]:value,error:''};if(key==='accountQuery'){this.cancelLoginFlow({clearSensitive:true});Object.assign(patch,{accountMatches:[],accountMatchesHasMore:false,selectedAccount:null});}this.setData(patch);if(key==='accountQuery')this.scheduleAccountMatch();if(this.data.view==='request')this.draft();},search(e){this.setData({keyword:e.detail.value.trim()});this.filter();},tab(e){this.setData({[e.currentTarget.dataset.key||'tab']:e.currentTarget.dataset.value,keyword:''});this.filter();},toggle(){this.setData({checked:!this.data.checked,error:''});},expand(){this.setData({expanded:!this.data.expanded});},fail,
 draft(){try{A.mutate(s=>{s.drafts[A.draftKey(s,this.serviceId)]=this.data.form;});}catch(e){this.fail('暂存失败，当前输入仍保留，请重试');}},
 // 微信官方头像填写能力（open-type=chooseAvatar）：已登录走上传+PUT 落库；未登录仅本机保存
 async onChooseAvatar(e){if(this.data.busy)return;const temp=e.detail&&e.detail.avatarUrl;if(!temp)return;
  if(!http.token())return this.saveAvatarLocal(temp);
  const check=require('../../utils/platform-profile').normalizeNickname(this.data.form.name||A.load().profile.name);
  if(!check.ok)return this.fail(check.error);
  const name=check.value,sessionAlive=http.requestGuard('avatar-upload'),generation=this.__profileGeneration||0;
  const alive=()=>sessionAlive()&&generation===(this.__profileGeneration||0);
  this.setData({busy:true,error:''});
  try{
   const url=await http.upload('/infra/file/upload',temp,{directory:'profile/avatar'});
   if(!alive())return;
   if(!/^https?:\/\//.test(String(url||''))||String(url).length>512)throw {message:'头像地址无效，请重新选择'};
   await http.put('/platform/profile/mine',{nickname:name,avatar:url});
   if(!alive())return;
   A.mutate(s=>{s.profile.avatar=url;s.profile.name=name;});
   this.setData({'profile.avatar':url,'profile.name':name,'form.name':name,error:''});
   wx.showToast({title:'头像已保存',icon:'success'});
  }catch(err){if(alive()&&!(err&&err.silent))this.fail((err&&err.message)||'头像保存失败，请重试');}
  finally{if(alive())this.setData({busy:false});}
 },
 saveAvatarLocal(temp){wx.saveFile({tempFilePath:temp,success:f=>{try{A.mutate(s=>{s.profile.avatar=f.savedFilePath;});this.refresh();wx.showToast({title:'头像已保存到本机（登录后可同步到账号）',icon:'none'}); // M11-T04：本机态清楚提示
}catch(e){this.fail('头像保存失败，请重试');}},fail:()=>this.fail('头像保存失败，请重新选择')});},
 photo(e){const key=e.currentTarget.dataset.key||'photos',limit=key==='screenshots'?3:6;if((this.data.form[key]||[]).length>=limit)return this.fail('已达到图片数量上限');wx.chooseMedia({count:limit-(this.data.form[key]||[]).length,mediaType:['image'],success:r=>{Promise.all(r.tempFiles.map(f=>new Promise((resolve,reject)=>wx.saveFile({tempFilePath:f.tempFilePath,success:x=>resolve(x.savedFilePath),fail:reject})))).then(paths=>{this.setData({['form.'+key]:(this.data.form[key]||[]).concat(paths),error:''});if(this.data.view==='request')this.draft();}).catch(()=>this.fail('图片保存失败，请重新选择'));}});},
 removePhoto(e){const {key,index}=e.currentTarget.dataset;this.setData({['form.'+key]:this.data.form[key].filter((x,i)=>i!==Number(index))});if(this.data.view==='request')this.draft();},
 async saveProfile(){if(this.data.busy)return;const check=require('../../utils/platform-profile').normalizeNickname(this.data.form.name);if(!check.ok)return this.fail(check.error);
  const sessionAlive=http.sessionGuard(),generation=this.__profileGeneration||0,alive=()=>sessionAlive()&&generation===(this.__profileGeneration||0);
  this.setData({busy:true,error:''});
  try{if(http.token())await http.put('/platform/profile/mine',{nickname:check.value});if(!alive())return;A.mutate(s=>{s.profile.name=check.value;});this.setData({'profile.name':check.value,'form.name':check.value});wx.showToast({title:'已保存',icon:'success'});}
  catch(error){if(alive()&&!(error&&error.silent))this.fail(error.message||'保存失败，请重试');}
  finally{if(alive())this.setData({busy:false});}
 },
 studyRole(){const post=http.token()?auth.currentApiPost():null;return post?post.roleCode:'';},
 lesson(){if(!Learning.canStudy(this.studyRole(),(this.data.currentCourse||{}).id))return this.fail('当前岗位无此课程权限');this.setData({modal:{type:'notice',title:'课程暂未开放',text:'教学视频尚未上线，上线后可播放并记录学习进度。'}});},continueCourse(){this.lesson({currentTarget:{dataset:{index:this.data.progress.current}}});},
 completeLesson(){ // API 化：章节完成走后端（幂等键重试复用；本地 A.completeChapter 不再用）
  if(!http.token())return this.fail('请先登录后端账号，学习进度将保存到你的岗位');
  const courseId=(this.data.currentCourse||{}).id;if(!Learning.canStudy(this.studyRole(),courseId))return this.fail('当前岗位无此课程权限');const index=Number(this.data.form.chapter);
  if(!Number.isInteger(index))return this.fail('章节无效，请重新选择');
  if(!this.__chapterKeys)this.__chapterKeys={};
  const k='chapter-'+courseId+'-'+index;
  if(!this.__chapterKeys[k])this.__chapterKeys[k]=k+'-'+Date.now();
  this.setData({busy:true});
  require('../../api/franchise/content').drafts.completeChapter(courseId,index,this.__chapterKeys[k]).then(()=>{
    delete this.__chapterKeys[k];
    this.closeModal();
    return this.syncCourseProgress();
  }).catch(e=>this.fail((e&&e.msg)||'章节完成保存失败，请重试')).finally(()=>this.setData({busy:false}));
 },openCourse(e){const id=e.currentTarget.dataset.id;if(!Learning.canStudy(this.studyRole(),id))return this.fail('当前岗位无此课程权限');if(!Learning.chapters[id])return this.setData({modal:{type:'notice',title:'课程内容',text:'该课程暂未配置教学内容，请联系培训管理员。'}});this.navigate('course',id);},previewResource(e){const id=e.currentTarget.dataset.id;if(!Learning.resourcesFor(this.studyRole()).some(row=>row.id===id))return this.fail('当前岗位无此资料权限');this.navigate('resource',id);},
 favorite(){if(!A.permitted(A.load(),'account'))return this.navigate('login');try{A.mutate(s=>{s.favorites=s.favorites.includes(this.id)?s.favorites.filter(x=>x!==this.id):s.favorites.concat(this.id);});this.refresh();}catch(e){this.fail('收藏保存失败，请重试');}},favorites(){this.setData({favoritesOnly:!this.data.favoritesOnly,tab:'资料'});this.filter();},
 read(e){const s=A.load(),id=e.currentTarget.dataset.id,m=M.mergeSources(A.scoped(s,s.messages),[],this.apiMessages||[]).find(x=>x.id===id);if(!m)return this.fail('消息不存在');if(M.isApiMessage(m))return this.readApi(m);let designTarget='';if(m.route==='design-dispatch'||m.route==='design-progress'){const B=require('../../utils/business'),SF=require('../../utils/sales-flow'),c=B.customer(m.objectId);if(!c)return this.fail('关联委托不存在或当前岗位无权访问');const next=SF.next(c);designTarget=m.route==='design-dispatch'&&next.view==='dispatch'&&next.actionable?'dispatch':'progress';}try{A.mutate(state=>A.markMessageRead(state,id));}catch(e){return this.fail('消息状态保存失败');}if(m.payment){this.refresh(false);this.setData({paymentReceipt:m.payment});return;}if(designTarget)return require('../../utils/page-navigation').open(require('../../utils/sales-flow').url(m.objectId,designTarget));if(m.route==='customer'){const B=require('../../utils/business');if(!B.customer(m.objectId))return this.fail('关联客户不存在或当前账号无权访问');return require('../../utils/page-navigation').open('/pages/workflow/index?view=customer&id='+encodeURIComponent(m.objectId));}this.navigate(m.route,m.objectId);},closePaymentReceipt(){this.setData({paymentReceipt:null});},
 readApi(m){return require('./platform-messages-page').read.call(this,m);},
 messageChange(e){const d=e.currentTarget.dataset;return require('./platform-messages-page').change.call(this,{[d.key]:d.value});},
 messageModuleChange(e){const i=Number(e.detail.value),m=this.data.messageModules[i];if(m)return require('./platform-messages-page').change.call(this,{messageModule:m.id,messageModuleIndex:i});},
 messageSearchInput(e){this.setData({messageKeyword:e.detail.value});},
 messageSearch(){return require('./platform-messages-page').change.call(this,{messageKeyword:(this.data.messageKeyword||'').trim()});},
 messageDetails(e){return require('./platform-messages-page').details.call(this,e);},
 messageAction(e){return require('./platform-messages-page').action.call(this,e);},
 closeMessageDetail(){this.setData({messageDetail:null});},
 syncApiMessages(append=false){return require('./platform-messages-page').sync.call(this,append);},
 retryMessages(){return this.syncApiMessages(false);},
 moreMessages(){return this.syncApiMessages(true);},
 syncApiProfile(){// GET 读侧（20 号任务书 D-2）：先 A.mutate 落 storage 再 setData——navigateBack 复用旧实例时经 refresh 重读 storage 兜住回填；失败/无 token 静默降级本地
  if(!http.token())return;const alive=http.requestGuard('profile');http.get('/platform/profile/mine').then(remote=>{if(!alive())return;const PP=require('../../utils/platform-profile'),profile=PP.mergeProfile(A.load().profile,remote);A.mutate(s=>{s.profile=profile;});this.setData({profile,maskedPhone:profile.phone?PP.maskPhone(profile.phone):'未绑定','form.name':profile.name});}).catch(()=>{});},
 readAll(){return require('./platform-messages-page').readAll.call(this);},
 submitFeedback(){if(http.token())return this.fail('反馈服务暂未开通，请联系门店管理员');const f=this.data.form;if(!(f.feedback||'').trim())return this.fail('请描述遇到的问题');if(f.feedback.length>300)return this.fail('问题描述最多300字');if(f.contact&&!/^1\d{10}$/.test(f.contact))return this.fail('请输入正确的联系方式');try{A.mutate(s=>{if(!s.session.loggedIn)throw Error('请先登录再提交反馈');s.feedback.unshift({id:'feedback-'+Date.now(),companyId:s.session.companyId,userId:s.session.userId,type:this.data.feedbackType,text:f.feedback,photos:f.screenshots||[],contact:f.contact||s.profile.phone,status:'已保存待同步',date:new Date().toISOString()});});this.setData({form:{},feedbackTab:'我的反馈'});this.refresh();wx.showToast({title:'已保存到本地',icon:'none'});}catch(e){this.fail(e.message);}},
 submitRequest(){if(this.data.busy)return;this.setData({busy:true});try{const id=A.mutate(s=>A.submitRequest(s,this.data.form));this.setData({busy:false});wx.redirectTo({url:'/pages/account/index?view=order&id='+encodeURIComponent(id)});}catch(e){this.fail(e.message);}},
 orderAction(e){const action=e.currentTarget.dataset.action;if(action==='cancel-resolve')return this.setData({modal:{type:'cancel-resolution',title:'核实取消申请',action}});if(['demo-plan','demo-delivery','demo-redelivery'].includes(action))return this.setData({modal:{type:'demo',title:'记录服务进度',action,text:'仅更新当前设备中的订单进度，不会通知服务方。'}});if(action==='cancel')return this.setData({modal:{type:'cancel',title:'申请取消服务',action}});this.perform(action);},
 perform(action){try{A.mutate(s=>A.transition(s,this.id,action,{designerId:this.data.form.designerId,version:this.data.planVersion,checked:this.data.checked,note:this.data.form.note||'',result:this.data.form.cancelResult,file:this.data.form.cancelProof?.length?{name:'取消核对依据',paths:this.data.form.cancelProof}:null}));this.closeModal();this.setData({checked:false});this.refresh();wx.showToast({title:'已保存本地记录',icon:'none'});}catch(e){this.fail(e.message);}},
 previewPlan(){if(!this.data.order||!this.data.order.plan)return this.fail('方案尚未提交');const p=this.data.order.plan;this.setData({modal:{type:'preview',title:p.name,image:p.image,text:'当前订单 '+this.data.order.id+' · 方案 V'+p.version}});},
 previewDelivery(e){const file=(this.data.order&&this.data.order.delivery&&this.data.order.delivery.files||[])[Number(e.currentTarget.dataset.index)];if(!file)return this.fail('交付文件不存在');if(file.path)return wx.openDocument({filePath:file.path,showMenu:true,fail:()=>this.fail('文件无法读取，请联系服务方补传')});this.setData({modal:{type:'notice',title:file.name,text:'该文件暂不可下载。'}});},pickCancel(e){this.setData({'form.cancelResult':['核实未收款，取消','退回取消申请','款项待核实'][Number(e.detail.value)]});},selectRole(e){this.setData({selectedRole:e.currentTarget.dataset.role});},
 selectApiPost(e){const postId=e.currentTarget.dataset.postId;const post=auth.apiPosts().find(p=>String(p.postId)===String(postId));if(!post)return this.fail('岗位不存在或已失效，请重新登录');if(!O.enabled(post.orgType,post.roleCode))return this.fail('该岗位工作台暂未开放，请选择其他身份');auth.switchPost(postId);this.setData({staffAccountDelivery:null});wx.showToast({title:'已切换为'+(O.roles[post.roleCode]||post.roleCode)});this.refresh();openApiHome(post);},
 logoutApi(){try{auth.logout();this.setData({checked:false,staffAccountDelivery:null});this.refresh();wx.showToast({title:'已退出后端账号',icon:'none'});}catch(e){this.fail('退出失败，请重试');}},
 loadApiMembers(){const post=auth.currentApiPost();if(!isOrgManager(post))return Promise.resolve();const alive=http.sessionGuard();const seq=this.memberLoadSeq=(this.memberLoadSeq||0)+1; // R2 P1-2：序号守卫——旧刷新响应丢弃，不覆盖操作后最新列表
  this.setData({apiMembersBusy:true});
  // R3 P1：返回完整 Promise 链（交接链 return this.loadApiMembers() 须等刷新完成才释放 busy）
  return Promise.all([orgApi.orgTeams(post.orgId),orgApi.orgStaff(post.orgId),orgApi.handoverPending(post.orgId).catch(()=>{if(!alive())return [];this.handoverPendingFailed=true;return this.data.apiPendingRows||[];})]).then(([teams,staff,pending])=>{
  if(seq!==this.memberLoadSeq||!alive())return; // 旧响应丢弃（handoverBusy 也不动——释放权在交接调用链）
  const roles=(O.templates[post.orgType]||[]).filter(r=>O.enabled(post.orgType,r)&&(post.orgType!=='store'||!['boss','owner'].includes(r)));const rows=staff.map(r=>({...r,roleName:O.roles[r.roleCode]||r.roleCode}));const names=Object.fromEntries(rows.map(r=>[String(r.userId),r.name]));const seen={},leavers=rows.filter(r=>r.active!==false&&!seen[String(r.userId)]&&(seen[String(r.userId)]=1)).map(r=>({userId:r.userId,name:r.name,roleName:r.roleName}));
  // M01-T04 R1：交接来源与在岗列表分离——handoverSource 独立保留（补交接的已撤权人不在在岗列表，按 userId 对回真实条目；
  // 接收人恒从真实在岗成员生成（合成条目不进接收人）；接收人索引随刷新钳制
  // R7 P1：先比对旧上下文——组织/岗位已变（自动切岗）或无已完成加载（ctx 空）则不保留旧交接双方，
  // 防止旧 userId 提交到新组织；R8 P2-1：ctx 变化同步清结果清单/补交接提示（同组织刷新保留）
  const prevCtx=this.handoverCtx;
  const ctxChanged=!prevCtx||prevCtx.postId!==String(post.postId)||prevCtx.orgId!==post.orgId;
  let source=this.data.handoverSource;
  let targetUserId=this.data.handoverTargetUserId;
  if(ctxChanged){source=null;targetUserId=null;this.setData({handoverResult:null,reassignHint:''});} // R8 P2-1：切组织同步清旧组织的结果清单与补交接提示
  if(source){const real=leavers.find(l=>String(l.userId)===String(source.userId));
    // R6 P2：合成「已撤权」条目仅在该来源仍有待分配行时保留；交接完成/无待办后清空（结果清单继续显示，不影响重复提交）
    if(real)source={...real};
    else{const stillPending=(pending||[]).some(pl=>String(pl.fromUserId)===String(source.userId));source=stillPending?{userId:source.userId,name:source.name,roleName:'已撤权'}:null;}}
  const receivers=leavers.filter(l=>!source||String(l.userId)!==String(source.userId));
  // R4 P1：接收人按 userId 保留选择（索引钳制会在列表变动时换人）；原接收人消失→置空要求重选
  let targetIndex=targetUserId?receivers.findIndex(l=>String(l.userId)===String(targetUserId)):-1;
  if(targetIndex<0)targetUserId=null;
  const pendingFailed=this.handoverPendingFailed===true;this.handoverPendingFailed=false;
  this.setData({apiMembersBusy:false,apiTeamOptions:teams,apiStaffRows:rows,apiRoleOptionsForAdd:roles.map(r=>({id:r,name:O.roles[r]})),apiLeavers:leavers,handoverSource:source,apiReceivers:receivers,handoverTargetIndex:targetIndex,handoverTargetUserId:targetUserId,handoverPendingError:pendingFailed,apiPendingRows:(pending||[]).map(r=>({...r,fromName:names[String(r.fromUserId)]||('用户'+r.fromUserId),inFlightText:r.inFlight<0?'数量待核':'在途 '+r.inFlight+' 项'})),'form.teamIndex':this.data.form.teamIndex||0,'form.roleIndex':this.data.form.roleIndex||0});
  this.handoverCtx={orgId:post.orgId,postId:String(post.postId)}; // R6 P1：选择绑定加载时的组织/岗位（切岗失配即清空）
  }).catch(e=>{if(seq!==this.memberLoadSeq||!alive())return;this.setData({apiMembersBusy:false});this.fail(e.message||'成员数据加载失败，请下拉重试');});},
 pickHandoverLeaver(e){const source=this.data.apiLeavers[Number(e.detail.value)];if(!source)return;this.setHandoverSource(source);},
 setHandoverSource(source){const receivers=this.data.apiLeavers.filter(l=>String(l.userId)!==String(source.userId));const idx=this.data.handoverTargetUserId?receivers.findIndex(l=>String(l.userId)===String(this.data.handoverTargetUserId)):-1; // R4 P1：按 userId 保留接收人；切来源不换人
  this.setData({handoverSource:source,apiReceivers:receivers,handoverTargetIndex:idx,handoverTargetUserId:idx<0?null:this.data.handoverTargetUserId,reassignHint:''});},
 pickHandoverTarget(e){const idx=Number(e.detail.value),target=this.data.apiReceivers[idx];this.setData({handoverTargetIndex:idx,handoverTargetUserId:target?target.userId:null});},
 assertManage(){const post=http.token()?auth.currentApiPost():null;if(!post)return this.fail('请先登录后端账号')&&null;if(!isOrgManager(post))return this.fail('成员与权限由老板或门店负责人办理')&&null;
  // R6 P1：自动切岗（如交接返回岗位失效码后认证层切组织）后旧选择可能操作另一组织——失配即清空选择并要求重选
  const ctx=this.handoverCtx;
  if(!ctx||ctx.postId!==String(post.postId)||ctx.orgId!==post.orgId){this.setData({handoverSource:null,apiReceivers:[],handoverTargetUserId:null,handoverTargetIndex:-1,reassignHint:''});return this.fail('岗位或组织已切换，请重新选择交接双方')&&null;}
  return post;},
 // R2 P1-2：busy 由交接调用链持有并释放（成功=刷新完成后释放；失败/取消即释放）——成员加载不再代管，旧刷新无法绕过
 confirmHandover(){if(this.data.handoverBusy||this.data.apiMembersBusy)return;const post=this.assertManage();if(!post)return;const source=this.data.handoverSource;if(!source)return this.fail('请选择离职成员');if(!this.data.apiReceivers.length)return this.fail('本组织无其他在岗成员可接收，请先加人');const target=this.data.handoverTargetIndex>=0?this.data.apiReceivers[this.data.handoverTargetIndex]:null;if(!target)return this.fail('请选择接收人');const alive=http.sessionGuard();this.setData({handoverBusy:true,handoverResult:null});orgApi.staffHandover({orgId:post.orgId,fromUserId:source.userId,toUserId:target.userId}).then(res=>{if(!alive())return;this.setData({handoverResult:this.handoverResultView(res,source.name,target.name)});wx.showToast({title:'交接完成',icon:'success'});return this.loadApiMembers();}).then(()=>{if(alive())this.setData({handoverBusy:false});}).catch(e=>{if(!alive()||e&&e.silent)return;this.setData({handoverBusy:false});this.fail(e.message||'交接失败，请重试');});},
 confirmEmergency(){if(this.data.handoverBusy||this.data.apiMembersBusy)return;const post=this.assertManage();if(!post)return;const source=this.data.handoverSource;if(!source)return this.fail('请选择要撤权的成员');wx.showModal({title:'紧急撤权',content:'立即撤销 '+source.name+' 在本组织的任职与钱包会话，不等待交接；其在途事项将转入待分配清单。确认执行？',confirmText:'立即撤权',confirmColor:'#ad4a4a',success:r=>{if(!r.confirm||this.data.handoverBusy||this.data.apiMembersBusy)return;
  // R8 P2-2：确认时核对捕获的岗位/组织与来源仍有效（弹窗打开期间可能切岗或刷新清源）
  const cur=this.assertManage();if(!cur)return;
  const srcNow=this.data.handoverSource;
  if(!srcNow||String(srcNow.userId)!==String(source.userId)||String(cur.orgId)!==String(post.orgId)||String(cur.postId)!==String(post.postId))return this.fail('交接选择已变化，请重新选择后再撤权');
  const alive=http.sessionGuard();this.setData({handoverBusy:true,handoverResult:null});orgApi.staffEmergencyRevoke({orgId:post.orgId,userId:source.userId}).then(res=>{if(!alive())return;this.setData({handoverResult:this.handoverResultView(res,source.name,'')});wx.showToast({title:'已撤权，在途事项转入待分配',icon:'none'});return this.loadApiMembers();}).then(()=>{if(alive())this.setData({handoverBusy:false});}).catch(e=>{if(!alive()||e&&e.silent)return;this.setData({handoverBusy:false});this.fail(e.message||'撤权失败，请重试');});}});},
 reassignRow(e){const fromUserId=e.currentTarget.dataset.userId,name=e.currentTarget.dataset.name;const real=this.data.apiLeavers.find(l=>String(l.userId)===String(fromUserId));this.setHandoverSource(real?{...real}:{userId:fromUserId,name:name||('用户'+fromUserId),roleName:'已撤权'});wx.pageScrollTo({scrollTop:0,duration:0});this.setData({handoverResult:null,reassignHint:'待分配补交接：'+(name||'该成员')+'的全部在途事项（含各域），请选择接收人后发起交接'});},
 pickMemberField(e){this.setData({['form.'+e.currentTarget.dataset.key]:Number(e.detail.value)});},
 // R2 P1-1：状态映射对齐 DepartureHandoverService 五态（HANDED_OVER/NONE/PENDING_UNASSIGNED/NOT_INTEGRATED/SCAN_FAILED；SCAN_FAILED 待分配行 inFlight=-1）
 handoverResultView(res,fromName,toName){const STATUS={HANDED_OVER:'已交接',NONE:'无在途事项',PENDING_UNASSIGNED:'转待分配',NOT_INTEGRATED:'域未接入',SCAN_FAILED:'扫描失败（待核）'};const items=(res&&res.items||[]).map(r=>({label:r.label||r.domain,inFlight:r.inFlight||0,inFlightText:r.inFlight<0?'数量待核':'在途 '+(r.inFlight||0)+' 项',transferred:r.transferred||0,statusText:STATUS[r.status]||r.status||'—',rawStatus:r.status||''}));return {title:res&&res.mode==='EMERGENCY'?fromName+' 紧急撤权清单':fromName+' → '+(toName||'待分配')+' 交接清单',items,openCount:items.filter(r=>r.rawStatus==='PENDING_UNASSIGNED').length};},
 memberCandidates(mobile){const byUser=new Map();(this.data.apiStaffRows||[]).filter(row=>String(row.mobile||'').trim()===mobile).forEach(row=>{const key=String(row.userId||'');if(!key)return;let candidate=byUser.get(key);if(!candidate){candidate={userId:row.userId,name:row.name||'未命名员工',roles:[]};byUser.set(key,candidate);}const role=row.roleName||O.roles[row.roleCode]||row.roleCode||'未标注岗位';if(!candidate.roles.includes(role))candidate.roles.push(role);});return [...byUser.values()].map(candidate=>({...candidate,label:candidate.name+' · '+candidate.roles.join('/')+' · 编号 '+candidate.userId}));},
 memberAddContext(post,name,mobile,role,team){return {alive:http.sessionGuard(),accountGeneration:this.memberAccountGeneration||0,postId:String(post.postId),orgId:String(post.orgId),orgType:post.orgType,name,mobile,roleCode:role.id,teamId:team?team.deptId:null,roleName:role.name};},
 sameMemberAddContext(ctx){let post=null;try{post=http.token()?auth.currentApiPost():null;}catch(e){return false;}const role=(this.data.apiRoleOptionsForAdd||[])[Number(this.data.form.roleIndex||0)],team=(this.data.apiTeamOptions||[])[Number(this.data.form.teamIndex||0)];return !!post&&isOrgManager(post)&&String(post.postId)===ctx.postId&&String(post.orgId)===ctx.orgId&&(this.data.form.memberName||'').trim()===ctx.name&&(this.data.form.memberMobile||'').trim()===ctx.mobile&&!!role&&role.id===ctx.roleCode&&String(team?team.deptId:'')===String(ctx.teamId||'');},
 submitMember(ctx,userId){const payload={orgId:ctx.orgId,name:ctx.name,mobile:ctx.mobile,roleCode:ctx.roleCode,teamId:ctx.teamId};if(userId!==undefined&&userId!==null)payload.userId=userId;const request=ctx.orgType==='store'?orgApi.openStaffAccount(payload):orgApi.addStaff(payload);request.then(result=>{if(!ctx.alive()||ctx.accountGeneration!==(this.memberAccountGeneration||0))return;if(!this.sameMemberAddContext(ctx)){this.setData({apiMembersBusy:false});return this.fail('添加信息或工作身份已变化，请重新确认');}const delivery=ctx.orgType==='store'?{accountId:result.accountId,personName:result.name||ctx.name,roleName:result.roleName||ctx.roleName,shortOrgName:result.shortOrgName||'',orgName:result.orgName||'',loginName:result.loginName||'',aliases:result.aliases||[],aliasesText:(result.aliases||[]).join(' / '),initialPassword:result.initialPassword||null,reusedUser:!!result.reusedUser}:null;this.setData({apiMembersBusy:false,staffAccountDelivery:delivery,'form.memberName':'','form.memberMobile':''});wx.showToast({title:delivery?'员工账号已开通':'已加入并指派'+ctx.roleName,icon:'none'});this.loadApiMembers();}).catch(e=>{if(!ctx.alive()||ctx.accountGeneration!==(this.memberAccountGeneration||0)||e&&e.silent)return;if(ctx.orgType!=='store'&&e&&e.code===STAFF_MOBILE_AMBIGUOUS&&userId===undefined)return this.chooseMemberAccount(ctx);this.setData({apiMembersBusy:false});this.fail(e.message||'添加失败，请重试');});},
 chooseMemberAccount(ctx){if(!this.sameMemberAddContext(ctx)){this.setData({apiMembersBusy:false});return this.fail('添加信息或工作身份已变化，请重新确认');}const candidates=this.memberCandidates(ctx.mobile);if(!candidates.length){this.setData({apiMembersBusy:false});return this.fail('本店成员列表无可分辨候选，请联系总部核对同号账号');}try{wx.showActionSheet({itemList:candidates.map(candidate=>candidate.label),success:result=>{try{if(!ctx.alive())return;if(!this.sameMemberAddContext(ctx)){this.setData({apiMembersBusy:false});return this.fail('添加信息或工作身份已变化，请重新确认');}const selected=candidates[Number(result.tapIndex)],fresh=selected&&this.memberCandidates(ctx.mobile).find(candidate=>String(candidate.userId)===String(selected.userId));if(!fresh){this.setData({apiMembersBusy:false});return this.fail('员工候选已变化，请刷新后重新选择');}this.submitMember(ctx,fresh.userId);}catch(e){this.setData({apiMembersBusy:false});this.fail('选择员工失败，请重试');}},fail:()=>{if(ctx.alive())this.setData({apiMembersBusy:false});}});}catch(e){this.setData({apiMembersBusy:false});this.fail('员工选择器打开失败，请重试');}},
 addMember(){if(this.data.apiMembersBusy)return;const post=this.assertManage();if(!post)return;this.memberAccountGeneration=(this.memberAccountGeneration||0)+1;this.setData({staffAccountDelivery:null});const name=(this.data.form.memberName||'').trim(),mobile=(this.data.form.memberMobile||'').trim(),teams=this.data.apiTeamOptions,roles=this.data.apiRoleOptionsForAdd;if(!name)return this.fail('请填写员工姓名');if(!/^1\d{10}$/.test(mobile))return this.fail('请输入正确的手机号');const role=roles[Number(this.data.form.roleIndex||0)];if(!role)return this.fail('请选择岗位');const team=teams[Number(this.data.form.teamIndex||0)],ctx=this.memberAddContext(post,name,mobile,role,team);this.setData({apiMembersBusy:true});this.submitMember(ctx);},
 copyStaffAccount(){const delivery=this.data.staffAccountDelivery;if(!delivery||!delivery.loginName)return this.fail('账号信息已失效，请重新开通或查询');const lines=['姓名：'+(delivery.personName||'员工'),'岗位：'+(delivery.roleName||'员工'),'门店：'+(delivery.shortOrgName||delivery.orgName||'当前门店'),'账号：'+delivery.loginName,'密码：'+(delivery.initialPassword||'沿用原密码')];if(delivery.aliases&&delivery.aliases.length)lines.push('简写：'+delivery.aliases.join(' / '));wx.setClipboardData({data:lines.join('\n'),success:()=>wx.showToast({title:'账号已复制，可发送给员工',icon:'none'}),fail:()=>this.fail('复制失败，请长按账号手动复制')});},
 revokeRow(e){
  const post=this.assertManage();if(!post)return;
  const postId=e.currentTarget.dataset.postId,name=e.currentTarget.dataset.name;
  wx.showModal({title:'撤销任职',content:'撤销 '+name+' 的该岗位？撤销后其登录将不再显示此身份。',confirmText:'撤销',success:r=>{
   if(!r.confirm)return;
   const current=this.assertManage();if(!current)return;
   if(String(current.postId)!==String(post.postId)||String(current.orgId)!==String(post.orgId))return this.fail('工作身份已变化，请重新确认');
   const alive=http.sessionGuard();
   orgApi.revokePost(postId).then(()=>{if(!alive())return;wx.showToast({title:'已撤销'});this.loadApiMembers();}).catch(err=>{if(alive()&&!err.silent)this.fail(err.message||'撤销失败，请重试');});
  }});
 },
 toggleActiveRow(e){
  const post=this.assertManage();if(!post)return;
  const userId=e.currentTarget.dataset.userId,name=e.currentTarget.dataset.name,raw=e.currentTarget.dataset.active,active=raw===true||raw==='true';
  wx.showModal({title:active?'停用员工':'恢复任职',content:active?'停用后 '+name+' 在本组织的全部任职立即撤销，其他组织的任职不受影响；恢复须重新指派岗位。':'全局停用请联系平台管理员启用；本组织停职请通过「加人/追加岗位」重新指派岗位。',confirmText:active?'停用':'知道了',showCancel:active,success:r=>{
   if(!r.confirm||!active)return;
   const current=this.assertManage();if(!current)return;
   if(String(current.postId)!==String(post.postId)||String(current.orgId)!==String(post.orgId))return this.fail('工作身份已变化，请重新确认');
   const alive=http.sessionGuard();
   orgApi.setStaffStatus(userId,false).then(()=>{if(!alive())return;wx.showToast({title:'已停用'});this.loadApiMembers();}).catch(err=>{if(alive()&&!err.silent)this.fail(err.message||'操作失败，请重试');});
  }});
 },
 switchRole(){try{A.mutate(s=>A.switchIdentity(s,s.session.companyId,this.data.selectedRole));const role=this.data.selectedRole;wx.switchTab({url:'/pages/'+A.access.home(role)+'/index'});}catch(e){this.fail(e.message);}},
 loadLoginPolicy(){
  if(this.data.loginPolicy)return Promise.resolve(this.data.loginPolicy);
  if(this.loginPolicyPromise)return this.loginPolicyPromise;
  const seq=this.__policySeq||0;
  const pending=auth.loginPolicy().then(policy=>{
   if(seq!==(this.__policySeq||0)||this.data.view!=='login')return null;
   if(!policy||!policy.agreementVersion||!policy.privacyVersion)throw new Error('登录设置暂不可用，请重试');
   this.setData({loginPolicy:policy});return policy;
  }).catch(e=>{if(seq===(this.__policySeq||0)&&!(e&&e.silent))this.fail(e.message||'登录设置获取失败，请重试');return null;});
  this.loginPolicyPromise=pending;
  pending.then(()=>{if(this.loginPolicyPromise===pending)this.loginPolicyPromise=null;});
  return pending;
 },
 ensureLoginConsent(action){
  const policy=this.data.loginPolicy;
  if(policy){this.loginConsent=AccountLogin.consent(policy)||this.loginConsent;
   if(this.loginConsent&&this.loginConsent.accepted===true&&this.loginConsent.agreementVersion===policy.agreementVersion&&this.loginConsent.privacyVersion===policy.privacyVersion)return true;
   this.pendingConsentAction=action;if(wx.hideKeyboard)wx.hideKeyboard();this.setData({consentOpen:true,consentChecked:false,consentDocument:'',error:''});return false;}
  const generation=this.loginGeneration;this.setData({loginBusy:true});
  this.loadLoginPolicy().then(current=>{if(!this.loginFlowCurrent(generation))return;this.setData({loginBusy:false});if(current&&this.ensureLoginConsent(action))this.runConsentAction(action);});return false;
 },
 runConsentAction(action){if(action==='account')return this.accountLogin();if(action==='wechat')return this.wechatLogin();if(action==='password-change')return this.submitNewPassword();},
 toggleConsent(){this.setData({consentChecked:!this.data.consentChecked,error:''});},
 confirmLoginConsent(){if(!this.data.consentOpen||!this.data.loginPolicy)return;if(!this.data.consentChecked)return this.fail('请先勾选同意用户协议和隐私政策');
  const action=this.pendingConsentAction;this.loginConsent=AccountLogin.saveConsent(this.data.loginPolicy);this.pendingConsentAction='';
  this.setData({consentOpen:false,consentChecked:false,consentDocument:'',error:''});this.runConsentAction(action);
 },
 closeLoginConsent(){this.pendingConsentAction='';this.setData({consentOpen:false,consentChecked:false,consentDocument:'',error:''});},
 readLoginDocument(e){const document=e.currentTarget.dataset.document;if(['agreement','policy'].includes(document))this.setData({consentDocument:document});},
 backToConsent(){this.setData({consentDocument:''});},
 togglePasswordVisibility(e){const keys={password:'passwordVisible',newPassword:'newPasswordVisible',confirmPassword:'confirmPasswordVisible'},key=keys[e.currentTarget.dataset.field];if(key)this.setData({[key]:!this.data[key]});},
 loginError(error,fallback){if(Number(error&&error.code)===1104000016){AccountLogin.clearConsent();this.loginConsent=null;this.setData({loginPolicy:null});}this.fail(error&&error.message||fallback);},
 cancelLoginFlow(options){this.clearAccountMatchTimer();const opts=options||{};this.loginGeneration=(this.loginGeneration||0)+1;this.accountMatchSeq=(this.accountMatchSeq||0)+1;this.accountLoginSeq=(this.accountLoginSeq||0)+1;if(this.loginSessionGeneration&&!opts.keepSession){auth.logout();this.loginSessionGeneration=0;}this.pendingConsentAction='';const patch={matchBusy:false,loginBusy:false,wechatPhoneRequired:false,wechatPhoneGeneration:null,consentOpen:false,consentChecked:false,consentDocument:''};if(opts.clearSensitive)Object.assign(patch,{passwordVisible:false,newPasswordVisible:false,confirmPasswordVisible:false,passwordChangeRequired:false,pendingAccountLogin:null,'form.password':'','form.newPassword':'','form.confirmPassword':''});this.setData(patch);return this.loginGeneration;},
 loginFlowCurrent(generation){return generation===this.loginGeneration&&this.data.view==='login';},
 selectLoginMode(e){const mode=e.currentTarget.dataset.mode;if(mode===this.data.loginMode)return;this.cancelLoginFlow({clearSensitive:true});this.setData({loginMode:mode,error:''});},
 clearAccountMatchTimer(){if(this.accountMatchTimer!==undefined&&this.accountMatchTimer!==null){clearTimeout(this.accountMatchTimer);this.accountMatchTimer=null;}},
 scheduleAccountMatch(){
  this.clearAccountMatchTimer();const query=(this.data.form.accountQuery||'').trim();
  if(this.data.view!=='login'||this.data.loginMode!=='account'||query.length<2)return;
  const generation=this.loginGeneration;
  this.accountMatchTimer=setTimeout(()=>{
   this.accountMatchTimer=null;
   if(!this.loginFlowCurrent(generation)||this.data.loginMode!=='account'||(this.data.form.accountQuery||'').trim()!==query)return;
   this.findAccounts();
  },300);
 },
 findAccounts(){
  if(this.data.loginBusy)return;
  const query=(this.data.form.accountQuery||'').trim();
  if(!query)return this.fail('请输入姓名、用户名或门店全名');
  const generation=this.cancelLoginFlow({clearSensitive:true}),seq=this.accountMatchSeq;
  this.setData({matchBusy:true,accountMatches:[],accountMatchesHasMore:false,selectedAccount:null,error:''});
  auth.accountMatches(query).then(result=>{
   if(!this.loginFlowCurrent(generation)||seq!==this.accountMatchSeq)return;
   const items=(result&&result.items||[]).filter(Boolean),hasMore=!!(result&&result.hasMore);
   const selected=items.length===1&&!hasMore?items[0]:null;
   const patch={matchBusy:false,accountMatches:items,accountMatchesHasMore:hasMore,selectedAccount:selected,error:items.length?'':'未找到账号，请核对名称或联系管理员'};
   if(selected&&selected.loginName)patch['form.accountQuery']=selected.loginName;
   this.setData(patch);
  }).catch(e=>{
   if(!this.loginFlowCurrent(generation)||seq!==this.accountMatchSeq||e&&e.silent)return;
   this.setData({matchBusy:false});this.fail(e.message||'账号查找失败，请重试');
  });
 },
 selectAccount(e){const accountId=String(e.currentTarget.dataset.accountId),selected=(this.data.accountMatches||[]).find(item=>String(item.accountId)===accountId);if(!selected)return;this.cancelLoginFlow({clearSensitive:true});const patch={selectedAccount:selected,error:''};if(selected.loginName)patch['form.accountQuery']=selected.loginName;this.setData(patch);},
 toggleRememberDefault(){this.setData({rememberDefault:!this.data.rememberDefault});},
 clearDefaultAccount(){this.cancelLoginFlow({clearSensitive:true});auth.logout();AccountLogin.clearDefault();const selected=this.data.selectedAccount,wasDefault=this.data.defaultAccount&&selected&&String(this.data.defaultAccount.accountId)===String(selected.accountId);this.setData({defaultAccount:null,selectedAccount:wasDefault?null:selected,'form.accountQuery':wasDefault?'':this.data.form.accountQuery||''});},
 accountLogin(){if(this.data.loginBusy)return;const selected=this.data.selectedAccount,password=this.data.form.password||'';if(!selected)return this.fail('请先输入用户名并选择匹配账号');if(!password)return this.fail('请输入密码');if(!this.ensureLoginConsent('account'))return;this.submitAccountLogin(selected,password);},
 submitNewPassword(){const pending=this.data.pendingAccountLogin,next=this.data.form.newPassword||'',confirm=this.data.form.confirmPassword||'';if(!pending)return this.fail('登录状态已变化，请重新登录');if(!AccountLogin.validNewPassword(next))return this.fail('新密码须为6–64位');if(next!==confirm)return this.fail('两次输入的新密码不一致');if(!this.ensureLoginConsent('password-change'))return;this.submitAccountLogin(pending.account,pending.password,next);},
 submitAccountLogin(account,password,newPassword){const generation=this.cancelLoginFlow(),seq=this.accountLoginSeq,accountId=String(account.accountId);this.setData({loginBusy:true,error:''});auth.accountLogin(accountId,password,newPassword,this.loginConsent).then(result=>{if(!this.loginFlowCurrent(generation)||seq!==this.accountLoginSeq||!this.data.selectedAccount||String(this.data.selectedAccount.accountId)!==accountId)return;if(result&&result.requirePasswordChange){this.setData({loginBusy:false,passwordChangeRequired:true,pendingAccountLogin:{account,password},'form.newPassword':'','form.confirmPassword':''});return;}this.setData({passwordChangeRequired:false,pendingAccountLogin:null});this.finishAccountLogin(result,generation,account);}).catch(e=>{if(!this.loginFlowCurrent(generation)||seq!==this.accountLoginSeq||e&&e.silent)return;this.setData({loginBusy:false});this.loginError(e,'账号或密码错误');});},
 wechatLogin(){return this.startWechatLogin();},
 onWechatPhoneNumber(e){
  if(this.data.view!=='login'||this.data.loginMode!=='wechat'||!this.data.wechatPhoneRequired||this.data.loginBusy)return;
  const generation=Number(e&&e.currentTarget&&e.currentTarget.dataset.generation);
  if(generation!==this.loginGeneration)return;
  const code=e&&e.detail&&e.detail.code;
  if(typeof code!=='string'||!code.trim())return this.fail('未完成微信手机号授权，请重试，也可使用账号密码登录');
  return this.startWechatLogin(code);
 },
 startWechatLogin(phoneCode){
  if(this.data.loginBusy)return;if(!this.ensureLoginConsent('wechat'))return;
  const generation=this.cancelLoginFlow({clearSensitive:true});this.setData({loginBusy:true,error:''});
  auth.login({appid:auth.appId(),phoneCode:phoneCode||undefined,consent:this.loginConsent}).then(result=>{
   if(!this.loginFlowCurrent(generation))return;
   if(result&&result.accessToken){this.finishAccountLogin(result,generation);return;}
   const flag=v=>v===true||v==='true'||v===1;
   if(result&&(flag(result.needPhone)||flag(result.needBind))){this.setData({loginBusy:false,wechatPhoneRequired:true,wechatPhoneGeneration:generation,error:''});return;}
   this.setData({loginBusy:false});this.fail('微信登录未完成，请重试');
  }).catch(err=>{
   if(!this.loginFlowCurrent(generation)||err&&err.silent)return;
   this.setData({loginBusy:false,wechatPhoneRequired:!!phoneCode&&Number(err&&err.code)!==1104000016,wechatPhoneGeneration:generation});
   this.loginError(err,'微信登录失败，请重试');
  });
 },
 finishAccountLogin(result,generation,account){const gen=generation===undefined?this.loginGeneration:generation;if(!this.loginFlowCurrent(gen))return;this.loadLoginSession(result,gen).then(()=>{if(!this.loginFlowCurrent(gen))return;if(account){if(this.data.rememberDefault){AccountLogin.saveDefault(account);this.setData({defaultAccount:account});}else if(this.data.defaultAccount&&String(this.data.defaultAccount.accountId)===String(account.accountId)){AccountLogin.clearDefault();this.setData({defaultAccount:null});}}this.goAfterLogin(gen);}).catch(()=>{});},
 loadLoginSession(result,generation){if(!this.loginFlowCurrent(generation))return Promise.reject({silent:true});if(!result||!result.accessToken){this.setData({loginBusy:false,error:'登录未完成，请重试'});return Promise.reject({silent:true});}auth.saveLogin(result,{remember:this.data.loginMode==='wechat'||this.data.rememberDefault});this.loginSessionGeneration=generation;const alive=auth.postLoadGuard();return auth.myPosts().then(posts=>{if(!this.loginFlowCurrent(generation)||!alive())return Promise.reject({silent:true});const list=(posts||[]).filter(Boolean);if(!list.length){auth.logout();this.loginSessionGeneration=0;this.setData({loginBusy:false,error:'当前账号暂无有效任职，请联系门店老板或平台管理员'});return Promise.reject({silent:true});}const preferred=list.find(p=>String(p.postId)===String(result.preferredPostId)),next=preferred||list[0];auth.savePosts(list);http.saveSession({postId:next.postId});return list;}).catch(e=>{if(!this.loginFlowCurrent(generation)||!alive()||e&&e.silent)throw e;this.setData({loginBusy:false,error:'岗位信息获取失败，请重试或返回登录'});throw e;});},
 afterRealLogin(result){this.finishAccountLogin(result);},
 goAfterLogin(generation){if(generation!==undefined&&!this.loginFlowCurrent(generation))return;const post=auth.currentApiPost();if(!post){auth.logout();this.loginSessionGeneration=0;this.setData({loginBusy:false,error:'当前账号暂无有效任职，请联系门店老板或平台管理员'});return;}this.loginSessionGeneration=0;wx.showToast({title:'以'+(O.roles[post.roleCode]||post.roleCode)+'身份进入',icon:'none'});return openApiHome(post);},
 onHide(){if(this.data.view==='messages'){this.__messagesGen=(this.__messagesGen||0)+1;require('./platform-messages-page').invalidateAction(this);}this.__policySeq=(this.__policySeq||0)+1;this.loginPolicyPromise=null;this.__profileGeneration=(this.__profileGeneration||0)+1;if(this.data.view==='profile')this.setData({busy:false});if(this.data.view==='login')this.cancelLoginFlow({clearSensitive:true});if(this.data.view==='api-members'){this.memberAccountGeneration=(this.memberAccountGeneration||0)+1;this.setData({apiMembersBusy:false,staffAccountDelivery:null});}},
 onUnload(){if(this.data.view==='messages')require('./platform-messages-page').invalidateAction(this,false);this.__messagesGen=(this.__messagesGen||0)+1;this.__messagesFlight=null;this.__policySeq=(this.__policySeq||0)+1;this.loginPolicyPromise=null;this.__profileGeneration=(this.__profileGeneration||0)+1;this.cancelLoginFlow({clearSensitive:true});this.memberAccountGeneration=(this.memberAccountGeneration||0)+1;this.setData({apiMembersBusy:false,staffAccountDelivery:null});},
 logout(){this.setData({modal:{type:'logout',title:'退出登录',text:'退出后保留本机已有记录，可重新登录查看。'}});},
 confirmModal(){const m=this.data.modal;if(!m)return;if(m.type==='logout'){try{A.mutate(s=>{A.logout(s);});wx.switchTab({url:'/pages/profile/index'});}catch(e){this.fail('退出失败，请重试');}return;}if(m.type==='lesson')return this.completeLesson();if(m.type==='demo'||m.type==='cancel'||m.type==='cancel-resolution')return this.perform(m.action);this.closeModal();},closeModal(){this.setData({modal:null,error:''});},
 notice(e){const key=e.currentTarget.dataset.key,texts={companies:'已加入公司：'+this.data.state.companies.map(x=>x.name).join('、')+'。',contact:'如需联系服务方，请在“帮助与反馈”提交问题。',faq:'常见问题：在“公司与工作身份”切换已授权角色；订单中的确认和接收由门店负责人办理。',quote:'该订单尚无正式报价记录，服务内容、费用和周期应在沟通后单独确认。',delete:'如需注销账号，请联系所在公司管理员或客服办理。',policy:'本页内容由运营主体发布，以正式公示版本为准。'};this.setData({modal:{type:'notice',title:{contact:'联系服务方',faq:'常见问题',quote:'服务报价',delete:'注销账号',policy:'个人信息使用说明'}[key]||'提示',text:texts[key]||'该服务尚未接入。'}});},backHome(){wx.switchTab({url:'/pages/profile/index'});},noop(){}
});
