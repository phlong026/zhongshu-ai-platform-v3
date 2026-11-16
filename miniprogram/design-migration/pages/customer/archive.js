const {protectedPage}=require('../../utils/access');
const session=require('../../utils/request');
const crm=require('../../../api/franchise/crm');
const design=require('../../../api/franchise/design');
const http=require('../../../api/http');
const {id}=require('../../utils/record-view');
const sha=require('../../utils/sha256');
protectedPage({
  data:{rows:[],keyword:'',loading:false,busy:false,error:'',hasMore:false,selected:'',saved:false},
  onLoad(q){
    this._token=session.captureSession();this._project=id(q.projectId);this._version=id(q.resultVersionId);this._budget=id(q.budgetId);this._revision=id(q.revisionId);
    this._kind=q.kind==='ELEVATION'?'FACADE':'PLAN';
    if(!this._budget&&(!this._project||!this._version)){this.setData({error:'方案编号无效，请返回结果页重新进入'});return;}
    this.load(true);
  },
  onUnload(){this._token=null;},
  current(){return session.isSameSession(this._token);},
  input(e){this.setData({keyword:e.detail.value});},
  search(){return this.load(true);},
  more(){return this.load(false);},
  async load(reset){
    if(!this.current()||this.data.loading)return;
    const page=reset?1:(this._page||1)+1;this.setData({loading:true,error:''});
    try{const result=await crm.customers.page(page,20,this.data.keyword.trim());if(!this.current())return;
      const rows=result.list||[];this._page=page;this.setData({rows:reset?rows:this.data.rows.concat(rows),hasMore:page*20<Number(result.total),loading:false});
    }catch(e){if(this.current())this.setData({loading:false,error:e.msg||e.message||'客户读取失败，请重试'});}
  },
  choose(e){if(!this.data.busy)this.setData({selected:String(e.currentTarget.dataset.id),saved:false,error:''});},
  async save(){
    if(!this.current()||this.data.busy||this.data.saved||!id(this.data.selected))return;
    const customer=this.data.selected,body=this._budget?{customerId:customer,budgetId:this._budget,revisionId:this._revision}:{customerId:customer,kind:this._kind,resultVersionId:this._version};
    const scope=require('../../utils/context').scope()+':'+JSON.stringify(body);
    const storage='design-customer-'+sha.sha256Hex(new Uint8Array(Array.from(unescape(encodeURIComponent(scope)),c=>c.charCodeAt(0))));
    this.setData({busy:true,error:''});
    try{let key=wx.getStorageSync(storage);if(!key){key='archive-'+Date.now()+'-'+Math.random().toString(36).slice(2,12);wx.setStorageSync(storage,key);}
      if(this._budget)await http.post('/franchise/design/flow-budget-archive',body,{'Idempotency-Key':key});
      else await design.projects.saveToCustomer(this._project,body,key);
      if(this.current()){this.setData({busy:false,saved:true});wx.showToast({title:'已保存至客户',icon:'success'});}
    }catch(e){if(this.current())this.setData({busy:false,error:e.msg||e.message||'保存失败，可重试同一请求'});}
  },
  back(){wx.navigateBack();}
});
