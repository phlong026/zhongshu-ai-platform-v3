const studio = require('../../utils/studio');
const MaterialLibrary = require('./material-library');

const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：七态映射/校验/降级）
const TaskResult=require('./content-task-result');
const TaskDisplay=require('./task-display');
const contentApi=P.api;                                 // /franchise/content/** 契约层
const crm=require('../../api/franchise/crm');            // 客资登记（发布链接回填时同页登记）
const http=require('../../api/http');
const keyboard=require('../../utils/creation-keyboard');
function makeTaskTitle({ kind, title, description, text, sourceTitle } = {}) {
  const labels={ai_video:'AI视频',ai:'AI视频',digital:'数字人口播',copy:'文案二创',remix:'文案二创',image:'AI图片'};
  const prefix=(labels[kind]||'AI创作')+'｜';
  let topic=String(title||sourceTitle||description||text||'').trim();
  if(topic.startsWith(prefix))topic=topic.slice(prefix.length);
  topic=topic.split(/\r?\n/)[0].replace(/\s+/g,' ').trim();
  topic=topic.replace(/^(?:(?:请帮我|帮我|请)\s*)?(?:生成|制作|创作)(?:一个|一段|一条|一份)?\s*/,'').trim();
  const chars=Array.from(topic);
  return prefix+(chars.length>24?chars.slice(0,23).join('')+'…':topic||'未命名创作');
}


