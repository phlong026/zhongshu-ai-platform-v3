const http=require('../../../api/http');
const auth=require('../../../api/auth');
const Home=require('../../../utils/home-metrics');
const nav=require('../../../utils/page-navigation');
const sections={
  business:['signed','customerNet','newCustomers','visits','budgets','handover'],
  engineering:['building','delivered','overdueNodes','rectifications','materials']
};
const labels={signed:'本月签约',customerNet:'客户净回款',newCustomers:'新增客户',building:'在建工程',rectifications:'待整改复验'};
const kinds={business:[['customers','名下客户'],['followups','待跟进'],['visitCustomers','本月接待客户']],engineering:[['projects','主责在建'],['pending','待处理事项']]};
const stateNames={PENDING_START:'待开工',IN_PROGRESS:'在建',READY_DELIVER:'待交付'};
Page({
  data:{area:'business',period:'',members:[],focus:[],large:[],small:[],more:false,expanded:false,selected:null,records:[],error:'',busy:false},
  onLoad(o){this.setData({area:o.area==='engineering'?'engineering':'business',period:Home.currentPeriod()});},
  onShow(){this.reload();},
  onHide(){this.version=(this.version||0)+1;},onUnload(){this.onHide();},
  async reload(){
    const version=this.version=(this.version||0)+1;
    const post=auth.currentApiPost(),{area,period}=this.data;
    const alive=http.sessionGuard();
    this.setData({busy:true,error:'',nickname:'',teamOpen:false,focusOpen:false,selected:null,records:[],members:[],focus:[],large:[],small:[],focusBusy:false,focusError:'',more:false});
    if(!post||!['boss','owner'].includes(post.roleCode)||post.orgType!=='store'){this.setData({busy:false,error:'请使用本公司老板岗位查看'});return;}
    http.get('/platform/profile/mine').then(profile=>{if(alive()&&version===this.version)this.setData({nickname:profile.nickname||''});}).catch(()=>{});
    try{
      const [home,members,focus]=await Promise.all([Home.load('owner',period),http.get('/franchise/home/team',{area,period}),http.get('/franchise/home/team/focus',{area,period,page:1})]);
      if(version!==this.version||!alive())return;
      const metrics=sections[area].map(key=>{const metric=Home.metric(key,home.metrics[key],'owner');return {...metric,label:labels[key]||metric.label,unit:['budgets','handover'].includes(key)?'单':metric.unit};});
      this.focusPage=1;
      this.setData({large:metrics.slice(0,2),small:metrics.slice(2),members,focus:this.decorate(focus.rows),focusTotal:focus.total,focusMore:focus.more,busy:false});
    }catch(e){if(version===this.version&&alive())this.setData({busy:false,error:e.msg||e.message||'业务数据暂时无法读取'});}
  },
  decorate(rows){
    return rows.map(row=>{
      const nodes=row.timeline?JSON.parse(row.timeline):[],next=nodes.findIndex(n=>!n.done);
      const start=next<0?Math.max(0,nodes.length-5):Math.min(Math.max(0,nodes.length-5),Math.max(0,next-2));
      const stages=nodes.slice(start,start+5).map((n,i)=>({...n,tone:n.done?'done':start+i===next?'now':''}));
      return {...row,note:stateNames[row.note]||row.note,stage:next>=0?nodes[next].name:'',stages};
    });
  },
  month(e){this.setData({period:e.detail.value});this.reload();},
  metric(e){nav.open(Home.detailUrl('owner',e.currentTarget.dataset.key,this.data.period));},
  toggleTeam(){this.setData({teamOpen:true,selected:null});},
  showMember(e){const m=this.data.members.find(v=>v.id===e.currentTarget.dataset.id);if(!m)return;this.setData({selected:m,filters:kinds[this.data.area],kind:kinds[this.data.area][0][0]});this.memberRows(false);},
  selectKind(e){this.setData({kind:e.currentTarget.dataset.kind});this.memberRows(false);},
  closeMember(){this.memberRequest=(this.memberRequest||0)+1;this.setData({teamOpen:false,focusOpen:false,selected:null,records:[],memberError:'',memberBusy:false});},
  moreRows(){this.memberRows(true);},
  async memberRows(append){
    if(append&&this.data.memberBusy)return;
    const gen=this.memberRequest=(this.memberRequest||0)+1,alive=http.sessionGuard(),version=this.version;
    const {area,period,kind,selected}=this.data,page=append?this.memberPage+1:1;
    this.setData({memberBusy:true,memberError:'',...(!append?{records:[],more:false}:{})});
    try{
      const d=await http.get('/franchise/home/team/'+selected.id,{area,period,kind,page});
      if(gen!==this.memberRequest||version!==this.version||!alive())return;
      this.memberPage=page;this.setData({records:append?this.data.records.concat(this.decorate(d.rows)):this.decorate(d.rows),more:d.more,memberBusy:false});
    }catch(e){if(gen===this.memberRequest&&version===this.version&&alive())this.setData({memberError:e.msg||e.message||'读取失败',memberBusy:false});}
  },
  showFocus(){this.setData({focusOpen:true,teamOpen:false,selected:null});},
  async expandFocus(){
    if(!this.data.focusMore||this.data.focusBusy)return;
    const {area,period}=this.data,page=this.focusPage+1,version=this.version,alive=http.sessionGuard();
    this.setData({focusBusy:true,focusError:''});
    try{const d=await http.get('/franchise/home/team/focus',{area,period,page});
      if(version===this.version&&alive()){this.focusPage=page;this.setData({focus:this.data.focus.concat(this.decorate(d.rows)),focusMore:d.more});}
    }catch(e){if(version===this.version&&alive())this.setData({focusError:e.msg||e.message});}
    finally{if(version===this.version&&alive())this.setData({focusBusy:false});}
  },
  original(e){const {id,node,project,kind}=e.currentTarget.dataset;
    if(kind==='issue'||kind==='quality'){
      const row=kind==='issue'?'issue='+id:'supply='+node+'&receipt='+id;
      return nav.open('/pages/build/issues/index?project='+project+'&'+row);
    }
    nav.open(this.data.area==='business'?'/pages/workflow/index?view=customer&id='+id:'/pages/build/record/index?id='+(project||id)+(node?'&view=node&nodeId='+node:'&view=overview'));
  }
});
