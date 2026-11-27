const sourceUrl=require('./source-url');
const Home=require('../../utils/home-metrics'),http=require('../../api/http'),auth=require('../../api/auth');
const nav=require('../../utils/page-navigation');
const apiScope=require('../../utils/api-scope');
Page({
 data:{title:'业务明细',period:'',monthly:false,rows:[],total:0,loading:false,error:'',more:false,selected:null,note:'',saving:false},
 onLoad(o={}){this.role=o.role;this.key=o.metric;this.setData({canRegisterVisit:this.role==='business'&&['sales','sales-lead'].includes((auth.currentApiPost()||{}).roleCode),key:this.key,title:Home.labels[this.key]||'业务明细',period:/^\d{4}-(0[1-9]|1[0-2])$/.test(o.period||'')?o.period:Home.currentPeriod()});},
 onShow(){this.__disposed=false;this.load();},onHide(){this.__generation=(this.__generation||0)+1;},onUnload(){this.__disposed=true;this.__generation=(this.__generation||0)+1;},
 onPullDownRefresh(){this.load().finally(()=>wx.stopPullDownRefresh());},
 async load(append=false){if(append&&this.data.loading)return;const post=auth.currentApiPost(),session=http.sessionGeneration(),generation=this.__generation=(this.__generation||0)+1,page=append?(this.__page||1)+1:1;
  const identity=session+':'+(post&&post.postId);if(this.__identity!==identity){this.__identity=identity;this.setData({rows:[],selected:null,note:'',saving:false,total:0});}
  const alive=()=>!this.__disposed&&generation===this.__generation&&session===http.sessionGeneration()&&String((auth.currentApiPost()||{}).postId)===String((post||{}).postId);
  this.setData({loading:true,error:'',...(!append?{rows:[],more:false}:{})});
  try{const result=await Home.details(this.role,this.data.period,this.key,page);if(!alive())return;
   const fresh=result.rows.map(r=>({...r,key:r.source_type+':'+r.id,amountText:Home.money(Number(r.amount),false).value,url:sourceUrl(r,this.role,this.key,this.data.period,post&&post.roleCode),action:r.source_type==='budget'?'核对预算':this.role==='owner'||(this.role==='business'&&['receipt','refund'].includes(r.source_type))?'查看原单':'进入办理'}));
   const rows=append?[...this.data.rows,...fresh]:fresh;this.__page=page;
   this.setData({rows,total:Number(result.total),monthly:result.monthly,noteText:result.note,metric:Home.metric(this.key,result,this.role),loading:false,more:page*result.pageSize<Number(result.total)});
  }catch(e){if(alive())this.setData({loading:false,error:e.message||e.msg||'明细加载失败，请重试'});}
 },
 more(){this.load(true);},retry(){this.load();},month(e){this.setData({period:e.detail.value});this.load();},
 async open(e){const row=this.data.rows.find(r=>r.key===e.currentTarget.dataset.key);if(!row)return;
  if(row.source_type!=='budget'){if(row.url)nav.open(row.url);return;}
  const guard=http.requestGuard('home-budget'),scope=apiScope(),generation=this.__generation;
  const alive=()=>guard()&&!this.__disposed&&scope===apiScope()&&generation===this.__generation;
  try{const detail=await require('../../api/franchise/crm').customers.get(row.customer_id);if(!alive())return;
   const budgets=(detail.schemes||[]).filter(b=>b.kind==='BUDGET'&&String(b.customerId)===String(row.customer_id));
   const budget=String(detail.customer?.id)===String(row.customer_id)&&budgets.find(b=>String(b.id)===String(row.source_id));if(!budget){wx.showToast({title:'预算原单不匹配，请刷新',icon:'none'});return;}
   const latest=!budgets.some(b=>Number(b.version)>Number(budget.version));
   const writable=latest&&!budget.confirmedAt&&['sales','sales-lead'].includes((auth.currentApiPost()||{}).roleCode);this.__confirmationKey='budget-confirm-'+Date.now()+'-'+Math.random().toString(16).slice(2,10);
   const readonlyNote=budget.confirmedAt?'本版已记录参考预算确认。':!latest?'历史版本仅供查看，请在最新版本登记确认。':'请由负责该客户的业务同事登记确认。';
   this.setData({selected:{...row,url:sourceUrl({...row,version:budget.version},this.role,this.key,this.data.period),budget,totalText:budget.totalCents==null?'未记录完整总额':'¥'+Home.money(Number(budget.totalCents),false).value,writable,readonlyNote},note:'',confirmError:''});
  }catch(e){if(alive())wx.showToast({title:e.message||'预算加载失败',icon:'none'});}
 },
 note(e){this.setData({note:e.detail.value});},close(){if(!this.data.saving)this.setData({selected:null});},noop(){},openBudget(){const s=this.data.selected;if(s&&s.url)nav.open(s.url);},
 async confirmBudget(){if(this.data.saving||!this.data.selected?.writable)return;const note=this.data.note.trim();if(!note)return this.setData({confirmError:'请填写客户确认的日期、方式和沟通记录'});
  const s=this.data.selected,alive=http.requestGuard('home-budget-confirm');this.setData({saving:true,confirmError:''});
  try{await http.post('/franchise/customers/'+encodeURIComponent(s.customer_id)+'/budget-confirmation',{schemeId:s.source_id,note},{'Idempotency-Key':this.__confirmationKey});if(!alive()||this.__disposed)return;this.setData({selected:null,saving:false});wx.showToast({title:'预算确认已记录',icon:'success'});this.load();}
  catch(e){if(alive()&&!this.__disposed)this.setData({saving:false,confirmError:e.message||e.msg||'保存失败，请重试'});}
 },
 collectVisit(){nav.open('/pages/customers/index');}
});
