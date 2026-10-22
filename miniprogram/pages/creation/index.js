const { assets, unavailable } = require('../../utils/creation');
const studio = require('../../utils/studio');
const Demo=require('../../utils/creation-demo');
Page({
data: {
link: Demo.samples[0].url, sheet: '', editorText: '', draft: null, assets, demoSamples:Demo.samples, sampleId:'daylight',sampleIndex:0,
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
this.setData({ draft: wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft')) || null });
if (options.section === 'works') wx.redirectTo({ url: '/pages/studio/index?view=works' });
if (options.section === 'digital') wx.redirectTo({ url: '/pages/studio/index?view=digital' });
if (options.editor === '1') this.openEditor();
},
onShow() {if(!studio.guard())return;if(this.actorScope!==studio.scope()){this.actorScope=studio.scope();this.setData({link:"",sheet:"",editorText:""});}
const draft = wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft')) || null;
const recent = studio.recentItems(studio.read()).slice(0, 2).map(item=>({...item,updatedText:item.updatedAt?studio.formatTime(item.updatedAt):''}));
this.setData({ draft, recent });
},
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
if (!/https?:\/\/[^\s]+/i.test(this.data.link)) {
wx.showToast({ title: '请先粘贴有效的视频分享链接', icon: 'none' }); return;
}
const sample=Demo.match(this.data.link),run=id=>{const state=studio.read();Demo.extract(state,this.data.link,id);studio.write(state);wx.navigateTo({url:'/pages/studio/index?view=remix'});};
if(sample)return run(sample.id);
wx.showModal({ title: '改用模拟文案', content: '真实视频解析尚未接入。继续将使用“'+Demo.samples.find(s=>s.id===this.data.sampleId).title+'”的模拟原文，不会读取此真实链接；手动草稿保留。', confirmText:'使用示例',confirmColor:'#824019',success:({confirm})=>{if(confirm)run(this.data.sampleId);} });
},
openEditor() {
const draft = this.data.draft;
const stored = wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft')) || draft;
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
const draft = wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft')) || {};
wx.setStorageSync(studio.scopedKey('zhongshu-creation-draft'), Object.assign({}, draft, { workingText: e.detail.value, updatedAt: Date.now() }));
},
saveDraft() {
const text = this.data.editorText.trim();
if (!text) { wx.showToast({ title: '请先填写文案', icon: 'none' }); return; }
const previous = wx.getStorageSync(studio.scopedKey('zhongshu-creation-draft')) || {};
const draft = { text, rewrittenText: previous.text === text ? previous.rewrittenText || '' : '', updatedAt: Date.now() };
wx.setStorageSync(studio.scopedKey('zhongshu-creation-draft'), draft);
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
