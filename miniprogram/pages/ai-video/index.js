const { assets, unavailable, persistMedia } = require('../../utils/creation');
const studio = require('../../utils/studio');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：F11 参数映射/降级文案）
const contentApi=require('../../api/franchise/content');
const http=require('../../api/http');
const media=require('../../utils/content-media');
const textResize = require('../../utils/creation-text-resize');
Page({
...textResize.handlers,
data: {
mode: 'frames', description: '', firstFrame: '', lastFrame: '', references: [], assets, libraryOpen: false, libraryTarget: '', editingTaskId: '', invalidMessage: '', draftStatus:'',durationInput:'8',durationError:'',
parameters: [
{ key: 'resolution', label: '分辨率', options: ['768P', '2K'], value: 0 },
{ key: 'duration', label: '时长', value: '8 秒' },
{ key: 'ratio', label: '画面比例', options: ['9:16', '16:9', '21:9', '4:3', '1:1', '3:4', '自动'], value: 0 },
{ key: 'count', label: '生成数量', options: ['1 个'], value: 0 }
]
},
onLoad(options) {
if(!studio.guard())return;this.actorScope=studio.scope();this.__draftSession=http.sessionGuard();
let draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').videoDraft));
if (options && options.task) {
const state = studio.read();
const task = state.tasks.find(item => item.id === options.task && item.kind === 'ai');
if (!task) { this.setData({ invalidMessage: '任务不存在或已清理，请返回任务中心。' }); return; }
this.setData({ editingTaskId: task.id });
draft = (state.videoTaskDrafts || {})[task.id] || Object.assign({}, task.configuration, { description: task.script });
}
this.__legacyDraft=!!draft&&!this.data.editingTaskId;
if (draft) {
let parameters;
try { parameters=P.restoreVideoParameters(draft.parameters,this.data.parameters); }
catch(error){this.setData({draftStatus:error.message});parameters=this.data.parameters;}
this.setData({ mode: draft.mode === 'reference' ? 'reference' : 'frames', description: draft.description || '', firstFrame: draft.firstFrame || '', lastFrame: draft.lastFrame || '', references: draft.references || [], parameters,durationInput:String(P.mapDuration(parameters.find(p=>p.key==='duration').value)) });
}
this.syncRatio();
if(http.token())this.restoreRemoteDraft();
},
restoreRemoteDraft() {
if(this.__draft)return this.__draft.start().catch(()=>{});
const key=this.data.editingTaskId?'task:'+this.data.editingTaskId:'new';
const scope=this.actorScope,storageKey=studio.scopedKey('zs-video-draft-v2:'+key);
this.__draft=require('./video-draft').create({key,storage:wx,storageKey,initialDirty:this.__legacyDraft,
  valid:()=>this.__draftSession()&&scope===studio.scope(),
  getForm:()=>{const {mode,description,firstFrame,lastFrame,references,parameters}=this.data;return{mode,description,firstFrame,lastFrame,references,parameters:P.mapVideoParameters(parameters)};},
  applyForm:draft=>{
    const parameters=P.restoreVideoParameters(draft.parameters,this.data.parameters);
    this.setData({mode:draft.mode==='reference'?'reference':'frames',description:String(draft.description||''),
      firstFrame:draft.firstFrame||'',lastFrame:draft.lastFrame||'',references:Array.isArray(draft.references)?draft.references.filter(r=>r.path):[],parameters,durationInput:String(P.mapDuration(parameters.find(p=>p.key==='duration').value))});
    this.syncRatio();
  },
  api:{read:key=>contentApi.drafts.videoDraft(key),save:(key,draft)=>{
    const payload={draftKey:key,revision:draft.revision,mode:draft.mode,description:draft.description,parameters:draft.parameters};
    if(draft.references.length)payload.references=P.mapReferences(draft.references);
    return contentApi.drafts.saveVideoDraft(payload);
  }},onStatus:draftStatus=>this.setData({draftStatus})});
return this.__draft.start().catch(()=>{});
},
onShow(){if(!studio.guard())return;if(this.actorScope!==studio.scope())wx.redirectTo({url:"/pages/ai-video/index"});},
onHide() { this.saveDraft(); },
onUnload() { this.saveDraft();if(this.__draft)this.__draft.stop(); },
saveDraft() {
  if(!studio.allowed()||this.actorScope!==studio.scope()||this.data.invalidMessage)return;
  if(this.__draft)return this.__draft.save().catch(()=>{});
},
retryDraft(){
  if(!this.__draft)return;
  if(this.__draft.conflicted())return wx.showActionSheet({itemList:['保留当前填写','使用云端草稿'],success:result=>this.__draft.resolve(result.tapIndex===0).catch(e=>this.setData({draftStatus:e.message}))});
  this.__draft.start().then(()=>this.__draft.save()).catch(()=>{});
},
syncRatio() { this.setData({ratioAutomatic:this.data.mode==='frames'?!!(this.data.firstFrame||this.data.lastFrame):this.data.references.some(r=>r.type==='image'||r.type==='video')}); },
switchMode(e) { this.setData({ mode: e.currentTarget.dataset.mode });this.syncRatio(); },
inputDescription(e) { this.setData({ description: e.detail.value }); },
inputDuration(e) {
const value=String(e.detail.value||''),valid=/^\d+$/.test(value)&&Number(value)>=4&&Number(value)<=15;
this.setData({durationInput:value,durationError:valid?'':'请输入 4–15 的整数秒'});
if(valid){const index=this.data.parameters.findIndex(p=>p.key==='duration');this.setData({['parameters['+index+'].value']:Number(value)+' 秒'});}
},
changeParameter(e) {
const index = Number(e.currentTarget.dataset.index), value = Number(e.detail.value), parameter = this.data.parameters[index];
if (parameter && parameter.options && Number.isInteger(value) && value >= 0 && value < parameter.options.length) this.setData({ ['parameters[' + index + '].value']: value });
},
chooseFrame(e) {
const target = e.currentTarget.dataset.target;
const itemList = ['从手机添加', '从素材库选择'];
if (this.data[target]) itemList.push('移除图片');
wx.showActionSheet({ itemList, success: ({ tapIndex }) => {
if (tapIndex === 1) this.openLibrary(target);
else if (tapIndex === 2) {this.setData({ [target]: '' });this.syncRatio();}
else wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], success: async result => {
try { this.setData({ [target]: await persistMedia(result.tempFiles[0].tempFilePath) });this.syncRatio(); this.saveDraft(); } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); }
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
if (type === 'audio') wx.chooseMessageFile({ count, type: 'file', extension: ['mp3'], success, fail: this.mediaFailure });
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
this.setData({ references: next });this.syncRatio();
},
removeReference(e) { this.setData({ references: this.data.references.filter(item => item.id !== e.currentTarget.dataset.id) });this.syncRatio(); },
openLibraryButton() { this.openLibrary('references'); },
openLibrary(target) { this.setData({ libraryOpen: true, libraryTarget: target }); },
selectAsset(e) {
const asset = assets.find(item => item.id === e.currentTarget.dataset.id);
if (this.data.libraryTarget === 'references') this.addReferences([asset]);
else this.setData({ [this.data.libraryTarget]: asset.path });
this.syncRatio();
this.closeLibrary();
},
closeLibrary() { this.setData({ libraryOpen: false }); },
help() { wx.showModal({ title: '参考素材', content: '图片最多8张、视频最多3个、音频最多3个，合计最多12个。素材在本地选择，生成前仍可移除。', showCancel: false, confirmColor: '#824019' }); },
optimize() {
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
if (!http.token()) return wx.showToast({ title: '请先登录后再优化提示词', icon: 'none' }); // 无 token：提示登录
// 提示词优化暂无在线通道：不再回落本地演示改写，如实提示
wx.showToast({ title: '提示词智能优化暂未开通，请手动调整画面描述', icon: 'none' });
},
generate() {
if (this.data.invalidMessage) return;
if(!/^\d+$/.test(this.data.durationInput)||Number(this.data.durationInput)<4||Number(this.data.durationInput)>15){this.setData({durationError:'请输入 4–15 的整数秒'});return;}
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
if (this.data.mode === 'reference' && !this.data.references.length) { wx.showToast({ title: '请先添加参考素材', icon: 'none' }); return; }
this.saveDraft();
if (http.token()) return this.generateOnline(); // 在线：直连后端创建 ai_video 任务
wx.showToast({ title: '请先登录后端账号后再生成视频', icon: 'none' });
},
async generateOnline() {
if(this.data.generating)return;
if(!/^\d+$/.test(this.data.durationInput)||Number(this.data.durationInput)<4||Number(this.data.durationInput)>15){this.setData({durationError:'请输入 4–15 的整数秒'});return;}
const sessionAlive=http.sessionGuard(),scope=this.actorScope;
const alive=()=>sessionAlive()&&scope===studio.scope();
this.setData({generating:true});
try {
const mapped = P.mapVideoParameters(this.data.parameters);
const hasVisual=this.data.mode==='frames'?!!(this.data.firstFrame||this.data.lastFrame):this.data.references.some(r=>r.type==='image'||r.type==='video');
if(hasVisual)mapped.ratio='adaptive';
else if(mapped.ratio==='adaptive')throw Error('纯文生视频请选择具体画面比例');
const payload = { kind: 'ai_video', description: this.data.description.trim(), resolution: mapped.resolution, duration: mapped.duration, ratio: mapped.ratio };
const inputs=this.data.mode==='reference'
  ? this.data.references.map(item=>({path:item.path,role:{image:'reference_image',video:'reference_video',audio:'reference_audio'}[item.type],name:item.name}))
  : [{path:this.data.firstFrame,role:'first_frame'},{path:this.data.lastFrame,role:'last_frame'}].filter(item=>item.path);
const signature=JSON.stringify({payload,inputs});
if(!this.__videoSubmission||this.__videoSubmission.signature!==signature){
  const references=[];
  for(const input of inputs){
    if(!input.role)throw Error('参考素材类型无效，请重新选择');
    const assetId=await media.upload(input.path,alive);
    references.push(Object.assign({role:input.role,assetId},input.name?{name:input.name}:{}));
  }
  if(references.length)payload.references=references;
  this.__videoSubmission={signature,payload,key:'video-'+Date.now()+'-'+Math.floor(Math.random()*1e8)};
}
if(!alive())return;
const res=await contentApi.tasks.create(this.__videoSubmission.payload,this.__videoSubmission.key);
if(!alive())return;
const taskId = res && (res.taskId||res.id) ? String(res.taskId||res.id) : '';
if (!taskId) throw Error('任务创建失败，请稍后重试');
this.__videoSubmission=null;
wx.showToast({ title: '任务已提交，可在任务中心查看进度', icon: 'none' });
const nav = { url: '/pages/studio/index?view=tasks' };
if (getCurrentPages().length >= 9) wx.redirectTo(nav); else wx.navigateTo(nav);
}catch(error){if(alive())wx.showToast({ title: P.degradeMessage(error), icon: 'none' });}
finally{if(alive())this.setData({generating:false});}
},
returnToTasks() { wx.redirectTo({ url: '/pages/studio/index?view=tasks' }); },
noop() {}
});
