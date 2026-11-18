const api = require('../../../api/franchise/engineering');
const auth = require('../../../api/auth');
const http = require('../../../api/http');
const states = { OPEN:'待整改', REVIEW:'待复验', CLOSED:'已销项' };
Page({
  data:{rows:[],selected:null,loading:true,saving:false,error:'',title:'',note:'',photo:'',quality:false,editable:false},
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
    this.setData({loading:true,error:'',selected:null,editable:false,rows:[]});
    try{
      const [p,rows]=await Promise.all([api.projects.get(project),supply?api.materials.summary(project):http.get(this.path)]);
      if(!alive())return;
      const post=auth.currentApiPost()||{};
      const editable=['engineer','project-manager'].includes(post.roleCode)&&[p.managerUserId,p.assignedEngineerUserId].some(id=>id&&String(id)===String(post.userId));
      if(supply){
        const line=rows.plans.flatMap(plan=>plan.lines).find(l=>String(l.supplyId)===supply);
        const r=line&&line.receipts.find(row=>String(row.id)===receipt);
        if(!r)throw Error('该收货批次已变更或当前岗位无权查看');
        this.setData({selected:{id:receipt,title:line.name,note:r.transactionNo,state:r.qualified?'CLOSED':'OPEN',status:r.qualified?'已验收':'待质检处置'}});
      }else{
        const all=rows.map(r=>({...r,status:states[r.state]}));
        const selected=issue?all.find(r=>String(r.id)===issue):null;
        if(issue&&!selected)throw Error('该整改单已变更或当前岗位无权查看');
        this.setData({rows:all,selected});
      }
      this.setData({projectName:p.name,loading:false,editable:editable&&((!supply&&!!issue)||!['DELIVERED','ARCHIVED','SETTLED'].includes(p.status))});
    }catch(e){if(alive())this.setData({loading:false,error:e.msg||e.message||'读取失败'});}
  },
  open(e){this.options.issue=String(e.currentTarget.dataset.id);this.setData({selected:this.data.rows.find(r=>String(r.id)===this.options.issue),note:'',error:''});},
  input(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value});},
  photo(){wx.chooseMedia({count:1,mediaType:['image'],success:r=>this.setData({photo:r.tempFiles[0].tempFilePath})});},
  async save(e){
    if(this.data.saving||!this.data.editable)return;
    const action=e.currentTarget.dataset.action,{note,title,photo,selected,quality}=this.data;
    if(!note.trim()||(action==='create'&&!title.trim())||(quality&&!photo)){this.setData({error:'请补全事项说明及所需凭据'});return;}
    const valid=http.sessionGuard(),alive=()=>valid()&&!this.closed;
    const cmd={action,note:note.trim(),...(action==='create'?{title:title.trim(),nodeId:this.options.node||null}:{issueId:selected.id})};
    const signature=JSON.stringify([cmd,photo]);
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
      this.signature='';this.setData({note:'',title:'',photo:''});await this.load();
      if(alive())wx.showToast({title:'已保存'});
    }catch(e){if(alive())this.setData({error:e.msg||e.message||'保存失败，可重试'});}
    finally{if(alive())this.setData({saving:false});}
  }
});
