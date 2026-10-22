const { assets, unavailable, persistMedia } = require('../../utils/creation');
const studio = require('../../utils/studio');
Page({
data: {
mode: 'frames', description: '', firstFrame: '', lastFrame: '', references: [], assets, libraryOpen: false, libraryTarget: '', editingTaskId: '', invalidMessage: '',
parameters: [
{ key: 'resolution', label: '分辨率', options: ['768P', '1080P', '720P'], value: 0 },
{ key: 'duration', label: '时长', options: ['8 秒', '5 秒', '10 秒'], value: 0 },
{ key: 'ratio', label: '画面比例', options: ['9:16', '16:9', '1:1'], value: 0 },
{ key: 'count', label: '生成数量', options: ['1 个'], value: 0 }
]
},
onLoad(options) {
if(!studio.guard())return;this.actorScope=studio.scope();
let draft = wx.getStorageSync(studio.scopedKey('zhongshu-video-draft'));
if (options && options.task) {
const state = studio.read();
const task = state.tasks.find(item => item.id === options.task && item.kind === 'ai');
if (!task) { this.setData({ invalidMessage: '任务不存在或已清理，请返回任务中心。' }); return; }
this.setData({ editingTaskId: task.id });
draft = (state.videoTaskDrafts || {})[task.id] || Object.assign({}, task.configuration, { description: task.script });
}
if (draft) {
const parameters = this.data.parameters.map(parameter => {
const saved = (draft.parameters || []).find(item => item.key === parameter.key);
const value = saved ? (typeof saved.value === 'number' ? (saved.options || parameter.options)[saved.value] : saved.value) : parameter.options[0];
return Object.assign({}, parameter, { value: Math.max(0, parameter.options.indexOf(value)) });
});
this.setData({ mode: draft.mode === 'reference' ? 'reference' : 'frames', description: draft.description || '', firstFrame: draft.firstFrame || '', lastFrame: draft.lastFrame || '', references: draft.references || [], parameters });
}
},
onShow(){if(!studio.guard())return;if(this.actorScope!==studio.scope())wx.redirectTo({url:"/pages/ai-video/index"});},
onHide() { this.saveDraft(); },
onUnload() { this.saveDraft(); },
saveDraft() {
if(!studio.allowed()||this.actorScope!==studio.scope())return;
if (this.data.invalidMessage) return;
const { mode, description, firstFrame, lastFrame, references, parameters } = this.data;
const draft = { mode, description, firstFrame, lastFrame, references, parameters, updatedAt: Date.now() };
if (this.data.editingTaskId) {
const state = studio.read(); state.videoTaskDrafts = state.videoTaskDrafts || {}; state.videoTaskDrafts[this.data.editingTaskId] = draft; studio.write(state);
} else wx.setStorageSync(studio.scopedKey('zhongshu-video-draft'), draft);
},
switchMode(e) { this.setData({ mode: e.currentTarget.dataset.mode }); },
inputDescription(e) { this.setData({ description: e.detail.value }); },
changeParameter(e) {
const index = Number(e.currentTarget.dataset.index), value = Number(e.detail.value), parameter = this.data.parameters[index];
if (parameter && Number.isInteger(value) && value >= 0 && value < parameter.options.length) this.setData({ ['parameters[' + index + '].value']: value });
},
chooseFrame(e) {
const target = e.currentTarget.dataset.target;
const itemList = ['从手机添加', '从素材库选择'];
if (this.data[target]) itemList.push('移除图片');
wx.showActionSheet({ itemList, success: ({ tapIndex }) => {
if (tapIndex === 1) this.openLibrary(target);
else if (tapIndex === 2) this.setData({ [target]: '' });
else wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], success: async result => {
try { this.setData({ [target]: await persistMedia(result.tempFiles[0].tempFilePath) }); this.saveDraft(); } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
}, fail: this.mediaFailure });
} });
},
uploadReferences() {
wx.showActionSheet({ itemList: ['添加图片', '添加视频', '添加音频'], success: ({ tapIndex }) => {
const type = ['image', 'video', 'audio'][tapIndex];
const existing = this.data.references.filter(item => item.type === type).length;
const maximum = type === 'image' ? 8 : 3;
const count = Math.min(maximum - existing, 12 - this.data.references.length);
if (count <= 0) { wx.showToast({ title: '已达到素材数量上限', icon: 'none' }); return; }
const success = async result => {
try {
const items = await Promise.all(result.tempFiles.map(async (file, i) => ({ id: Date.now() + '-' + i, type, path: await persistMedia(file.tempFilePath || file.path), name: file.name || ({ image: '图片', video: '视频', audio: '音频' })[type] })));
this.addReferences(items); this.saveDraft();
} catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
};
if (type === 'audio') wx.chooseMessageFile({ count, type: 'file', extension: ['mp3', 'wav', 'm4a', 'aac'], success, fail: this.mediaFailure });
else wx.chooseMedia({ count, mediaType: [type], success, fail: this.mediaFailure });
} });
},
mediaFailure(error) { if (!/cancel/.test(error.errMsg || '')) wx.showToast({ title: '未能读取素材，请重新选择', icon: 'none' }); },
addReferences(items) {
const next = this.data.references.slice();
for (const item of items) {
if (next.length >= 12 || next.filter(ref => ref.type === item.type).length >= (item.type === 'image' ? 8 : 3)) continue;
if (!next.some(ref => ref.path === item.path)) next.push(item);
}
this.setData({ references: next });
},
removeReference(e) { this.setData({ references: this.data.references.filter(item => item.id !== e.currentTarget.dataset.id) }); },
openLibraryButton() { this.openLibrary('references'); },
openLibrary(target) { this.setData({ libraryOpen: true, libraryTarget: target }); },
selectAsset(e) {
const asset = assets.find(item => item.id === e.currentTarget.dataset.id);
if (this.data.libraryTarget === 'references') this.addReferences([asset]);
else this.setData({ [this.data.libraryTarget]: asset.path });
this.closeLibrary();
},
closeLibrary() { this.setData({ libraryOpen: false }); },
help() { wx.showModal({ title: '参考素材', content: '图片最多8张、视频最多3个、音频最多3个，合计最多12个。素材在本地选择，生成前仍可移除。', showCancel: false, confirmColor: '#824019' }); },
optimize() {
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
this.setData({description:require('../../utils/creation-demo').optimize(this.data.description,this.data.mode)});this.saveDraft();wx.showToast({title:'模拟提示词已优化',icon:'none'});
},
generate() {
if (this.data.invalidMessage) return;
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
if (this.data.mode === 'reference' && !this.data.references.length) { wx.showToast({ title: '请先添加参考素材', icon: 'none' }); return; }
this.saveDraft();
wx.showModal({ title: '体验视频创作流程', content: '当前为本地演示，未接入视频生成与计费服务。可创建演示任务体验后续保存和发布流程，不会扣费。', confirmText: '体验流程', confirmColor: '#824019', success: ({ confirm }) => {
if (confirm) {
const configuration = { mode: this.data.mode, parameters: this.data.parameters.map(item => ({ key: item.key, value: item.options[item.value] })), firstFrame: this.data.firstFrame, lastFrame: this.data.lastFrame, references: this.data.references };
try {
const task = studio.createDemoTask('ai', this.data.description, configuration);
const nav = { url: '/pages/studio/index?view=tasks&task=' + task.id };
if (getCurrentPages().length >= 9) wx.redirectTo(nav); else wx.navigateTo(nav);
} catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
}
} });
},
returnToTasks() { wx.redirectTo({ url: '/pages/studio/index?view=tasks' }); },
noop() {}
});
