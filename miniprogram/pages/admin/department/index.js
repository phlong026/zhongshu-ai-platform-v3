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
const emptyView=()=>({viewReady:false,nickname:'',members:[],focus:[],large:[],small:[],focusTotal:0,focusMore:false,teamOpen:false,focusOpen:false,selected:null,records:[],more:false,focusBusy:false,focusError:'',memberBusy:false,memberError:''});
const scopeOf=page=>{const p=auth.currentApiPost()||{};return JSON.stringify([http.sessionGeneration(),http.currentUserId(),http.tenantId(),p.tenantId,p.orgId,p.postId,p.roleCode,p.orgType,page.data.period]);};
const denied=e=>e?.postInvalid||[e?.code,e?.statusCode,e?.status].some(c=>[401,403].includes(Number(c)));
Page({
  data:{area:'business',period:'',members:[],focus:[],large:[],small:[],more:false,expanded:false,selected:null,records:[],error:'',busy:false,refreshing:false},
  onLoad(o){this.setData({area:o.area==='engineering'?'engineering':'business',period:Home.currentPeriod()});},
  onShow(){this.reload();},
  onHide(){this.__resumeMember=!!this.data.selected;this.version=(this.version||0)+1;this.__flight=null;this.setData({focusBusy:false,memberBusy:false});},onUnload(){this.onHide();},
  clearView(error){this.__views={};this.__readyArea='';this.__memberReady='';this.__resumeMember=false;this.__flight=null;this.version=(this.version||0)+1;this.memberRequest=(this.memberRequest||0)+1;this.setData({...emptyView(),busy:false,refreshing:false,error});},
  currentView(version,scope){if(version!==this.version)return false;if(scope===scopeOf(this)&&http.token())return true;this.clearView('身份已变化，请重新加载');return false;},
  rememberView(){if(this.__readyArea!==this.data.area)return;const saved={focusPage:this.focusPage};for(const key of ['viewReady','nickname','large','small','members','focus','focusTotal','focusMore'])saved[key]=this.data[key];this.__views[this.data.area]=saved;},
  switchWorkspaceTab(tab){
    const area=tab.key==='owner-business'?'business':tab.key==='owner-engineering'?'engineering':'';
    const post=auth.currentApiPost();
    if(!area||!post||post.orgType!=='store'||!['boss','owner'].includes(post.roleCode))return false;
    if(area===this.data.area&&this.__scope===scopeOf(this))return true;
    if(this.__scope===scopeOf(this))this.rememberView();
    this.memberRequest=(this.memberRequest||0)+1;this.reload(area);return true;
  },
  reload(nextArea){
    const post=auth.currentApiPost(),area=sections[nextArea]?nextArea:this.data.area,period=this.data.period,scope=scopeOf(this);
    if(this.__scope!==scope){this.__scope=scope;this.__views={};this.__readyArea='';this.__viewArea='';this.__memberReady='';this.__resumeMember=false;this.__flight=null;this.setData(emptyView());}
    if(!http.token()||!post||!['boss','owner'].includes(post.roleCode)||post.orgType!=='store'){this.clearView('请使用本公司老板岗位查看');return;}
    if(this.__flight?.scope===scope&&this.__flight.area===area)return this.__flight.promise;
    if(this.__viewArea!==area){const saved=this.__views[area];this.__viewArea=area;this.__readyArea=saved?area:'';this.__memberReady='';this.__resumeMember=false;this.focusPage=saved?.focusPage||1;this.setData({area,...emptyView(),...saved});}
    const ready=this.__readyArea===area,lastFocusPage=ready?this.focusPage||1:1,version=this.version=(this.version||0)+1;
    const current=()=>this.currentView(version,scope);
    this.__resumeMember=!!this.data.selected;
    this.memberRequest=(this.memberRequest||0)+1;
    this.setData({busy:!ready,refreshing:ready,error:'',focusBusy:false,memberBusy:false});
    const flight={scope,area,promise:null};this.__flight=flight;
    http.get('/platform/profile/mine').then(profile=>{if(current())this.setData({nickname:profile.nickname||''});}).catch(()=>{});
    flight.promise=(async()=>{try{
      const [home,members,first]=await Promise.all([Home.load('owner',period),http.get('/franchise/home/team',{area,period}),http.get('/franchise/home/team/focus',{area,period,page:1})]);
      if(!current())return;
      let focus=first,rows=first.rows,page=1;
      while(focus.more&&page<lastFocusPage){focus=await http.get('/franchise/home/team/focus',{area,period,page:++page});if(!current())return;rows=rows.concat(focus.rows);}
      const metrics=sections[area].map(key=>{const metric=Home.metric(key,home.metrics[key],'owner');return {...metric,label:labels[key]||metric.label,unit:['budgets','handover'].includes(key)?'单':metric.unit};});
      this.focusPage=page;this.__readyArea=area;
      this.setData({viewReady:true,large:metrics.slice(0,2),small:metrics.slice(2),members,focus:this.decorate(rows),focusTotal:first.total,focusMore:focus.more,busy:false,refreshing:false});this.rememberView();
      const selected=this.data.selected&&members.find(row=>String(row.id)===String(this.data.selected.id));
      if(this.data.selected&&!selected)this.closeMember();
      else if(selected){this.setData({selected});if(this.__resumeMember&&!this.data.memberBusy)this.memberRows(false);}
      this.__resumeMember=false;
    }catch(e){if(current()){const error=e.msg||e.message||'业务数据暂时无法读取';if(denied(e))this.clearView(error);else{this.setData({busy:false,refreshing:false,error});if(this.__resumeMember&&this.data.selected&&!this.data.memberBusy)this.memberRows(false);this.__resumeMember=false;}}}
    finally{if(this.__flight===flight)this.__flight=null;}})();
    return flight.promise;
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
    if(!this.data.selected||append&&(this.data.memberBusy||this.data.busy||this.data.refreshing))return;
    const gen=this.memberRequest=(this.memberRequest||0)+1,alive=http.sessionGuard(),version=this.version,scope=scopeOf(this);
    const {area,period,kind,selected}=this.data;
    const key=JSON.stringify([scope,area,kind,selected.id]),retain=this.__memberReady===key;
    const first=append?(this.memberPage||0)+1:1,last=append?first:retain?this.memberPage||1:1;
    this.setData({memberBusy:true,memberError:'',...(!append&&!retain?{records:[],more:false}:{})});
    try{
      let rows=[],more=false,page=first;
      for(;page<=last;page++){
        const d=await http.get('/franchise/home/team/'+selected.id,{area,period,kind,page});
        if(gen!==this.memberRequest||!this.currentView(version,scope)||!alive())return;
        rows=rows.concat(d.rows);more=d.more;
        if(!more||page===last)break;
      }
      this.memberPage=page;this.__memberReady=key;this.setData({records:append?this.data.records.concat(this.decorate(rows)):this.decorate(rows),more,memberBusy:false});
    }catch(e){if(gen===this.memberRequest&&this.currentView(version,scope)&&alive()){if(denied(e))this.clearView(e.msg||e.message||'当前身份无法查看');else this.setData({memberError:e.msg||e.message||'读取失败',memberBusy:false});}}
  },
  showFocus(){this.setData({focusOpen:true,teamOpen:false,selected:null});},
  async expandFocus(){
    if(!this.data.focusMore||this.data.focusBusy||this.data.busy||this.data.refreshing)return;
    const {area,period}=this.data,page=this.focusPage+1,version=this.version,alive=http.sessionGuard(),scope=scopeOf(this);
    this.setData({focusBusy:true,focusError:''});
    try{const d=await http.get('/franchise/home/team/focus',{area,period,page});
      if(this.currentView(version,scope)&&alive()){this.focusPage=page;this.setData({focus:this.data.focus.concat(this.decorate(d.rows)),focusMore:d.more});}
    }catch(e){if(this.currentView(version,scope)&&alive()){if(denied(e))this.clearView(e.msg||e.message||'当前身份无法查看');else this.setData({focusError:e.msg||e.message});}}
    finally{if(this.currentView(version,scope)&&alive())this.setData({focusBusy:false});}
  },
  original(e){const {id,node,project,kind}=e.currentTarget.dataset;
    if(kind==='issue'||kind==='quality'){
      const row=kind==='issue'?'issue='+id:'supply='+node+'&receipt='+id;
      return nav.open('/pages/build/issues/index?project='+project+'&'+row);
    }
    nav.open(this.data.area==='business'?'/pages/workflow/index?view=customer&id='+id:'/pages/build/record/index?id='+(project||id)+(node?'&view=node&nodeId='+node:'&view=overview'));
  }
});
