const api = require('../api');
const model = require('../model');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const apiScope = require('../../../utils/api-scope');
const { commandKey } = require('../../../utils/platform-crm');

// A lost receipt is retried with its original body even when the same stage is reopened.
const commands = new Map();
const empty = () => ({ rows: [], more: false, loading: false, loadError: '', canWrite: false,
  note: '', files: [], saving: false, uncertain: false, uploading: false, uploadName: '', uploadError: '',
  retryUploadAllowed: false, writeError: '', previewError: '' });
const denied = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001].includes(Number(error?.code));
const uncertain = error => {
  const code = Number(error?.code);
  return !Number.isFinite(code) || code <= 0 || (code >= 500 && code < 600);
};
function identity() {
  const post = auth.currentApiPost();
  return http.token() && post && String(post.postId) === String(http.currentPostId()) ? apiScope() : '';
}
function savedRow(row, customer, code) {
  if (!row || !model.id(row.id) || model.id(row.customerId) !== customer || row.stepCode !== code) throw Error('资料记录与当前步骤不符，请重新加载');
  return { id: model.id(row.id), note: String(row.note || ''),
    actorName: row.actorName || (model.id(row.actorUserId) ? '经办人 ' + model.id(row.actorUserId) : '经办人未留存'),
    createdText: model.dateTime(row.createdAt) || '时间未留存',
    attachments: (Array.isArray(row.attachments) ? row.attachments : []).filter(f => model.id(f?.assetId))
      .map(f => ({assetId:model.id(f.assetId), title:String(f.title || '原始附件')})) };
}

