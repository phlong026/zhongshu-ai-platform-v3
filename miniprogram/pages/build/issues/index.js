const api = require('../../../api/franchise/engineering');
const auth = require('../../../api/auth');
const http = require('../../../api/http');
const states = { OPEN:'待整改', REVIEW:'待复验', CLOSED:'已销项' };
const actions = { create:'登记整改', assign:'分派负责人及期限', submit:'提交复验', return:'退回整改', pass:'复验通过并销项' };
function viewIssue(row) {
  const person = (name,id) => name || (id ? '人员 '+id : '');
  return {...row,status:states[row.state],ownerLabel:person(row.ownerName,row.ownerUserId),
    history:(row.history||[]).map(step=>({...step,label:actions[step.action]||step.action,
      actorLabel:person(step.actorName,step.actorUserId),ownerLabel:person(step.ownerName,step.ownerUserId)}))};
}
Page({
  data:{rows:[],selected:null,loading:true,saving:false,error:'',title:'',note:'',photo:'',quality:false,editable:false,
    handlers:[],ownerUserId:'',ownerIndex:-1,dueDate:'',editingAssignment:false},
  onLoad(options){
    this.options=options;
    this.path='/franchise/engineering/'+encodeURIComponent(options.project)+'/rectifications';
    this.setData({quality:!!options.supply});
  },
  onShow(){this.load();},
  onUnload(){this.closed=true;},
  async load(){
    const valid=http.sessionGuard(),generation=this.generation=(this.generation||0)+1;
    const alive=()=>!this.closed&&valid()&&generation===this.generation;
    const {project,supply,receipt,issue}=this.options;
    const scope=JSON.stringify([project,supply,receipt,issue]);
    const keepDraft=this.loadedSession?.()&&this.loadedScope===scope;
    this.loadedSession=null;
    this.setData({loading:true,saving:false,error:'',selected:null,editable:false,rows:[],handlers:[],
      ...(keepDraft?{}:{title:'',note:'',photo:'',ownerUserId:'',ownerIndex:-1,dueDate:'',editingAssignment:false})});
    try{
      const [p,rows]=await Promise.all([api.projects.get(project),supply?api.materials.summary(project):
        http.get(issue?this.path+'/'+encodeURIComponent(issue):this.path)]);
      if(!alive())return;
      const post=auth.currentApiPost()||{};
      const editable=['engineer','project-manager'].includes(post.roleCode)&&[p.managerUserId,p.assignedEngineerUserId].some(id=>id&&String(id)===String(post.userId));
      if(supply){
        const line=rows.plans.flatMap(plan=>plan.lines).find(l=>String(l.supplyId)===supply);
        const r=line&&line.receipts.find(row=>String(row.id)===receipt);
        if(!r)throw Error('该收货批次已变更或当前岗位无权查看');
        this.setData({selected:{id:receipt,title:line.name,note:r.transactionNo,state:r.qualified?'CLOSED':'OPEN',status:r.qualified?'已验收':'待质检处置'}});
      }else{
        const selected=issue?viewIssue(rows):null;
        if(selected&&String(selected.id)!==String(issue))throw Error('原整改单已变更，请返回重新打开');
        this.setData({rows:issue?[]:rows.map(viewIssue),selected});
        if(editable&&(!selected||selected.state!=='CLOSED')){
          const ids=[...new Set([p.managerUserId,p.assignedEngineerUserId].filter(Boolean).map(String))];
          const names=await api.progress.handlers(project).catch(()=>[]);
          if(!alive())return;
          const handlers=ids.map(id=>({id,name:(names.find(row=>String(row.id)===id)||{}).name||
            (id===String(p.managerUserId)&&p.managerName)||'人员 '+id}));
          const keepAssignment=keepDraft&&(!selected||this.data.editingAssignment);
          const ownerIndex=handlers.findIndex(h=>h.id===String(keepAssignment?this.data.ownerUserId:selected?.ownerUserId));
          this.setData({handlers,ownerIndex,ownerUserId:ownerIndex>=0?handlers[ownerIndex].id:'',dueDate:keepAssignment?this.data.dueDate:selected?.dueDate||''});
        }
      }
      this.loadedSession=valid;
      this.loadedScope=scope;
      this.setData({projectName:p.name,loading:false,editable:editable&&((!supply&&!!issue)||!['DELIVERED','ARCHIVED','SETTLED'].includes(p.status))});
    }catch(e){if(alive())this.setData({loading:false,error:e.msg||e.message||'读取失败'});}
  },
  open(e){this.options.issue=String(e.currentTarget.dataset.id);return this.load();},
  showAssignment(){this.setData({editingAssignment:true,error:''});},
  cancelAssignment(){
    const selected=this.data.selected,ownerIndex=this.data.handlers.findIndex(h=>h.id===String(selected.ownerUserId));
    this.setData({editingAssignment:false,error:'',ownerIndex,ownerUserId:ownerIndex>=0?this.data.handlers[ownerIndex].id:'',dueDate:selected.dueDate||''});
  },
  pickOwner(e){const chosen=this.data.handlers[Number(e.detail.value)];if(chosen)this.setData({ownerIndex:Number(e.detail.value),ownerUserId:chosen.id});},
  pickDate(e){this.setData({dueDate:e.detail.value});},
  input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
  photo(){wx.chooseMedia({count:1,mediaType:['image'],success:r=>this.setData({photo:r.tempFiles[0].tempFilePath})});},
  async save(e){
    if(this.data.saving||!this.data.editable)return;
    if(!this.loadedSession||!this.loadedSession()){this.setData({editable:false,error:'岗位已切换，请重新加载原单'});return;}
    const action=e.currentTarget.dataset.action,{note,title,photo,selected,quality}=this.data;
    if(!note.trim()||(action==='create'&&!title.trim())||(quality&&!photo)){this.setData({error:'请补全事项说明及所需凭据'});return;}
    const valid=http.sessionGuard(),alive=()=>valid()&&!this.closed;
    const cmd={action,note:note.trim(),...(action==='create'?{title:title.trim(),nodeId:this.options.node||null}:{issueId:selected.id})};
    if(!quality&&(action==='create'||action==='assign')){
      if(!this.data.handlers.some(h=>h.id===this.data.ownerUserId)||!/^\d{4}-\d{2}-\d{2}$/.test(this.data.dueDate)){
        this.setData({error:'请选择本项目负责人并填写整改期限'});return;
      }
      cmd.ownerUserId=this.data.ownerUserId;
      cmd.dueDate=this.data.dueDate;
    }
    const signature=JSON.stringify([this.loadedScope,http.currentPostId(),cmd,photo]);
    if(signature!==this.signature){this.signature=signature;this.key='site-'+Date.now()+'-'+Math.floor(Math.random()*1e8);}
    this.setData({saving:true,error:''});
    try{
      if(quality){
        const fileAssetId=await api.uploadVoucher(photo,'ENG_SITE_PHOTO');
        if(!alive())return;
        await api.materials.arrival(this.options.supply,{qualityResolution:{receiptId:selected.id,note:cmd.note,fileAssetId}},this.key);
      }else{
        const id=await http.post(this.path,cmd,{'Idempotency-Key':this.key});
        if(alive())this.options.issue=String(id);
      }
      if(!alive())return;
      this.signature='';this.loadedScope=null;await this.load();
      if(alive())wx.showToast({title:'已保存'});
    }catch(e){if(alive())this.setData({error:e.msg||e.message||'保存失败，可重试'});}
    finally{if(alive())this.setData({saving:false});}
  }
});
