const studio = require('../../utils/studio');
const MaterialLibrary = require('./material-library');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：七态映射/校验/降级）
const TaskResult=require('./content-task-result');
const TaskDisplay=require('./task-display');
const contentApi=P.api;                                 // /franchise/content/** 契约层
const http=require('../../api/http');
const keyboard=require('../../utils/creation-keyboard');
const titles = { remix: '文案二创', digital: '数字人口播', tasks: '任务中心', result: '视频结果', publish: '手动发布登记', receipt:'登记发布链接', records: '发布记录', detail: '发布详情', works: '我的作品', library: '素材库', recent: '全部创作' };
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
resultLoading:false,resultSaving:false,resultError:'',resultUrlNote:'',
view: 'remix', pageTitle: '', original: '', script: '', title: studio.title, rewriteMode: 0, ipIndex: 0, rewriting: false, operationError: '',
ipOptions: ['不指定人物'], personaOptions: [{id:'',name:'不指定人物'}], selectedPersonaId: '', requirement: '',digitalSubmitting:false,digitalRetry:false,
taskFilter: 0, recordFilter: 0, taskTabs: ['全部', '生成中', '已完成', '异常'], recordTabs: ['全部', '进行中', '已发布', '异常'],
taskCount:0,tasks: [], records: [], works: [],  publicationPlatforms:studio.publicationPlatforms||[], sheet: '', sheetTitle: '', editingText: '', selectedAccount: 0,publicationAccount:'',publishSubmitting:false,publishedUrlInput:'',receiptError:'',receiptSaved:false,receiptSaving:false,receiptLoading:false,tasksError:'',tasksLoading:false,worksLoading:false,worksError:'',recordsLoading:false,recordsError:'',
publishText: '',
tags: '', cover: '', workId: '', saved: false,
actor: '', voice: '', avatarCloneId: '', voiceCloneId: '', digitalPersonaId: '', digitalPersonas: [], digitalPersonaIndex: 0, avatarOptions: [], voiceOptions: [], digitalLoading: false, filter: '', detail: null, activeTask: null, invalidMessage: '', recent: [], timeline: [], scriptLength: 0, estimatedSeconds: 0
},
onLoad(options) {
if(!studio.guard())return;this.__closed=false;this.actorScope=studio.scope();this.actorSessionGeneration=http.sessionGeneration();
const view = titles[options.view] ? options.view : 'remix';
this.options = options;
const state = studio.read();
this.setData({ view, pageTitle: titles[view], original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), title: state.title, taskId: options.task || '', recordId: options.record || '', workId: options.work || '' });
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
if (options.work && http.token()) this.loadApiWork(options.work); // 在线作品：接口回读，缺失不拦截
}
if (view === 'remix' && state.rewriteSettings) this.setData(Object.assign({},state.rewriteSettings,{rewriteMode:0,customIp:''}));
if (view === 'remix') {
if(options.persona)this.setData({selectedPersonaId:String(options.persona),rewriteMode:0});
this.loadPersonas();
}
const sourceId=options.content||options.confirm;if(view==='publish'&&sourceId){const c=require('../../utils/growth').load().contents.find(c=>c.id===sourceId&&c.workId===options.work);if(c)this.setData({title:c.pack.title,publishText:c.pack.publishText,tags:c.pack.tags,cover:c.pack.cover,selectedAccount:Math.max(0,(studio.publicationPlatforms||[]).findIndex(a=>a.platform===c.pack.platform))});}
this.refreshData();
if(view==='receipt'){
this.setData({publishedUrlInput:state.publicationLinkDrafts?.[this.data.recordId]||this.data.detail?.publishedUrl||''});
if(http.token()){this.receiptApi=true;this.loadApiReceipt(this.data.recordId);} // 在线发布记录：回执页按后端数据渲染
}
},
onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
onShow() {if(!studio.guard())return;if(this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()){wx.redirectTo({url:'/pages/studio/index?view='+this.data.view});return;}if(this.data.sheet==='editor')keyboard.open(this);if (this.options) this.refreshData();if(this.data.view==='digital')this.loadDigitalProfiles(this.data.digitalPersonaId||this.options&&this.options.persona);
  },
