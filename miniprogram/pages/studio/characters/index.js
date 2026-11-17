const api = require('../../../api/franchise/content');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const studio = require('../../../utils/studio');
const P = require('../../../utils/platform-content');
const Draft = require('./profile-draft');
const DraftApi = require('./draft-api');
const FIELDS = [
  { key: 'name', label: '姓名', max: 64, required: true, example:'例如：张工' },
  { key: 'who', label: '身份', max: 200, example:'例如：泸州乡墅设计师' },
  { key: 'sell', label: '服务范围', max: 500, multiline: true, example:'例如：自建房设计、施工与预算规划' },
  { key: 'audience', label: '目标人群', max: 200, example:'例如：准备回乡建房的家庭' },
  { key: 'expressionStyle', label: '表达特点', max: 500, example:'例如：专业、通俗、不过度承诺' }
];
const EXTRA = [
  { key: 'audienceNeeds', label: '客户关注', max: 600, example:'例如：预算怎么分配、户型是否实用、施工如何避坑' },
  { key: 'factualBackground', label: '真实资料', max: 2000, example:'例如：已核实的本地项目、服务或资质（注明来源）' },
  { key: 'sampleScript', label: '代表口播', max: 2000, example:'例如：回乡建房，先把预算和家人的需求想清楚。' },
  { key: 'forbiddenClaims', label: '禁用表达', max: 600, example:'例如：不承诺最低价、不保证固定工期、不虚构案例' }
];
const LEGACY = ['cta', 'ipType', 'track', 'differentiator'];
const TITLES = { home:'人物库', edit:'新增人物', supplement:'补充定位', detail:'人物详情', avatar:'口播分身', voice:'语音克隆', resources:'人物资源' };
const emptyForm = () => Object.assign({ enabled: true }, ...FIELDS.concat(EXTRA).map(f => ({ [f.key]: '' })));

