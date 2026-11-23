const Home=require('../../utils/home-metrics');
const http=require('../../api/http');
const auth=require('../../api/auth');
const validMonth=value=>/^\d{4}-(0[1-9]|1[0-2])$/.test(value);
function context(component){
 const post=http.token()?auth.currentApiPost():null,role=component.properties.role,month=component.data.month;
 return {post,role,month,key:JSON.stringify([http.sessionGeneration(),http.currentUserId?.()||'',http.tenantId?.()||'',post?.userId||'',post?.tenantId||'',post?.orgId||'',post?.postId||'',role,month])};
}
Component({
 properties:{role:{type:String,value:'business',observer(){if(this.__attached&&!this.__hidden)this.load();}},period:{type:String,value:'',observer(value){if(validMonth(value)&&value!==this.data.month){this.setData({month:value});if(this.__attached&&!this.__hidden)this.load();}}}},
 data:{month:'',board:null,loading:false,error:'',tab:'operating'},
 lifetimes:{attached(){this.__attached=true;this.__hidden=false;this.setData({month:validMonth(this.properties.period)?this.properties.period:Home.currentPeriod()});return this.load();},detached(){this.__attached=false;this.__flight=null;}},
 pageLifetimes:{show(){this.__hidden=false;if(this.__attached)return this.load();},hide(){this.__hidden=true;this.__flight=null;}},
 methods:{
  load(){
   if(!this.__attached||this.__hidden)return;
   const {post,role,month,key}=context(this);
   if(this.__flight?.key===key)return this.__flight.promise;
   const patch={loading:!!post,error:post?'':'登录后查看业务数据'};
   // A visible board belongs to one identity, role and month. Refresh it in place.
   if(this.__scope!==key||!this.data.board)patch.board=Home.view(role);
   this.__scope=key;this.setData(patch);
   if(!post){this.__flight=null;return;}
   const flight={key,promise:null};this.__flight=flight;
   const alive=()=>{
    if(!this.__attached||this.__hidden||this.__flight!==flight)return false;
    const now=context(this);
    if(now.key===key)return true;
    // Identity can change while the request is pending, before another show event.
    this.__scope=now.key;this.setData({board:Home.view(now.role),loading:false,error:now.post?'':'登录后查看业务数据'});
    return false;
   };
   flight.promise=(async()=>{
    try{const result=await Home.load(role,month);if(alive())this.setData({board:Home.view(role,result),loading:false,error:''});}
    catch(error){
     if(alive()){
      const denied=!error?.silent&&(error?.postInvalid||[error?.code,error?.statusCode,error?.status].some(code=>[401,403].includes(Number(code))));
      this.setData({loading:false,error:error?.silent?'':denied?'当前身份无法查看业务数据':'概览暂时无法加载',...(denied?{board:Home.view(role)}:{})});
     }
    }finally{if(this.__flight===flight)this.__flight=null;}
   })();
   return flight.promise;
  },
  changeMonth(e){const value=e.detail.value;if(!validMonth(value)||value===this.data.month)return;this.setData({month:value});this.load();this.triggerEvent('monthchange',{value});},
  metric(e){const key=e.currentTarget.dataset.key;if(!this.data.board.metrics[key]||!this.data.board.metrics[key].known)return;require('../../utils/page-navigation').open(Home.detailUrl(this.properties.role,key,this.data.month));},
  switchTab(e){this.setData({tab:e.currentTarget.dataset.tab});}
 }
});