onHide() { if(this.data.view==='library')this.materialHide(); keyboard.stop(this);if(this.__voiceAudio)this.__voiceAudio.stop();this.persistEditor(); },
onUnload() { if (this.__digitalPoll && this.__digitalPoll.cancel) this.__digitalPoll.cancel(); if(this.data.view==='library')this.materialUnload(); keyboard.stop(this);if(this.__voiceAudio)this.__voiceAudio.destroy();if(this.data.digitalSubmitting)wx.hideLoading(); this.__closed=true; this.persistEditor(); }, // 终止任务轮询（防 UI 长时间占锁）
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
const { title, publishText, tags, selectedAccount, publicationAccount, cover, workId } = this.data;
state.publishDrafts[workId] = { title, publishText, tags, selectedAccount, publicationAccount, cover, workId }; studio.write(state);
}
},
refreshData() {
if (http.token()) { // 仅在线路径：任务中心走后端列表；不装配本地演示 tasks/records/works
if (['remix', 'digital'].includes(this.data.view)) { // 编辑器文案（草稿暂存键）仍回显
const state = studio.read();
this.setData({ title: state.title, original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), scriptLength: state.script.length, estimatedSeconds: Math.ceil(state.script.replace(/\s/g, '').length / 4), extraction: state.extraction || null, rewriteVersion: (state.rewriteHistory[state.rewriteHistory.length - 1] || {}).version || 0 });
}
if (this.data.view === 'library') this.materialInit();
if (this.data.view === 'tasks') this.syncApiTasks();
if (this.data.view === 'works') this.syncApiWorks();
if (this.data.view === 'records') this.syncApiRecords();
if (this.data.view === 'detail') this.syncApiRecords(this.data.recordId);
return;
}
// 无 token：清空演示集合并提示登录（不回落本地数据）
this.setData({ taskCount:0,tasks: [], records: [], works: [], recent: [], activeTask: null, detail: null, tasksError: '', invalidMessage: '请先登录后使用创作中心' });
return;
},
/** 在线任务列表：tasks.page 七态 → 页面四态（generating/completed/failed/uncertain），异常页签含 failed/uncertain */
syncApiTasks() {
const filter=this.data.taskFilter,scope=this.actorScope,guard=http.requestGuard('studio-tasks');
const sequence=this.__taskListSeq=(this.__taskListSeq||0)+1;
const current=()=>!this.__closed&&guard()&&scope===studio.scope()&&this.data.view==='tasks'&&sequence===this.__taskListSeq;
this.setData({tasksLoading:true,tasksError:''});
return contentApi.tasks.page().then(rows=>{
if(!current())return;
const mapped=P.rowsOf(rows).map(task=>this.mapApiTask(task)).filter(Boolean);
const tasks=mapped.filter(task=>filter===0||(filter===1&&task.status==='generating')||(filter===2&&task.status==='completed')||(filter===3&&task.status!=='generating'&&task.status!=='completed'));
this.setData({tasks,taskCount:tasks.length,tasksError:'',tasksLoading:false});
}).catch(()=>{if(current())this.setData({tasksError:'任务列表加载失败，请重试',tasks:[],taskCount:0,tasksLoading:false});});
},
/** 在线作品列表：drafts.works 返回本人作品；异步结果只允许写回发起时的会话、岗位与页面。 */
syncApiWorks() {
const alive=http.requestGuard('studio-works'),scope=this.actorScope;
this.setData({worksLoading:true,worksError:''});
contentApi.drafts.works().then(rows=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!=='works')return;
const works=P.rowsOf(rows).map(row=>{
if(!row||row.id==null||row.id==='')return null;
const kind=row.kind==='ai_video'?'ai':(row.kind==='digital'?'digital':'remix');
return{id:String(row.id),taskId:row.taskId==null?'':String(row.taskId),title:String(row.title||'未命名作品'),kind,status:String(row.status||'draft'),image:studio.imageFor({kind})};
}).filter(Boolean);
this.setData({works,worksLoading:false,worksError:''});
}).catch(error=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!=='works')return;
this.setData({works:[],worksLoading:false,worksError:P.degradeMessage(error)});
});
},
/** 发布记录列表与详情共用 publications.page；状态筛选在页面端完成。 */
syncApiRecords(recordId) {
const alive=http.requestGuard('studio-records'),scope=this.actorScope,view=this.data.view,filter=this.data.recordFilter;
this.setData({recordsLoading:true,recordsError:''});
contentApi.publications.page().then(rows=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!==view)return;
const mapped=P.rowsOf(rows).map(row=>this.mapApiPublication(row)).filter(Boolean);
if(recordId!=null&&recordId!==''){
const detail=mapped.find(item=>item.id===String(recordId));
if(!detail)throw Error('发布记录不存在或已清理');
this.setData({detail,timeline:this.publicationTimeline(detail),recordsLoading:false,recordsError:'',invalidMessage:''});
return;
}
const records=mapped.filter(item=>filter===0||(filter===1&&['publishing','verify','scheduled'].includes(item.status))||(filter===2&&item.status==='published')||(filter===3&&item.status==='failed'));
this.setData({records,recordsLoading:false,recordsError:''});
}).catch(error=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!==view)return;
const message=P.degradeMessage(error);
if(recordId!=null&&recordId!=='')this.setData({detail:null,timeline:[],recordsLoading:false,recordsError:message,invalidMessage:message});
else this.setData({records:[],recordsLoading:false,recordsError:message});
});
},
mapApiPublication(row) {
if(!row||row.id==null||row.id==='')return null;
const status=String(row.status||'publishing'),info=statusInfo[status]||{label:'状态待确认',icon:'warning',tone:'yellow',action:'查看详情'};
const platform=String(row.platform||'douyin'),account=String(row.accountName||row.account||row.accountId||'账号未记录');
const timeValue=status==='published'?(row.publishedAt||row.createTime):status==='scheduled'?(row.scheduledAt||row.createTime):row.createTime;
return{id:String(row.id),apiRecord:true,workId:row.workId==null?'':String(row.workId),title:String(row.title||'未命名发布'),publishText:String(row.publishText||''),tags:String(row.tags||''),platform,account,status,publishedUrl:String(row.publishedUrl||''),failureReason:String(row.failureReason||''),signature:String(row.signature||''),cover:'/pages/studio/assets/studio-publication-cover.jpg',kindLabel:'数字人口播',time:studio.formatTime(timeValue),displayTime:studio.formatTime(timeValue),timeLabel:status==='published'?'发布时间':status==='scheduled'?'计划发布时间':'提交时间',identifier:String(row.signature||row.id),label:info.label,icon:info.icon,tone:info.tone,action:info.action||'查看详情',note:status==='failed'?String(row.failureReason||info.note||'发布失败'):String(info.note||'')};
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
title: text ? text.split('\n')[0].slice(0, 28) : (String(task.title || '').trim() || studio.kindLabel(kind) + '任务'),
status: view, saveRetryable:P.saveFailure(task), statusText:task.statusText||'', stageLabel:P.saveFailure(task)?'生成完成，保存失败':TaskDisplay.stage(task.status), saved: false,
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
if(this.data.rewriting)return;
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
const guard=http.requestGuard('remix-generate');
const alive=()=>!this.__closed&&guard()&&this.actorScope===scope&&scope===studio.scope();
const finish=()=>{if(alive()){this.setData({rewriting:false});this.scrollToCopy();}};
const apply=text=>{
if(!alive())return;
const state=studio.read();
const version=((state.rewriteHistory[state.rewriteHistory.length-1]||{}).version||0)+1;
state.original=original;state.script=text;
state.rewriteHistory=state.rewriteHistory.concat({version,online:true,mode:'api-optimize',persona,requirement,original,script:text,at:Date.now()});
state.rewriteSettings={rewriteMode:this.data.rewriteMode,customIp:this.data.customIp||'',ipIndex:this.data.ipIndex,selectedPersonaId:this.data.selectedPersonaId||'',requirement};
studio.write(state);
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(draft&&draft.text===original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
this.setData({script:text,scriptShort:text.split('\n').slice(0,2).join('\n'),scriptLength:text.length,estimatedSeconds:Math.ceil(text.replace(/\s/g,'').length/4),rewriteVersion:version});
wx.showToast({title:'二创已生成',icon:'none'});
finish();
};
this.fetchPersonaId(persona).then(personaId=>{
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
resolveText(promise) {
return promise.then(res=>{
if(res&&res.taskId)return P.pollTask(String(res.taskId)).then(task=>{
if(P.taskView(task.status)!=='completed')throw Error(task.failedReason||'生成未完成，请稍后在任务中心查看');
return String((task.result&&(task.result.text||task.result.script))||'').trim();
});
const text=String((res&&(res.text||res.script))||'').trim();
if(!text)throw Error('未返回文案，请稍后重试');
return text;
});
},
scrollToCopy(){if(!wx.createSelectorQuery)return;const scroll=()=>{try{const q=wx.createSelectorQuery();if(!q.selectViewport)return;q.select('.rewritten-card').boundingClientRect();q.selectViewport().scrollOffset();q.exec(rows=>{if(rows&&rows[0]&&rows[1])wx.pageScrollTo({scrollTop:Math.max(0,rows[0].top+rows[1].scrollTop-((getApp().globalData.windowInfo||wx.getSystemInfoSync()).statusBarHeight+56)),duration:250});});}catch(_){/* Scrolling must never block a successfully generated script. */}};if(wx.nextTick)wx.nextTick(scroll);else scroll();},
openOptimization(){if(!this.data.script.trim())return wx.showToast({title:'请先生成二创文案',icon:'none'});this.setData({sheet:'optimize',sheetTitle:'AI优化',optimizeRequirement:'口语自然'});},
inputOptimization(e){this.setData({optimizeRequirement:e.currentTarget.dataset.value});},
optimizeScript(){
const current=String(this.data.script||'');
if(!current.trim())return wx.showToast({title:'请先生成二创文案',icon:'none'});
if(!http.token())return wx.showToast({title:'请先登录后再优化文案',icon:'none'}); // 无 token：提示登录，不回落本地优化
return this.optimizeOnline(current); // 在线：AI 优化走后端 scripts.optimize
},
/** 在线 AI 优化：成功后写回本地文案与优化历史（不触碰 tasks/works/records 守卫键） */
optimizeOnline(current) {
const requirement=String(this.data.optimizeRequirement||'').trim();
this.resolveText(contentApi.scripts.optimize({sourceText:current,requirement})).then(text=>{
if(this.actorScope!==studio.scope())return;
const state=studio.read();
state.script=text;
state.copyOptimizations=(state.copyOptimizations||[]).concat({before:current,after:text,requirement,online:true,at:Date.now()}).slice(-20);
studio.write(state);
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(draft&&draft.text===state.original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
this.setData({sheet:''});
this.refreshData();
wx.showToast({title:'文案优化已完成',icon:'none'});
}).catch(error=>{
const message=P.degradeMessage(error);
this.setData({operationError:message,sheet:''});
wx.showToast({title:message,icon:'none'});
});
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
async loadDigitalProfiles(preferred) {
const guard=http.requestGuard('digital-personas'),scope=this.actorScope;
this.setData({digitalLoading:true,operationError:'',digitalPersonas:[],digitalPersonaId:'',actor:'',voice:'',avatarCloneId:'',voiceCloneId:'',avatarOptions:[],voiceOptions:[]});
try{
const rows=P.rowsOf(await contentApi.personas.page()).filter(r=>r&&r.enabled!==false&&r.id!=null&&r.mock!==true&&r.demo!==true).map(r=>({id:String(r.id),name:String(r.name||'未命名人物')}));
if(this.__closed||!guard()||scope!==studio.scope())return;
const attempt=this.__digitalAttempt;
const saved=attempt?{personaId:attempt.personaId,avatarId:attempt.payload.avatarCloneId,voiceId:attempt.payload.voiceCloneId}:studio.read().digitalSelection||{};
const id=String(saved.personaId&&attempt?saved.personaId:preferred||saved.personaId||''),index=Math.max(0,rows.findIndex(r=>r.id===id));
this.setData({digitalPersonas:rows,digitalPersonaIndex:index,digitalPersonaId:rows[index]?rows[index].id:'',digitalLoading:false});
if(rows[index])return this.loadDigitalClones(rows[index].id,saved);
this.setData({operationError:'人物库暂无可选人物，请先由管理员新增人物并制作分身和声音'});
}catch(e){if(!this.__closed&&guard()&&scope===studio.scope())this.setData({digitalLoading:false,operationError:P.degradeMessage(e)});}
},
changeDigitalPersona(e) {if(this.__generating||this.data.digitalRetry)return;const index=Number(e.detail.value),person=this.data.digitalPersonas[index];if(person){this.setData({digitalPersonaIndex:index,digitalPersonaId:person.id});this.loadDigitalClones(person.id,{});}},
async loadDigitalClones(personaId,saved={}) {
const guard=http.requestGuard('digital-clones'),scope=this.actorScope;
this.setData({digitalLoading:true,operationError:'',avatarOptions:[],voiceOptions:[],avatarCloneId:'',voiceCloneId:'',actor:'',voice:''});
try{
const rows=P.rowsOf(await contentApi.clones.list(personaId)).filter(r=>r&&r.ready&&r.id!=null&&r.mock!==true&&r.demo!==true);
if(this.__closed||!guard()||scope!==studio.scope()||this.data.digitalPersonaId!==personaId)return;
const avatars=rows.filter(r=>r.kind==='AVATAR'),voices=rows.filter(r=>r.kind==='VOICE');
// Pending paid submissions keep their exact snapshot; fresh generation prefers server defaults.
const choose=(options,id)=>this.__digitalAttempt?options.find(r=>String(r.id)===String(id)):
  options.find(r=>r.isDefault===true)||options.find(r=>String(r.id)===String(id))||options[0];
const a=choose(avatars,saved.avatarId),v=choose(voices,saved.voiceId);
this.setData({digitalLoading:false,avatarOptions:avatars,voiceOptions:voices,avatarCloneId:a?String(a.id):'',voiceCloneId:v?String(v.id):'',actor:a?a.title:'',voice:v?v.title:'',actorIsDefault:!!(a&&a.isDefault),voiceIsDefault:!!(v&&v.isDefault),operationError:''});
this.persistDigitalSelection();
}catch(e){if(!this.__closed&&guard()&&scope===studio.scope())this.setData({digitalLoading:false,operationError:P.degradeMessage(e)});}
},
persistDigitalSelection(){if(this.actorScope!==studio.scope())return;const state=studio.read();state.digitalSelection={personaId:this.data.digitalPersonaId,avatarId:this.data.avatarCloneId,voiceId:this.data.voiceCloneId};studio.write(state);},
changeActor() {if(this.__generating||this.data.digitalRetry)return; this.setData({ sheet: 'actor', sheetTitle: '选择数字分身' }); },
selectActor(e) {if(this.__generating||this.data.digitalRetry)return; const item=this.data.avatarOptions.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(item){this.setData({actor:item.title,avatarCloneId:String(item.id),actorIsDefault:!!item.isDefault,sheet:''});this.persistDigitalSelection();} },
changeVoice() {if(this.__generating||this.data.digitalRetry)return; this.setData({ sheet: 'voice', sheetTitle: '选择克隆声音' }); },
selectVoice(e) {if(this.__generating||this.data.digitalRetry)return; const item=this.data.voiceOptions.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(item){this.setData({voice:item.title,voiceCloneId:String(item.id),voiceIsDefault:!!item.isDefault,sheet:''});this.persistDigitalSelection();} },
audition() {
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
if(!this.__digitalAttempt&&(this.data.digitalLoading||!this.data.avatarCloneId||!this.data.voiceCloneId))return wx.showToast({title:'请先选择已就绪的数字分身和克隆声音',icon:'none'});
const text=this.data.script.trim();
const title=String(this.data.title||'').trim()||text.split('\n')[0].slice(0,28);
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
// 在线任务：云端执行，不做本地「标记完成」
if (shown.status === 'generating') { wx.showModal({ title: '任务进度', content: (shown.stageLabel||'正在处理')+'，任务在云端执行，可稍后刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.saveRetryable) return this.retryTaskSave(id);
if (shown.status === 'failed') { wx.showModal({ title: '生成失败', content: shown.failedReason || '生成失败，请重新创建任务。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.status === 'uncertain') { wx.showModal({ title: '结果确认中', content: '任务提交结果暂不确定，请稍后在任务中心刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
this.go('result', '&task=' + encodeURIComponent(id));
},
async retryTaskSave(id) {
 if(this.__retryingSave)return;
 const alive=http.requestGuard('studio-retry-save'),scope=this.actorScope;
 this.__retryingSave=true;
 try {
  await contentApi.tasks.retrySave(id);
  if(!alive()||this.__closed||scope!==studio.scope())return;
  wx.showToast({title:'正在恢复保存，不会再次扣点',icon:'none'});
  await this.syncApiTasks();
 }catch(e){if(alive()&&!this.__closed&&scope===studio.scope())wx.showToast({title:P.degradeMessage(e),icon:'none'});}
 finally{this.__retryingSave=false;}
},
refreshTasks() { return this.syncApiTasks(); },
playVideo() {
const task=this.data.activeTask;
if(task&&task.apiTask&&task.resultUrl){if(this.data.view==='result')wx.createVideoContext('digital-result',this).play();else if(wx.previewMedia)wx.previewMedia({sources:[{url:task.resultUrl,type:'video'}]});return;}
wx.showToast({title:'暂无可播放视频',icon:'none'});
},
videoError(){this.setData({'activeTask.videoReady':false,resultError:'视频暂不可播放，请重试读取成果'});},
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
 const res=await contentApi.tasks.saveWork(this.data.taskId, '', this.contentKey('save-work:'+this.data.taskId));
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
  this.setData({activeTask:mapped,saved:false,invalidMessage:'',pageTitle:mapped.kindLabel+'结果'});
  if(mapped.outputType==='video'){
   if(!mapped.videoReady)throw Error('任务已完成，但视频成果尚不可读取，请重试或联系管理员');
   const state=await contentApi.tasks.resultUrlStatus(taskId);if(!current())return;
   if(state&&state.expired)this.setData({'activeTask.videoReady':false,'activeTask.resultUrl':'',resultError:state.message||'视频链接已过期，请联系管理员恢复成果'});
  }
 }catch(e){if(current()&&!e.silent)this.setData({resultError:P.degradeMessage(e)});}
 finally{if(current())this.setData({resultLoading:false});}
},
/** 在线作品回读：drafts.works 找标题/文案（best-effort，失败沿用当前表单值） */
loadApiWork(workId) {
this.setData({ workId, publishOnline: true });
contentApi.drafts.works().then(rows => {
const work=P.rowsOf(rows).find(item=>item&&String(item.workId!=null?item.workId:item.id)===String(workId));
if(work&&this.data.view==='publish')this.setData({title:String(work.title||this.data.title),publishText:String(work.script||work.text||this.data.publishText)});
}).catch(() => {});
},
editResult() {
const state = studio.read(); const task = this.data.activeTask;
if (!task) { this.refreshData(); return; }
if (task.kind === 'ai') wx.navigateTo({ url: '/pages/ai-video/index?task=' + encodeURIComponent(task.id) });
else { state.script = task.script || ''; state.scriptEdited=true; state.title = task.title; delete state.editorBuffer; studio.write(state); this.go('digital'); }
},
selectAccount(e) { this.setData({ selectedAccount: Number(e.currentTarget.dataset.index), sheet: '' }); },
switchAccount() { this.setData({ sheet: 'accounts', sheetTitle: '更换账号' }); },
changeCover() { this.setData({ sheet: 'cover', sheetTitle: '更换封面' }); },
selectCover(e) { this.setData({ cover: e.currentTarget.dataset.path, sheet: '' }); },
inputTitle(e) { this.setData({ title: e.detail.value }); },
clearTitle() { this.setData({ title: '' }); },
inputPublishText(e) { this.setData({ publishText: e.detail.value }); },
inputTags(e) { this.setData({ tags: e.detail.value }); },
inputPublicationAccount(e) { this.setData({ publicationAccount: e.detail.value }); },
moreSettings() { this.setData({ sheet: 'settings', sheetTitle: '更多设置' }); },
submitPublish() {
if (!http.token()) return wx.showToast({ title: '请先登录后再发布', icon: 'none' }); // 无 token：提示登录，不落本地发布记录
return this.submitPublishOnline(); // 发布链走后端
},
/** 在线手动发布登记：提交快照与幂等键保留到成功或明确业务失败；网络响应丢失按原请求重试。 */
async submitPublishOnline() {
if(this.data.publishSubmitting)return;
const sessionAlive=http.sessionGuard(),scope=this.actorScope;
const alive=()=>sessionAlive()&&this.actorScope===scope&&scope===studio.scope()&&this.data.view==='publish';
if(!this.__publicationSubmission){
if(this.data.invalidMessage||!String(this.data.workId||'').trim())return wx.showToast({title:'请先选择已保存的作品',icon:'none'});
const error=P.validatePublicationForm({title:this.data.title,publishText:this.data.publishText},Date.now());
if(error)return wx.showToast({title:error,icon:'none'});
const platform=(studio.publicationPlatforms||[])[this.data.selectedAccount];
if(!platform)return wx.showToast({title:'请选择发布平台',icon:'none'});
const accountId=String(this.data.publicationAccount||'').trim();
if(!accountId)return wx.showToast({title:'请填写实际发布账号名称',icon:'none'});
if(accountId.length>64)return wx.showToast({title:'发布账号名称不能超过64个字符',icon:'none'});
const payload={workId:String(this.data.workId),title:this.data.title.trim(),publishText:this.data.publishText.trim(),tags:String(this.data.tags||'').trim(),accountId,platform:platform.platform};
const action='publication:'+payload.workId;
this.__publicationSubmission={payload,key:this.contentKey(action),action};
}
const submission=this.__publicationSubmission;
this.setData({publishSubmitting:true,operationError:''});
try{
const res=await contentApi.publications.create(submission.payload,submission.key);
if(!alive())return;
const id=res&&(res.id||res.publicationId)?String(res.id||res.publicationId):'';
if(!id)throw Error('发布记录创建失败，请稍后重试');
this.contentDropKey(submission.action);this.__publicationSubmission=null;
this.persistEditor();
this.go('receipt','&record='+encodeURIComponent(id));
}catch(error){
if(!alive())return;
const code=Number(error&&error.code);
const uncertain=!Number.isFinite(code)||code===-1||code===0||(code>=500&&code<600);
if(!uncertain){
this.contentDropKey(submission.action);this.__publicationSubmission=null;
}
const message=uncertain?'登记结果待确认，请重试核对本次提交':P.degradeMessage(error);
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
}finally{if(alive())this.setData({publishSubmitting:false});}
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
const detail={ id:String(recordId), apiRecord:true, title:String(row.title||''), publishText:String(row.publishText||''), tags:String(row.tags||''), platform, account:String(row.accountName||row.account||row.accountId||'账号未记录'), authorName:String(row.authorName||''), workId:String(row.workId||''), kindLabel:studio.kindLabel(row.kind==='ai_video'?'ai':(row.kind||'digital')), cover:'/pages/studio/assets/studio-publication-cover.jpg', status, publishedUrl:String(row.publishedUrl||''), label:info.label, icon:info.icon, tone:info.tone };
this.setData({ detail, publishedUrlInput:detail.publishedUrl||this.data.publishedUrlInput, receiptLoading:false,invalidMessage:'' });
}).catch(error=>{
if(!alive()||this.actorScope!==scope||studio.scope()!==scope||this.data.view!=='receipt'||String(this.data.recordId)!==expected)return;
this.setData({detail:null,receiptLoading:false,invalidMessage:P.degradeMessage(error)});
});
},
openRecord(e) { this.go('detail', '&record=' + e.currentTarget.dataset.id); },
refreshRecords() { this.refreshData(); wx.showToast({ title: '状态已刷新', icon: 'none' }); },
registerConsultation(){const record=this.data.detail;if(record?.status==='published'&&record.contentId)wx.navigateTo({url:'/pages/growth/index?view=capture&publicationId='+encodeURIComponent(record.contentId)});},
inputPublishedUrl(e){this.setData({publishedUrlInput:e.detail.value,receiptError:'',receiptSaved:false});},
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
// 后端 verify 仅支持 publishing→verify；失败不可吞，否则 link 会产生误导性二次失败。
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
if (item.id === 'draft') { if (studio.restoreManualDraft()) this.go('remix'); else wx.navigateTo({ url: '/pages/creation/index?editor=1' }); }
else if (item.id === 'video-draft') wx.navigateTo({ url: '/pages/ai-video/index' });
else if (item.editTask) wx.navigateTo({ url: '/pages/ai-video/index?task=' + encodeURIComponent(item.editTask) });
else this.go('result', '&task=' + encodeURIComponent(item.taskId));
},

closeSheet() { if (this.data.sheet === 'editor') { keyboard.close(this);wx.showToast({ title: '编辑内容已暂存，可继续编辑', icon: 'none' }); }this.setData({ sheet: '' }); },
noop() {}
});
