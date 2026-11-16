const { assets, unavailable } = require('../../utils/creation');
const studio = require('../../utils/studio');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5）
const http=require('../../api/http');
const linkDraft=require('../../utils/creation-link-draft');
Page({
data: {
link: '', sheet: '', editorText: '', draft: null, assets,
extracting: false, extractStage: '', parseProgress: 0, extractError: '',
modes:[{id:'remix',title:'爆款二创',subtitle:'参考视频改写',icon:'video-filled'},{id:'digital',title:'数字人口播',subtitle:'文案生成口播',icon:'person-filled'},{id:'ai',title:'AI视频',subtitle:'文字图片成片',icon:'home-video-icon'}],
shortcuts: [
{ key: 'library', title: '素材库', icon: 'folder' },
{ key: 'tasks', title: '任务中心', icon: 'tasks' },
{ key: 'characters', title: '人物库', icon: 'works' },
{ key: 'records', title: '发布记录', icon: 'publish' }
],
recent: []
},
onLoad(options) {
if(!studio.guard())return;this.__closed=false;this.actorScope=studio.scope();this.actorSessionGeneration=http.sessionGeneration();
this.setData({ link:linkDraft.read(this.actorScope,this.actorSessionGeneration),draft: wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || null });
if (options.section === 'works') wx.redirectTo({ url: '/pages/studio/index?view=works' });
if (options.section === 'digital') wx.redirectTo({ url: '/pages/studio/index?view=digital' });
if (options.editor === '1') this.openEditor();
},
onShow() {if(!studio.guard())return;if(this.actorScope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration()){if(this.__parsePoll&&this.__parsePoll.cancel)this.__parsePoll.cancel();this.__extractAttempt=null;this.actorScope=studio.scope();this.actorSessionGeneration=http.sessionGeneration();this.setData({link:linkDraft.read(this.actorScope,this.actorSessionGeneration),sheet:"",editorText:"",extracting:false,extractError:''});}
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').creationDraft)) || null;
const recent = studio.recentItems(studio.read()).slice(0, 2).map(item=>({...item,updatedText:item.updatedAt?studio.formatTime(item.updatedAt):''}));
this.setData({ draft, recent });
},
onUnload() { this.__closed=true; if (this.__parsePoll && this.__parsePoll.cancel) this.__parsePoll.cancel(); },
inputLink(e) { this.setData({ link: linkDraft.write(this.actorScope,this.actorSessionGeneration,e.detail.value), extractError: '' }); },
chooseMode(e){const mode=e.currentTarget.dataset.mode;if(mode==='digital')wx.navigateTo({url:'/pages/studio/index?view=digital'});else if(mode==='ai')this.startVideo();},
paste() {
const scope=this.actorScope,generation=this.actorSessionGeneration;
wx.getClipboardData({
success: ({ data }) => {if(!this.__closed&&scope===studio.scope()&&generation===http.sessionGeneration())this.inputLink({detail:{value:data}});},
fail: () => wx.showToast({ title: '未能读取剪贴板，请手动粘贴', icon: 'none' })
});
},
extract() {
if(this.data.extracting)return;
const invalid=P.validateShareLink(this.data.link);
if(invalid){wx.showToast({title:invalid,icon:'none'});return;}
if(!http.token())return wx.showToast({title:'请先登录后再提取文案',icon:'none'}); // 无 token：提示登录，不回落内置文案
return this.extractOnline(); // 在线：后端链接解析（任务化轮询）
},
/** 先解析媒体再转写口播；解析结果的 text 是发布简介，不能作为视频完整文案。重试复用同一请求键。 */
async extractOnline() {
const url=P.shareLinkUrl(this.data.link),scope=this.actorScope;
if(!url)return;
if(scope!==studio.scope()||this.actorSessionGeneration!==http.sessionGeneration())return;
const requestAlive=http.requestGuard('creation-extract');
const alive=()=>!this.__closed&&requestAlive()&&scope===studio.scope();
this.__extractAttempt=linkDraft.attempt(scope,this.actorSessionGeneration,url,()=>'extract-'+Date.now()+'-'+Math.floor(Math.random()*1e8));
const attempt=this.__extractAttempt;
this.setData({extracting:true,extractStage:'解析链接中',parseProgress:0,extractError:''});
const wait=async res=>{
if(!alive())throw {cancelled:true};
if(!res||!res.taskId)throw Error('解析任务创建失败，请稍后重试');
const poll=this.__parsePoll=P.pollTask(String(res.taskId),{onTick:task=>{if(alive())this.setData({parseProgress:P.progressOf(task.status)});}});
const task=await poll;
if(this.__parsePoll===poll)this.__parsePoll=null;
if(!alive())throw {cancelled:true};
const view=P.taskView(task.status);
if(view!=='completed')throw Error(view==='failed'?'文案提取失败：'+(task.failedReason||'请稍后重试'):'任务结果待核实，请在任务中心查看，勿重复提交');
return task;
};
try{
const parsed=await wait(await P.api.links.parse(url,attempt.parseKey));
const media=parsed.result||{};
const sourceUrl=[media.audioUrl,media.videoUrl].map(value=>String(value||'').trim()).find(value=>/^https?:\/\//i.test(value));
if(!sourceUrl)throw Error('未解析到可转写的音视频，请重新复制链接或手动填写文案');
this.setData({extractStage:'提取文案中',parseProgress:0});
const payload={sourceUrl};
if(Number(media.durationMs)>0)payload.durationSec=Number(media.durationMs)/1000;
const task=await wait(await P.api.scripts.extract(payload,attempt.asrKey));
const text=String((task.result&&(task.result.text||task.result.script))||'').trim();
if(!text)throw Error('未提取到口播文案，可手动填写');
const state=studio.read();
state.title=text.split('\n')[0].slice(0,28);
state.original=text;state.script='';state.rewriteHistory=[];state.copyOptimizations=[];
state.extraction={online:true,taskId:String(task.id||''),sourceTitle:String(media.title||url),sourceUrl:url,extractedAt:Date.now()};
delete state.editorBuffer;
studio.write(state);
wx.navigateTo({url:'/pages/studio/index?view=remix'});
}catch(error){
if(!alive()||(error&&error.cancelled))return;
const message=P.degradeMessage(error);
this.setData({extractError:message});
wx.showToast({title:message,icon:'none'});
}finally{if(alive())this.setData({extracting:false});}
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
if (item) wx.navigateTo({ url: item.key==='characters'?'/pages/studio/characters/index':'/pages/studio/index?view=' + item.key });
},
allRecent() { wx.navigateTo({ url: '/pages/studio/index?view=recent' }); },
previewAsset(e) { wx.previewImage({ current: e.currentTarget.dataset.path, urls: assets.map(asset => asset.path) }); },
closeSheet() { this.setData({ sheet: '' }); this.onShow(); },
noop() {}
});
