const studio = require('../../utils/studio');
const { unavailable, assets } = require('../../utils/creation');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：七态映射/校验/降级）
const contentApi=P.api;                                 // /franchise/content/** 契约层
const http=require('../../api/http');
const titles = { remix: '文案二创', digital: '数字人口播', tasks: '任务中心', result: '视频结果', publish: '发布设置', receipt:'登记发布链接', records: '发布记录', detail: '发布详情', works: '我的作品', library: '素材库', recent: '全部创作' };
const statusInfo = {
publishing: { label: '待登记链接', icon: 'clock', tone: 'warm', action: '补填链接' },
published: { label: '已发布', icon: 'check-green', tone: 'green', action: '查看回执' },
verify: { label: '待核实', icon: 'warning', tone: 'yellow', action: '核实结果', note: '请勿重复发布' },
failed: { label: '失败', icon: 'error', tone: 'red', action: '查看原因', note: '账号授权已失效' },
scheduled: { label: '待发布', icon: 'clock', tone: 'warm', action: '查看进度' }
};
Page({
data: { 
view: 'remix', pageTitle: '', original: '', script: '', title: studio.title, rewriteMode: 0, ipIndex: 0, rewriting: false, operationError: '',
ipOptions: ['乡墅建房顾问', '乡村设计师'], requirement: '突出采光和生活动线，语气亲切一些。',
taskFilter: 0, recordFilter: 0, taskTabs: ['全部', '生成中', '已完成', '异常'], recordTabs: ['全部', '进行中', '已发布', '异常'],
tasks: [], records: [], works: [], libraryAssets: assets, sheet: '', sheetTitle: '', editingText: '', selectedAccount: 0,publishedUrlInput:'',receiptError:'',receiptSaved:false,receiptSaving:false,tasksError:'',worksLoading:false,worksError:'',recordsLoading:false,recordsError:'',
publishText: '想把二层乡墅住得舒适，先从家人的日常出发。\n采光、动线和通风，才是住得舒服的关键。',
tags: '#乡墅设计  #农村建房', scheduled: '', cover: '/assets/studio-publication-cover.jpg', workId: '', saved: false,
actor: '李顾问', voice: '李顾问原声', filter: '', detail: null, activeTask: null, invalidMessage: '', recent: [], timeline: [], scriptLength: 0, estimatedSeconds: 0
},
onLoad(options) {
if(!studio.guard())return;this.actorScope=studio.scope();
const view = titles[options.view] ? options.view : 'remix';
this.options = options;
const state = studio.read();
this.setData({ view, pageTitle: titles[view], original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), title: state.title, taskId: options.task || 'task-complete', recordId: options.record || 'record-success', workId: options.work || '' });
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
if (view === 'remix' && state.rewriteSettings) this.setData(state.rewriteSettings);
const sourceId=options.content||options.confirm;if(view==='publish'&&sourceId){const c=require('../../utils/growth').load().contents.find(c=>c.id===sourceId&&c.workId===options.work);if(c)this.setData({title:c.pack.title,publishText:c.pack.publishText,tags:c.pack.tags,cover:c.pack.cover,scheduled:c.pack.scheduled,selectedAccount:studio.accounts.findIndex(a=>a.id===c.pack.accountId)});}
this.refreshData();
if(view==='receipt'){
this.setData({publishedUrlInput:state.publicationLinkDrafts?.[this.data.recordId]||this.data.detail?.publishedUrl||''});
if(http.token()){this.receiptApi=true;this.loadApiReceipt(this.data.recordId);} // 在线发布记录：回执页按后端数据渲染
}
},
onPageScroll(e){this.__navigationScrollTop=e.scrollTop;},
onShow() {if(!studio.guard())return;if(this.actorScope!==studio.scope()){wx.redirectTo({url:'/pages/studio/index?view='+this.data.view});return;}if (this.options) this.refreshData(); 
  },
onHide() { this.persistEditor(); },
onUnload() { this.persistEditor(); if (this.__digitalPoll && this.__digitalPoll.cancel) this.__digitalPoll.cancel(); }, // 终止任务轮询（防 UI 长时间占锁）
persistEditor() {
if(!studio.allowed()||this.actorScope!==studio.scope())return;
if(this.data.view==='receipt'&&!this.data.receiptSaved&&this.data.detail){const state=studio.read();state.publicationLinkDrafts=state.publicationLinkDrafts||{};state.publicationLinkDrafts[this.data.recordId]=this.data.publishedUrlInput;studio.write(state);}
if (this.data.view === 'remix') {
const state = studio.read();
const { rewriteMode, ipIndex, customIp, requirement } = this.data;
state.rewriteSettings = { rewriteMode, ipIndex, customIp: customIp || '', requirement }; studio.write(state);
}
if (this.data.view === 'publish' && !this.data.invalidMessage && this.data.workId) {
const state = studio.read();
const { title, publishText, tags, selectedAccount, scheduled, cover, workId } = this.data;
state.publishDrafts[workId] = { title, publishText, tags, selectedAccount, scheduled, cover, workId }; studio.write(state);
}
},
refreshData() {
if (http.token()) { // 仅在线路径：任务中心走后端列表；不装配本地演示 tasks/records/works
if (['remix', 'digital'].includes(this.data.view)) { // 编辑器文案（草稿暂存键）仍回显
const state = studio.read();
this.setData({ title: state.title, original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), scriptLength: state.script.length, estimatedSeconds: Math.ceil(state.script.replace(/\s/g, '').length / 4), extraction: state.extraction || null, rewriteVersion: (state.rewriteHistory[state.rewriteHistory.length - 1] || {}).version || 0 });
}
if (this.data.view === 'tasks') this.syncApiTasks();
if (this.data.view === 'works') this.syncApiWorks();
if (this.data.view === 'records') this.syncApiRecords();
if (this.data.view === 'detail') this.syncApiRecords(this.data.recordId);
return;
}
// 无 token：清空演示集合并提示登录（不回落本地数据）
this.setData({ tasks: [], records: [], works: [], recent: [], activeTask: null, detail: null, tasksError: '', invalidMessage: '请先登录后使用创作中心' });
return;
},
/** 在线任务列表：tasks.page 七态 → 页面四态（generating/completed/failed/uncertain），异常页签含 failed/uncertain */
syncApiTasks() {
const filter = this.data.taskFilter;
contentApi.tasks.page().then(rows => {
if (this.data.view !== 'tasks') return;
const mapped = P.rowsOf(rows).map(task => this.mapApiTask(task)).filter(Boolean);
const tasks = mapped.filter(task => filter === 0 || (filter === 1 && task.status === 'generating') || (filter === 2 && task.status === 'completed') || (filter === 3 && task.status !== 'generating' && task.status !== 'completed'));
this.setData({ tasks, tasksError: '' });
}).catch(() => { this.setData({ tasksError: '任务列表加载失败，请下拉重试', tasks: [] }); }); // 拉取失败：错误态 + 清空，不回落本地列表
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
const platform=String(row.platform||'douyin'),account=String(row.accountName||row.account||row.accountId||('众墅之家 · '+(platform==='douyin'?'建房顾问':'乡墅设计')));
const timeValue=status==='published'?(row.publishedAt||row.createTime):status==='scheduled'?(row.scheduledAt||row.createTime):row.createTime;
return{id:String(row.id),apiRecord:true,workId:row.workId==null?'':String(row.workId),title:String(row.title||'未命名发布'),publishText:String(row.publishText||''),tags:String(row.tags||''),platform,account,status,publishedUrl:String(row.publishedUrl||''),failureReason:String(row.failureReason||''),signature:String(row.signature||''),cover:'/assets/studio-publication-cover.jpg',kindLabel:'数字人口播',time:studio.formatTime(timeValue),displayTime:studio.formatTime(timeValue),timeLabel:status==='published'?'发布时间':status==='scheduled'?'计划发布时间':'提交时间',identifier:String(row.signature||row.id),label:info.label,icon:info.icon,tone:info.tone,action:info.action||'查看详情',note:status==='failed'?String(row.failureReason||info.note||'发布失败'):String(info.note||'')};
},
publicationTimeline(record) {
const timeline=[{label:'已提交发布',time:record.time}];
if(record.status==='scheduled')timeline.push({label:'等待计划时间',time:record.displayTime});
if(record.status==='verify')timeline.push({label:'等待链接核验',time:'待完成'});
if(record.status==='published')timeline.push({label:'已登记发布链接',time:record.displayTime});
if(record.status==='failed')timeline.push({label:'发布失败',time:record.failureReason||'原因未记录'});
return timeline;
},
/** 后端 TaskView → 本地任务形状（ID 一律不透明字符串；progress 用区间示意） */
mapApiTask(task) {
if (!task || task.id == null || task.id === '') return null;
const kind = task.kind === 'ai_video' ? 'ai' : 'digital';
const view = P.taskView(task.status);
const text = String((task.result && (task.result.text || task.result.script)) || '').trim();
return {
id: String(task.id), apiTask: true, kind,
title: text ? text.split('\n')[0].slice(0, 28) : (String(task.title || '').trim() || studio.kindLabel(kind) + '任务'),
status: view, progress: P.progressOf(task.status), saved: false,
script: text, failedReason: String(task.failedReason || ''), resultUrl: String(task.resultUrl || ''),
configuration: {}, image: studio.imageFor({ kind }), kindLabel: studio.kindLabel(kind)
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
if (index >= 0) this.setData({ ['tasks[' + index + '].progress']: P.progressOf(status), ['tasks[' + index + '].status']: P.taskView(status) });
},
switchRewrite(e) { this.setData({ rewriteMode: Number(e.currentTarget.dataset.index) }); },
changeIp(e) { this.setData({ ipIndex: Number(e.detail.value) }); },
inputRequirement(e) { this.setData({ requirement: e.detail.value, operationError: '' }); },
inputOriginal(e) { const state=studio.read();state.original=String(e.detail.value||'');state.script='';state.rewriteHistory=[];delete state.extraction;studio.write(state);this.setData({original:state.original,script:'',scriptShort:'',scriptLength:0,estimatedSeconds:0,rewriteVersion:0,operationError:'',extraction:null}); },
inputCustomIp(e) { this.setData({ customIp: e.detail.value }); },
editScript() { const buffer = studio.read().editorBuffer; this.setData({ sheet: 'editor', sheetTitle: '编辑口播文案', editingText: buffer && buffer.source === this.data.original ? buffer.text : this.data.script }); },
inputEdit(e) { this.setData({ editingText: e.detail.value }); const state = studio.read(); state.editorBuffer = { source: this.data.original, text: e.detail.value }; studio.write(state); },
saveScript() {
if (!this.data.editingText.trim()) { wx.showToast({ title: '请填写口播文案', icon: 'none' }); return; }
const state = studio.read(); state.script = this.data.editingText.trim(); state.scriptEdited=true; delete state.editorBuffer; studio.write(state);
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft));
if (draft && draft.text === state.original) wx.setStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft), Object.assign({}, draft, { rewrittenText: state.script, updatedAt: Date.now() }));
this.setData({ script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), sheet: '' }); this.refreshData();
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
const finish=()=>{this.setData({rewriting:false});this.scrollToCopy();};
const apply=text=>{
if(this.actorScope!==scope)return finish(); // 工作身份已变化，丢弃迟到结果
const state=studio.read();
const version=((state.rewriteHistory[state.rewriteHistory.length-1]||{}).version||0)+1;
state.original=original;state.script=text;
state.rewriteHistory=state.rewriteHistory.concat({version,online:true,mode:'api-optimize',persona,requirement,original,script:text,at:Date.now()});
state.rewriteSettings={rewriteMode:this.data.rewriteMode,customIp:this.data.customIp||'',ipIndex:this.data.ipIndex,requirement};
studio.write(state);
const key=studio.scopedKey(require('../../utils/storage-keys').creationDraft),draft=wx.getStorageSync(key);
if(draft&&draft.text===original)wx.setStorageSync(key,Object.assign({},draft,{rewrittenText:text,updatedAt:Date.now()}));
this.setData({script:text,scriptShort:text.split('\n').slice(0,2).join('\n'),scriptLength:text.length,estimatedSeconds:Math.ceil(text.replace(/\s/g,'').length/4),rewriteVersion:version});
wx.showToast({title:'二创已生成',icon:'none'});
finish();
};
this.fetchPersonaId(persona).then(personaId=>{
const payload={sourceText:original};
if(requirement)payload.requirement=requirement;
if(personaId)payload.personaId=personaId;
return this.resolveText(contentApi.scripts.optimize(payload));
}).then(apply).catch(error=>{
const message=P.degradeMessage(error);
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
finish();
});
},
/** 组织人设库按名称匹配（personas.page）；列表为空/未命中/接口失败均返回空串 */
fetchPersonaId(label) {
if(!label)return Promise.resolve('');
return contentApi.personas.page().then(rows=>{
const hit=P.rowsOf(rows).find(item=>item&&(item.name===label||item.title===label));
return hit&&hit.id!=null?String(hit.id):'';
}).catch(()=>'');
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
this.go('digital');
},
changeActor() { this.setData({ sheet: 'actor', sheetTitle: '出镜人物' }); },
selectActor() { this.setData({ actor: '李顾问', voice: '李顾问原声', sheet: '' }); },
changeVoice() { this.setData({ sheet: 'voice', sheetTitle: '口播声音' }); },
selectVoice() { this.setData({ voice: '李顾问原声', sheet: '' }); },
audition() { wx.showModal({ title: '声音试听', content: '当前仅提供声音选择界面，尚未配置试听音频。', showCancel: false, confirmColor: '#824019' }); },
generateDigital() {
if (!this.data.script.trim()) { wx.showToast({ title: '请先填写口播文案', icon: 'none' }); return; }
if (!http.token()) return wx.showToast({ title: '请先登录后再生成口播', icon: 'none' }); // 无 token：提示登录，不弹本地预览
return this.generateDigitalOnline(); // 在线：直连后端创建 digital 任务
},
/** 在线数字人口播：tasks.create(digital) → 任务中心态展示轮询进度 → SUCCEEDED 后 saveWork（一任务一件幂等）。
 * showLoading 只覆盖提交阶段；轮询进度经任务中心列表回填展示，onUnload 终止。 */