Page({
  data: { view:'home',pageTitle:'人物库',rows: [], filtered: [], keyword: '', loading: false, saving: false, error: '', editing: false, editId: '', form: {}, fields: FIELDS, extraFields:EXTRA, canCreate:false, canManage: false,canUseDigital:false,cloneFileName:'',cloneUploadStatus:'',draftStatus:'',draftConfirming:false,
    digitalPersona: null, digitalPeople: [], digitalPersonIndex: 0, cloneEntryKind: '', cloneRows: [], cloneLoading: false, cloneBusy: false, cloneError: '', cloneKind: 'AVATAR', cloneTitle: '', cloneUploadId: '', cloneConsented: false, cloneQuote: null, cloneRetry: false,expressionTags:[],profileExpanded:false,
    cloneStatus: { SUBMITTING:'提交确认中', RUNNING:'制作中', READY:'已就绪', FAILED:'制作失败', UNKNOWN:'结果待核实' } },
  onLoad() { if (!studio.guard()) return; this.__closed=false;this.__scope = studio.scope();this.__generation=http.sessionGeneration();this.__draftNetwork=e=>{if(e.isConnected&&this.__draft&&this.alive())this.__draft.flush().catch(()=>{});};if(wx.onNetworkStatusChange)wx.onNetworkStatusChange(this.__draftNetwork); },
  onShow() {
    if (!studio.guard()) return;
    const post = auth.currentApiPost();
    this.__hidden=false;
    if(this.__scope!==studio.scope()||this.__generation!==http.sessionGeneration()) {this.stopRefresh();this.__cloneAttempt=null;this.__pendingCloneKind=null;if(this.__audio)this.__audio.destroy();this.setData({view:'home',pageTitle:'人物库',rows:[],filtered:[],editing:false,form:{},editId:'',digitalPersona:null,digitalPeople:[],cloneEntryKind:'',cloneRows:[],cloneBusy:false,cloneError:'',cloneRetry:false});}
    this.__scope = studio.scope();
    this.__generation=http.sessionGeneration();
    this.setData({ canCreate: !!post && ['sales','sales-lead','boss','owner'].includes(post.roleCode), canManage: this.canEditPersona(this.data.digitalPersona) });
    if(this.__draft&&!this.data.editing){this.__draft.stop();this.__draft=null;}
    if(this.__draft&&this.data.editing)this.__draft.flush().catch(()=>{});
    // 素材选择会触发 onShow，不能清空尚未保存的定位或制作表单。
    if(!this.data.editing&&!this.data.cloneBusy)this.load();
    if(this.data.digitalPersona&&!this.data.cloneBusy)this.loadClones();
  },
  onUnload() {this.flushDraft(true);this.__closed = true;this.stopRefresh();if(wx.offNetworkStatusChange&&this.__draftNetwork)wx.offNetworkStatusChange(this.__draftNetwork); if(this.__audio)this.__audio.destroy(); },
  onHide() {this.flushDraft();this.__hidden=true;this.stopRefresh(); if(this.__audio)this.__audio.stop(); },
  alive() { return !this.__closed && !!http.token() && this.__scope === studio.scope()&&this.__generation===http.sessionGeneration(); },
  showView(view) {this.stopRefresh();this.setData({view,pageTitle:view==='edit'&&this.data.editId?'编辑定位':TITLES[view],error:''});if(wx.pageScrollTo)wx.pageScrollTo({scrollTop:0,duration:0});},
  back() {if(this.data.saving||this.data.cloneBusy)return;if(this.data.view==='supplement')return this.showView('edit');if(this.data.view==='edit')return this.close();this.__cloneAttempt=null;this.setData({cloneRetry:false});this.showView(this.data.view==='detail'?'home':this.data.digitalPersona?'detail':'home');},
  detail(e) {const row=this.data.rows.find(r=>r.id===String(e.currentTarget.dataset.id));if(row&&this.alive()){this.setData({digitalPersona:row,canManage:this.canEditPersona(row),profileExpanded:false});this.showView('detail');}},
  toggleProfile() {this.setData({profileExpanded:!this.data.profileExpanded});},
  supplement() {if(this.data.editing&&!this.data.saving)this.showView('supplement');},
  resources() {if(this.data.digitalPersona){this.showView('resources');this.loadClones();}},
  async load() {
    if (!http.token()) { this.setData({ rows: [], filtered: [], loading: false, error: '请先登录后使用人物库' }); return; }
    const guard = http.requestGuard('character-library');
    this.setData({ loading: true, error: '' });
    try {
      const response = await api.personas.page();
      if (!guard() || !this.alive()) return;
      const rows = P.rowsOf(response).filter(r => r && r.id != null && r.mock!==true && r.demo!==true).map(r => Object.assign({}, r, { id: String(r.id), expressionStyle:String(r.expressionStyle==null?r.differentiator||'':r.expressionStyle),enabled: r.enabled !== false }));
      this.setData({ rows, digitalPeople: rows.filter(row => row.enabled), loading: false }); this.filter();
      if(this.data.digitalPersona){const selected=rows.find(r=>r.id===this.data.digitalPersona.id);if(selected)this.setData({digitalPersona:selected,canManage:this.canEditPersona(selected),canUseDigital:selected.enabled&&this.readyPair()});else {this.setData({digitalPersona:null,cloneRows:[],canUseDigital:false});this.showView('home');}}
      return rows;
    } catch (e) { if (guard() && this.alive()) this.setData({ rows: [], filtered: [], digitalPeople:[],loading: false, error: P.degradeMessage(e) }); }
  },
  search(e) { this.setData({ keyword: e.detail.value }); this.filter(); },
  filter() { const word = String(this.data.keyword || '').trim(); this.setData({ filtered: this.data.rows.filter(r => !word || [r.name, r.who, r.track].some(v => String(v || '').includes(word))) }); },
  canEditPersona(row) {
    const post=auth.currentApiPost();
    return !!row && !!post && (row.canManage===true || row.canManage!==false && ['sales-lead','boss','owner'].includes(post.roleCode));
  },
  edit(e) {
    if (!this.alive()||this.data.saving) return;
    const id = String(e.currentTarget.dataset.id || '');
    const row = id && this.data.rows.find(r => r.id === id);
    if (id ? !this.canEditPersona(row) : !this.data.canCreate) return;
    const form = emptyForm();
    if (row) { FIELDS.concat(EXTRA).forEach(f => { form[f.key] = String(row[f.key] || ''); });LEGACY.forEach(k=>{form[k]=String(row[k]||'');}); form.enabled = row.enabled; }
    this.setData({ editing: true, editId: id, form, error: '' });
    this.styleTags(form.expressionStyle);
    this.showView('edit');
    this.openDraft(id,form);
  },
  openDraft(id,form) {
    if(this.__draft)this.__draft.stop();
    const post=auth.currentApiPost(),scope=studio.scope(),generation=http.sessionGeneration();
    const key=id||'new',storageKey=studio.scopedKey('zs-persona-draft:'+String(http.tenantId())+':'+post.orgId+':'+post.userId+':'+key);
    const valid=()=>!!http.token()&&scope===studio.scope()&&generation===http.sessionGeneration();
    let draft;
    draft=Draft.create({api:DraftApi(http),storage:wx,storageKey,key,form,valid,
      onForm:value=>{if(this.__draft===draft&&this.data.editing&&!this.__closed){this.setData({form:value});this.styleTags(value.expressionStyle);}},
      onStatus:text=>{if(this.__draft===draft&&!this.__closed)this.setData({draftStatus:text});},
      onLock:value=>{if(this.__draft===draft&&!this.__closed)this.setData({draftConfirming:value});}});
    this.__draft=draft;this.setData({draftStatus:'',draftConfirming:false});draft.start().catch(()=>{});
  },
  changedDraft() {if(this.__draft)this.__draft.change(this.data.form);},
  flushDraft(stop) {if(this.__draft){const draft=this.__draft;draft.flush().catch(()=>{});if(stop)draft.stop();}},
  async retryDraft() {
    const draft=this.__draft;if(!draft||this.data.saving||this.data.draftConfirming)return;
    if(draft.conflicted())return wx.showActionSheet({itemList:['保留当前填写','使用云端草稿'],success:result=>{
      if(this.__draft===draft&&this.alive())draft.resolve(result.tapIndex===0).catch(e=>this.setData({error:P.degradeMessage(e)}));}});
    try {await draft.flush();}catch(e){if(this.alive())this.setData({error:P.degradeMessage(e)});}
  },
  input(e) {
    if(this.data.saving||this.data.draftConfirming)return;
    const key = e.currentTarget.dataset.key;
    if (FIELDS.concat(EXTRA).some(f => f.key === key)) this.setData({ ['form.' + key]: e.detail.value,error:'' });
    if(key==='expressionStyle')this.styleTags(e.detail.value);
    this.changedDraft();
  },
  styleTags(value) {const values=String(value||'').split(/[、，,；;\s]+/).filter(Boolean);this.setData({expressionTags:['专业','通俗','不过度承诺'].map(label=>({label,selected:values.includes(label)}))});},
  toggleStyle(e) {if(this.data.saving||this.data.draftConfirming)return;const value=e.currentTarget.dataset.value;if(!['专业','通俗','不过度承诺'].includes(value))return;const values=String(this.data.form.expressionStyle||'').split(/[、，,；;\s]+/).filter(Boolean);const next=values.includes(value)?values.filter(v=>v!==value):values.concat(value);const text=next.join('、');if(text.length<=500){this.setData({'form.expressionStyle':text});this.styleTags(text);this.changedDraft();}},
  enabled(e) {if(this.data.saving||this.data.draftConfirming)return;this.setData({ 'form.enabled': !!e.detail.value });this.changedDraft();},
  close() { if (!this.data.saving) {this.flushDraft(true);this.__draft=null;this.__pendingCloneKind=null;this.setData({ editing: false, form: {}, editId: '', error: '',draftStatus:'',draftConfirming:false });this.showView(this.data.digitalPersona?'detail':'home'); } },
  async save() {
    if (!this.alive() || this.data.saving || (this.data.editId ? !this.canEditPersona(this.data.rows.find(row=>row.id===this.data.editId)) : !this.data.canCreate)) return;
    const body = { enabled: !!this.data.form.enabled };
    for (const field of FIELDS.concat(EXTRA)) {
      const value = String(this.data.form[field.key] || '').trim();
      if ((field.required && !value) || value.length > field.max) { this.setData({ error: field.required && !value ? '请填写姓名' : field.label + '最多' + field.max + '字' }); return; }
      body[field.key] = value;
    }
    LEGACY.forEach(k=>{if(Object.prototype.hasOwnProperty.call(this.data.form,k))body[k]=this.data.form[k];});
    const guard = http.requestGuard('character-save');
    this.setData({ saving: true, error: '' });
    try {
      if(!this.__draft)throw Error('请重新打开定位编辑页');
      if(!this.data.draftConfirming)this.__draft.change(body);
      const saved = await this.__draft.publish();
      if (!guard() || !this.alive()) return;
      if(!saved||saved.id==null)throw Error('保存结果待确认，请刷新人物库');
      this.__draft=null;
      this.setData({ saving: false, editing: false, form: {}, editId: '',digitalPersona:{...saved,id:String(saved.id),enabled:saved.enabled!==false},canManage:this.canEditPersona(saved) });
      this.showView('detail');
      const rows=await this.load();
      if(!guard()||!this.alive())return;
      if(this.__pendingCloneKind&&saved&&rows&&rows.some(row=>row.id===String(saved.id)&&row.enabled)) {
        const kind=this.__pendingCloneKind;this.__pendingCloneKind=null;
        this.openDigital({currentTarget:{dataset:{id:String(saved.id),kind}}});
      }
    } catch (e) { if (guard() && this.alive()) this.setData({ saving: false, error: P.degradeMessage(e) }); }
  },
  use(e) {
    if (!this.alive()) return;
    const id=e&&e.currentTarget.dataset.id||this.data.digitalPersona&&this.data.digitalPersona.id;
    const row = this.data.rows.find(r => r.id === String(id));
    if (row && row.enabled) wx.navigateTo({ url: '/pages/studio/index?view=remix&persona=' + encodeURIComponent(row.id) });
  },
  openCloneEntry(e) {
    if(!this.alive()||this.data.cloneBusy||this.data.cloneRetry||this.data.loading||this.data.saving)return;
    const kind=e.currentTarget.dataset.kind;if(!['AVATAR','VOICE'].includes(kind))return;
    this.setData({cloneEntryKind:kind,keyword:''});this.filter();
    const people=this.data.rows.filter(row=>row.enabled&&this.canEditPersona(row));
    if(people.length) {
      const selected=people.find(row=>this.data.digitalPersona&&row.id===this.data.digitalPersona.id)||people[0];
      this.openDigital({currentTarget:{dataset:{id:selected.id,kind}}});
    } else if(this.data.canCreate&&!this.data.error) {
      this.__pendingCloneKind=kind;
      this.edit({currentTarget:{dataset:{}}});
    }else wx.showToast({title:'请联系管理员新增人物',icon:'none'});
  },
  chooseDigitalPerson(e) {
    if(this.data.cloneBusy||this.data.cloneRetry)return;
    const resources=this.data.view==='resources';
    const person=this.data.digitalPeople[Number(e.detail.value)];
    if(person){this.openDigital({currentTarget:{dataset:{id:person.id,kind:this.data.cloneKind}}});if(resources)this.showView('resources');}
  },
  openDigital(e) {
    if(!this.alive()||this.data.cloneBusy||this.data.cloneRetry)return;
    const id=e.currentTarget.dataset.id||this.data.digitalPersona&&this.data.digitalPersona.id;
    const row=this.data.rows.find(r=>r.id===String(id));if(!row)return;
    const kind=['AVATAR','VOICE'].includes(e.currentTarget.dataset.kind)?e.currentTarget.dataset.kind:'AVATAR';
    this.setData({digitalPersona:row,canManage:this.canEditPersona(row),digitalPersonIndex:Math.max(0,this.data.digitalPeople.findIndex(person=>person.id===row.id)),cloneEntryKind:kind,cloneRows:[],cloneKind:kind,cloneTitle:row.name.slice(0,20),cloneUploadId:'',cloneConsented:false,cloneQuote:null,cloneRetry:false,cloneError:''});
    const attempt=wx.getStorageSync(studio.scopedKey('zs-clone-attempt:'+row.id));
    this.__cloneAttempt=attempt&&attempt.key&&attempt.body?attempt:null;
    if(this.__cloneAttempt){const body=attempt.body;this.setData({cloneKind:body.kind,cloneTitle:body.title,cloneUploadId:body.sourceUploadId||'',cloneConsented:true,cloneRetry:true});}
    this.showView(this.data.canManage&&row.enabled?(this.data.cloneKind==='AVATAR'?'avatar':'voice'):'resources');
    this.loadClones();if(this.data.canManage)this.loadQuote();
  },
  async loadClones() {
    const person=this.data.digitalPersona;if(!person)return;
    const guard=http.requestGuard('character-clones');this.setData({cloneLoading:true,cloneError:''});
    try{const rows=await api.clones.list(person.id);if(guard()&&this.alive()&&this.data.digitalPersona&&this.data.digitalPersona.id===person.id){this.setData({cloneRows:P.rowsOf(rows).filter(r=>r&&r.id!=null&&r.mock!==true&&r.demo!==true).map(r=>({...r,id:String(r.id)})),cloneLoading:false});this.setData({canUseDigital:person.enabled&&this.readyPair()});this.scheduleRefresh();}}
    catch(e){if(guard()&&this.alive()&&this.data.digitalPersona&&this.data.digitalPersona.id===person.id)this.setData({cloneLoading:false,cloneError:P.degradeMessage(e)});}
  },
  async loadQuote() {
    const kind=this.data.cloneKind,guard=http.requestGuard('clone-quote');this.setData({cloneQuote:null});
    try{const quote=await api.clones.quote(kind);if(guard()&&this.alive()&&kind===this.data.cloneKind)this.setData({cloneQuote:quote});}
    catch(e){if(guard()&&this.alive())this.setData({cloneQuote:{available:false,message:P.degradeMessage(e)}});}
  },
  chooseKind(e) {this.openDigital(e);},
  readyPair() {return this.data.cloneRows.some(r=>r.ready&&r.kind==='AVATAR')&&this.data.cloneRows.some(r=>r.ready&&r.kind==='VOICE');},
  async setDefaultClone(e) {
    if(!this.data.canManage||!this.alive()||this.data.cloneBusy||this.data.cloneLoading||this.data.cloneRetry)return;
    const person=this.data.digitalPersona,id=String(e.currentTarget.dataset.id);
    const row=this.data.cloneRows.find(row=>String(row.id)===id);
    if(!person||!person.enabled||!row||!row.ready||row.isDefault)return;
    const guard=http.requestGuard('clone-default');this.setData({cloneBusy:true,cloneError:''});
    try{
      await api.clones.setDefault(id);
      if(!guard()||!this.alive()||this.data.digitalPersona?.id!==person.id)return;
      this.setData({cloneBusy:false});await this.loadClones();
    }catch(error){if(guard()&&this.alive()&&this.data.digitalPersona?.id===person.id)this.setData({cloneBusy:false,cloneError:P.degradeMessage(error)});}
  },
  stopRefresh() {if(this.__refreshTimer)clearTimeout(this.__refreshTimer);this.__refreshTimer=null;},
  scheduleRefresh() {this.stopRefresh();if(this.__hidden||this.data.view!=='resources'||!this.data.cloneRows.some(r=>['SUBMITTING','RUNNING'].includes(r.status)||(r.status==='READY'&&!r.ready)))return;this.__refreshTimer=setTimeout(()=>this.refreshAll(),8000);},
  async refreshAll() {
    if(!this.alive()||this.data.cloneBusy||!this.data.digitalPersona)return;
    const person=this.data.digitalPersona.id,guard=http.requestGuard('clone-refresh');this.setData({cloneBusy:true,cloneError:''});
    try{for(const row of this.data.cloneRows.filter(r=>r.status!=='FAILED'&&!(r.status==='READY'&&r.ready))){if(!guard()||!this.alive()||!this.data.digitalPersona||this.data.digitalPersona.id!==person)return;await api.clones.refresh(row.id);}if(guard()&&this.alive()){this.setData({cloneBusy:false});await this.loadClones();}}
    catch(e){if(guard()&&this.alive())this.setData({cloneBusy:false,cloneError:P.degradeMessage(e)});}
  },
  cloneTitle(e) {if(!this.data.cloneRetry)this.setData({cloneTitle:e.detail.value});},
  consent(e) {this.setData({cloneConsented:e.detail.value.includes('authorized')});},
  uploadClone() {
    if(!this.data.canManage||this.data.cloneBusy||this.data.cloneRetry||!this.data.digitalPersona)return;
    const kind=this.data.cloneKind,person=this.data.digitalPersona.id;
    const guard=http.requestGuard('clone-upload');
    const alive=()=>guard()&&this.alive()&&this.data.digitalPersona&&this.data.digitalPersona.id===person&&this.data.cloneKind===kind;
    const chosen=async file=>{
      if(!alive())return;
      this.setData({cloneBusy:true,cloneError:'',cloneUploadStatus:'正在检查素材…'});
      try{const path=file.tempFilePath||file.path;if(kind==='VOICE')await require('../../../utils/clone-media').checkVoice(path,file.size,alive);else await require('../../../utils/clone-media').checkVideo(path,file.size,alive);const id=await require('../../../utils/hifly-upload')(path,kind,person,alive,{...file,onProgress:status=>{if(alive())this.setData({cloneUploadStatus:status});}});if(alive())this.setData({cloneUploadId:id,cloneFileName:file.name||'素材已上传',cloneConsented:false});}
      catch(e){if(alive()&&!e.cancelled)this.setData({cloneError:P.degradeMessage(e)});}
      finally{if(alive())this.setData({cloneBusy:false,cloneUploadStatus:''});}
    };
    if(kind==='AVATAR')wx.showActionSheet({itemList:['从相册选择或拍摄','从聊天选择MP4/MOV文件'],success:r=>{if(r.tapIndex===0)wx.chooseMedia({count:1,mediaType:['video'],sourceType:['album','camera'],compressed:false,maxDuration:60,success:r=>chosen(r.tempFiles[0])});else wx.chooseMessageFile({count:1,type:'file',extension:['mp4','mov'],success:r=>chosen(r.tempFiles[0])});}});
    else wx.chooseMessageFile({count:1,type:'file',extension:['mp3','m4a','wav'],success:r=>chosen(r.tempFiles[0])});
  },
  async createClone() {
    if(!this.data.canManage||!this.alive()||this.data.cloneBusy||!this.data.digitalPersona)return;
    const person=this.data.digitalPersona.id,quote=this.data.cloneQuote;
    if(!this.__cloneAttempt){
      if(!this.data.cloneTitle.trim()||!this.data.cloneUploadId||!this.data.cloneConsented)return this.setData({cloneError:'请填写名称、上传素材并确认授权'});
      if(!quote||!quote.available)return this.setData({cloneError:quote&&quote.message||'请先获取制作价格'});
      this.__cloneAttempt={key:'clone-'+Date.now()+'-'+Math.floor(Math.random()*1e8),body:{kind:this.data.cloneKind,title:this.data.cloneTitle.trim(),sourceUploadId:this.data.cloneUploadId,consentVersion:quote.consentVersion,confirmedCredits:quote.credits}};
      wx.setStorageSync(studio.scopedKey('zs-clone-attempt:'+person),this.__cloneAttempt);
    }
    const attempt=this.__cloneAttempt,guard=http.requestGuard('clone-create');this.setData({cloneBusy:true,cloneError:'',cloneRetry:true});
    try{
      await api.clones.create(person,attempt.body,attempt.key);
      if(!guard()||!this.alive())return;
      wx.removeStorageSync(studio.scopedKey('zs-clone-attempt:'+person));this.__cloneAttempt=null;
      this.setData({cloneUploadId:'',cloneFileName:'',cloneConsented:false,cloneRetry:false,cloneBusy:false});this.showView('resources');this.loadClones();
    }catch(e){if(guard()&&this.alive()){
      // 明确的本地拒绝尚未外呼，可修改后重提；网络未知保留完整原请求以安全回读。
      if([1072000010,1072000011,1072000013,1072000015,1072000016,1114000001,403].includes(Number(e.code))){wx.removeStorageSync(studio.scopedKey('zs-clone-attempt:'+person));this.__cloneAttempt=null;this.setData({cloneRetry:false});this.loadQuote();}
      this.setData({cloneBusy:false,cloneError:P.degradeMessage(e)});
    }}
  },
  async refreshClone(e) {
    if(this.data.cloneBusy||!this.alive())return;
    const id=String(e.currentTarget.dataset.id),guard=http.requestGuard('clone-refresh');this.setData({cloneBusy:true,cloneError:''});
    try{await api.clones.refresh(id);if(guard()&&this.alive()){this.setData({cloneBusy:false});this.loadClones();}}
    catch(error){if(guard()&&this.alive())this.setData({cloneBusy:false,cloneError:P.degradeMessage(error)});}
  },
  useDigital() {
    const person=this.data.digitalPersona;
    if(this.alive()&&person&&person.enabled&&this.readyPair())wx.navigateTo({url:'/pages/studio/index?view=digital&persona='+encodeURIComponent(person.id)});
    else this.setData({cloneError:'请先准备同一人物名下已就绪的数字分身和克隆声音'});
  },
  audition(e) {
    const row=this.data.cloneRows.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(!this.alive()||!row||!row.ready||!row.demoUrl)return;
    if(this.__audio)this.__audio.destroy();this.__audio=wx.createInnerAudioContext();this.__audio.src=row.demoUrl;
    this.__audio.onError(()=>{if(this.alive())this.setData({cloneError:'试听音频暂不可用，请稍后重试'});});this.__audio.play();
  }
});
