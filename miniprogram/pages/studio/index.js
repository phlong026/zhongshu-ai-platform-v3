const studio = require('../../utils/studio');
const Demo=require('../../utils/creation-demo');
const { unavailable, assets } = require('../../utils/creation');
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
tasks: [], records: [], works: [], libraryAssets: assets, sheet: '', sheetTitle: '', editingText: '', selectedAccount: 0,publishedUrlInput:'',receiptError:'',receiptSaved:false,receiptSaving:false,demoPublishedUrl:'',
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
const task = state.tasks.find(task => task.id === options.task);
if (!task || task.status === 'generating') {
const destination = task ? 'tasks' : 'works';
this.setData({ view: destination, pageTitle: titles[destination] });
wx.showToast({ title: task ? '任务尚未完成，请先查看进度' : '任务不存在或已清理', icon: 'none' });
wx.redirectTo({ url: '/pages/studio/index?view=' + destination });
return;
}
}
if (view === 'publish') {
const work = state.works.find(work => work.id === options.work);
if (work) this.setData({ title: work.title, publishText: work.script || this.data.publishText });
if (work) {
this.setData({ cover: work.image });
if (state.publishDrafts[work.id]) this.setData(state.publishDrafts[work.id]);
}
}
if (view === 'remix' && state.rewriteSettings) this.setData(state.rewriteSettings);
const sourceId=options.content||options.confirm;if(view==='publish'&&sourceId){const c=require('../../utils/growth').load().contents.find(c=>c.id===sourceId&&c.workId===options.work);if(c)this.setData({title:c.pack.title,publishText:c.pack.publishText,tags:c.pack.tags,cover:c.pack.cover,scheduled:c.pack.scheduled,selectedAccount:studio.accounts.findIndex(a=>a.id===c.pack.accountId)});}
this.refreshData();
if(view==='receipt'){
const demoPublishedUrl=studio.demoPublicationUrl(this.data.recordId);
this.setData({demoPublishedUrl,publishedUrlInput:state.publicationLinkDrafts?.[this.data.recordId]||this.data.detail?.publishedUrl||demoPublishedUrl});
}
},
onShow() {require('../../utils/demo-session').apply(this); if(!studio.guard())return;if(this.actorScope!==studio.scope()){wx.redirectTo({url:'/pages/studio/index?view='+this.data.view});return;}if (this.options) this.refreshData(); },
onHide() { this.persistEditor(); },
onUnload() { this.persistEditor(); },
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
const state = studio.read();
if (['remix', 'digital'].includes(this.data.view)) this.setData({ title: state.title, original: state.original, script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), scriptLength: state.script.length, estimatedSeconds: Math.ceil(state.script.replace(/\s/g, '').length / 4) });
const taskFilter = this.data.taskFilter, recordFilter = this.data.recordFilter;
const tasks = state.tasks.filter(task => taskFilter === 0 || (taskFilter === 1 && task.status === 'generating') || (taskFilter === 2 && task.status === 'completed') || (taskFilter === 3 && task.status === 'save-failed')).map(task => Object.assign({}, task, { image: studio.imageFor(task), kindLabel: studio.kindLabel(task.kind) }));
const decorateRecord = record => {
const work = record.workSnapshot || state.works.find(item => item.id === record.workId);
const task = state.tasks.find(item => item.id === record.taskId);
const kind = record.kind || (work && work.kind) || (task && task.kind) || '';
return Object.assign({}, record, statusInfo[record.status] || { label: '状态待确认', icon: 'warning', tone: 'yellow', action: '查看详情' }, { kindLabel: studio.kindLabel(kind), cover: record.cover || (work && work.image) || studio.imageFor(task || {}), time: (record.status === 'published' ? '发布 ' : '提交 ') + studio.formatTime(record.publishedAt || record.submittedAt || record.createdAt), timeLabel: record.scheduledAt ? '计划发布时间' : record.status === 'published' ? '发布时间' : '提交时间', displayTime: record.scheduledAt || studio.formatTime(record.publishedAt || record.submittedAt || record.createdAt), identifier: record.id });
};
const records = state.records.filter(record => recordFilter === 0 || (recordFilter === 1 && ['publishing', 'scheduled'].includes(record.status)) || (recordFilter === 2 && record.status === 'published') || (recordFilter === 3 && ['verify', 'failed'].includes(record.status))).map(decorateRecord);
const activeTask = state.tasks.find(task => task.id === this.data.taskId) || null;
const detailRecord = state.records.find(record => record.id === this.data.recordId);
const detail = detailRecord ? decorateRecord(detailRecord) : null;
const work = state.works.find(item => item.id === this.data.workId);
let invalidMessage = '';
if (this.data.view === 'result' && !activeTask) invalidMessage = '任务不存在或已清理，请从任务中心重新选择。';
if (['detail','receipt'].includes(this.data.view) && !detail) invalidMessage = '发布记录不存在或已清理，请返回发布记录。';
if (this.data.view === 'publish' && !work) invalidMessage = '作品不存在或已清理，请从我的作品重新选择。';
const configuration = (activeTask && activeTask.configuration) || {};
const params = configuration.parameters || [];
const parameter = key => (params.find(item => item.key === key) || {}).value;
const resultInfo = activeTask && activeTask.kind === 'ai'
? [{ label: '分辨率', value: parameter('resolution') || '768P' }, { label: '画面比例', value: parameter('ratio') || '9:16' }]
: [{ label: '人物', value: configuration.actor || '李顾问' }, { label: '声音', value: configuration.voice || '李顾问原声' }];
const timeline = detailRecord ? [
{ label: '作品已保存', time: studio.formatTime(detailRecord.workSnapshot && detailRecord.workSnapshot.savedAt) },
{ label: '发布信息已保存', time: studio.formatTime(detailRecord.submittedAt || detailRecord.createdAt) },
{ label: detailRecord.status === 'published' ? '发布链接已登记' : (statusInfo[detailRecord.status] || {}).label || '状态待确认', time: detailRecord.status === 'published' ? studio.formatTime(detailRecord.publishedAt) : '尚未完成' }
] : [];
this.setData({ extraction:state.extraction||null,rewriteVersion:(state.rewriteHistory[state.rewriteHistory.length-1]||{}).version||0,tasks, records, works: state.works, activeTask, saved: !!(activeTask && activeTask.saved), detail, resultInfo, invalidMessage, timeline, recent: studio.recentItems(state) });
},
go(view, query) {
const options = { url: '/pages/studio/index?view=' + view + (query || '') };
if (getCurrentPages().length >= 9) wx.redirectTo(options); else wx.navigateTo(options);
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
const draft = wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft'));
if (draft && draft.text === state.original) wx.setStorageSync(studio.scopedKey('zhongshu-creation-draft'), Object.assign({}, draft, { rewrittenText: state.script, updatedAt: Date.now() }));
this.setData({ script: state.script, scriptShort: state.script.split('\n').slice(0, 2).join('\n'), sheet: '' }); this.refreshData();
},
regenerate() {
if(this.data.rewriting)return;
this.setData({rewriting:true,operationError:''});
try {
this.attempt(()=>{
const state=studio.read();
state.original=String(this.data.original||'').trim();
Demo.rewrite(state,{rewriteMode:this.data.rewriteMode,customIp:this.data.customIp,ip:this.data.ipOptions[this.data.ipIndex],requirement:this.data.requirement});
state.rewriteSettings={rewriteMode:this.data.rewriteMode,customIp:this.data.customIp||'',ipIndex:this.data.ipIndex,requirement:this.data.requirement};
studio.write(state);
const key=studio.scopedKey('zhongshu-creation-draft'),draft=wx.getStorageSync(key);
if(draft?.text===state.original)wx.setStorageSync(key,{...draft,rewrittenText:state.script,updatedAt:Date.now()});
this.setData({script:state.script,scriptShort:state.script.split('\n').slice(0,2).join('\n'),scriptLength:state.script.length,estimatedSeconds:Math.ceil(state.script.replace(/\s/g,'').length/4),rewriteVersion:state.rewriteHistory[state.rewriteHistory.length-1].version});
wx.showToast({title:'模拟二创已生成',icon:'none'});
});
} finally {
this.setData({rewriting:false});
this.scrollToCopy();
}
},
scrollToCopy(){if(!wx.createSelectorQuery)return;const scroll=()=>{try{const q=wx.createSelectorQuery();if(!q.selectViewport)return;q.select('.rewritten-card').boundingClientRect();q.selectViewport().scrollOffset();q.exec(rows=>{if(rows&&rows[0]&&rows[1])wx.pageScrollTo({scrollTop:Math.max(0,rows[0].top+rows[1].scrollTop-((getApp().globalData.windowInfo||wx.getSystemInfoSync()).statusBarHeight+56)),duration:250});});}catch(_){/* Scrolling must never block a successfully generated script. */}};if(wx.nextTick)wx.nextTick(scroll);else scroll();},
openOptimization(){if(!this.data.script.trim())return wx.showToast({title:'请先生成二创文案',icon:'none'});this.setData({sheet:'optimize',sheetTitle:'AI优化',optimizeRequirement:'口语自然'});},
inputOptimization(e){this.setData({optimizeRequirement:e.currentTarget.dataset.value});},
optimizeScript(){this.attempt(()=>{const state=studio.read();Demo.optimizeCopy(state,this.data.optimizeRequirement);studio.write(state);const key=studio.scopedKey('zhongshu-creation-draft'),draft=wx.getStorageSync(key);if(draft?.text===state.original)wx.setStorageSync(key,{...draft,rewrittenText:state.script,updatedAt:Date.now()});this.setData({sheet:''});this.refreshData();wx.showToast({title:'模拟优化已完成',icon:'none'});});},
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
wx.showModal({ title: '体验口播创作流程', content: '当前为本地演示，未接入数字人生成服务。继续将创建演示任务，不会扣费。', confirmText: '体验流程', confirmColor: '#824019', success: ({ confirm }) => {
if (confirm) { const task = studio.createDemoTask('digital', '', { actor: this.data.actor, voice: this.data.voice }); this.go('tasks', '&task=' + task.id); }
} });
},
changeTaskFilter(e) { this.setData({ taskFilter: Number(e.currentTarget.dataset.index) }); this.refreshData(); },
changeRecordFilter(e) { this.setData({ recordFilter: Number(e.currentTarget.dataset.index) }); this.refreshData(); },
taskAction(e) {
const id = e.currentTarget.dataset.id;
const task = studio.read().tasks.find(task => task.id === id);
if (!task) { wx.showToast({ title: '任务不存在或已清理', icon: 'none' }); this.refreshData(); return; }
if (task.status === 'generating') {
wx.showModal({ title: '演示任务进度', content: '当前进度 ' + task.progress + '%。可将此演示任务推进到完成状态，体验后续流程。', confirmText: '演示完成', confirmColor: '#824019', success: ({ confirm }) => { if (confirm) { studio.completeTask(id); this.refreshData(); } } });
} else if (task.status === 'save-failed') {
this.attempt(() => { studio.saveWork(id); this.refreshData(); wx.showToast({ title: '演示作品已保存' }); });
} else this.go('result', '&task=' + id);
},
refreshTasks() { this.refreshData(); wx.showToast({ title: '演示状态已刷新', icon: 'none' }); },
playVideo() { wx.showModal({ title: '视频播放说明', content: '当前为本地演示，仅提供封面预览，尚未接入视频生成与播放。', showCancel: false, confirmColor: '#824019' }); },
attempt(action) { try { this.setData({operationError:''});return action(); } catch (error) { const message=error.message||'操作未完成，请重试';this.setData({operationError:message});wx.showToast({ title: message, icon: 'none' }); return null; } },
saveResult() { this.attempt(() => { const work = studio.saveWork(this.data.taskId); this.setData({ workId: work.id, saved: true }); wx.showToast({ title: '已保存到我的作品' }); }); },
publishResult() {
this.attempt(() => { const work = studio.saveWork(this.data.taskId); this.setData({ workId: work.id, saved: true }); this.go('publish', '&work=' + encodeURIComponent(work.id)); });
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
if (!studio.read().works.some(work => work.id === this.data.workId)) { this.refreshData(); wx.showToast({ title: '请从我的作品重新选择', icon: 'none' }); return; }
if (!this.data.title.trim() || !this.data.publishText.trim()) { wx.showToast({ title: '请填写标题和发布文案', icon: 'none' }); return; }
const invalidSchedule = studio.scheduleError(this.data.scheduled);
if (invalidSchedule) { wx.showToast({ title: invalidSchedule, icon: 'none' }); return; }
this.attempt(() => {
const result = studio.createPublication(this.data.workId, { title: this.data.title, publishText: this.data.publishText, tags: this.data.tags, cover: this.data.cover, account: this.data.selectedAccount, scheduled: this.data.scheduled });
if (result.duplicate) wx.showToast({ title: '此账号已有发布记录，请勿重复提交', icon: 'none' });
this.persistEditor();this.go(result.record.status==='published'&&result.record.publishedUrl?'detail':'receipt','&record='+encodeURIComponent(result.record.id));
});
},
openRecord(e) { this.go('detail', '&record=' + e.currentTarget.dataset.id); },
refreshRecords() { this.refreshData(); wx.showToast({ title: '演示状态已刷新', icon: 'none' }); },
registerConsultation(){const record=this.data.detail;if(record?.status==='published'&&record.contentId)wx.navigateTo({url:'/pages/growth/index?view=capture&contentId='+encodeURIComponent(record.contentId)});},
inputPublishedUrl(e){this.setData({publishedUrlInput:e.detail.value,receiptError:'',receiptSaved:false});},
savePublicationLink(){
if(this.data.receiptSaving)return;
this.setData({receiptSaving:true,receiptError:''});
try{
 if(this.actorScope!==studio.scope())throw Error('工作身份已变化，请重新打开本人发布记录');
 if(!this.data.receiptSaved){
  studio.confirmDemoPublication(this.data.recordId,this.data.publishedUrlInput);
  const state=studio.read();if(state.publicationLinkDrafts)delete state.publicationLinkDrafts[this.data.recordId];studio.write(state);
  this.setData({receiptSaved:true});
 }
}catch(error){this.setData({receiptSaving:false,receiptError:error.message});wx.showToast({title:error.message,icon:'none'});return;}
wx.reLaunch({
 url:'/pages/business/index',
 success:()=>wx.showToast({title:'发布链接已保存',icon:'success'}),
 fail:()=>this.setData({receiptError:'链接已保存，返回首页失败，请点击按钮重试。'}),
 complete:()=>this.setData({receiptSaving:false})
});
},
copyPublishedUrl(){if(this.data.detail?.publishedUrl)wx.setClipboardData({data:this.data.detail.publishedUrl});},
advancePublication() {
const state = studio.read(); const record = state.records.find(record => record.id === this.data.recordId);
if (record && ['publishing', 'verify', 'scheduled','published'].includes(record.status)) {
this.go('receipt','&record='+encodeURIComponent(record.id));
} else if (record && record.status === 'failed') {
wx.showModal({ title: '账号授权已失效', content: '真实账号授权尚未接入。可返回发布设置更换示例账号，体验重试流程。', confirmText: '更换账号', confirmColor: '#824019', success: ({ confirm }) => {
if (confirm) this.attempt(() => {
const work = state.works.find(item => item.id === record.workId) || (record.taskId ? studio.saveWork(record.taskId) : null);
if (!work) { wx.showToast({ title: '关联作品已失效，请重新选择', icon: 'none' }); this.go('works'); return; }
this.go('publish', '&work=' + encodeURIComponent(work.id));
});
} });
} else unavailable('查看平台作品');
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
