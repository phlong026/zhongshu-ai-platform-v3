const http = require('../../api/http');
const studio = require('../../utils/studio');
const { hashFile } = require('../../utils/sha256-stream');
const { putTicketBytes } = require('../../utils/b-asset-upload');
const { downloadTicketFile } = require('../../utils/b-asset-download');
const Audio = require('./characters/audio-preview');
const base = '/franchise/content/material-library';
const api = {
  page: query => http.get(base, query), upload: body => http.post(base + '/upload-tickets', body),
  complete: id => http.post(base + '/' + encodeURIComponent(id) + '/complete', {}),
  download: id => http.post(base + '/' + encodeURIComponent(id) + '/download-tickets', {}),
  update: (id, body) => http.put(base + '/' + encodeURIComponent(id), body),
  remove: id => http.del(base + '/' + encodeURIComponent(id))
};
const message = e => e && (e.msg || e.message || e.errMsg) || '暂时无法完成，请重试';
const call = (method, args) => new Promise((resolve, reject) => wx[method](Object.assign({}, args, {success:resolve, fail:reject})));
const kind = mime => String(mime).split('/')[0];
const size = bytes => bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
const pendingKey = page => 'zs-material-pending-v1:' + page.actorScope;
const alive = page => !page.__closed && !!http.token() && page.actorScope === studio.scope() && page.actorSessionGeneration === http.sessionGeneration();
function present(row) {
  const type = kind(row.mimeType);
  return Object.assign({}, row, {id:String(row.id), type, sizeText:size(row.sizeBytes), dateText:String(row.createdAt || '').slice(0,10).replace(/-/g,'.'),
    typeText:{image:'图片',video:'视频',audio:'音频'}[type] || '素材', durationText:Audio.time(row.durationSec), coverUrl:'', coverError:false});
}
async function source(page, id, guard) {
  const ticket = await api.download(id);
  if (!guard()) throw {cancelled:true};
  // B proxy tickets need session headers; a signed external URL must never receive those headers.
  if (/^https:\/\//i.test(ticket.downloadUrl) && !String(ticket.downloadUrl).startsWith(String(http.base()).replace(/\/admin-api\/?$/, '') + '/')) return ticket.downloadUrl;
  return (await downloadTicketFile({http,downloadUrl:ticket.downloadUrl,alive:guard,label:'素材'})).tempFilePath;
}
const data = {
  materialScope:'PERSONAL', materialType:'ALL', materialKeyword:'', materialQuery:'', materialItems:[], materialPage:0,
  materialTotal:0, materialLoading:false, materialError:'', materialManage:false, materialBusy:false,
  materialUploadPhase:'', materialUploadName:'', materialUploadError:'', materialPending:false,
  materialPreview:null, materialPreviewUrl:'', materialPreviewLoading:false, materialPreviewError:'',
  materialTypes:[{id:'ALL',label:'全部'},{id:'IMAGE',label:'图片'},{id:'VIDEO',label:'视频'},{id:'AUDIO',label:'音频'}],
  previewAudioPlaying:false, previewAudioLoading:false, previewAudioPosition:'00:00', previewAudioDuration:'00:00', previewAudioProgress:0, previewAudioError:''
};
const handlers = {
  materialInit() {
    if (!alive(this)) return;
    const pending = wx.getStorageSync(pendingKey(this));
    if (pending && pending.id) { this.__materialPending=pending;this.setData({materialPending:true,materialUploadName:pending.name,materialUploadError:'素材已上传，待确认保存'}); }
    if (!this.data.materialLoading && !this.__materialChoosing && !this.data.materialBusy) return this.loadMaterials();
  },
  async loadMaterials(more) {
    if (!alive(this) || (more === true && this.data.materialLoading)) return;
    more = more === true;
    const seq=this.__materialSeq=(this.__materialSeq||0)+1;
    const valid=()=>alive(this)&&seq===this.__materialSeq;
    const page=more?this.data.materialPage+1:1;
    this.setData(Object.assign({materialLoading:true,materialError:''},more?{}:{materialItems:[],materialPage:0,materialTotal:0}));
    try {
      const result=await api.page({scope:this.data.materialScope,type:this.data.materialType,keyword:this.data.materialQuery,page});
      if (!valid()) return;
      const rows=(result.items||[]).map(present), old=more?this.data.materialItems:[];
      const items=old.concat(rows.filter(r=>!old.some(o=>o.id===r.id)));
      this.setData({materialItems:items,materialPage:page,materialTotal:Number(result.total)||0,materialLoading:false});
      // Limit concurrent thumbnail requests. Failed images have a recoverable, honest fallback.
      let next=0;
      await Promise.all([0,1,2,3].map(async()=>{
        while (next<rows.length && valid()) {
          const row=rows[next++], id=row.type==='image'?row.id:row.coverId;
          if (!id) continue;
          const index=items.findIndex(item=>item.id===row.id);
          try {const url=await source(this,id,valid);if(valid())this.setData({['materialItems['+index+'].coverUrl']:url});}
          catch(e) {if(valid())this.setData({['materialItems['+index+'].coverError']:true});}
        }
      }));
    } catch(e) {if(valid())this.setData({materialLoading:false,materialError:message(e)});}
  },
  materialScopeChange(e) {
    const scope=e.currentTarget.dataset.scope;if(scope===this.data.materialScope)return;
    this.closeMaterialPreview();this.setData({materialScope:scope,materialManage:false});this.loadMaterials();
  },
  materialTypeChange(e) {this.setData({materialType:e.currentTarget.dataset.type});this.loadMaterials();},
  materialSearchInput(e) {this.setData({materialKeyword:e.detail.value});clearTimeout(this.__materialSearch);this.__materialSearch=setTimeout(()=>{if(alive(this))this.materialSearch();},350);},
  materialSearch() {clearTimeout(this.__materialSearch);this.setData({materialQuery:this.data.materialKeyword.trim()});this.loadMaterials();},
  materialClearSearch() {this.setData({materialKeyword:'',materialQuery:''});this.loadMaterials();},
  materialMore() {if(this.data.materialItems.length<this.data.materialTotal)return this.loadMaterials(true);},
  materialManageToggle() {this.setData({materialManage:!this.data.materialManage});},
  materialCoverError(e) {const i=this.data.materialItems.findIndex(r=>r.id===String(e.currentTarget.dataset.id));if(i>=0)this.setData({['materialItems['+i+'].coverUrl']:'',['materialItems['+i+'].coverError']:true});},
  async openMaterial(e) {
    const item=this.data.materialItems.find(r=>r.id===String(e.currentTarget.dataset.id));if(!item)return;
    if(this.data.materialManage){if(item.canManage)return this.manageMaterial(item);return;}
    this.closeMaterialPreview();
    const seq=this.__materialPreviewSeq=(this.__materialPreviewSeq||0)+1,valid=()=>alive(this)&&seq===this.__materialPreviewSeq;
    this.setData({materialPreview:item,materialPreviewUrl:'',materialPreviewLoading:true,materialPreviewError:''});
    try {
      const url=await source(this,item.id,valid);if(!valid())return;
      if(item.type==='image') {this.setData({materialPreview:null,materialPreviewLoading:false});await call('previewImage',{current:url,urls:[url]});return;}
      this.setData({materialPreviewUrl:url,materialPreviewLoading:false});
      if(item.type==='audio')this.materialToggleAudio();
    } catch(e) {if(valid())this.setData({materialPreviewLoading:false,materialPreviewError:message(e)});}
  },
  retryMaterialPreview() {if(this.data.materialPreview)return this.openMaterial({currentTarget:{dataset:{id:this.data.materialPreview.id}}});},
  materialToggleAudio() {
    if(!this.__materialAudio)this.__materialAudio=Audio.create({createAudio:()=>wx.createInnerAudioContext(),valid:()=>alive(this)&&!!this.data.materialPreview,onChange:patch=>this.setData(patch)});
    if(this.data.materialPreview)this.__materialAudio.toggle(this.data.materialPreview.id,this.data.materialPreviewUrl);
  },
  materialSeekAudio(e) {if(this.__materialAudio&&this.data.materialPreview)this.__materialAudio.seek(this.data.materialPreview.id,e.detail.value);},
  materialMediaError() {this.setData({materialPreviewError:'暂时无法播放，请重试'});},
  closeMaterialPreview() {
    this.__materialPreviewSeq=(this.__materialPreviewSeq||0)+1;
    if(this.__materialAudio){this.__materialAudio.destroy();this.__materialAudio=null;}
    if(this.data.materialPreview&&this.data.materialPreview.type==='video'){try{wx.createVideoContext('material-video',this).stop();}catch(e){}}
    this.setData({materialPreview:null,materialPreviewUrl:'',materialPreviewError:'',materialPreviewLoading:false,previewAudioError:'',previewAudioPlaying:false});
  },
  materialHide() {if(this.__materialAudio)this.__materialAudio.pause();if(this.data.materialPreview&&this.data.materialPreview.type==='video'){try{wx.createVideoContext('material-video',this).pause();}catch(e){}}},
  materialUnload() {clearTimeout(this.__materialSearch);this.__materialSeq=(this.__materialSeq||0)+1;this.closeMaterialPreview();},
  async manageMaterial(item) {
    if(this.data.materialBusy||!alive(this))return;
    const valid=()=>alive(this), shared=item.visibility==='COMPANY';
    try {
      const choice=await call('showActionSheet',{itemList:['重命名',shared?'取消公司共享':'共享到公司','移出素材库']});if(!valid())return;
      let mutation;
      if(choice.tapIndex===0) {
        const result=await call('showModal',{title:'重命名素材',editable:true,content:item.name,placeholderText:'输入素材名称'});
        if(!valid()||!result.confirm)return;
        const name=String(result.content||'').trim();if(!name||name.length>100)throw Error('请输入 1–100 个字符的名称');
        mutation=()=>api.update(item.id,{name});
      } else if(choice.tapIndex===1) {
        const result=await call('showModal',{title:shared?'取消共享':'共享到公司',content:shared?'取消后，仅你可以从素材库查看此素材。':'同公司成员将可以查看此素材。',confirmText:shared?'取消共享':'确认共享'});
        if(!valid()||!result.confirm)return;
        mutation=()=>api.update(item.id,{visibility:shared?'PERSONAL':'COMPANY'});
      } else if(choice.tapIndex===2) {
        const result=await call('showModal',{title:'移出素材库',content:'移出后将不再显示，已引用此素材的创作任务不受影响。',confirmText:'确认移出'});
        if(!valid()||!result.confirm)return;mutation=()=>api.remove(item.id);
      }
      if(!mutation)return;this.setData({materialBusy:true});await mutation();
      if(valid()){wx.showToast({title:'已更新',icon:'success'});await this.loadMaterials();}
    } catch(e) {if(valid()&&!/cancel/i.test(message(e)))wx.showToast({title:message(e),icon:'none'});}
    finally {if(valid())this.setData({materialBusy:false});}
  },
  async materialChooseUpload() {
    if(this.__materialChoosing||this.data.materialBusy||!alive(this))return;
    if(this.data.materialPending)return this.materialRetryComplete();
    this.__materialChoosing=true;const valid=()=>alive(this),scope=this.data.materialScope;
    try {
      const action=await call('showActionSheet',{itemList:['上传图片','上传视频','上传音频（MP3）']});if(!valid())return;
      if(scope==='COMPANY'){const yes=await call('showModal',{title:'上传到公司素材',content:'同公司成员可以查看本次上传的素材。',confirmText:'继续上传'});if(!valid()||!yes.confirm)return;}
      let file;
      if(action.tapIndex===2){const result=await call('chooseMessageFile',{count:1,type:'file',extension:['mp3']});file=result.tempFiles[0];}
      else {const result=await call('chooseMedia',{count:1,mediaType:[action.tapIndex===0?'image':'video'],sourceType:['album','camera'],sizeType:['compressed'],maxDuration:60});file=result.tempFiles[0];}
      if(!valid()||!file)return;
      file.path=file.path||file.tempFilePath;
      const type=action.tapIndex===0?'image':action.tapIndex===1?'video':'audio';
      const name=file.name||({image:'图片',video:'视频',audio:'音频'}[type]+'_'+new Date().toISOString().slice(0,19).replace(/[:T]/g,'-'));
      this.setData({materialBusy:true,materialUploadName:name,materialUploadPhase:'正在检查素材',materialUploadError:''});
      const id=await this.materialUploadFile(file,type,name,scope,valid);
      if(!valid())return;
      // The main asset is already saved; optional cover failure must never make it disappear.
      if(type==='video'&&file.thumbTempFilePath){try{await this.materialUploadFile({path:file.thumbTempFilePath},'image','视频封面',scope,valid,id);}catch(e){}}
      if(valid()){this.setData({materialUploadPhase:'上传完成',materialUploadError:'',materialScope:scope,materialType:'ALL',materialQuery:'',materialKeyword:''});await this.loadMaterials();wx.showToast({title:'上传完成',icon:'success'});}
    } catch(e) {if(valid()&&!/cancel/i.test(message(e)))this.setData({materialUploadPhase:'',materialUploadError:message(e)});}
    finally {this.__materialChoosing=false;if(valid())this.setData({materialBusy:false});}
  },
  async materialUploadFile(file,type,name,scope,valid,coverOf) {
    const fs=wx.getFileSystemManager();
    const fileSize=Number(file.size)||Number(fs.statSync(file.path).size);
    if(!fileSize||fileSize>50*1024*1024)throw Error('单个素材须小于 50MB');
    const bytes=await new Promise((resolve,reject)=>fs.readFile({filePath:file.path,success:r=>resolve(r.data),fail:reject}));
    if(!valid())throw {cancelled:true};
    const length=bytes.byteLength;if(!length||length>50*1024*1024)throw Error('单个素材须小于 50MB');
    const head=new Uint8Array(bytes,0,Math.min(12,length));
    const mime=type==='image'?(head[0]===137&&head[1]===80?'image/png':head[0]===255&&head[1]===216?'image/jpeg':''):type==='video'?'video/mp4':'audio/mpeg';
    if(!mime)throw Error('请选择 JPG 或 PNG 图片');
    const sha256=await hashFile(fs,file.path,length,valid);if(!valid())throw {cancelled:true};
    const ticket=await api.upload({name,visibility:scope,mimeType:mime,sizeBytes:length,sha256,durationSec:Math.max(0,Math.floor(Number(file.duration)||0)),...(coverOf?{coverOf}:{})});
    if(!valid())throw {cancelled:true};
    if(!coverOf)this.setData({materialUploadPhase:'正在上传素材'});
    await putTicketBytes({http,uploadUrl:ticket.uploadUrl,bytes,mimeType:mime,alive:valid,label:'素材'});
    if(!valid())throw {cancelled:true};
    if(!coverOf){this.__materialPending={id:String(ticket.assetId),name};wx.setStorageSync(pendingKey(this),this.__materialPending);this.setData({materialPending:true,materialUploadPhase:'正在确认保存'});}
    await api.complete(ticket.assetId);if(!valid())throw {cancelled:true};
    if(!coverOf){wx.removeStorageSync(pendingKey(this));this.__materialPending=null;this.setData({materialPending:false});}
    return String(ticket.assetId);
  },
  async materialRetryComplete() {
    const pending=this.__materialPending;if(!pending||this.data.materialBusy||!alive(this))return;
    this.setData({materialBusy:true,materialUploadPhase:'正在确认保存',materialUploadError:''});
    try {await api.complete(pending.id);if(!alive(this))return;wx.removeStorageSync(pendingKey(this));this.__materialPending=null;this.setData({materialPending:false,materialUploadPhase:'上传完成'});await this.loadMaterials();}
    catch(e){if(alive(this))this.setData({materialUploadPhase:'',materialUploadError:message(e)});}
    finally{if(alive(this))this.setData({materialBusy:false});}
  },
  async materialDiscardPending() {
    const pending=this.__materialPending;if(!pending||this.data.materialBusy||!alive(this))return;
    try {
      const result=await call('showModal',{title:'放弃本次上传',content:'本次素材将移出素材库，你可以重新选择文件上传。',confirmText:'确认放弃'});
      if(!result.confirm||!alive(this))return;
      this.setData({materialBusy:true});await api.remove(pending.id);
      if(!alive(this))return;
      wx.removeStorageSync(pendingKey(this));this.__materialPending=null;
      this.setData({materialPending:false,materialUploadPhase:'',materialUploadName:'',materialUploadError:''});
    }catch(e){if(alive(this))this.setData({materialUploadError:message(e)});}
    finally{if(alive(this))this.setData({materialBusy:false});}
  }
};
module.exports={data,handlers,present};
