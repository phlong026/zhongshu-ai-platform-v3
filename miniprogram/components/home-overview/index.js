const Home=require('../../utils/home-metrics');
const http=require('../../api/http');
const auth=require('../../api/auth');
Component({
 properties:{role:{type:String,value:'business'},period:{type:String,value:'',observer(value){if(/^\d{4}-(0[1-9]|1[0-2])$/.test(value)&&value!==this.data.month){this.setData({month:value});if(this.__attached)this.load();}}}},
 data:{month:'',board:null,loading:false,error:'',tab:'operating'},
 lifetimes:{attached(){this.__attached=true;this.setData({month:this.properties.period||Home.currentPeriod(),board:Home.view(this.properties.role)});this.load();},detached(){this.__attached=false;this.__generation=(this.__generation||0)+1;}},
 pageLifetimes:{show(){if(this.__attached)this.load();},hide(){this.__generation=(this.__generation||0)+1;}},
 methods:{
  async load(){const post=auth.currentApiPost(),generation=this.__generation=(this.__generation||0)+1,session=http.sessionGeneration(),month=this.data.month,role=this.properties.role;
   const identity=session+':'+(post&&post.postId);this.__identity=identity;
   this.setData({loading:true,error:'',board:Home.view(role)});
   if(!post||!http.token()){this.setData({loading:false,error:'登录后查看业务数据'});return;}
   const alive=()=>this.__attached&&generation===this.__generation&&session===http.sessionGeneration()&&String(post.postId)===String((auth.currentApiPost()||{}).postId);
   try{const result=await Home.load(role,month);if(alive())this.setData({board:Home.view(role,result),loading:false});}
   catch(e){if(alive())this.setData({loading:false,error:'概览暂时无法加载'});}
  },
  changeMonth(e){const value=e.detail.value;if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(value))return;this.setData({month:value});this.load();this.triggerEvent('monthchange',{value});},
  metric(e){const key=e.currentTarget.dataset.key;if(!this.data.board.metrics[key]||!this.data.board.metrics[key].known)return;require('../../utils/page-navigation').open(Home.detailUrl(this.properties.role,key,this.data.month));},
  switchTab(e){this.setData({tab:e.currentTarget.dataset.tab});}
 }
});
