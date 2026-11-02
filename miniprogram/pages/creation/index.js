const { assets, unavailable } = require('../../utils/creation');
const studio = require('../../utils/studio');
const Demo=require('../../utils/creation-demo');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5）
const http=require('../../api/http');
Page({
data: {
link: Demo.samples[0].url, sheet: '', editorText: '', draft: null, assets, demoSamples:Demo.samples, sampleId:'daylight',sampleIndex:0,
extracting: false, parseProgress: 0,
modes:[{id:'remix',title:'爆款二创',subtitle:'参考视频改写',icon:'video-filled'},{id:'digital',title:'数字人口播',subtitle:'文案生成口播',icon:'person-filled'},{id:'ai',title:'AI视频',subtitle:'文字图片成片',icon:'home-video-icon'}],
shortcuts: [
{ key: 'library', title: '素材库', icon: 'folder' },
{ key: 'tasks', title: '任务中心', icon: 'tasks' },
{ key: 'works', title: '我的作品', icon: 'works' },
{ key: 'records', title: '发布记录', icon: 'publish' }
],
recent: []
},
onLoad(options) {
if(!studio.guard())return;this.actorScope=studio.scope();
this.setData({ draft: wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || null });
if (options.section === 'works') wx.redirectTo({ url: '/pages/studio/index?view=works' });
if (options.section === 'digital') wx.redirectTo({ url: '/pages/studio/index?view=digital' });
if (options.editor === '1') this.openEditor();
},
onShow() {if(!studio.guard())return;if(this.actorScope!==studio.scope()){this.actorScope=studio.scope();this.setData({link:"",sheet:"",editorText:""});}
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || null;
const recent = studio.recentItems(studio.read()).slice(0, 2).map(item=>({...item,updatedText:item.updatedAt?studio.formatTime(item.updatedAt):''}));
this.setData({ draft, recent });
},
onUnload() { if (this.__parsePoll && this.__parsePoll.cancel) this.__parsePoll.cancel(); }, // 终止解析轮询（防 UI 长时间占锁）
inputLink(e) { this.setData({ link: e.detail.value }); },
chooseDemo(e){const sample=Demo.samples.find(s=>s.id===e.currentTarget.dataset.id);if(sample)this.setData({link:sample.url,sampleId:sample.id,sampleIndex:Demo.samples.indexOf(sample)});},
pickDemo(e){const sample=Demo.samples[Number(e.detail.value)];if(sample)this.chooseDemo({currentTarget:{dataset:{id:sample.id}}});},
chooseMode(e){const mode=e.currentTarget.dataset.mode;if(mode==='digital')wx.navigateTo({url:'/pages/studio/index?view=digital'});else if(mode==='ai')this.startVideo();},
paste() {
wx.getClipboardData({
success: ({ data }) => this.setData({ link: String(data || '').slice(0, 2000) }),
fail: () => wx.showToast({ title: '未能读取剪贴板，请手动粘贴', icon: 'none' })
});
},
extract() {
const invalid=P.validateShareLink(this.data.link);
if(invalid){wx.showToast({title:invalid,icon:'none'});return;}
const sample=Demo.match(this.data.link),run=id=>{const state=studio.read();Demo.extract(state,this.data.link,id);studio.write(state);wx.navigateTo({url:'/pages/studio/index?view=remix'});};
if(sample)return run(sample.id); // 演示样品链接仍走本地流程
if(http.token())return this.extractOnline(); // 在线：后端链接解析（任务化轮询）
this.fallbackExtract();
},
/** 在线提取：links.parse → pollTask → 有文案（result.text）直接进二创流；无文案/失败提示后走演示兜底。
 * showLoading 只覆盖提交阶段；轮询进度在本页按钮态展示（creation 无任务中心态），onUnload 取消轮询。 */
extractOnline() {
const url=String(this.data.link).trim(),scope=this.actorScope;
wx.showLoading({title:'提交解析任务',mask:true});
P.api.links.parse(url).then(res=>{
wx.hideLoading();
const taskId=res&&res.taskId?String(res.taskId):'';
if(!taskId)throw Error('解析任务创建失败，请稍后重试');
this.setData({extracting:true,parseProgress:P.progressOf('QUEUED')});
const poll=this.__parsePoll=P.pollTask(taskId,{onTick:task=>{if(this.__parsePoll===poll)this.setData({parseProgress:P.progressOf(task.status)});}});
return poll.then(task=>{
if(this.__parsePoll!==poll)return null; // 已取消/新一轮
this.__parsePoll=null;this.setData({extracting:false});
if(scope!==studio.scope())return null; // 工作身份已变化，丢弃迟到结果
const view=P.taskView(task.status);
if(view!=='completed'){wx.showToast({title:view==='failed'?'文案解析失败：'+(task.failedReason||'请稍后重试'):'解析结果暂不确定，请稍后重试',icon:'none'});return null;}
const text=String((task.result&&(task.result.text||task.result.script))||'').trim();
if(!text){wx.showToast({title:'未解析到口播文案，可手动填写或使用演示样品',icon:'none'});return null;}
const state=studio.read();
state.title=text.split('\n')[0].slice(0,28);
state.original=text;state.script='';state.rewriteHistory=[];state.copyOptimizations=[];
state.extraction={online:true,taskId:String(task.id||''),sourceTitle:String((task.result&&task.result.title)||url),sourceUrl:url,extractedAt:Date.now()};
delete state.editorBuffer;
studio.write(state);
wx.navigateTo({url:'/pages/studio/index?view=remix'});
return null;
}).catch(error=>{
if(this.__parsePoll===poll){this.__parsePoll=null;this.setData({extracting:false});}
if(error&&error.cancelled)return null; // 页面卸载取消：静默
wx.showToast({title:P.degradeMessage(error),icon:'none'});
this.fallbackExtract(); // 接口失败回落演示兜底
});
}).catch(error=>{
wx.hideLoading();
wx.showToast({title:P.degradeMessage(error),icon:'none'});
this.fallbackExtract(); // 接口失败回落演示兜底
});
},
/** 演示兜底（离线或接口失败时维持原体验） */
fallbackExtract() {
const run=id=>{const state=studio.read();Demo.extract(state,this.data.link,id);studio.write(state);wx.navigateTo({url:'/pages/studio/index?view=remix'});};
wx.showModal({ title: '使用内置文案', content: '视频链接解析暂未开通。继续将使用“'+Demo.samples.find(s=>s.id===this.data.sampleId).title+'”的内置文案，不会读取此链接；手动草稿保留。', confirmText:'使用内置文案',confirmColor:'#824019',success:({confirm})=>{if(confirm)run(this.data.sampleId);} });
},
openEditor() {
const draft = this.data.draft;
const stored = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || draft;
this.setData({ sheet: 'editor', sheetTitle: '手动写文案', editorText: stored ? (typeof stored.workingText === 'string' ? stored.workingText : stored.text || '') : '' });
},
editRecent(e) {
const item = this.data.recent.find(row => row.id === e.currentTarget.dataset.id);
if (!item) return;
if (item.id === 'draft') { if (studio.restoreManualDraft()) wx.navigateTo({ url: '/pages/studio/index?view=remix' }); else this.openEditor(); }
else if (item.id === 'video-draft') this.startVideo();
else if (item.editTask) wx.navigateTo({ url: '/pages/ai-video/index?task=' + encodeURIComponent(item.editTask) });
else wx.navigateTo({ url: '/pages/studio/index?view=result&task=' + encodeURIComponent(item.taskId) });
},
inputEditor(e) {
this.setData({ editorText: e.detail.value });
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || {};
wx.setStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft), Object.assign({}, draft, { workingText: e.detail.value, updatedAt: Date.now() }));
},
saveDraft() {
const text = this.data.editorText.trim();
if (!text) { wx.showToast({ title: '请先填写文案', icon: 'none' }); return; }
const previous = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || {};
const draft = { text, rewrittenText: previous.text === text ? previous.rewrittenText || '' : '', updatedAt: Date.now() };
wx.setStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft), draft);
this.setData({ draft, sheet: '' });
const state = studio.read(); delete state.editorBuffer;
state.original = text; state.script = draft.rewrittenText; state.title = text.split('\n')[0].slice(0, 28); delete state.extraction;state.rewriteHistory=[];studio.write(state);
wx.navigateTo({ url: '/pages/studio/index?view=remix' });
},
startVideo() { wx.navigateTo({ url: '/pages/ai-video/index' }); },
openShortcut(e) {
const item = this.data.shortcuts.find(item => item.key === e.currentTarget.dataset.key);
if (item) wx.navigateTo({ url: '/pages/studio/index?view=' + item.key });
},
allRecent() { wx.navigateTo({ url: '/pages/studio/index?view=recent' }); },
previewAsset(e) { wx.previewImage({ current: e.currentTarget.dataset.path, urls: assets.map(asset => asset.path) }); },
closeSheet() { this.setData({ sheet: '' }); this.onShow(); },
noop() {}
});