generateDigitalOnline() {
if(this.__generating)return; // 提交防重（loading 只护提交阶段）
const text=this.data.script.trim();
const title=String(this.data.title||'').trim()||text.split('\n')[0].slice(0,28);
const state=studio.read();state.script=text;state.title=title;studio.write(state); // 本地留痕（script/title 非守卫键）
this.__generating=true;
wx.showLoading({title:'提交生成任务',mask:true});
contentApi.tasks.create({kind:'digital',voice:this.data.voice,text,avatar:this.data.actor,title},this.contentKey('task')).then(res=>{
wx.hideLoading();
const taskId=res&&res.taskId?String(res.taskId):'';
if(!taskId)throw Error('任务创建失败，请稍后重试');
this.contentDropKey('task'); // 提交成功释放；失败保留，重试复用同键
this.go('tasks'); // 轮询进度在任务中心态展示
const poll=this.__digitalPoll=P.pollTask(taskId,{onTick:task=>this.patchTaskProgress(String(task.id||taskId),task.status)});
return poll.then(task=>{
if(this.__digitalPoll!==poll)return null; // 已取消/新一轮提交
this.__digitalPoll=null;this.__generating=false;
const view=P.taskView(task.status);
if(view==='completed')return contentApi.tasks.saveWork(taskId,title,this.contentKey('save-work:'+taskId)).then(()=>{
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
wx.showToast({title:P.degradeMessage(error),icon:'none'}); // 任务已在任务中心，轮询失败仅提示
});
}).catch(error=>{
wx.hideLoading();this.__generating=false;
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
if (shown.status === 'generating') { wx.showModal({ title: '任务进度', content: '当前进度约 ' + shown.progress + '%，任务在云端执行，可稍后刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.status === 'failed') { wx.showModal({ title: '生成失败', content: shown.failedReason || '生成失败，请重新创建任务。', showCancel: false, confirmColor: '#824019' }); return; }
if (shown.status === 'uncertain') { wx.showModal({ title: '结果确认中', content: '任务提交结果暂不确定，请稍后在任务中心刷新查看。', showCancel: false, confirmColor: '#824019' }); return; }
this.go('result', '&task=' + encodeURIComponent(id));
},
refreshTasks() { this.refreshData(); wx.showToast({ title: '状态已刷新', icon: 'none' }); },
playVideo() {
const task=this.data.activeTask;
if(task&&task.apiTask&&task.resultUrl){wx.setClipboardData({data:task.resultUrl,success:()=>wx.showToast({title:'成片链接已复制，可在浏览器查看',icon:'none'})});return;}
wx.showModal({ title: '视频播放说明', content: '视频播放暂未开通，当前仅提供封面预览。', showCancel: false, confirmColor: '#824019' });
},
attempt(action) { try { this.setData({operationError:''});return action(); } catch (error) { const message=error.message||'操作未完成，请重试';this.setData({operationError:message});wx.showToast({ title: message, icon: 'none' }); return null; } },
saveResult() { return this.saveApiResult(false); }, // 任务全部在线：保存走 save-work 幂等通道
publishResult() { return this.saveApiResult(true); },
/** 在线任务→作品：save-work 幂等（重复返既有），成功后可去发布；幂等键重试复用 */
saveApiResult(publish) {
contentApi.tasks.saveWork(this.data.taskId, '', this.contentKey('save-work:' + this.data.taskId)).then(res => {
this.contentDropKey('save-work:' + this.data.taskId);
const workId=String((res&&res.workId)||this.data.taskId);
this.setData({ workId, saved: true, 'activeTask.saved': true });
wx.showToast({ title: '已保存到我的作品' });
if (publish) this.go('publish', '&work=' + encodeURIComponent(workId));
}).catch(error => {
const message=P.degradeMessage(error);
this.setData({ operationError: message });
wx.showToast({ title: message, icon: 'none' });
});
},
/** 在线任务结果页：tasks.get 直取；未完成回任务中心 */
loadApiResult(taskId) {
contentApi.tasks.get(taskId).then(task => {
const view=P.taskView(task&&task.status);
if(view!=='completed'){
wx.showToast({ title: view==='generating' ? '任务尚未完成，请先查看进度' : '任务未成功完成，请在任务中心查看', icon: 'none' });
wx.redirectTo({ url: '/pages/studio/index?view=tasks' });
return;
}
const mapped=this.mapApiTask(task);
mapped.status='completed';mapped.progress=100;
this.setData({ activeTask: mapped, saved: false, invalidMessage: '' });
// 成片直链过期探测（best-effort）：expired 时展示失效文案，提示重新保存生成新链接
contentApi.tasks.resultUrlStatus(taskId).then(state=>{
if(state&&state.expired&&this.data.view==='result')this.setData({resultUrlNote:'成片链接已过期，重新保存作品可获取新链接'});
}).catch(()=>{});
}).catch(() => {
wx.showToast({ title: '任务不存在或已清理', icon: 'none' });
wx.redirectTo({ url: '/pages/studio/index?view=tasks' });
});
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
else { state.script = task.script || ''; state.title = task.title; delete state.editorBuffer; studio.write(state); this.go('digital'); }
},
selectAccount(e) { this.setData({ selectedAccount: Number(e.currentTarget.dataset.index), sheet: '' }); },
switchAccount() { this.setData({ sheet: 'accounts', sheetTitle: '更换账号' }); },
changeCover() { this.setData({ sheet: 'cover', sheetTitle: '更换封面' }); },
selectCover(e) { this.setData({ cover: e.currentTarget.dataset.path, sheet: '' }); },
inputTitle(e) { this.setData({ title: e.detail.value }); },
clearTitle() { this.setData({ title: '' }); },
inputPublishText(e) { this.setData({ publishText: e.detail.value }); },
inputTags(e) { this.setData({ tags: e.detail.value }); },
chooseSchedule() { const parts = this.data.scheduled.split(' '); this.setData({ sheet: 'schedule', sheetTitle: '发布时间', scheduleDay: parts[0] || '', scheduleClock: parts[1] || '' }); },
immediate() { this.setData({ scheduled: '', sheet: '' }); },
scheduleDate(e) { this.setData({ scheduleDay: e.detail.value }); },
scheduleTime(e) { this.setData({ scheduleClock: e.detail.value }); },
saveSchedule() {
const { scheduleDay, scheduleClock } = this.data;
const time = scheduleDay && scheduleClock ? scheduleDay + ' ' + scheduleClock : '';
const error = time ? studio.scheduleError(time) : '请选择日期和时间';
if (error) { wx.showToast({ title: error, icon: 'none' }); return; }
this.setData({ scheduled: time, sheet: '' });
},
moreSettings() { this.setData({ sheet: 'settings', sheetTitle: '更多设置' }); },
submitPublish() {
if (!http.token()) return wx.showToast({ title: '请先登录后再发布', icon: 'none' }); // 无 token：提示登录，不落本地发布记录
return this.submitPublishOnline(); // 发布链走后端
},
/** 在线发布链第一步：publications.create {workId,title,publishText,tags,accountId,platform,scheduledAt?} → 回执页登记链接 */
submitPublishOnline() {
const error=P.validatePublicationForm({title:this.data.title,publishText:this.data.publishText,scheduled:this.data.scheduled},Date.now());
if(error)return wx.showToast({title:error,icon:'none'});
const account=studio.accounts[this.data.selectedAccount]||studio.accounts[0]; // 平台发布账号为组织配置，沿用 demo 两账号 id 上送
const payload={workId:String(this.data.workId),title:this.data.title.trim(),publishText:this.data.publishText.trim(),tags:String(this.data.tags||'').trim(),accountId:account.id,platform:account.platform};
if(this.data.scheduled)payload.scheduledAt=new Date(this.data.scheduled.replace(' ','T')+':00').toISOString();
contentApi.publications.create(payload).then(res=>{
const id=res&&(res.id||res.publicationId)?String(res.id||res.publicationId):'';
if(!id)throw Error('发布记录创建失败，请稍后重试');
this.persistEditor();
this.go('receipt','&record='+encodeURIComponent(id));
}).catch(error=>{
const message=P.degradeMessage(error);
this.setData({operationError:message});
wx.showToast({title:message,icon:'none'});
});
},
/** 在线回执页：publications.page 找记录并按 statusInfo 五态渲染（best-effort） */
loadApiReceipt(recordId) {
contentApi.publications.page().then(rows=>{
const row=P.rowsOf(rows).find(item=>item&&String(item.id!=null?item.id:item.publicationId)===String(recordId));
if(this.data.view!=='receipt')return;
if(!row)throw Error('发布记录加载失败，请返回重试');
const status=String(row.status||'publishing');
const info=statusInfo[status]||{label:'状态待确认',icon:'warning',tone:'yellow'};
const platform=String(row.platform||'douyin');
const detail={ id:String(recordId), apiRecord:true, title:String(row.title||''), publishText:String(row.publishText||''), tags:String(row.tags||''), platform, account:String(row.accountName||row.account||('众墅之家 · '+(platform==='douyin'?'建房顾问':'乡墅设计'))), authorName:String(row.authorName||''), workId:String(row.workId||''), kindLabel:studio.kindLabel(row.kind==='ai_video'?'ai':(row.kind||'digital')), cover:'/assets/studio-publication-cover.jpg', status, publishedUrl:String(row.publishedUrl||''), label:info.label, icon:info.icon, tone:info.tone };
this.setData({ detail, publishedUrlInput:detail.publishedUrl||this.data.publishedUrlInput, invalidMessage:'' });
}).catch(error=>{
this.setData({invalidMessage:P.degradeMessage(error)});
});
},
openRecord(e) { this.go('detail', '&record=' + e.currentTarget.dataset.id); },
refreshRecords() { this.refreshData(); wx.showToast({ title: '状态已刷新', icon: 'none' }); },
registerConsultation(){const record=this.data.detail;if(record?.status==='published'&&record.contentId)wx.navigateTo({url:'/pages/growth/index?view=capture&contentId='+encodeURIComponent(record.contentId)});},
inputPublishedUrl(e){this.setData({publishedUrlInput:e.detail.value,receiptError:'',receiptSaved:false});},
savePublicationLink(){
if(this.data.receiptSaving)return;
if(!http.token())return wx.showToast({title:'请先登录后再登记发布链接',icon:'none'}); // 无 token：提示登录，不落本地演示回执
return this.savePublicationLinkOnline(); // 在线发布记录：publications.verify（已发布时）→ link 登记链接
},
/** 在线登记发布链接：重复链接后端报 1_114_000_003 → 对应文案；演示已发布态先 verify 再 link */
savePublicationLinkOnline(){
const invalid=P.validatePublishedUrl(this.data.publishedUrlInput);
if(invalid)return this.setData({receiptError:invalid});
const url=this.data.publishedUrlInput.trim(),recordId=this.data.recordId;
this.setData({receiptSaving:true,receiptError:''});
// 演示已发布（published）需先转待回执核验；其余状态直接登记；幂等键按记录维度生成，失败保留重试复用
const prepare=(this.data.detail&&this.data.detail.status==='published')?contentApi.publications.verify(recordId,this.contentKey('verify:'+recordId)).catch(()=>{}):Promise.resolve();
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
unavailable('查看平台作品'); // 本地演示发布记录已移除；在线记录经回执页（receipt）办理
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
previewLibrary(e) { wx.previewImage({ current: e.currentTarget.dataset.path, urls: assets.map(asset => asset.path) }); },
closeSheet() { if (this.data.sheet === 'editor') wx.showToast({ title: '编辑内容已暂存，可继续编辑', icon: 'none' }); this.setData({ sheet: '' }); },
noop() {}
});