function listIdentity() {
const post=require('../../api/auth').currentApiPost()||{};
return JSON.stringify([studio.scope(),http.sessionGeneration(),http.tenantId(),post.orgId,post.postId,post.userId,post.orgType,post.roleCode]);
}
function listQuery(data,kind) { return kind==='tasks'?data.taskFilter:kind==='records'?(data.view==='detail'?String(data.recordId||''):data.recordFilter):''; }
function emptyList(kind,view) { return kind==='tasks'?{tasks:[],taskCount:0}:kind==='works'?{works:[]}:view==='detail'?{detail:null,timeline:[]}:{records:[]}; }
// 仅剥显式标题，不猜测口播开场。
function spokenBody(value) {
const text=String(value||'').trim();
const lines=text.split(/\r?\n/);
if(!/^(?:#{1,6}\s*)?(?:\*\*)?(?:标题|title)(?:\*\*)?\s*[:：]/i.test(lines[0]))return text;
return lines.slice(1).join('\n').trim().replace(/^(?:\*\*)?(?:口播正文|口播文案|正文)(?:\*\*)?\s*[:：](?:\*\*)?\s*/, '');
}
const titles = { remix: '文案二创', digital: '数字人口播', tasks: '任务中心', result: '视频结果', publish: '回填发布链接', receipt:'登记发布链接', records: '发布记录', detail: '发布详情', works: '我的作品', library: '素材库', recent: '全部创作' };
const statusInfo = {
publishing: { label: '待登记链接', icon: 'clock', tone: 'warm', action: '补填链接' },
published: { label: '已发布', icon: 'check-green', tone: 'green', action: '查看回执' },
verify: { label: '待核实', icon: 'warning', tone: 'yellow', action: '核实结果', note: '请勿重复发布' },
failed: { label: '失败', icon: 'error', tone: 'red', action: '查看原因', note: '请查看失败原因' },
scheduled: { label: '待发布', icon: 'clock', tone: 'warm', action: '查看进度' }
};
const textResize = require('../../utils/creation-text-resize');
Page({
...textResize.handlers,
...MaterialLibrary.handlers,
data: {
...MaterialLibrary.data,
resultLoading:false,resultSaving:false,resultError:'',resultUrlNote:'',resultVideoHeight:1148,
view: 'remix', pageTitle: '', original: '', script: '', title: studio.title, rewriteMode: 0, ipIndex: 0, rewriting: false, optimizing:false, optimizationRetry:false, operationError: '',
ipOptions: ['不指定人物'], personaOptions: [{id:'',name:'不指定人物'}], selectedPersonaId: '', requirement: '',digitalSubmitting:false,digitalRetry:false,
taskFilter: 0, recordFilter: 0, taskTabs: ['全部', '生成中', '已完成', '异常'], recordTabs: ['全部', '进行中', '已发布', '异常'],
taskCount:0,tasks: [], records: [], works: [],  publicationPlatforms:studio.publicationPlatforms||[], sheet: '', sheetTitle: '', editingText: '', selectedAccount: 0,publicationAccount:'',publishSubmitting:false,linkSubmitting:false,linkRecordId:'',linkSaved:false,leadOpen:false,leadName:'',leadPhone:'',leadNeed:'',publishedUrlInput:'',receiptError:'',receiptSaved:false,receiptSaving:false,receiptLoading:false,tasksError:'',tasksLoading:false,worksLoading:false,worksError:'',recordsLoading:false,recordsError:'',
tasksLoaded:false,worksLoaded:false,recordsLoaded:false,
digitalResourceError:'',
publishText: '',
tags: '', cover: '', coverAssetId:'',coverReady:false,coverUploading:false,coverLoading:false,coverError:'', workId: '', saved: false,
coverTemplates:[],coverTemplatesLoaded:false,coverTemplatesLoading:false,coverTemplatesError:'',coverTemplatesPage:0,coverTemplatesHasMore:false,coverTemplateId:'',coverGenerationTitle:'',coverCanvasPreset:'9:16',coverGenerating:false,coverUsing:false,coverGenerationPending:false,coverGenerationTerminal:false,coverGenerationLocked:false,coverGenerationStatus:'',coverGenerationMessage:'',coverGenerationRequestTitle:'',coverGenerationError:'',coverGeneratedAssetId:'',coverGeneratedPreview:'',coverGeneratedPreviewLoading:false,coverGeneratedPreviewError:'',
publisherAccounts:[],publisherAccountsAll:[],publisherAccountsLoaded:false,publisherAccountsLoading:false,publisherAccountsError:'',publisherAccountId:'',publisherAccountBusy:false,publisherAccountEditingId:'',publisherAccountNameInput:'',publisherAccountIdentifierInput:'',publisherAccountEditError:'',
publicationPending:false,publicationPendingTitle:'',publicationPendingPlatform:'',publicationExistingId:'',publicationExistingStatus:'',
actor: '', voice: '', avatarCloneId: '', voiceCloneId: '', digitalPersonaId: '', digitalPersonas: [], digitalPersonaIndex: 0, avatarOptions: [], voiceOptions: [], digitalLoading: false, filter: '', detail: null, activeTask: null, invalidMessage: '', recent: [], timeline: [], scriptLength: 0, estimatedSeconds: 0
},
onLoad(options) {
if(!studio.guard())return;this.__closed=false;this.__initialShow=true;this.actorScope=studio.scope();this.actorSessionGeneration=http.sessionGeneration();
const view = titles[options.view] ? options.view : 'remix';
this.options = options;
const state = studio.read();
this.setData({ view, pageTitle: titles[view], original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), title: view==='publish'&&options.work?'':state.title, taskId: options.task || '', recordId: options.record || '', workId: options.work || '' });
if(view==='library'&&options.select)this.materialPickerInit(options.select);
if(view==='digital')this.restoreDigitalAttempt();
if (view === 'result' && options.task) {
if (http.token()) return this.loadApiResult(options.task); // 在线任务：直取后端详情（不回落本地演示任务）
this.setData({ view: 'tasks', pageTitle: titles.tasks });
wx.showToast({ title: '请先登录后查看任务', icon: 'none' });
wx.redirectTo({ url: '/pages/studio/index?view=tasks' });
return;
}
if (view === 'publish') {
const draft = options.work && state.publishDrafts ? state.publishDrafts[options.work] : null; // 发布表单草稿（本地暂存，非演示集合）
if (draft) this.setData(draft);
this.restorePublicationPending();
this.__linkFlow = this.readLinkFlow(options.work || '');
if (this.__linkFlow && this.__linkFlow.recordId) this.setData({linkRecordId:String(this.__linkFlow.recordId)});
if (options.work && http.token()) this.loadApiWork(options.work); // 在线作品：接口回读，缺失不拦截
}
if (view === 'remix' && state.rewriteSettings) this.setData(Object.assign({},state.rewriteSettings,{rewriteMode:0,customIp:''}));
if (view === 'remix') {
if(options.persona)this.setData({selectedPersonaId:String(options.persona),rewriteMode:0});
this.loadPersonas();
}
const sourceId=options.content||options.confirm;if(view==='publish'&&sourceId){const c=require('../../utils/growth').load().contents.find(c=>c.id===sourceId&&c.workId===options.work);if(c)this.setData({title:c.pack.title,publishText:c.pack.publishText,tags:c.pack.tags,cover:c.pack.cover,selectedAccount:Math.max(0,(studio.publicationPlatforms||[]).findIndex(a=>a.platform===c.pack.platform))});}
if(view==='publish'){this.loadPublisherAccounts();}
this.refreshData();
if(view==='receipt'){
this.setData({publishedUrlInput:state.publicationLinkDrafts?.[this.data.recordId]||this.data.detail?.publishedUrl||''});
if(http.token()){this.receiptApi=true;this.loadApiReceipt(this.data.recordId);} // 在线发布记录：回执页按后端数据渲染
}
},
onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
onShow() {if(!studio.guard()){this.resetLists();if(this.data.view==='digital')this.resetDigitalResources();return;}if(this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()||(this.__listIdentity&&this.__listIdentity!==listIdentity())){this.resetLists();if(this.data.view==='digital')this.resetDigitalResources();const keep=this.data.view==='detail'?'&record='+encodeURIComponent(this.data.recordId||''):this.data.view==='publish'&&this.data.workId?'&work='+encodeURIComponent(this.data.workId):'';wx.redirectTo({url:'/pages/studio/index?view='+this.data.view+keep});return;}if(this.data.sheet==='editor')keyboard.open(this);const initial=this.__initialShow;this.__initialShow=false;if (this.options&&!initial) this.refreshData();if(this.data.view==='digital')this.loadDigitalProfiles(this.data.digitalPersonaId||this.options&&this.options.persona);if(this.data.view==='publish'&&!initial)this.loadPublisherAccounts();
  },
onHide() {
// 返回时保留快照，但不复用隐藏前读取。
if(this.__listFlights){this.__listFlights={};this.setData({tasksLoading:false,worksLoading:false,recordsLoading:false});}
if(this.data.view==='digital')this.cancelDigitalResources();
this.stopOptimization();if(this.data.view==='library')this.materialHide(); keyboard.stop(this);if(this.__voiceAudio)this.__voiceAudio.stop();this.persistEditor(); },
onUnload() { this.stopOptimization();if (this.__digitalPoll && this.__digitalPoll.cancel) this.__digitalPoll.cancel(); if(this.data.view==='library')this.materialUnload(); keyboard.stop(this);if(this.__voiceAudio)this.__voiceAudio.destroy();if(this.data.digitalSubmitting)wx.hideLoading(); this.__closed=true; this.persistEditor(); }, // 终止任务轮询（防 UI 长时间占锁）
persistEditor() {
if(!studio.allowed()||this.actorScope!==studio.scope())return;
if(this.data.view==='receipt'&&!this.data.receiptSaved&&['publishing','verify'].includes(this.data.detail&&this.data.detail.status)){const state=studio.read();state.publicationLinkDrafts=state.publicationLinkDrafts||{};state.publicationLinkDrafts[this.data.recordId]=this.data.publishedUrlInput;studio.write(state);}
if (this.data.view === 'remix') {
const state = studio.read();
const { rewriteMode, ipIndex, selectedPersonaId, customIp, requirement } = this.data;
state.rewriteSettings = { rewriteMode, ipIndex, selectedPersonaId, customIp: customIp || '', requirement }; studio.write(state);
}
if (this.data.view === 'publish' && !this.data.invalidMessage && this.data.workId) {
const state = studio.read();
const { title, publishText, tags, selectedAccount, publicationAccount, publisherAccountId, coverAssetId, cover, publishedUrlInput, leadOpen, leadName, leadPhone, leadNeed, workId } = this.data;
state.publishDrafts[workId] = { title, publishText, tags, selectedAccount, publicationAccount, publisherAccountId, coverAssetId, cover, publishedUrlInput, leadOpen, leadName, leadPhone, leadNeed, workId }; studio.write(state);
}
},
refreshData() {
if (http.token()) { // 仅在线路径：任务中心走后端列表；不装配本地演示 tasks/records/works
if (['remix', 'digital'].includes(this.data.view)) { // 编辑器文案（草稿暂存键）仍回显
const state = studio.read();
this.setData({ title: state.title, original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), scriptLength: state.script.length, estimatedSeconds: Math.ceil(state.script.replace(/\s/g, '').length / 4), extraction: state.extraction || null, rewriteVersion: (state.rewriteHistory[state.rewriteHistory.length - 1] || {}).version || 0, optimizationRetry:!!state.optimizationAttempt });
}
if (this.data.view === 'library') return this.materialInit();
if (this.data.view === 'tasks') return this.syncApiTasks();
if (this.data.view === 'works') return this.syncApiWorks();
if (this.data.view === 'records') return this.syncApiRecords();
if (this.data.view === 'detail') return this.syncApiRecords(this.data.recordId);
return;
}
// 无登录不回退演示数据。
this.resetLists();
this.setData({ taskCount:0,tasks: [], records: [], works: [], recent: [], activeTask: null, detail: null, tasksError: '', invalidMessage: '请先登录后使用创作中心' });
return;
},
resetLists() {
this.__listFlights={};this.__listSnapshots={};this.__listIdentity='';this.__taskListSeq=(this.__taskListSeq||0)+1;
this.__dismissingTask=null;this.__retryingSave=null;
this.setData({tasks:[],taskCount:0,works:[],records:[],detail:null,timeline:[],tasksLoaded:false,worksLoaded:false,recordsLoaded:false,tasksLoading:false,worksLoading:false,recordsLoading:false,tasksError:'',worksError:'',recordsError:''});
},
/** Reuse only a matching in-flight read; a successful snapshot stays mounted during refresh. */
loadList(kind,request,render,afterRender) {
if(this.__closed)return;
if(!http.token()||this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()){this.resetLists();return;}
const identity=listIdentity(),view=this.data.view,query=listQuery(this.data,kind);
if(this.__listIdentity!==identity){this.resetLists();this.__listIdentity=identity;}
const key=JSON.stringify([identity,view,query]),previous=this.__listFlights[kind];
if(previous&&previous.key===key&&previous.current())return previous.promise;
const guard=http.requestGuard('studio-'+kind),sequence=kind==='tasks'?(this.__taskListSeq=(this.__taskListSeq||0)+1):0;
const flight={key};this.__listFlights[kind]=flight;
const current=flight.current=()=>!this.__closed&&this.__listFlights[kind]===flight&&guard()&&http.token()&&identity===listIdentity()&&this.actorScope===studio.scope()&&this.actorSessionGeneration===http.sessionGeneration()&&view===this.data.view&&query===listQuery(this.data,kind)&&(kind!=='tasks'||sequence===this.__taskListSeq);
const loaded=this.__listSnapshots[kind]===key;
this.setData(Object.assign({[kind+'Loading']:true,[kind+'Loaded']:loaded,[kind+'Error']:''},loaded?{}:emptyList(kind,view),view==='detail'?{invalidMessage:''}:{}));
flight.promise=request().then(rows=>{
if(!current())return;
const patch=render(rows);this.__listSnapshots[kind]=key;
this.setData(Object.assign({},patch,{[kind+'Loaded']:true,[kind+'Loading']:false,[kind+'Error']:''}));
if(afterRender)afterRender();
}).catch(error=>{
const denied=error&&(error.postInvalid||[401,403,1103000002].includes(Number(error.code)));
// 仅当前请求可清除过期会话页面。
if(!current()&&!(denied&&!this.__closed&&this.__listFlights[kind]===flight&&view===this.data.view))return;
const clear=denied||error&&error.notFound,message=kind==='tasks'?'任务列表加载失败，请重试':P.degradeMessage(error);
if(clear)delete this.__listSnapshots[kind];
this.setData(Object.assign({[kind+'Loading']:false,[kind+'Error']:message},clear?Object.assign(emptyList(kind,view),{[kind+'Loaded']:false},view==='detail'?{invalidMessage:message}:{}):{}));
}).finally(()=>{if(this.__listFlights[kind]===flight)delete this.__listFlights[kind];});
return flight.promise;
},
/** 在线任务列表：tasks.page 七态 → 页面四态（generating/completed/failed/uncertain），异常页签含 failed/uncertain */
syncApiTasks() {
const filter=this.data.taskFilter;
return this.loadList('tasks',()=>contentApi.tasks.page(),rows=>{
const previous=new Map((this.data.tasks||[]).map(task=>[task.id,task]));
const mapped=P.rowsOf(rows).map(task=>this.mapApiTask(task)).filter(Boolean);
const tasks=mapped.filter(task=>filter===0||(filter===1&&task.status==='generating')||(filter===2&&task.status==='completed')||(filter===3&&task.status!=='generating'&&task.status!=='completed')).map(task=>{
const shown=previous.get(task.id);return shown?Object.assign(task,{swiped:task.canDismiss&&!!shown.swiped,dismissing:!!shown.dismissing}):task;
});
return {tasks,taskCount:tasks.length};
});
},
/** 在线作品列表：drafts.works 返回本人作品；异步结果只允许写回发起时的会话、岗位与页面。 */
syncApiWorks() {
return this.loadList('works',()=>contentApi.drafts.works(),rows=>{
const works=P.rowsOf(rows).map(row=>{
if(!row||row.id==null||row.id==='')return null;
const kind=row.kind==='ai_video'?'ai':(row.kind==='digital'?'digital':'remix');
return{id:String(row.id),taskId:row.taskId==null?'':String(row.taskId),title:String(row.title||'未命名作品'),kind,status:String(row.status||'draft'),image:studio.imageFor({kind})};
}).filter(Boolean);
return {works};
});
},
/** 发布记录列表与详情共用 publications.page；状态筛选在页面端完成。 */
syncApiRecords(recordId) {
const filter=this.data.recordFilter;
return this.loadList('records',()=>contentApi.publications.page(),rows=>{
const mapped=P.rowsOf(rows).map(row=>this.mapApiPublication(row)).filter(Boolean);
if(recordId!=null&&recordId!==''){
const detail=mapped.find(item=>item.id===String(recordId));
if(!detail)throw {notFound:true,message:'发布记录不存在或已清理'};
return {detail,timeline:this.publicationTimeline(detail),invalidMessage:''};
}
const records=mapped.filter(item=>filter===0||(filter===1&&['publishing','verify','scheduled'].includes(item.status))||(filter===2&&item.status==='published')||(filter===3&&item.status==='failed'));
return {records};
},()=>this.hydratePublicationCovers());
},
mapApiPublication(row) {
if(!row||row.id==null||row.id==='')return null;
const status=String(row.status||'publishing'),info=statusInfo[status]||{label:'状态待确认',icon:'warning',tone:'yellow',action:'查看详情'};
const platform=String(row.platform||'douyin'),account=String(row.accountName||row.account||row.accountId||'账号未记录');
const timeValue=status==='published'?(row.publishedAt||row.createTime):status==='scheduled'?(row.scheduledAt||row.createTime):row.createTime;
return{id:String(row.id),apiRecord:true,workId:row.workId==null?'':String(row.workId),title:String(row.title||'未命名发布'),publishText:String(row.publishText||''),tags:String(row.tags||''),platform,account,status,publishedUrl:String(row.publishedUrl||''),failureReason:String(row.failureReason||''),signature:String(row.signature||''),cover:'',coverAssetId:String(row.coverAssetId||''),coverError:false,kindLabel:'数字人口播',time:studio.formatTime(timeValue),displayTime:studio.formatTime(timeValue),timeLabel:status==='published'?'发布时间':status==='scheduled'?'计划发布时间':'提交时间',identifier:String(row.signature||row.id),label:info.label,icon:info.icon,tone:info.tone,action:info.action||'查看详情',note:status==='failed'?String(row.failureReason||info.note||'发布失败'):String(info.note||'')};
},
publicationTimeline(record) {
const timeline=[{label:'已提交发布',time:record.time}];
if(record.status==='scheduled')timeline.push({label:'等待计划时间',time:record.displayTime});
if(record.status==='verify')timeline.push({label:'等待链接核验',time:'待完成'});
if(record.status==='published')timeline.push({label:'已登记发布链接',time:record.displayTime});
if(record.status==='failed')timeline.push({label:'发布失败',time:record.failureReason||'原因未记录'});
return timeline;
},
/** 后端 TaskView → 本地任务形状（ID 一律不透明字符串；只显示服务端状态） */
mapApiTask(task) {
if (!task || task.id == null || task.id === '' || TaskDisplay.isDemo(task)) return null;
const result=TaskResult.describe(task);if(!result)return null;
const kind=task.kind==='ai_video'?'ai':task.kind==='digital'?'digital':'remix';
const view = P.taskView(task.status);
const text = String((task.result && (task.result.text || task.result.script)) || '').trim();
return {
id: String(task.id), apiTask: true, kind, ...result,
title: makeTaskTitle({ kind: task.kind, title: task.title, description: task.description || (task.input && task.input.description), text: task.result && (task.result.text || task.result.script) }),
status: view, canDismiss: task.canDismiss !== false && !P.saveFailure(task)
  && ['SUCCEEDED','FAILED','CANCELLED'].includes(task.status), saveRetryable:P.saveFailure(task), statusText:task.statusText||'', stageLabel:P.saveFailure(task)?'生成完成，保存失败':TaskDisplay.stage(task.status), saved: false,
script: text, failedReason: String(task.failedReason || ''), resultUrl: result.resultUrl,
configuration: {}, image: TaskDisplay.icon(task.kind), kindLabel: result.kindLabel
};
},
go(view, query) {
const options = { url: '/pages/studio/index?view=' + view + (query || '') };
require('../../utils/page-navigation').open(options.url);
},
/** 内容域写命令一次性幂等键（重试同一动作复用，成功后释放；工程域 idem() 先例） */
contentKey(action) {
if (!this.__contentKeys) this.__contentKeys = {};
if (!this.__contentKeys[action]) this.__contentKeys[action] = 'content-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8);
return this.__contentKeys[action];
},
contentDropKey(action) { if (this.__contentKeys) delete this.__contentKeys[action]; },
/** 任务中心列表行轮询进度回填（onTick；行不在当前列表或已离开任务中心时忽略） */
patchTaskProgress(taskId, status) {
if (this.data.view !== 'tasks') return;
const index = (this.data.tasks || []).findIndex(t => t.id === taskId);
if (index >= 0) this.setData({ ['tasks[' + index + '].stageLabel']: TaskDisplay.stage(status), ['tasks[' + index + '].status']: P.taskView(status), ['tasks[' + index + '].statusText']: status==='ARCHIVING'?'生成完成，保存中':'' });
},
switchRewrite(e) { this.setData({ rewriteMode: Number(e.currentTarget.dataset.index) }); },
changeIp(e) { const ipIndex=Number(e.detail.value),item=this.data.personaOptions[ipIndex];if(item)this.setData({ipIndex,selectedPersonaId:item.id,operationError:''}); },
loadPersonas() {
const guard=http.requestGuard('remix-personas'),scope=this.actorScope;
return contentApi.personas.page().then(rows=>{
if(this.__closed||!guard()||scope!==studio.scope())return;
const options=[{id:'',name:'不指定人物'}].concat(P.rowsOf(rows).filter(r=>r&&r.id!=null&&r.enabled!==false).map(r=>({id:String(r.id),name:String(r.name||'未命名人物')})));
const selected=String(this.data.selectedPersonaId||''),index=options.findIndex(p=>p.id===selected);
this.setData({personaOptions:options,ipOptions:options.map(p=>p.name),ipIndex:Math.max(0,index),selectedPersonaId:index>=0?selected:'',operationError:selected&&index<0?'原选人物已停用或不存在，请重新选择人物':''});
}).catch(error=>{if(guard()&&scope===studio.scope())this.setData({operationError:'人物库加载失败：'+P.degradeMessage(error)});});
},
openCharacters() { wx.navigateTo({url:'/pages/studio/characters/index'}); },
inputRequirement(e) { this.setData({ requirement: e.detail.value, operationError: '' }); },
inputOriginal(e) { const state=studio.read();state.original=String(e.detail.value||'');state.script='';state.rewriteHistory=[];delete state.extraction;studio.write(state);this.setData({original:state.original,script:'',scriptShort:'',scriptLength:0,estimatedSeconds:0,rewriteVersion:0,operationError:'',extraction:null}); },
inputCustomIp(e) { this.setData({ customIp: e.detail.value }); },
inputRewrittenScript(e) {
if(this.__closed||this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration())return;
const text=String(e.detail.value||''),state=studio.read();
state.script=text;state.scriptEdited=true;delete state.editorBuffer;studio.write(state);
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(draft&&draft.text===state.original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
this.setData({script:text,scriptShort:text.split('\n').slice(0,2).join('\n'),scriptLength:text.length,estimatedSeconds:Math.ceil(text.replace(/\s/g,'').length/4),operationError:''});
},
editScript() { const buffer = studio.read().editorBuffer; this.setData({ sheet: 'editor', sheetTitle: '编辑口播文案', editingText: buffer && buffer.source === this.data.original ? buffer.text : this.data.script });keyboard.open(this); },
editorKeyboardChange(e) { keyboard.update(this,e); },
inputEdit(e) { this.setData({ editingText: e.detail.value }); const state = studio.read(); state.editorBuffer = { source: this.data.original, text: e.detail.value }; studio.write(state); },
saveScript() {
if (!this.data.editingText.trim()) { wx.showToast({ title: '请填写口播文案', icon: 'none' }); return; }
const state = studio.read(); state.script = this.data.editingText.trim(); state.scriptEdited=true; delete state.editorBuffer; studio.write(state);
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft));
if (draft && draft.text === state.original) wx.setStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft), Object.assign({}, draft, { rewrittenText: state.script, updatedAt: Date.now() }));
this.setData({ script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), sheet: '' }); keyboard.close(this);this.refreshData();
},
regenerate() {
if(this.data.rewriting||this.data.optimizing)return;
if(studio.read().optimizationAttempt)return wx.showToast({title:'请先确认上次优化结果',icon:'none'});
const onlineOriginal=String(this.data.original||'').trim();
if(!http.token())return wx.showToast({title:'请先登录后再生成二创',icon:'none'}); // 无 token：提示登录，不回落本地改写
if(!onlineOriginal)return wx.showToast({title:'请先粘贴链接或填写口播原文',icon:'none'});
this.setData({rewriting:true,operationError:''});
return this.regenerateOnline(onlineOriginal); // 在线：口播改写走后端（人设库匹配）
},
/** 在线二创改写：scripts.optimize {sourceText,requirement?,personaId?}；人设按名称匹配组织人设库，未匹配不传（后端按补充要求兜底） */
regenerateOnline(original) {
const persona=this.data.rewriteMode===1?String(this.data.customIp||'').trim():String(this.data.ipOptions[this.data.ipIndex]||'');
const requirement=String(this.data.requirement||'').trim();
const scope=this.actorScope;
const revision=studio.read().copyRevision;
const guard=http.requestGuard('remix-generate');
const alive=()=>!this.__closed&&guard()&&this.actorScope===scope&&scope===studio.scope();
const finish=()=>{if(alive()){this.setData({rewriting:false});this.scrollToCopy();}};
const apply=text=>{
if(!alive())return;
const state=studio.read();
const version=((state.rewriteHistory[state.rewriteHistory.length-1]||{}).version||0)+1;
const applied=state.copyRevision===revision&&this.data.original.trim()===original&&this.data.sheet!=='editor';
if(applied){state.original=original;state.script=text;delete state.editorBuffer;
state.rewriteSettings={rewriteMode:this.data.rewriteMode,customIp:this.data.customIp||'',ipIndex:this.data.ipIndex,selectedPersonaId:this.data.selectedPersonaId||'',requirement};}
state.rewriteHistory=state.rewriteHistory.concat({version,online:true,mode:'api-optimize',persona,requirement,original,script:text,applied,at:Date.now()});
studio.write(state);
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(applied&&draft&&draft.text===original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
if(applied)this.setData({script:text,scriptShort:text.split('\n').slice(0,2).join('\n'),scriptLength:text.length,estimatedSeconds:Math.ceil(text.replace(/\s/g,'').length/4),rewriteVersion:version});
wx.showToast({title:applied?'二创已生成':'稿件已修改，生成结果已保留，可到任务中心查看',icon:'none'});
finish();
};
return this.fetchPersonaId(persona).then(personaId=>{
if(!alive())throw {cancelled:true};
const payload={sourceText:original};
const custom=this.data.rewriteMode===1?persona:'';
if(requirement||custom)payload.requirement=[requirement,custom?'人物设定：'+custom:''].filter(Boolean).join('\n');
if(personaId)payload.personaId=personaId;
return this.resolveText(contentApi.scripts.optimize(payload));
}).then(apply).catch(error=>{
if(!alive()||(error&&error.cancelled))return;
const message=P.degradeMessage(error);
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
finish();
});
},
/** 组织人设库按名称匹配（personas.page）；列表为空/未命中/接口失败均返回空串 */
fetchPersonaId(label) {
if(this.data.rewriteMode===1||!this.data.selectedPersonaId)return Promise.resolve('');
return contentApi.personas.page().then(rows=>{
const hit=P.rowsOf(rows).find(item=>item&&String(item.id)===String(this.data.selectedPersonaId)&&item.enabled!==false);
if(!hit)throw Error('所选人物已停用或不存在，请重新选择');
return String(hit.id);
});
},
/** 优化响应双形状兼容：同步 {text} 或任务化 {taskId}→轮询取 result.text */
resolveText(promise,{isCurrent,onPoll}={}) {
return promise.then(res=>{
if(isCurrent&&!isCurrent())throw {cancelled:true};
if(res&&res.taskId){
const poll=P.pollTask(String(res.taskId));if(onPoll)onPoll(poll);
return poll.then(task=>{
if(P.taskView(task.status)!=='completed'){
const error=Error(task.failedReason||'生成未完成，请稍后在任务中心查看');
error.taskFailed=task.status==='FAILED'&&!P.saveFailure(task);throw error;
}
const text=spokenBody(task.result&&(task.result.text||task.result.script));
if(!text)throw Error('未返回文案，请稍后重试');return text;
});
}
const text=spokenBody(res&&(res.text||res.script));
if(!text)throw Error('未返回文案，请稍后重试');
return text;
});
},
scrollToCopy(){if(!wx.createSelectorQuery)return;const scroll=()=>{try{const q=wx.createSelectorQuery();if(!q.selectViewport)return;q.select('.rewritten-card').boundingClientRect();q.selectViewport().scrollOffset();q.exec(rows=>{if(rows&&rows[0]&&rows[1])wx.pageScrollTo({scrollTop:Math.max(0,rows[0].top+rows[1].scrollTop-((getApp().globalData.windowInfo||wx.getSystemInfoSync()).statusBarHeight+56)),duration:250});});}catch(_){/* Scrolling must never block a successfully generated script. */}};if(wx.nextTick)wx.nextTick(scroll);else scroll();},
openOptimization(){
if(this.data.optimizing||this.data.rewriting)return;
const attempt=studio.read().optimizationAttempt;
if(!attempt&&!this.data.script.trim())return wx.showToast({title:'请先生成二创文案',icon:'none'});
this.setData({sheet:'optimize',sheetTitle:'AI优化',optimizationRetry:!!attempt,optimizeRequirement:attempt?.payload?.requirement||'口语自然'});
},
inputOptimization(e){if(!this.data.optimizing&&!this.data.optimizationRetry)this.setData({optimizeRequirement:e.currentTarget.dataset.value});},
optimizeScript(){
const current=String(this.data.script||'');
if(!current.trim()&&!studio.read().optimizationAttempt)return wx.showToast({title:'请先生成二创文案',icon:'none'});
if(!http.token())return wx.showToast({title:'请先登录后再优化文案',icon:'none'}); // 无 token：提示登录，不回落本地优化
return this.optimizeOnline(current); // 在线：AI 优化走后端 scripts.optimize
},
stopOptimization(){
this.__optimizationGuard=null;
if(this.__optimizationPoll?.cancel)this.__optimizationPoll.cancel();
this.__optimizationPoll=null;
if(this.data.optimizing)this.setData({optimizing:false});
},
persistOptimizationAttempt(attempt){
const state=studio.read();state.optimizationAttempt=attempt;studio.write(state);
if(JSON.stringify(studio.read().optimizationAttempt)!==JSON.stringify(attempt))throw Error('未能保存优化记录，请释放本机存储后重试');
},
/** One paid operation keeps its payload/key until confirmed. A later edit only receives history, never an automatic overwrite. */
async optimizeOnline(current) {
if(this.data.optimizing||this.data.rewriting||this.__closed||this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration())return;
const scope=this.actorScope,guard=this.__optimizationGuard=http.requestGuard('remix-optimize');
const alive=()=>!this.__closed&&this.__optimizationGuard===guard&&guard()&&scope===studio.scope();
let attempt,creating=false;
this.setData({optimizing:true,operationError:''});
try{
const state=studio.read();
attempt=state.optimizationAttempt;
if(!attempt){
attempt={key:this.contentKey('optimize'),payload:{sourceText:current,requirement:String(this.data.optimizeRequirement||'').trim()},original:state.original,revision:state.copyRevision};
this.persistOptimizationAttempt(attempt);
}
if(!attempt.key||typeof attempt.payload?.sourceText!=='string'||typeof attempt.payload.requirement!=='string'||!Number.isSafeInteger(attempt.revision))throw Error('原优化记录无法确认，请到任务中心查看');
this.setData({optimizationRetry:true,optimizeRequirement:attempt.payload.requirement});
creating=!attempt.taskId;
const response=attempt.taskId?{taskId:attempt.taskId}:await contentApi.scripts.optimize(attempt.payload,attempt.key);
creating=false;if(!alive())return;
if(response?.taskId&&!attempt.taskId){attempt={...attempt,taskId:String(response.taskId)};this.persistOptimizationAttempt(attempt);}
const text=await this.resolveText(Promise.resolve(response),{isCurrent:alive,onPoll:poll=>{this.__optimizationPoll=poll;}});
if(!alive())return;
const latest=studio.read();if(latest.optimizationAttempt?.key!==attempt.key)return;
const applied=latest.copyRevision===attempt.revision&&latest.original===attempt.original&&latest.script===attempt.payload.sourceText&&this.data.sheet!=='editor';
if(applied)latest.script=text;
latest.copyOptimizations=(latest.copyOptimizations||[]).concat({before:attempt.payload.sourceText,after:text,requirement:attempt.payload.requirement,taskId:attempt.taskId||'',applied,online:true,at:Date.now()}).slice(-20);
// 先保存副草稿，再释放生成尝试。
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(applied&&draft&&draft.text===latest.original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
delete latest.optimizationAttempt;studio.write(latest);this.contentDropKey('optimize');
if(this.data.sheet==='optimize')this.setData({sheet:''});
this.refreshData();
wx.showToast({title:applied?'文案优化已完成':'稿件已修改，优化结果已保留，可到任务中心查看',icon:'none'});
}catch(error){
if(!alive()||error?.cancelled)return;
let message=P.degradeMessage(error);
try{
const latest=studio.read();
// 仅确定拒绝或生成失败才释放请求。
if(attempt&&latest.optimizationAttempt?.key===attempt.key&&(error.taskFailed||creating&&['400','1114000001'].includes(String(error.code)))){
delete latest.optimizationAttempt;studio.write(latest);this.contentDropKey('optimize');
}
this.setData({optimizationRetry:!!latest.optimizationAttempt});
}catch(storageError){message=P.degradeMessage(storageError);}
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
}finally{if(alive()){this.__optimizationGuard=null;this.__optimizationPoll=null;this.setData({optimizing:false});}}
},
makeDigital() {
if(!this.data.script.trim())return wx.showToast({title:'请先生成二创文案',icon:'none'});
const state = studio.read(); state.script = this.data.script; state.title = this.data.title; studio.write(state);
this.go('digital',this.data.selectedPersonaId?'&persona='+encodeURIComponent(this.data.selectedPersonaId):'');
},
restoreDigitalAttempt() {
const attempt=wx.getStorageSync(studio.scopedKey('zs-digital-attempt'));
const payload=attempt&&attempt.payload;
this.__digitalAttempt=attempt&&attempt.key&&payload&&payload.avatarCloneId&&payload.voiceCloneId&&typeof payload.text==='string'&&attempt.mock!==true&&attempt.demo!==true?attempt:null;
if(!this.__digitalAttempt)this.setData({digitalRetry:false});
if(this.__digitalAttempt){const {payload,personaId}=attempt,state=studio.read();state.script=payload.text;state.title=payload.title;studio.write(state);this.setData({digitalRetry:true,digitalPersonaId:personaId||'',script:payload.text,title:payload.title,scriptLength:payload.text.length});}
},
inputDigitalScript(e) {if(this.data.digitalSubmitting||this.data.digitalRetry)return;const text=e.detail.value,state=studio.read();state.script=text;state.scriptEdited=true;studio.write(state);this.setData({script:text,scriptLength:text.length,estimatedSeconds:Math.ceil(text.replace(/\s/g,'').length/4),operationError:''});},
cancelDigitalResources(){this.__digitalProfilesFlight=null;this.__digitalClonesFlight=null;if(this.data.digitalLoading)this.setData({digitalLoading:false});},
clearDigitalClones(patch={}){
this.__digitalClonesFlight=null;this.__digitalClonePersona='';
this.setData({avatarOptions:[],voiceOptions:[],avatarCloneId:'',voiceCloneId:'',actor:'',voice:'',actorIsDefault:false,voiceIsDefault:false,...patch});
},
resetDigitalResources(){
this.__digitalProfilesFlight=null;this.__digitalScope='';
if(this.__voiceAudio)this.__voiceAudio.stop();
this.clearDigitalClones({digitalPersonas:[],digitalPersonaId:'',digitalPersonaIndex:0,digitalLoading:false,digitalResourceError:'',...(['actor','voice'].includes(this.data.sheet)?{sheet:''}:{})});
},
prepareDigitalResources(){
if(this.__closed)return '';
if(!http.token()||this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()){this.resetDigitalResources();return '';}
const scope=listIdentity();if(this.__digitalScope!==scope){this.resetDigitalResources();this.__digitalScope=scope;}return scope;
},
digitalResourceCurrent(flight){
if(this.__closed||this[flight.owner]!==flight||this.data.view!==flight.view)return false;
if(!http.token()||flight.scope!==listIdentity()||this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()){
this.resetDigitalResources();this.setData({digitalResourceError:'工作身份已变化，请重新加载资源'});return false;
}
return flight.guard()&&(!flight.personaId||this.data.digitalPersonaId===flight.personaId);
},
digitalResourceFailure(flight,error){
if(this.__closed||this[flight.owner]!==flight||this.data.view!==flight.view)return;
const denied=error&&(error.postInvalid||[401,403,1103000002].includes(Number(error.code)));
if(denied)this.resetDigitalResources();else if(!this.digitalResourceCurrent(flight))return;
this.setData({digitalResourceError:P.degradeMessage(error)});
},
finishDigitalResource(flight){if(this[flight.owner]===flight){this[flight.owner]=null;this.setData({digitalLoading:!!(this.__digitalProfilesFlight||this.__digitalClonesFlight)});}},
digitalSelectionCurrent(){
if(this.__closed)return false;
if(http.token()&&this.actorScope===studio.scope()&&this.actorSessionGeneration===http.sessionGeneration()&&this.__digitalScope===listIdentity())return true;
this.resetDigitalResources();this.setData({digitalResourceError:'工作身份已变化，请重新加载资源'});return false;
},
loadDigitalProfiles(preferred) {
const scope=this.prepareDigitalResources();if(!scope)return;
if(this.__digitalProfilesFlight&&this.digitalResourceCurrent(this.__digitalProfilesFlight))return this.__digitalProfilesFlight.promise;
const flight={scope,view:this.data.view,owner:'__digitalProfilesFlight',guard:http.requestGuard('digital-personas')};this.__digitalProfilesFlight=flight;
this.setData({digitalLoading:true,digitalResourceError:''});
flight.promise=(async()=>{try{
const rows=P.rowsOf(await contentApi.personas.page()).filter(r=>r&&r.enabled!==false&&r.id!=null&&r.mock!==true&&r.demo!==true).map(r=>({id:String(r.id),name:String(r.name||'未命名人物')}));
if(!this.digitalResourceCurrent(flight))return;
const attempt=this.__digitalAttempt,saved=attempt?{personaId:attempt.personaId,avatarId:attempt.payload.avatarCloneId,voiceId:attempt.payload.voiceCloneId}:studio.read().digitalSelection||{};
const id=String(attempt?saved.personaId||'':this.data.digitalPersonaId||preferred||saved.personaId||'');
let index=rows.findIndex(r=>r.id===id);if(index<0&&!attempt)index=rows.length?0:-1;
const person=rows[index],patch={digitalPersonas:rows,digitalPersonaIndex:Math.max(0,index),digitalPersonaId:person?person.id:''};
if(!person||person.id!==this.data.digitalPersonaId)this.clearDigitalClones(patch);else this.setData(patch);
if(person)await this.loadDigitalClones(person.id,saved.personaId===person.id?saved:{});
else this.setData({digitalResourceError:attempt?'原提交人物已停用或不存在，可到任务中心确认原提交结果':'人物库暂无可选人物，请先由管理员新增人物并制作分身和声音'});
}catch(e){this.digitalResourceFailure(flight,e);}finally{this.finishDigitalResource(flight);}})();
return flight.promise;
},
refreshDigitalResources(){return this.loadDigitalProfiles(this.data.digitalPersonaId);},
changeDigitalPersona(e) {if(this.__generating||this.data.digitalRetry||!this.digitalSelectionCurrent())return;const index=Number(e.detail.value),person=this.data.digitalPersonas[index];if(person&&person.id!==this.data.digitalPersonaId){this.__digitalProfilesFlight=null;this.clearDigitalClones({digitalPersonaIndex:index,digitalPersonaId:person.id});return this.loadDigitalClones(person.id,{});}},
loadDigitalClones(personaId,saved={}) {
personaId=String(personaId||'');const scope=this.prepareDigitalResources();if(!scope||!personaId)return;
if(this.data.digitalPersonaId&&this.data.digitalPersonaId!==personaId)return;
if(!this.data.digitalPersonaId)this.setData({digitalPersonaId:personaId});
if(this.__digitalClonesFlight&&this.digitalResourceCurrent(this.__digitalClonesFlight))return this.__digitalClonesFlight.promise;
const retained=this.__digitalClonePersona===personaId;
if(!retained)this.clearDigitalClones();this.__digitalClonePersona=personaId;
const flight={scope,view:this.data.view,personaId,owner:'__digitalClonesFlight',guard:http.requestGuard('digital-clones')};this.__digitalClonesFlight=flight;
this.setData({digitalLoading:true,digitalResourceError:''});
flight.promise=(async()=>{try{
const rows=P.rowsOf(await contentApi.clones.list(personaId)).filter(r=>r&&r.ready&&r.id!=null&&r.mock!==true&&r.demo!==true);
if(!this.digitalResourceCurrent(flight))return;
const avatars=rows.filter(r=>r.kind==='AVATAR'),voices=rows.filter(r=>r.kind==='VOICE'),attempt=this.__digitalAttempt;
// 新建用服务端默认；刷新保留选择，重试冻结素材 ID。
const choose=(options,id,current)=>attempt?options.find(r=>String(r.id)===String(id)):
  (retained&&options.find(r=>String(r.id)===String(current)))||options.find(r=>r.isDefault===true)||options.find(r=>String(r.id)===String(id))||options[0];
const a=choose(avatars,attempt?attempt.payload.avatarCloneId:saved.avatarId,this.data.avatarCloneId),v=choose(voices,attempt?attempt.payload.voiceCloneId:saved.voiceId,this.data.voiceCloneId);
this.setData({avatarOptions:avatars,voiceOptions:voices,avatarCloneId:a?String(a.id):'',voiceCloneId:v?String(v.id):'',actor:a?a.title:'',voice:v?v.title:'',actorIsDefault:!!(a&&a.isDefault),voiceIsDefault:!!(v&&v.isDefault),digitalResourceError:!a||!v?'当前人物缺少可用的'+(!a?'分身':'')+(!a&&!v?'和':'')+(!v?'声音':'')+'，请在人物资源中查看状态':''});
this.persistDigitalSelection();
}catch(e){this.digitalResourceFailure(flight,e);}finally{this.finishDigitalResource(flight);}})();
return flight.promise;
},
persistDigitalSelection(){if(!this.digitalSelectionCurrent())return;const state=studio.read();state.digitalSelection={personaId:this.data.digitalPersonaId,avatarId:this.data.avatarCloneId,voiceId:this.data.voiceCloneId};studio.write(state);},
changeActor() {if(this.__generating||this.data.digitalRetry||!this.digitalSelectionCurrent())return; this.setData({ sheet: 'actor', sheetTitle: '选择数字分身' }); },
selectActor(e) {if(this.__generating||this.data.digitalRetry||!this.digitalSelectionCurrent())return; const item=this.data.avatarOptions.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(item){this.setData({actor:item.title,avatarCloneId:String(item.id),actorIsDefault:!!item.isDefault,sheet:''});this.persistDigitalSelection();} },
changeVoice() {if(this.__generating||this.data.digitalRetry||!this.digitalSelectionCurrent())return; this.setData({ sheet: 'voice', sheetTitle: '选择克隆声音' }); },
selectVoice(e) {if(this.__generating||this.data.digitalRetry||!this.digitalSelectionCurrent())return; const item=this.data.voiceOptions.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(item){this.setData({voice:item.title,voiceCloneId:String(item.id),voiceIsDefault:!!item.isDefault,sheet:''});this.persistDigitalSelection();} },
audition() {
if(!this.digitalSelectionCurrent())return;
const selected=this.data.voiceOptions.find(r=>String(r.id)===String(this.data.voiceCloneId));
if(!selected||!selected.demoUrl)return wx.showToast({title:'暂无可用试听音频，请在人物库刷新状态',icon:'none'});
if(this.__voiceAudio)this.__voiceAudio.destroy();this.__voiceAudio=wx.createInnerAudioContext();this.__voiceAudio.src=selected.demoUrl;
this.__voiceAudio.onError(()=>wx.showToast({title:'试听音频暂不可用，请稍后重试',icon:'none'}));this.__voiceAudio.play();
},
generateDigital() {
if (!this.data.script.trim()) { wx.showToast({ title: '请先填写口播文案', icon: 'none' }); return; }
if (!http.token()) return wx.showToast({ title: '请先登录后再生成口播', icon: 'none' }); // 无 token：提示登录，不弹本地预览
return this.generateDigitalOnline(); // 在线：直连后端创建 digital 任务
},
/** 在线数字人口播：tasks.create(digital) → 任务中心态展示轮询进度 → SUCCEEDED 后 saveWork（一任务一件幂等）。
 * showLoading 只覆盖提交阶段；轮询进度经任务中心列表回填展示，onUnload 终止。 */
generateDigitalOnline() {
if(this.__generating)return; // 提交防重（loading 只护提交阶段）
if(!this.__digitalAttempt&&!this.digitalSelectionCurrent())return; // 新提交核对资源身份；待确认请求仍使用冻结参数与幂等键
if(!this.__digitalAttempt&&(this.data.digitalLoading||!this.data.avatarCloneId||!this.data.voiceCloneId))return wx.showToast({title:'请先选择已就绪的数字分身和克隆声音',icon:'none'});
const text=this.data.script.trim();
const title=makeTaskTitle({kind:'digital',title:this.data.title,text});
if(!this.__digitalAttempt&&(!text||text.length>10000))return this.setData({operationError:'口播文案须为1至10000字'});
const state=studio.read();state.script=text;state.title=title;studio.write(state); // 本地留痕（script/title 非守卫键）
if(!this.__digitalAttempt){this.__digitalAttempt={personaId:this.data.digitalPersonaId,payload:{kind:'digital',avatarCloneId:this.data.avatarCloneId,voiceCloneId:this.data.voiceCloneId,text,title},key:this.contentKey('task')};wx.setStorageSync(studio.scopedKey('zs-digital-attempt'),this.__digitalAttempt);}
const attempt=this.__digitalAttempt,guard=http.requestGuard('digital-create'),scope=this.actorScope;
const alive=()=>!this.__closed&&guard()&&scope===studio.scope();
if(this.__digitalPoll&&this.__digitalPoll.cancel)this.__digitalPoll.cancel();this.__digitalPoll=null;
this.__generating=true;
this.setData({digitalSubmitting:true,digitalRetry:true,operationError:''});
wx.showLoading({title:'提交生成任务',mask:true});
return contentApi.tasks.create(attempt.payload,attempt.key).then(res=>{
if(!alive())return;
wx.hideLoading();
const taskId=res&&res.id?String(res.id):'';
if(!taskId)throw Error('任务创建失败，请稍后重试');
wx.removeStorageSync(studio.scopedKey('zs-digital-attempt'));this.__digitalAttempt=null;this.__generating=false;this.setData({digitalSubmitting:false,digitalRetry:false});
this.contentDropKey('task'); // 提交成功释放；失败保留，重试复用同键
this.go('tasks'); // 轮询进度在任务中心态展示
const poll=this.__digitalPoll=P.pollTask(taskId,{onTick:task=>{if(alive())this.patchTaskProgress(String(task.id||taskId),task.status);}});
return poll.then(task=>{
if(!alive()||this.__digitalPoll!==poll)return null; // 已取消/新一轮提交
this.__digitalPoll=null;this.__generating=false;
const view=P.taskView(task.status);
if(view==='completed')return contentApi.tasks.saveWork(taskId,attempt.payload.title,this.contentKey('save-work:'+taskId)).then(()=>{
if(!alive())return;
this.contentDropKey('save-work:'+taskId);
wx.showToast({title:'已生成并保存到我的作品'});
if(this.data.view==='tasks')this.refreshData();
});
if(view==='failed'){wx.showModal({title:'生成失败',content:task.failedReason||'请稍后重试或调整文案。',showCancel:false,confirmColor:'#824019'});return null;}
wx.showToast({title:'提交结果确认中，已可在任务中心查看',icon:'none'});
return null;
}).catch(error=>{
if(this.__digitalPoll===poll){this.__digitalPoll=null;this.__generating=false;}
if(error&&error.cancelled)return null; // 页面卸载取消：静默
if(!alive())return null;
wx.showToast({title:P.degradeMessage(error),icon:'none'}); // 任务已在任务中心，轮询失败仅提示
});
}).catch(error=>{
if(!alive())return;
wx.hideLoading();this.__generating=false;
const code=Number(error&&error.code),uncertain=!Number.isFinite(code)||code<=0||(code>=500&&code<600);
if(!uncertain){wx.removeStorageSync(studio.scopedKey('zs-digital-attempt'));this.__digitalAttempt=null;this.contentDropKey('task');}
this.setData({digitalSubmitting:false,digitalRetry:uncertain,operationError:P.degradeMessage(error)});
wx.showToast({title:P.degradeMessage(error),icon:'none'}); // 接口失败仅提示错误，不回落演示兜底
});
},
changeTaskFilter(e) { this.setData({ taskFilter: Number(e.currentTarget.dataset.index) }); this.refreshData(); },
changeRecordFilter(e) { this.setData({ recordFilter: Number(e.currentTarget.dataset.index) }); this.refreshData(); },
taskAction(e) {
const id = e.currentTarget.dataset.id;
const shown = this.data.tasks.find(task => task.id === id);
if (!shown) { wx.showToast({ title: '任务不存在或已清理', icon: 'none' }); return; }
// 在线任务仅由云端执行。
if (shown.status === 'generating') { wx.showModal({ title: '任务进度', content: (shown.stageLabel||'正在处理')+'，任务在云端执行，可稍后刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.saveRetryable) return this.retryTaskSave(id);
if (shown.status === 'failed') { wx.showModal({ title: '生成失败', content: shown.failedReason || '生成失败，请重新创建任务。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.status === 'uncertain') { wx.showModal({ title: '结果确认中', content: '任务提交结果暂不确定，请稍后在任务中心刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
this.go('result', '&task=' + encodeURIComponent(id));
},
onTaskTouchStart(e) {
const id=String(e.currentTarget.dataset.id||'');
const point=e.touches&&e.touches[0];
if(!id||!point||!(this.data.tasks||[]).some(row=>row.id===id&&row.canDismiss))return;
this.__taskTouch={id,x:point.clientX,y:point.clientY,dx:0,dy:0};
},
onTaskTouchMove(e) {
const touch=this.__taskTouch,point=e.touches&&e.touches[0];
if(!touch||!point)return;
touch.dx=point.clientX-touch.x;touch.dy=point.clientY-touch.y;
if(Math.abs(touch.dx)>12&&Math.abs(touch.dx)>Math.abs(touch.dy)) {
 const tasks=(this.data.tasks||[]).map(row=>({...row,swiped:row.id===touch.id&&row.canDismiss&&touch.dx<-28}));
 this.setData({tasks});
}
},
onTaskTouchEnd() {
const touch=this.__taskTouch;this.__taskTouch=null;
if(!touch)return;
const open=touch.dx<-48&&Math.abs(touch.dx)>Math.abs(touch.dy);
const tasks=(this.data.tasks||[]).map(row=>({...row,swiped:row.id===touch.id&&row.canDismiss?open:false}));
this.setData({tasks});
},
onTaskLongPress(e) { this.dismissTaskTap(e); },
dismissTaskTap(e) {
 const id=String(e.currentTarget.dataset.id||'');
 const shown=(this.data.tasks||[]).find(row=>row.id===id);
 if(!shown?.canDismiss||this.__dismissingTask||this.data.view!=='tasks')return;
 const current=http.requestGuard('studio-task-dismiss-confirm'),scope=studio.scope(),identity=listIdentity();
 wx.showModal({title:'移除任务',content:'仅从你的任务中心移除，不会删除云端任务、已保存作品或客资记录。确定继续？',confirmText:'移除',confirmColor:'#a33b2b',
   success:res=>{if(res.confirm&&current()&&!this.__closed&&scope===studio.scope()&&identity===listIdentity())this.dismissTask(id);}});
},
async dismissTask(id) {
 if(this.__dismissingTask||this.__closed||this.data.view!=='tasks')return;
 const shown=(this.data.tasks||[]).find(row=>String(row.id)===String(id));
 if(!shown?.canDismiss)return;
 const alive=http.requestGuard('studio-task-dismiss'),scope=this.actorScope,identity=listIdentity(),attempt={};
 const current=()=>alive()&&!this.__closed&&this.__dismissingTask===attempt&&scope===studio.scope()&&identity===listIdentity()&&this.data.view==='tasks';
 this.__dismissingTask=attempt;
 this.setData({tasks:(this.data.tasks||[]).map(row=>({...row,dismissing:String(row.id)===String(id)}))});
 try {
  await contentApi.tasks.dismiss(id);
  if(!current())return;
  // 关闭后废弃旧列表响应，再刷新当前筛选。
  this.__taskListSeq=(this.__taskListSeq||0)+1;
  const remaining=(this.data.tasks||[]).filter(row=>String(row.id)!==String(id));
  this.setData({tasks:remaining,taskCount:remaining.length});
  wx.showToast({title:'已从任务中心移除',icon:'success'});
  this.syncApiTasks();
 } catch(error) {
  if(current()) {
   this.setData({tasks:(this.data.tasks||[]).map(row=>({...row,dismissing:String(row.id)===String(id)?false:row.dismissing}))});
   wx.showToast({title:P.degradeMessage(error),icon:'none'});
  }
 } finally {if(this.__dismissingTask===attempt)this.__dismissingTask=null;}
},

async retryTaskSave(id) {
 if(this.__retryingSave)return;
 const alive=http.requestGuard('studio-retry-save'),scope=this.actorScope,identity=listIdentity(),attempt={};
 const current=()=>alive()&&!this.__closed&&this.__retryingSave===attempt&&scope===studio.scope()&&identity===listIdentity()&&this.data.view==='tasks';
 this.__retryingSave=attempt;
 try {
  await contentApi.tasks.retrySave(id);
  if(!current())return;
  wx.showToast({title:'正在恢复保存，不会再次扣点',icon:'none'});
  this.__taskListSeq=(this.__taskListSeq||0)+1;
  await this.syncApiTasks();
 }catch(e){if(current())wx.showToast({title:P.degradeMessage(e),icon:'none'});}
 finally{if(this.__retryingSave===attempt)this.__retryingSave=null;}
},
refreshTasks() { return this.syncApiTasks(); },
playVideo() {
const task=this.data.activeTask;
if(task&&task.apiTask&&task.resultUrl){if(this.data.view==='result')wx.createVideoContext('digital-result',this).play();else if(wx.previewMedia)wx.previewMedia({sources:[{url:task.resultUrl,type:'video'}]});return;}
wx.showToast({title:'暂无可播放视频',icon:'none'});
},
videoError(){this.setData({'activeTask.videoReady':false,resultError:'视频暂不可播放，请重试读取成果'});},
/** 按真实视频比例设置播放器高度，避免固定高度内的黑边。 */
videoMetadata(e){const width=Number(e&&e.detail&&e.detail.width),height=Number(e&&e.detail&&e.detail.height);if(!(width>0&&height>0))return;const next=Math.round(646*height/width);this.setData({resultVideoHeight:Math.max(320,Math.min(1300,next))});},
attempt(action) { try { this.setData({operationError:''});return action(); } catch (error) { const message=error.message||'操作未完成，请重试';this.setData({operationError:message});wx.showToast({ title: message, icon: 'none' }); return null; } },
saveResult() { return this.saveApiResult(false); }, // 任务全部在线：保存走 save-work 幂等通道
publishResult() { return this.saveApiResult(true); },
/** 在线任务→作品：save-work 幂等（重复返既有），成功后可去发布；幂等键重试复用 */
async saveApiResult(publish) {
const task=this.data.activeTask;
if(this.data.resultSaving)return;
if(!task||task.outputType!=='video'||!task.videoReady){this.setData({resultError:'请先读取可用的视频成果'});return;}
const alive=http.requestGuard('studio-save-result'),scope=this.actorScope;
this.setData({resultSaving:true,resultError:''});
try{
 const res=await contentApi.tasks.saveWork(this.data.taskId, task.title || '', this.contentKey('save-work:'+this.data.taskId));
 if(!alive()||this.__closed||scope!==studio.scope())return;
 if(!res||!res.workId)throw Error('作品保存结果缺少编号，请重试');
 this.contentDropKey('save-work:'+this.data.taskId);
 this.setData({workId:String(res.workId),saved:true,'activeTask.saved':true});
 if(publish)this.go('publish','&work='+encodeURIComponent(res.workId));
 else wx.showToast({title:'已保存到我的作品'});
}catch(e){if(alive()&&!this.__closed)this.setData({resultError:P.degradeMessage(e)});}
finally{if(alive()&&!this.__closed)this.setData({resultSaving:false});}
},
retryResult(){return this.loadApiResult(this.data.taskId);},
useTextResult(){
 const task=this.data.activeTask;if(!task||task.outputType!=='text'||!task.resultText)return;
 const state=studio.read();state.original=task.resultText;state.script=task.taskKind==='copy'?task.resultText:'';
 state.title=task.resultText.split('\n')[0].slice(0,28);delete state.editorBuffer;studio.write(state);this.go('remix');
},
async loadApiResult(taskId) {
 const seq=this.__resultSeq=(this.__resultSeq||0)+1,alive=http.requestGuard('studio-result'),scope=this.actorScope;
 const current=()=>seq===this.__resultSeq&&alive()&&!this.__closed&&this.data.view==='result'&&scope===studio.scope();
 this.setData({resultLoading:true,resultError:'',resultUrlNote:'',activeTask:null});
 try{
  const task=await contentApi.tasks.get(taskId);if(!current())return;
  if(P.taskView(task&&task.status)!=='completed')throw Error('任务尚未成功完成，请在任务中心查看状态');
  const mapped=this.mapApiTask(task);if(!mapped)throw Error('暂不支持此任务类型');
  const defaultVideoHeight=mapped.kind==='digital'?1148:363;
  this.setData({activeTask:mapped,saved:false,invalidMessage:'',pageTitle:mapped.kindLabel+'结果',resultVideoHeight:defaultVideoHeight});
  if(mapped.outputType==='video'){
   if(!mapped.videoReady)throw Error('任务已完成，但视频成果尚不可读取，请重试或联系管理员');
   const state=await contentApi.tasks.resultUrlStatus(taskId);if(!current())return;
   if(state&&state.expired)this.setData({'activeTask.videoReady':false,'activeTask.resultUrl':'',resultError:state.message||'视频链接已过期，请联系管理员恢复成果'});
  }
 }catch(e){if(current()&&!e.silent)this.setData({resultError:P.degradeMessage(e)});}
 finally{if(current())this.setData({resultLoading:false});}
},
/** 本人作品正文只从详情读取；已填写或恢复的发布表单优先。 */
loadApiWork(workId) {
this.setData({workId,publishOnline:true,workDetailLoading:true,workDetailFailed:false});
const alive=http.requestGuard('publication-work'),scope=this.actorScope;
const current=()=>alive()&&!this.__closed&&scope===studio.scope()&&this.data.view==='publish'&&String(this.data.workId)===String(workId);
const titleEdit=this.__workTitleEdit,textEdit=this.__workTextEdit;
return contentApi.drafts.work(workId).then(work => {
if(!current())return;
if(String(work?.workId)!==String(workId))throw Error('作品未读到');
const next={};
if(titleEdit===this.__workTitleEdit&&!this.data.title&&work.title)next.title=String(work.title).trim();
if(textEdit===this.__workTextEdit&&!this.data.publishText&&work.script)next.publishText=String(work.script).trim();
this.setData(next);
}).catch(() => {if(current())this.setData({workDetailFailed:true});})
.finally(() => {if(current())this.setData({workDetailLoading:false});});
},
retryApiWork(){if(this.data.workId&&!this.data.workDetailLoading)return this.loadApiWork(this.data.workId);},
editResult() {
const state = studio.read(); const task = this.data.activeTask;
if (!task) { this.refreshData(); return; }
if (task.kind === 'ai') wx.navigateTo({ url: '/pages/ai-video/index?task=' + encodeURIComponent(task.id) });
else { state.script = task.script || ''; state.scriptEdited=true; state.title = task.title; delete state.editorBuffer; studio.write(state); this.go('digital'); }
},
selectAccount(e) { return require('./publication-accounts').selectPlatform(this,this.publicationEnvironment(),e.currentTarget.dataset.index); },
switchAccount() { this.setData({ sheet: 'accounts', sheetTitle: '更换账号' }); },
changeCover() { if(!this.data.coverUploading)this.setData({ sheet: 'cover', sheetTitle: '更换封面' }); },
restoreCoverGeneration(){return require('./publication-cover-templates').restore(this,this.publicationEnvironment());},
openCoverTemplates(){return require('./publication-cover-templates').open(this,this.publicationEnvironment());},
loadCoverTemplates(){return require('./publication-cover-templates').load(this,this.publicationEnvironment());},
moreCoverTemplates(){return require('./publication-cover-templates').load(this,this.publicationEnvironment(),true);},
selectCoverTemplate(e){return require('./publication-cover-templates').edit(this,'coverTemplateId',e.currentTarget.dataset.id);},
inputCoverGenerationTitle(e){return require('./publication-cover-templates').edit(this,'coverGenerationTitle',e.detail.value);},
selectCoverCanvas(e){return require('./publication-cover-templates').edit(this,'coverCanvasPreset',e.currentTarget.dataset.value);},
generatePublicationCover(){return require('./publication-cover-templates').generate(this,this.publicationEnvironment());},
resumeCoverGeneration(){return require('./publication-cover-templates').resume(this,this.publicationEnvironment());},
useGeneratedCover(){return require('./publication-cover-templates').use(this,this.publicationEnvironment());},
resetCoverGeneration(){return require('./publication-cover-templates').reset(this,this.publicationEnvironment());},
reloadGeneratedCover(){return require('./publication-cover-templates').previewCandidate(this,this.publicationEnvironment());},
generatedCoverPreviewError(){this.setData({coverGeneratedPreview:'',coverGeneratedPreviewError:'封面预览读取失败，请重试'});},
uploadPublicationCover(){return require('./publication-cover').upload(this,this.publicationEnvironment());},
restorePublicationCover(){return require('./publication-cover').restore(this,this.publicationEnvironment());},
removePublicationCover(){return require('./publication-cover').clear(this);},
publicationCoverError(){this.setData({cover:'',coverReady:false,coverError:'封面读取失败，请重新读取'});},
hydratePublicationCovers(){
const rows=this.data.view==='records'?this.data.records:[this.data.detail];
if(!(rows||[]).some(row=>row&&row.coverAssetId))return;
return require('./publication-cover').hydrate(this,this.publicationEnvironment());
},
inputTitle(e) { this.__workTitleEdit=(this.__workTitleEdit||0)+1;this.setData({ title: e.detail.value }); },
clearTitle() { this.__workTitleEdit=(this.__workTitleEdit||0)+1;this.setData({ title: '' }); },
inputPublishText(e) { this.__workTextEdit=(this.__workTextEdit||0)+1;this.setData({ publishText: e.detail.value }); },
inputTags(e) { this.setData({ tags: e.detail.value }); },
loadPublisherAccounts(){return require('./publication-accounts').load(this,this.publicationEnvironment());},
selectPublisherAccount(e){return require('./publication-accounts').select(this,e.currentTarget.dataset.id);},
managePublisherAccounts(){return require('./publication-accounts').manage(this,this.publicationEnvironment());},
editPublisherAccount(e){return require('./publication-accounts').edit(this,e.currentTarget.dataset.id);},
inputPublisherAccountName(e){this.setData({publisherAccountNameInput:e.detail.value});},
inputPublisherAccountIdentifier(e){this.setData({publisherAccountIdentifierInput:e.detail.value});},
savePublisherAccount(){return require('./publication-accounts').save(this,this.publicationEnvironment());},
togglePublisherAccount(e){return require('./publication-accounts').toggle(this,this.publicationEnvironment(),e.currentTarget.dataset.id);},
moreSettings() { this.setData({ sheet: 'settings', sheetTitle: '更多设置' }); },
/** The pending request survives page exit and is replayed only through an explicit recovery action. */
publicationEnvironment() {
return {wx,http,studio,api:contentApi,post:require('../../api/auth').currentApiPost(),message:P.degradeMessage,validate:P.validatePublicationForm};
},
restorePublicationPending(){return require('./publication-pending').restore(this,this.publicationEnvironment());},
resumePublication(){return require('./publication-pending').resume(this,this.publicationEnvironment());},
openExistingPublication(){
if(!this.data.publicationExistingId)return;
this.persistEditor();
this.go(this.data.publicationExistingStatus==='published'?'detail':'receipt','&record='+encodeURIComponent(this.data.publicationExistingId));
},
/** 从链接域名推断平台；无法判断时返回 -1。 */
inferPlatform(value) {
const match=/^https?:\/\/([^/?#]+)/i.exec(String(value||'').trim());
if(!match)return -1;
const host=match[1].split(':')[0].toLowerCase();
if(/(^|\.)douyin\.com$|(^|\.)iesdouyin\.com$/.test(host))return 0;
if(/(^|\.)weixin\.qq\.com$|(^|\.)finder\.video\.qq\.com$/.test(host))return 1;
return -1;
},
toggleLead() { this.setData({ leadOpen: !this.data.leadOpen, operationError: '' }); },
inputLeadName(e) { this.setData({ leadName: e.detail.value, operationError: '' }); },
inputLeadPhone(e) { this.setData({ leadPhone: e.detail.value, operationError: '' }); },
inputLeadNeed(e) { this.setData({ leadNeed: e.detail.value, operationError: '' }); },
readLinkFlow(workId) {
try{const flows=studio.read().linkFlows||{},flow=flows[String(workId||'')];return flow&&typeof flow==='object'?flow:null;}catch(_){return null;}
},
saveLinkFlow(flow) {
try{const state=studio.read();state.linkFlows=state.linkFlows||{};state.linkFlows[String(this.data.workId)]=flow;studio.write(state);}catch(_){/* 本地恢复失败不应阻断已提交的网络动作 */}
},
clearLinkFlow() {
this.__linkFlow=null;
try{const state=studio.read();if(state.linkFlows&&state.linkFlows[String(this.data.workId)]){delete state.linkFlows[String(this.data.workId)];studio.write(state);}}catch(_){}
},
/** 回读已有发布记录状态，避免刷新后重复 verify/link。 */
async refreshLinkStatus(recordId,flow) {
try{
const rows=await contentApi.publications.page();
const row=P.rowsOf(rows).find(item=>item&&String(item.id!=null?item.id:item.publicationId)===String(recordId));
if(row&&row.status){
flow.status=String(row.status);
if(flow.status==='published')flow.publishedUrl=String(row.publishedUrl||'').trim();
if(row.publisherAccountId!=null)flow.confirmedAccount=String(row.publisherAccountId);
return flow.status;
}
}catch(_){}
return String(flow.status||'');
},
leadPayload() {
if(!this.data.leadOpen)return {payload:null};
const name=String(this.data.leadName||'').trim(),phone=String(this.data.leadPhone||'').trim(),need=String(this.data.leadNeed||'').trim();
if(!name&&!phone&&!need)return {payload:null};
if(!name)return {error:'请填写客户称呼，或清空客资信息后只保存链接'};
if(!/^1\d{10}$/.test(phone))return {error:'请填写有效的手机号'};
if(!need)return {error:'请填写客户咨询需求'};
return {payload:{name,phone,need}};
},
submitLinkAndLead() {
if(this.data.linkSubmitting||this.data.linkSaved||this.data.publicationPending||this.data.publicationExistingId)return;
if(!http.token())return wx.showToast({title:'请先登录后再保存',icon:'none'});
if(this.data.invalidMessage)return wx.showToast({title:'作品信息尚未加载完成，请返回重试',icon:'none'});
const workId=String(this.data.workId||'').trim();
if(!workId)return wx.showToast({title:'请先选择已保存的作品',icon:'none'});
const urlError=P.validatePublishedUrl(this.data.publishedUrlInput);
if(urlError){this.setData({operationError:urlError});return wx.showToast({title:urlError,icon:'none'});}
const platform=(studio.publicationPlatforms||[])[this.data.selectedAccount];
if(!platform)return wx.showToast({title:'请选择发布平台',icon:'none'});
if(this.data.publisherAccountsLoading)return wx.showToast({title:'发布账号加载中，请稍候',icon:'none'});
if(!this.data.publisherAccountsLoaded||this.data.publisherAccountsError)return wx.showToast({title:'请先重新加载发布账号',icon:'none'});
const account=(this.data.publisherAccounts||[]).find(row=>row&&String(row.id)===String(this.data.publisherAccountId)&&row.enabled===true&&row.platform===platform.platform);
if(!account)return wx.showToast({title:'请选择发布账号',icon:'none'});
const lead=this.leadPayload();
if(lead.error){this.setData({operationError:lead.error});return wx.showToast({title:lead.error,icon:'none'});}
return this.submitLinkOnline({workId,url:this.data.publishedUrlInput.trim(),platform:platform.platform,publisherAccountId:String(account.id),lead:lead.payload});
},
/** 创建发布记录 → verify → link；如填写客资，再按同一链接来源登记。 */
async submitLinkOnline({workId,url,platform,publisherAccountId,lead}) {
if(this.__linkSubmitting)return;
let flow=(this.__linkFlow&&String(this.__linkFlow.workId||'')===String(workId))?this.__linkFlow:(this.readLinkFlow(workId)||{});
const oldRecord=flow.recordId||this.data.linkRecordId;
if(!oldRecord&&!flow.payload&&this.data.workDetailLoading&&!String(this.data.publishText||'').trim())
 return wx.showToast({title:'正在读取作品正文，请稍候',icon:'none'});
const sessionAlive=http.sessionGuard(),scope=this.actorScope;
const alive=()=>!this.__closed&&sessionAlive()&&this.actorScope===scope&&scope===studio.scope()&&this.data.view==='publish';
this.__linkSubmitting=true;
this.setData({linkSubmitting:true,operationError:''});
flow=Object.assign({},flow,{workId:String(workId)});
flow.createKey=flow.createKey||this.contentKey('publication:'+workId);
flow.verifyKey=flow.verifyKey||this.contentKey('verify:'+workId);
flow.linkKey=flow.linkKey||this.contentKey('link:'+workId);
flow.leadKey=flow.leadKey||this.contentKey('capture:'+workId);
this.__linkFlow=flow;this.saveLinkFlow(flow);
let stage='create',recordId=String(flow.recordId||this.data.linkRecordId||''),status=String(flow.status||'');
try{
if(recordId){
stage='status';
status=await this.refreshLinkStatus(recordId,flow);
if(!alive())return;
if(status==='failed'||status==='scheduled')throw Error('该发布记录当前状态不可回填链接，请到发布记录查看');
}
const sameInput=()=>!(flow.linkInput&&flow.linkInput!==url)
  && !(flow.payload?.publisherAccountId&&flow.payload.publisherAccountId!==publisherAccountId)
  && !(flow.confirmedAccount&&flow.confirmedAccount!==publisherAccountId);
if(!sameInput())throw {code:400,message:'请用原链接和发布账号核对上一次提交'};
if(!recordId){
stage='create';
const title=String(this.data.title||'').trim()||'未命名作品';
const publishText=String(this.data.publishText||'').trim()||title;
const payload=flow.payload||{workId:String(workId),title,publishText,tags:String(this.data.tags||'').trim(),publisherAccountId,platform};
flow.payload=payload;this.saveLinkFlow(flow);
const res=await contentApi.publications.create(payload,flow.createKey);
if(!alive())return;
recordId=res&&(res.id||res.publicationId)?String(res.id||res.publicationId):'';
if(!recordId)throw Error('发布记录创建失败，请稍后重试');
status=String(res&&res.status||'');
if(status==='published')flow.publishedUrl=String(res.publishedUrl||'').trim();
if(res.publisherAccountId!=null)flow.confirmedAccount=String(res.publisherAccountId);
flow.recordId=recordId;flow.status=status;this.setData({linkRecordId:recordId});this.saveLinkFlow(flow);
this.contentDropKey('publication:'+workId);
}
stage='status';
if(!sameInput())throw {code:400,message:'请用原链接和发布账号核对上一次提交'};
stage='verify';
if(status!=='verify'&&status!=='published'){
await contentApi.publications.verify(recordId,flow.verifyKey);
if(!alive())return;
flow.status='verify';status='verify';this.saveLinkFlow(flow);
}
stage='status';
if(status==='published'&&flow.publishedUrl!==url){
  throw {code:400,message:'已登记的链接与当前输入不一致，请到发布记录核对'};
}
stage='link';
if(status!=='published'){
flow.linkInput=url;this.saveLinkFlow(flow);
await contentApi.publications.link(recordId,url,flow.linkKey);
if(!alive())return;
flow.publishedUrl=url;flow.status='published';status='published';this.saveLinkFlow(flow);
this.contentDropKey('verify:'+workId);this.contentDropKey('link:'+workId);
}
stage='status';
if(flow.leadInput&&JSON.stringify(flow.leadInput)!==JSON.stringify(lead)){
  throw {code:400,message:'客资结果尚未确认，请恢复原客资后核对，不要重复登记'};
}
stage='lead';
if(lead){
flow.leadInput=lead;this.saveLinkFlow(flow);
const res=await crm.leads.capture(Object.assign({},lead,{source:'内容发布:'+recordId}),flow.leadKey);
if(!alive())return;
const result=String(res&&res.result||''),customerId=res&&res.customerId!=null?String(res.customerId):'';
if(result==='LINK_REQUIRED'||((result==='CREATED'||result==='LINKED')&&customerId)){
this.clearLinkFlow();this.contentDropKey('capture:'+workId);
this.setData({linkSubmitting:false,linkSaved:true,linkRecordId:recordId,leadOpen:false,leadName:'',leadPhone:'',leadNeed:''});this.__linkSubmitting=false;
try{this.persistEditor();}catch(_){}
if(result==='LINK_REQUIRED'){wx.showToast({title:'已登记，待负责人关联原客户',icon:'none'});this.returnHome();}
else{wx.showToast({title:'客资已登记',icon:'none'});wx.navigateTo({url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(customerId)});}
return;
}
if(result!=='CREATED'&&result!=='LINKED'&&result!=='LINK_REQUIRED')throw Error('客资登记结果待确认，请稍后到客资列表查看');
}
this.clearLinkFlow();this.contentDropKey('capture:'+workId);
this.setData({linkSubmitting:false,linkSaved:true,linkRecordId:recordId,leadOpen:false,leadName:'',leadPhone:'',leadNeed:''});this.__linkSubmitting=false;
try{this.persistEditor();}catch(_){}
wx.showToast({title:lead?'发布链接与客资已保存':'发布链接已保存',icon:'success'});
this.returnHome();
}catch(error){
if(!alive())return;
const code=Number(error&&error.code);
const codeText=String((error&&error.code)||''),rawMessage=String((error&&error.message)||'');
const duplicate=stage==='link'&&(codeText==='1_114_000_003'||codeText==='1114000003'||/已登记|重复/.test(rawMessage));
const uncertain=!duplicate&&(!Number.isFinite(code)||code===-1||code===0||(code>=500&&code<600));
let message=P.degradeMessage(error);
if(duplicate)message='该发布链接已登记过，请核对后填写';
else if(stage==='lead'&&flow.status==='published')message='发布链接已保存，客资登记失败：'+message;
else if(uncertain)message='提交结果待确认，请重试核对本次提交';
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
if(!uncertain){
if(stage==='create'){this.contentDropKey('publication:'+workId);this.clearLinkFlow();}
else if(stage==='verify'){flow.verifyKey='';this.saveLinkFlow(flow);}
else if(stage==='link'){flow.linkKey='';delete flow.linkInput;this.contentDropKey('link:'+workId);this.saveLinkFlow(flow);}
else if(stage==='lead'){flow.leadKey='';delete flow.leadInput;this.contentDropKey('capture:'+workId);this.saveLinkFlow(flow);}
}
}finally{
if(alive())this.setData({linkSubmitting:false});
this.__linkSubmitting=false;
}
},
/** 在线回执页：列表回读真实状态；迟到响应不得跨会话、岗位或记录写回。 */
loadApiReceipt(recordId) {
const alive=http.requestGuard('studio-receipt'),scope=this.actorScope,expected=String(recordId);
this.setData({receiptLoading:true,receiptError:'',invalidMessage:''});
contentApi.publications.page().then(rows=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!=='receipt'||String(this.data.recordId)!==expected)return;
const row=P.rowsOf(rows).find(item=>item&&String(item.id!=null?item.id:item.publicationId)===String(recordId));
if(!row)throw Error('发布记录加载失败，请返回重试');
const status=String(row.status||'');
const info=statusInfo[status]||{label:'状态待确认',icon:'warning',tone:'yellow'};
const platform=String(row.platform||'douyin');
const detail={ id:String(recordId), apiRecord:true, title:String(row.title||''), publishText:String(row.publishText||''), tags:String(row.tags||''), platform, account:String(row.accountName||row.account||row.accountId||'账号未记录'), authorName:String(row.authorName||''), workId:String(row.workId||''), kindLabel:studio.kindLabel(row.kind==='ai_video'?'ai':(row.kind||'digital')), cover:'',coverAssetId:String(row.coverAssetId||''),coverError:false, status, publishedUrl:String(row.publishedUrl||''), label:info.label, icon:info.icon, tone:info.tone };
this.setData({ detail, publishedUrlInput:detail.publishedUrl||this.data.publishedUrlInput, receiptLoading:false,invalidMessage:'' });
this.hydratePublicationCovers();
}).catch(error=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!=='receipt'||String(this.data.recordId)!==expected)return;
this.setData({detail:null,receiptLoading:false,invalidMessage:P.degradeMessage(error)});
});
},
openRecord(e) { this.go('detail', '&record=' + e.currentTarget.dataset.id); },
async openPublicationVideo(e) {
 const id=String(e.currentTarget.dataset.id||this.data.recordId||this.data.detail&&this.data.detail.id||'');
 if(!/^\d+$/.test(id))return wx.showToast({title:'发布记录编号无效',icon:'none'});
 const scope=this.actorScope,alive=http.requestGuard('studio-publication-playback');
 const current=()=>!this.__closed&&alive()&&scope===this.actorScope&&scope===studio.scope();
 try {
  const result=await contentApi.publications.playback(id);
  if(!current())return;
  const url=String(result&&result.resultUrl||'');
  if(!url)return wx.showToast({title:'原视频暂不可播放',icon:'none'});
  if(wx.previewMedia)wx.previewMedia({sources:[{url,type:'video'}]});
  else wx.showToast({title:'当前微信版本暂不支持视频预览',icon:'none'});
 } catch(error) { if(current())wx.showToast({title:P.degradeMessage(error),icon:'none'}); }
},
refreshRecords() { return this.refreshData(); },
registerConsultation(){const record=this.data.detail;if(record?.status==='published'&&record.id)wx.navigateTo({url:'/pages/growth/index?view=capture&publicationId='+encodeURIComponent(record.id)});},
inputPublishedUrl(e){const value=e.detail.value,patch={publishedUrlInput:value,operationError:'',receiptError:'',receiptSaved:false};this.setData(patch);const platform=this.inferPlatform(value);if(platform>=0&&platform!==this.data.selectedAccount){try{this.selectAccount({currentTarget:{dataset:{index:platform}}});}catch(_){}}},
receiptWriteError(){
if(this.data.receiptLoading)return '发布记录加载中，请稍候';
const detail=this.data.detail,status=detail&&detail.status;
if(!detail||String(detail.id)!==String(this.data.recordId)||!status)return '发布记录尚未加载完成，请返回重试';
if(status==='scheduled')return '历史定时记录暂不支持登记链接，请返回发布记录查看';
if(status==='published')return '已发布记录仅供查看，不能重复登记链接';
if(!['publishing','verify'].includes(status))return '当前发布状态不可登记链接';
return '';
},
guardReceiptWrite(){const message=this.receiptWriteError();if(!message)return true;this.setData({receiptError:message});wx.showToast({title:message,icon:'none'});return false;},
savePublicationLink(){
if(this.data.receiptSaving)return;
if(!this.guardReceiptWrite())return;
if(!http.token())return wx.showToast({title:'请先登录后再登记发布链接',icon:'none'}); // 无 token：提示登录，不落本地演示回执
return this.savePublicationLinkOnline();
},
/** 在线登记发布链接：publishing 先人工确认外部发布；verify 才可直接登记链接。 */
savePublicationLinkOnline(){
if(!this.guardReceiptWrite())return;
const invalid=P.validatePublishedUrl(this.data.publishedUrlInput);
if(invalid)return this.setData({receiptError:invalid});
const url=this.data.publishedUrlInput.trim(),recordId=this.data.recordId;
this.setData({receiptSaving:true,receiptError:''});
// verify 失败不可吞，否则 link 会误导。
const status=this.data.detail&&this.data.detail.status;
const prepare=status==='publishing'?contentApi.publications.verify(recordId,this.contentKey('verify:'+recordId)):Promise.resolve();
prepare.then(()=>contentApi.publications.link(recordId,url,this.contentKey('link:'+recordId))).then(()=>{
this.contentDropKey('verify:'+recordId);this.contentDropKey('link:'+recordId);
this.setData({receiptSaved:true});
wx.reLaunch({
url:'/pages/business/index',
success:()=>wx.showToast({title:'发布链接已保存',icon:'success'}),
fail:()=>this.setData({receiptError:'链接已保存，返回首页失败，请点击按钮重试。'}),
complete:()=>this.setData({receiptSaving:false})
});
}).catch(error=>{
const code=String((error&&error.code)||'');
const message=code==='1_114_000_003'||code==='1114000003'||/已登记|重复/.test(String((error&&error.message)||''))?'该发布链接已登记过，请核对后填写':(error&&error.message)||'保存失败，请稍后重试';
this.setData({receiptSaving:false,receiptError:message});
wx.showToast({title:message,icon:'none'});
});
},
copyPublishedUrl(){if(this.data.detail?.publishedUrl)wx.setClipboardData({data:this.data.detail.publishedUrl});},
advancePublication() {
const record=this.data.detail;
if(record&&record.id&&record.status!=='published')this.go('receipt','&record='+encodeURIComponent(record.id));
},
returnHome(){wx.reLaunch({url:'/pages/business/index'});},
returnRecords() { wx.redirectTo({ url: '/pages/studio/index?view=records' }); },
openWork(e) { this.go('result', '&task=' + e.currentTarget.dataset.task); },
openTasks() { this.go('tasks'); },
returnToList() { this.go(this.data.view === 'detail' ? 'records' : this.data.view === 'result' ? 'tasks' : 'works'); },
openRecent(e) {
const item = this.data.recent.find(row => row.id === e.currentTarget.dataset.id);
if (!item) return;
if (item.id === 'draft') { if (studio.restoreManualDraft()) this.go('remix'); else require('../../utils/page-navigation').open('/pages/creation/index?editor=1'); }
else if (item.id === 'video-draft') wx.navigateTo({ url: '/pages/ai-video/index' });
else if (item.editTask) wx.navigateTo({ url: '/pages/ai-video/index?task=' + encodeURIComponent(item.editTask) });
else this.go('result', '&task=' + encodeURIComponent(item.taskId));
},

closeSheet() { if (this.data.sheet === 'editor') { keyboard.close(this);wx.showToast({ title: '编辑内容已暂存，可继续编辑', icon: 'none' }); }this.setData({ sheet: '' }); },
noop() {}
});