Component({
  properties: { customerId: String, stepCode: String },
  data: empty(),
  observers: { 'customerId, stepCode': function() { if (this._mounted) return this.refresh(); } },
  lifetimes: {
    attached() { this._mounted = true; this._visible = true; return this.refresh(); },
    detached() { this._mounted = false; this._visible = false; this._read = this._preview = null; this._drafts = null; }
  },
  pageLifetimes: {
    show() { this._visible = true; return this.refresh(); },
    hide() { this._visible = false; this._read = this._preview = null; this.setData({rows:[], canWrite:false, previewError:''}); }
  },
  methods: {
    same(context) {
      return !!context && this._mounted && identity() === context.identity && model.id(this.data.customerId) === context.customer
        && this.data.stepCode === context.code && this._context === context;
    },
    visible(context) { return this._visible && this.same(context); },
    context() {
      const user = identity(), customer = model.id(this.data.customerId), code = this.data.stepCode;
      if (!user || !customer || !model.definition(code)) return null;
      const key = JSON.stringify([user,customer,code]);
      if (this._context?.key === key) return this._context;
      if (!this._drafts || user !== this._context?.identity || customer !== this._context?.customer) this._drafts = new Map();
      for (const [id, cmd] of commands) if (cmd.identity !== user) commands.delete(id);
      this._context = {key,identity:user,customer,code};
      this._read = this._preview = this._watch = null;
      this._page = 0;
      this.setData(empty());
      return this._context;
    },
    draft(context) {
      if (!this._drafts.has(context.code)) this._drafts.set(context.code,{note:'',files:[],queue:[],uploading:false});
      return this._drafts.get(context.code);
    },
    projectDraft(context) {
      const draft = this.draft(context), cmd = commands.get(context.key), job = draft.queue[0];
      const body = cmd?.body || draft;
      this.setData({ note:body.note, files:cmd ? cmd.body.attachments : draft.files, saving:!!cmd?.request,
        uncertain:!!cmd && !cmd.request, uploading:draft.uploading, uploadName:job?.file.name || (job?'现场照片':''),
        uploadError:job?.error || '', retryUploadAllowed:!!job && !job.rejected });
      if (cmd?.request) this.watchSave(context,cmd,cmd.request);
    },
    clearAccess(context) {
      if (context) { commands.delete(context.key); this._drafts?.delete(context.code); }
      this._preview = this._read = this._watch = null;
      this.setData(empty());
    },
    refresh() { return this.loadPage(1); },
    async loadPage(page) {
      if (!this._mounted || !this._visible) return;
      const context = this.context();
      if (!context) { this.clearAccess(this._context); this._context = null; this._drafts = null; return; }
      if (this._read?.context === context && this._read.page === page) return this._read.promise;
      const request = this._read = {context,page};
      this.setData({loading:true,loadError:'', ...(page === 1 ? {canWrite:false} : {})});
      request.promise = (async () => {
        try {
          const result = await api.listSupplements(context.customer,context.code,page);
          if (!this.visible(context) || this._read !== request) return;
          if (!Array.isArray(result?.list)) throw Error('资料清单暂不可用，请重试');
          const next = result.list.map(r => savedRow(r,context.customer,context.code));
          const rows = [...new Map((page === 1 ? next : this.data.rows.concat(next)).map(row => [row.id,row])).values()];
          this._page = page;
          if (result.canWrite !== true) this.clearAccess(context);
          this.setData({rows, more:next.length > 0 && rows.length < Number(result.total), canWrite:result.canWrite === true,
            loading:false, loadError:''});
          if (result.canWrite === true) {
            this.projectDraft(context);
            const draft=this.draft(context);
            if (draft.queue.length && !draft.queue[0].error && !commands.has(context.key)) this.retryUpload();
          }
        } catch (error) {
          if (!this.visible(context) || this._read !== request) return;
          if (denied(error)) this.clearAccess(context);
          this.setData({loading:false,loadError:model.errorText(error)});
        } finally { if (this._read === request) this._read = null; }
      })();
      return request.promise;
    },
    moreSupplements() { if (this.data.more && !this.data.loading) return this.loadPage(this._page+1); },
    mayEdit(context) { return this.visible(context) && this.data.canWrite && !commands.has(context.key); },
    inputNote(e) {
      const context=this._context;
      if (!this.mayEdit(context)) return;
      const draft=this.draft(context);draft.note=String(e.detail.value || '').slice(0,4000);
      this.setData({note:draft.note,writeError:''});
    },
    chooseAttachment(e) {
      const context=this._context;
      if (!this.mayEdit(context)) return;
      const draft=this.draft(context), count=Math.min(9,20-draft.files.length);
      if (count<=0 || draft.queue.length) return;
      const options={count,success:result=>{
        if (!this.same(context) || !this._drafts || this._drafts.get(context.code)!==draft || commands.has(context.key)) return;
        for (const file of (result.tempFiles || []).slice(0,count)) draft.queue.push({file});
        if (this._visible && this.data.canWrite) return this.retryUpload();
      }, fail:error=>{
        if (this.visible(context) && !/cancel/i.test(error.errMsg || '')) this.setData({writeError:'选择资料失败，请重试'});
      }};
      if (e.currentTarget.dataset.kind==='photo') wx.chooseMedia({...options,mediaType:['image'],sourceType:['album','camera']});
      else wx.chooseMessageFile({...options,type:'file',extension:['pdf','jpg','jpeg','png']});
    },
    async retryUpload() {
      const context=this._context;
      if (!this.mayEdit(context)) return;
      const draft=this.draft(context); if (draft.uploading || !draft.queue.length) return;
      draft.uploading=true;
      const current=()=>this.same(context) && this._drafts?.get(context.code)===draft;
      try {
        while (current() && draft.queue.length) {
          let job=draft.queue[0];
          if (job.rejected) return;
          if (job.restartRequired) job=draft.queue[0]={file:job.file,bytes:job.bytes,mimeType:job.mimeType};
          job.error='';if (this.visible(context)) this.projectDraft(context);
          const file=await require('../upload').run(job,current);
          if (!current()) return;
          if (!draft.files.some(f=>f.assetId===file.assetId)) draft.files.push({assetId:file.assetId,title:String(file.title||'附件').slice(0,200)});
          draft.queue.shift();
        }
      } catch (error) {
        if (current() && denied(error)) { this.clearAccess(context); this.setData({loadError:model.errorText(error)}); }
        else if (current() && draft.queue[0]) draft.queue[0].error=model.errorText(error);
      }
      finally { draft.uploading=false; if (current() && this._visible) this.projectDraft(context); }
    },
    discardUpload() {
      const context=this._context;if (!this.mayEdit(context)) return;
      const draft=this.draft(context);if (draft.uploading) return;
      draft.queue.shift();this.projectDraft(context);if (draft.queue.length) return this.retryUpload();
    },
    removeAttachment(e) {
      const context=this._context;if (!this.mayEdit(context)) return;
      const draft=this.draft(context);draft.files=draft.files.filter(f=>f.assetId!==String(e.currentTarget.dataset.asset));
      this.projectDraft(context);
    },
    saveSupplement() {
      const context=this._context;if (!this.visible(context) || !this.data.canWrite) return;
      let cmd=commands.get(context.key);
      if (!cmd) {
        const draft=this.draft(context);if(draft.queue.length || draft.uploading)return;
        const note=draft.note.trim(),attachments=draft.files.map(f=>({...f}));
        if (!note && !attachments.length) { this.setData({writeError:'请填写说明或添加资料'});return; }
        cmd={key:commandKey('workflow-supplement'),identity:context.identity,body:{note,attachments},draft};
        commands.set(context.key,cmd);
      }
      if (!cmd.request) {
        cmd.request=api.addSupplement(context.customer,context.code,cmd.body,cmd.key)
          .then(value=>({row:savedRow(value,context.customer,context.code)}),error=>({error}))
          .catch(error=>({error}))
          .then(out=>{
            cmd.request=null;
            if (!out.error) { cmd.draft.note=''; cmd.draft.files=[]; }
            if (commands.get(context.key)===cmd && (!out.error || !uncertain(out.error))) commands.delete(context.key);
            return out;
          });
      }
      this.setData({saving:true,uncertain:false,writeError:''});
      return this.watchSave(context,cmd,cmd.request);
    },
    watchSave(context,cmd,request) {
      if (this._watch?.request===request) return this._watch.done;
      const watcher=this._watch={request};
      watcher.done=request.then(async ({row,error})=>{
        if(!this.same(context) || this._watch!==watcher)return;
        this._watch=null;
        if (error) {
          if (denied(error)) this.clearAccess(context);
          if (this.visible(context)) this.setData({saving:false,uncertain:commands.get(context.key)===cmd,writeError:model.errorText(error)});
          return;
        }
        this._drafts?.delete(context.code);
        if (!this.visible(context))return;
        this._read=null;this.projectDraft(context);
        this.setData({writeError:'',rows:[row,...this.data.rows.filter(r=>r.id!==row.id)]});
        wx.showToast({title:'补充资料已保存',icon:'success'});await this.refresh();
      });
      return watcher.done;
    },
    async openAttachment(e) {
      const context=this._context;if (!this.visible(context)) return;
      const {id,asset}=e.currentTarget.dataset;
      const file=this.data.rows.find(r=>r.id===String(id))?.attachments.find(f=>f.assetId===String(asset));
      if(!file)return;
      const request=this._preview={}; const alive=()=>this.visible(context)&&this._preview===request;
      this.setData({previewError:''});
      try {
        const ticket=await api.download(context.customer,context.code,file.assetId);if(!alive())return;
        const opened=await require('../../../utils/b-asset-download').downloadTicketFile({http,downloadUrl:ticket.downloadUrl,alive,label:'补充资料'});
        if(!alive())return;
        const fail=()=>{if(alive())this.setData({previewError:'附件未能打开，请重试'});};
        if (/^image\//.test(opened.contentType||'') || /\.(jpe?g|png)$/i.test(file.title)) wx.previewImage({urls:[opened.tempFilePath],fail});
        else wx.openDocument({filePath:opened.tempFilePath,showMenu:true,fail});
      } catch(error) {
        if(!alive())return;
        if(denied(error))this.clearAccess(context);
        this.setData({previewError:model.errorText(error)});
      }
    }
  }
});
