const { persistMedia } = require('../../utils/creation');
const studio = require('../../utils/studio');
const P=require('../../utils/platform-content');       // 内容域纯函数层（阶段 1.5：F11 参数映射/降级文案）
const contentApi=require('../../api/franchise/content');
const http=require('../../api/http');
const media=require('../../utils/content-media');
const textResize = require('../../utils/creation-text-resize');
const assetId=value=>value==null||String(value)==='0'?'':String(value);
const restoreReferences=values=>Array.isArray(values)?values.map((value,index)=>{
const item=value&&typeof value==='object'?value:{};
return {...item,id:item.id||'reference-'+index+'-'+assetId(item.assetId),assetId:assetId(item.assetId),type:item.type||{reference_image:'image',reference_video:'video',reference_audio:'audio'}[item.role]||'',name:item.name||'参考素材'+(index+1),path:item.path||''};
}):[];
Page({
...textResize.handlers,
data: {
mode: 'frames', description: '', firstFrame: '', lastFrame: '', firstFrameSource:{},lastFrameSource:{},firstFrameAssetId:'', lastFrameAssetId:'', references: [], editingTaskId: '', taskLoading:false,taskDraftBlocked:false,draftConflicted:false,submissionPending:false,invalidMessage: '', draftStatus:'',mediaLoading:false,mediaError:'',durationInput:'8',durationError:'',
parameters: [
{ key: 'resolution', label: '分辨率', options: ['768P', '2K'], value: 0 },
{ key: 'duration', label: '时长', value: '8 秒' },
{ key: 'ratio', label: '画面比例', options: ['9:16', '16:9', '21:9', '4:3', '1:1', '3:4', '自动'], value: 0 },
{ key: 'count', label: '生成数量', options: ['1 个'], value: 0 }
]
},
onLoad(options) {
if(!studio.guard())return;this.__closed=false;this.actorScope=studio.scope();this.__draftSession=http.sessionGuard();
if (options && options.task) {
this.setData({editingTaskId:String(options.task)});
return this.restoreTaskInput();
}
const draft = wx.getStorageSync(studio.scopedKey(require('../../utils/storage-keys').videoDraft));
this.__legacyDraft=!!draft&&!this.data.editingTaskId;
if (draft) {
let parameters;
try { parameters=P.restoreVideoParameters(draft.parameters,this.data.parameters); }
catch(error){this.setData({draftStatus:error.message});parameters=this.data.parameters;}
this.setData({ mode: draft.mode === 'reference' ? 'reference' : 'frames', description: draft.description || '', firstFrame: draft.firstFrame || '', lastFrame: draft.lastFrame || '',firstFrameSource:draft.firstFrameSource||{},lastFrameSource:draft.lastFrameSource||{}, firstFrameAssetId:assetId(draft.firstFrameAssetId),lastFrameAssetId:assetId(draft.lastFrameAssetId),references:restoreReferences(draft.references), parameters,durationInput:String(P.mapDuration(parameters.find(p=>p.key==='duration').value)) });
}
this.syncRatio();
if(http.token()){this.restoreRemoteDraft();this.restoreMediaPreviews();}
},
async restoreTaskInput(){
if(this.data.taskLoading)return;
const id=this.data.editingTaskId;
if(!/^[1-9]\d{0,18}$/.test(id)){this.setData({invalidMessage:'任务编号无效，请返回任务中心。'});return;}
if(!http.token()){this.setData({invalidMessage:'请先登录后再读取原任务。'});return;}
this.__originalTaskForm=null;
this.setData({taskLoading:true,taskDraftBlocked:true,invalidMessage:''});
try{
  const input=await contentApi.tasks.editableInput(id);
  if(!this.videoAlive())return;
  if(!input||String(input.taskId)!==id||input.kind!=='ai_video'||typeof input.description!=='string'
    ||!['frames','reference'].includes(input.mode)||!Array.isArray(input.references)
    ||!['resolution','duration','ratio'].every(key=>input.parameters&&input.parameters[key]!=null))throw Error('原任务输入无法完整恢复');
  this.applyVideoForm(input);
  this.__originalTaskForm=JSON.parse(JSON.stringify(this.videoForm()));
  // Authorize the original task before considering any local or cloud task-specific edits.
  await this.restoreRemoteDraft();
}catch(error){if(this.videoAlive())this.setData({invalidMessage:'原任务输入读取失败：'+P.degradeMessage(error)+'。请重试或返回任务中心。'});}
finally{if(this.videoAlive())this.setData({taskLoading:false});}
},
applyVideoForm(draft){
const parameters=P.restoreVideoParameters(draft.parameters,this.data.parameters);
this.setData({mode:draft.mode==='reference'?'reference':'frames',description:String(draft.description||''),
  firstFrame:draft.firstFrame||'',lastFrame:draft.lastFrame||'',firstFrameSource:draft.firstFrameSource||{},lastFrameSource:draft.lastFrameSource||{},firstFrameAssetId:assetId(draft.firstFrameAssetId),lastFrameAssetId:assetId(draft.lastFrameAssetId),references:restoreReferences(draft.references),parameters,durationInput:String(P.mapDuration(parameters.find(p=>p.key==='duration').value))});
this.syncRatio();this.restoreMediaPreviews();
},
restoreRemoteDraft() {
if(!this.__draft){
const key=this.data.editingTaskId?'task:'+this.data.editingTaskId:'new';
const scope=this.actorScope,storageKey=studio.scopedKey('zs-video-draft-v2:'+key);
this.__draft=require('./video-draft').create({key,storage:wx,storageKey,initialDirty:this.__legacyDraft,
  valid:()=>this.__draftSession()&&scope===studio.scope(),
  getForm:()=>this.videoForm(),
  applyForm:draft=>{if(this.videoAlive())this.applyVideoForm(draft);},
  api:{read:key=>contentApi.drafts.videoDraft(key),save:(key,draft)=>this.saveVideoDraftSnapshot(key,draft)},onStatus:draftStatus=>{if(this.videoAlive())this.setData({draftStatus,draftConflicted:!!this.__draft?.conflicted()});}});
}
const draft=this.__draft;
return draft.start().then(ready=>{
  if(this.videoAlive()&&this.data.editingTaskId)this.setData({taskDraftBlocked:!ready||draft.conflicted()});
  if(ready&&this.videoAlive())this.saveDraft();
  return ready;
}).catch(()=>false);
},
onShow(){if(!studio.guard())return;if(this.actorScope!==studio.scope()||this.__draftSession&&!this.__draftSession())wx.redirectTo({url:'/pages/ai-video/index'+(this.data.editingTaskId?'?task='+encodeURIComponent(this.data.editingTaskId):'')});},
onHide() { this.cancelDraftSave();return this.saveDraft(); },
onUnload() { this.__closed=true;this.cancelDraftSave();return this.saveDraft(true); },
cancelDraftSave(){if(this.__draftTimer!=null){clearTimeout(this.__draftTimer);this.__draftTimer=null;}},
queueDraftSave(){
  if(!this.videoAlive()||!this.__draft)return;
  this.__draft.cache();this.cancelDraftSave();
  this.__draftTimer=setTimeout(()=>{this.__draftTimer=null;if(this.videoAlive())this.saveDraft();},500);
},
saveDraft(final=false) {
  if(!studio.allowed()||!this.videoSessionValid()||this.data.invalidMessage||this.data.taskLoading||this.data.taskDraftBlocked){if(final&&this.__draft)this.__draft.stop();return;}
  if(this.__draft)return (final?this.__draft.finish():this.__draft.save()).catch(()=>{});
},
videoSessionValid(){return !!http.token()&&this.actorScope===studio.scope()&&(!this.__draftSession||this.__draftSession());},
videoAlive(){return !this.__closed&&this.videoSessionValid();},
videoForm(){
const {mode,description,firstFrame,lastFrame,firstFrameSource,lastFrameSource,firstFrameAssetId,lastFrameAssetId,references,parameters}=this.data;
return {mode,description,firstFrame,lastFrame,firstFrameSource,lastFrameSource,firstFrameAssetId:assetId(firstFrameAssetId),lastFrameAssetId:assetId(lastFrameAssetId),references:references.map(item=>({...item,assetId:assetId(item.assetId)})),parameters:P.mapVideoParameters(parameters)};
},
saveVideoDraftSnapshot(key,draft){
const {revision,...form}=draft;
if(form.references.some(item=>!['image','video','audio'].includes(item.type)))return Promise.reject(Error('参考素材类型无效，请移除后重新选择'));
const send=prepared=>{
  if(!this.videoSessionValid())throw Error('工作身份已变化，草稿已存本机');
  return contentApi.drafts.saveVideoDraft({draftKey:key,revision,mode:prepared.mode,description:prepared.description,parameters:prepared.parameters,
    firstFrameAssetId:prepared.firstFrameAssetId||null,lastFrameAssetId:prepared.lastFrameAssetId||null,references:P.mapReferences(prepared.references)})
    .then(result=>({...result,form:prepared}));
};
const needsUpload=['firstFrame','lastFrame'].some(field=>form[field]&&!form[field+'AssetId'])||form.references.some(item=>!item.assetId);
if(!needsUpload)return send(form);
if(this.videoAlive())this.setData({draftStatus:'正在上传素材，草稿暂存本机…'});
return this.prepareVideoMedia(form,true).then(send).then(result=>{
  if(this.videoAlive())this.restoreMediaPreviews();
  return result;
});
},
async prepareVideoMedia(form,allowClosed=false){
const prepared=JSON.parse(JSON.stringify(form)),alive=()=>allowClosed?this.videoSessionValid():this.videoAlive();
const check=()=>{if(!alive())throw Error('工作身份已变化或页面已关闭');};
check();
const upload=(path,source)=>{
  if(!path)throw Error('参考素材缺少可用文件，请移除后重新选择');
  this.__mediaUploads=this.__mediaUploads||new Map();
  // The final draft may share an upload begun by generation; each caller checks its own page lifetime.
  const uploadAlive=()=>this.videoSessionValid();
  const key=JSON.stringify([path,source]);
  if(!this.__mediaUploads.has(key))this.__mediaUploads.set(key,media.upload(path,uploadAlive,source).then(id=>{if(!uploadAlive())throw Error('工作身份已变化');if(!assetId(id))throw Error('素材上传未确认，请重试');return assetId(id);}).catch(error=>{this.__mediaUploads.delete(key);throw error;}));
  return this.__mediaUploads.get(key);
};
for(const field of ['firstFrame','lastFrame'])if(prepared[field]&&!prepared[field+'AssetId']){
  const id=await upload(prepared[field],{...prepared[field+'Source'],type:'image'});check();prepared[field+'AssetId']=id;
  if(this.videoAlive()&&this.data[field]===form[field]&&assetId(this.data[field+'AssetId'])===assetId(form[field+'AssetId'])){
    this.setData({[field+'AssetId']:id});this.__draft?.cache();
  }
}
for(const item of prepared.references){
  if(!['image','video','audio'].includes(item.type))throw Error('参考素材类型无效，请移除后重新选择');
  if(item.assetId)continue;
  const id=await upload(item.path,{name:item.sourceName||item.name,mimeType:item.mimeType,type:item.type});check();item.assetId=id;
  const current=this.data.references.find(row=>row.id===item.id&&row.path===item.path&&!assetId(row.assetId));
  if(current&&this.videoAlive()){this.setData({references:this.data.references.map(row=>row===current?{...row,assetId:id}:row)});this.__draft?.cache();}
}
return prepared;
},
async restoreMediaPreviews(){
const sequence=this.__mediaPreviewSequence=(this.__mediaPreviewSequence||0)+1,alive=()=>this.videoAlive()&&sequence===this.__mediaPreviewSequence;
if(!alive())return;
const jobs=[],errors=[];
for(const field of ['firstFrame','lastFrame'])if(assetId(this.data[field+'AssetId'])){
  const id=assetId(this.data[field+'AssetId']);
  jobs.push({id,type:'image',path:this.data[field],current:()=>assetId(this.data[field+'AssetId'])===id,apply:path=>this.setData({[field]:path})});
}
for(const item of this.data.references){
  const id=assetId(item.assetId),current=()=>this.data.references.some(row=>row.id===item.id&&assetId(row.assetId)===id);
  if(!['image','video','audio'].includes(item.type)){errors.push({current,message:item.name+'类型无效，请重新选择'});continue;}
  if(!id){if(!item.path)errors.push({current,message:item.name+'缺少资产，请重新选择'});continue;}
  jobs.push({id,type:item.type,path:item.path,current,apply:path=>this.setData({references:this.data.references.map(row=>row.id===item.id&&assetId(row.assetId)===id?{...row,path}:row)})});
}
this.setData({mediaLoading:!!jobs.length,mediaError:''});
await Promise.all(jobs.map(async job=>{
  try{const path=await media.source(job.id,alive,{type:job.type,localPath:job.path});if(alive()&&job.current())job.apply(path);}
  catch(error){if(alive()&&job.current()){job.apply('');errors.push({current:job.current,message:'素材读取失败：'+P.degradeMessage(error)});}}
}));
if(alive())this.setData({mediaLoading:false,mediaError:errors.filter(error=>error.current()).map(error=>error.message).join('；')});
},
retryDraft(){
  if(!this.__draft||!this.videoAlive()||this.data.generating)return;
  if(this.__draft.conflicted())return wx.showActionSheet({itemList:['保留当前填写','使用云端草稿'],success:result=>this.__draft.resolve(result.tapIndex===0)
    .then(()=>{if(this.videoAlive())this.setData({taskDraftBlocked:false});})
    .catch(e=>{if(this.videoAlive())this.setData({draftStatus:e.message});})});
  return this.restoreRemoteDraft().then(ready=>{if(ready)return this.__draft.save();}).catch(()=>{});
},
async useOriginalTask(){
  const original=this.__originalTaskForm,id=this.data.editingTaskId;
  if(!original||!this.data.taskDraftBlocked||this.data.taskLoading||this.data.generating||!this.videoAlive()||this.__draft?.conflicted())return;
  if(this.__videoSubmission)return this.generateOnline(original);
  const result=await new Promise(resolve=>wx.showModal({title:'用原任务另建',content:'将使用原任务的文案和素材创建新任务，按当前规则计费。尚未读取的修改稿会保留。',confirmText:'用原任务',success:resolve,fail:()=>resolve({confirm:false})}));
  if(!result.confirm||!this.videoAlive()||id!==this.data.editingTaskId||original!==this.__originalTaskForm||!this.data.taskDraftBlocked)return;
  return this.generateOnline(original);
},
syncRatio() { this.setData({ratioAutomatic:this.data.mode==='frames'?!!(this.data.firstFrame||this.data.lastFrame||this.data.firstFrameAssetId||this.data.lastFrameAssetId):this.data.references.some(r=>r.type==='image'||r.type==='video')}); },
switchMode(e) { this.setData({ mode: e.currentTarget.dataset.mode });this.syncRatio();this.queueDraftSave(); },
inputDescription(e) { this.setData({ description: e.detail.value });this.queueDraftSave(); },
inputDuration(e) {
const value=String(e.detail.value||''),valid=/^\d+$/.test(value)&&Number(value)>=4&&Number(value)<=15;
this.setData({durationInput:value,durationError:valid?'':'请输入 4–15 的整数秒'});
if(valid){const index=this.data.parameters.findIndex(p=>p.key==='duration');this.setData({['parameters['+index+'].value']:Number(value)+' 秒'});this.queueDraftSave();}
},
changeParameter(e) {
const index = Number(e.currentTarget.dataset.index), value = Number(e.detail.value), parameter = this.data.parameters[index];
if (parameter && parameter.options && Number.isInteger(value) && value >= 0 && value < parameter.options.length) {this.setData({ ['parameters[' + index + '].value']: value });this.queueDraftSave();}
},
chooseFrame(e) {
const target = e.currentTarget.dataset.target;
if(!['firstFrame','lastFrame'].includes(target))return;
const session=http.sessionGuard(),valid=()=>session()&&this.videoAlive();
const itemList = ['从手机添加', '从素材库选择'];
if (this.data[target]||this.data[target+'AssetId']) itemList.push('移除图片');
wx.showActionSheet({ itemList, success: ({ tapIndex }) => {
if(!valid())return;
if (tapIndex === 1) this.openLibrary(target);
else if (tapIndex === 2) {this.setData({ [target]: '',[target+'AssetId']:'',[target+'Source']:{} });this.syncRatio();this.saveDraft();this.restoreMediaPreviews();}
else wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], success: async result => {
try { const file=result.tempFiles[0],path=await persistMedia(file.tempFilePath);if(!valid())return;this.setData({ [target]:path,[target+'AssetId']:'',[target+'Source']:{name:file.name||file.tempFilePath,mimeType:file.mimeType||''} });this.syncRatio(); this.saveDraft();this.restoreMediaPreviews(); } catch (error) { if(valid())wx.showToast({ title: error.message, icon: 'none' }); }
}, fail: this.mediaFailure });
} });
},
uploadReferences() {
const session=http.sessionGuard(),valid=()=>session()&&this.videoAlive();
wx.showActionSheet({ itemList: ['添加图片', '添加视频', '添加音频'], success: ({ tapIndex }) => {
if(!valid())return;
const type = ['image', 'video', 'audio'][tapIndex];
if(!type)return;
const existing = this.data.references.filter(item => item.type === type).length;
const maximum = type === 'image' ? 8 : 3;
const count = Math.min(maximum - existing, 12 - this.data.references.length);
if (count <= 0) { wx.showToast({ title: '已达到素材数量上限', icon: 'none' }); return; }
const success = async result => {
try {
const items = await Promise.all(result.tempFiles.map(async (file, i) => ({ id: Date.now() + '-' + i, type, path: await persistMedia(file.tempFilePath || file.path), name: file.name || ({ image: '图片', video: '视频', audio: '音频' })[type],sourceName:file.name||file.tempFilePath||file.path,mimeType:file.mimeType||'' })));
if(!valid())return;
this.addReferences(items); this.saveDraft();
} catch (error) { if(valid())wx.showToast({ title: error.message, icon: 'none' }); }
};
if (type === 'audio') wx.chooseMessageFile({ count, type: 'file', success, fail: this.mediaFailure });
else wx.chooseMedia({ count, mediaType: [type], success, fail: this.mediaFailure });
} });
},
mediaFailure(error) { if (!/cancel/.test(error.errMsg || '')) wx.showToast({ title: '未能读取素材，请重新选择', icon: 'none' }); },
addReferences(items) {
const next = this.data.references.slice();
for (const item of items) {
if (next.length >= 12 || next.filter(ref => ref.type === item.type).length >= (item.type === 'image' ? 8 : 3)) continue;
if (!next.some(ref => item.assetId?String(ref.assetId)===String(item.assetId):!!item.path&&ref.path===item.path)) next.push(item);
}
this.setData({ references: next });this.syncRatio();
},
removeReference(e) { this.setData({ references: this.data.references.filter(item => item.id !== e.currentTarget.dataset.id) });this.syncRatio();this.saveDraft();this.restoreMediaPreviews(); },
openLibraryButton() { this.openLibrary('references'); },
openLibrary(target) {
if(this.data.generating||!['firstFrame','lastFrame','references'].includes(target))return;
const session=http.sessionGuard(),scope=this.actorScope,sequence=this.__librarySequence=(this.__librarySequence||0)+1;
const valid=()=>!this.__closed&&session()&&scope===studio.scope()&&sequence===this.__librarySequence;
wx.navigateTo({url:'/pages/studio/index?view=library&select='+(target==='references'?'ALL':'IMAGE'),events:{materialSelected:asset=>{
if(!valid())return;
try{
if(!asset||!/^[1-9]\d{0,18}$/.test(String(asset.assetId))||!['image/jpeg','image/png','video/mp4','audio/mpeg'].includes(asset.mimeType)||asset.type!==asset.mimeType.split('/')[0])throw Error('素材类型无效，请重新选择');
if(target!=='references'&&asset.type!=='image')throw Error('首尾帧请选择图片素材');
if(target==='references'){
const maximum=asset.type==='image'?8:3;
if(this.data.references.length>=12||this.data.references.filter(r=>r.type===asset.type).length>=maximum)throw Error('已达到素材数量上限');
this.addReferences([{...asset,id:'material-'+asset.assetId,assetId:String(asset.assetId)}]);
}else this.setData({[target]:asset.path||'',[target+'AssetId']:String(asset.assetId),[target+'Source']:{}});
this.syncRatio();this.saveDraft();if(this.data.mediaError)this.restoreMediaPreviews();
}catch(error){wx.showToast({title:error.message,icon:'none'});}
}},fail:()=>{if(valid())wx.showToast({title:'素材库打开失败，请重试',icon:'none'});}});
},
help() { wx.showModal({ title: '参考素材', content: '图片最多8张、视频最多3个、音频最多3个，合计最多12个。可从手机或素材库选择，生成前仍可移除。', showCancel: false, confirmColor: '#824019' }); },
optimize() {
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
if (!http.token()) return wx.showToast({ title: '请先登录后再优化提示词', icon: 'none' }); // 无 token：提示登录
// 提示词优化暂无在线通道：不再回落本地演示改写，如实提示
wx.showToast({ title: '提示词智能优化暂未开通，请手动调整画面描述', icon: 'none' });
},
async generate() {
if (this.data.invalidMessage||this.data.taskLoading||this.data.taskDraftBlocked||this.__draft?.conflicted()) return;
if(this.__videoSubmission)return this.generateOnline();
if(!/^\d+$/.test(this.data.durationInput)||Number(this.data.durationInput)<4||Number(this.data.durationInput)>15){this.setData({durationError:'请输入 4–15 的整数秒'});return;}
if (!this.data.description.trim()) { wx.showToast({ title: '请先填写画面描述', icon: 'none' }); return; }
if (this.data.mode === 'reference' && !this.data.references.length) { wx.showToast({ title: '请先添加参考素材', icon: 'none' }); return; }
await this.saveDraft();
if (http.token()) return this.generateOnline(); // 在线：直连后端创建 ai_video 任务
wx.showToast({ title: '请先登录后端账号后再生成视频', icon: 'none' });
},
async generateOnline(originalForm) {
const useOriginal=!!originalForm&&originalForm===this.__originalTaskForm;
if(!this.videoAlive()||this.data.generating||this.data.invalidMessage||this.data.taskLoading||this.data.taskDraftBlocked&&!useOriginal||this.__draft?.conflicted())return;
const submission=this.__videoSubmission,retrying=!!submission;
if(!retrying&&!useOriginal&&(!/^\d+$/.test(this.data.durationInput)||Number(this.data.durationInput)<4||Number(this.data.durationInput)>15)){this.setData({durationError:'请输入 4–15 的整数秒'});return;}
const sessionAlive=http.sessionGuard(),scope=this.actorScope;
const alive=()=>!this.__closed&&sessionAlive()&&scope===studio.scope();
this.setData({generating:true});
let submitted=false;
try {
if(retrying){
  const description=String(submission.payload.description||''),preview=description.slice(0,120)+(description.length>120?'…':'');
  const result=await new Promise(resolve=>wx.showModal({title:'按原内容重试',content:'如果原任务已创建，本次只恢复原任务；如果上次未提交成功，本次会按原内容创建任务并按当前规则计费。当前修改不参与本次重试。\n\n原文案：'+preview,confirmText:'确认重试',success:resolve,fail:()=>resolve({confirm:false})}));
  if(!result.confirm||!alive()||this.__videoSubmission!==submission||this.__draft?.conflicted())return;
}
// A replay resolves the frozen request before any new-form or material checks.
// The server returns an existing task first, and authorizes assets if it must create one.
if(!retrying){
const current=useOriginal?JSON.parse(JSON.stringify(originalForm)):this.videoForm();
// Mode switches retain both drafts, but only the visible mode's media participates in this task.
const selected=current.mode==='frames'?{...current,references:[]}:{...current,firstFrame:'',lastFrame:'',firstFrameAssetId:'',lastFrameAssetId:''};
const form=await this.prepareVideoMedia(selected);if(!alive())return;
const mapped = form.parameters;
const hasVisual=form.mode==='frames'?!!(form.firstFrame||form.lastFrame||form.firstFrameAssetId||form.lastFrameAssetId):form.references.some(r=>r.type==='image'||r.type==='video');
if(hasVisual)mapped.ratio='adaptive';
else if(mapped.ratio==='adaptive')throw Error('纯文生视频请选择具体画面比例');
const description = form.description.trim();
 const title = P.makeTaskTitle({ kind: 'ai_video', description });
 const payload = { kind: 'ai_video', title, description, resolution: mapped.resolution, duration: mapped.duration, ratio: mapped.ratio };
const inputs=form.mode==='reference'
  ? form.references.map(item=>({assetId:item.assetId,role:{image:'reference_image',video:'reference_video',audio:'reference_audio'}[item.type],name:item.name}))
  : [{assetId:form.firstFrameAssetId,role:'first_frame'},{assetId:form.lastFrameAssetId,role:'last_frame'}].filter(item=>item.assetId);
// The generic CONTENT_ASSET gate supports both phone uploads and library media; all IDs stay subject to current access checks.
for(const input of inputs){
  if(!input.role)throw Error('参考素材类型无效，请重新选择');
  if(input.assetId){
    const ticket=await contentApi.assets.downloadUrl(String(input.assetId));if(!alive())return;
    if(!ticket?.downloadUrl)throw Error('素材已不可用，请重新选择');
  }
}
  const references=[];
  for(const input of inputs){
    references.push(Object.assign({role:input.role,assetId:String(input.assetId)},input.name?{name:input.name}:{}));
  }
  if(references.length)payload.references=references;
  if(this.__draft?.conflicted())throw Error('请先选择要使用的草稿版本');
  this.__videoSubmission={payload,key:'video-'+Date.now()+'-'+Math.floor(Math.random()*1e8)};
}
if(!alive())return;
submitted=true;
const res=await contentApi.tasks.create(this.__videoSubmission.payload,this.__videoSubmission.key);
if(!alive())return;
const taskId = res && (res.taskId||res.id) ? String(res.taskId||res.id) : '';
if (!taskId) throw Error('任务创建失败，请稍后重试');
this.__videoSubmission=null;
this.setData({submissionPending:false});
wx.showToast({ title: '任务已提交，可在任务中心查看进度', icon: 'none' });
const nav = { url: '/pages/studio/index?view=tasks' };
if (getCurrentPages().length >= 9) wx.redirectTo(nav); else wx.navigateTo(nav);
}catch(error){if(alive()){
  const code=Number(error&&error.code);
  // A definite first-attempt rejection allows correction. A later error cannot
  // prove an uncertain original request was never accepted.
  if(!retrying&&submitted&&Number.isFinite(code)&&code>0&&code!==429&&(code<500||code>=600))this.__videoSubmission=null;
  this.setData({submissionPending:!!this.__videoSubmission});
  wx.showToast({ title: P.degradeMessage(error), icon: 'none' });
}}
finally{if(alive())this.setData({generating:false});}
},
returnToTasks() { wx.redirectTo({ url: '/pages/studio/index?view=tasks' }); },
noop() {}
});
