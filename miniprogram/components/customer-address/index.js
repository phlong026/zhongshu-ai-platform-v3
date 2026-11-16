const crm = require('../../api/franchise/crm');
const http = require('../../api/http');
const LEVELS = [
  {key:'province',label:'省／自治区',placeholder:'请选择省／自治区',length:2},
  {key:'city',label:'市',placeholder:'请先选择省',length:4},
  {key:'district',label:'区／县',placeholder:'请先选择市',length:6},
  {key:'town',label:'乡镇／街道',placeholder:'请先选择区／县',length:9},
  {key:'village',label:'村／社区',placeholder:'请先选择乡镇／街道',length:12}
];
function signature(value) {
  return JSON.stringify(LEVELS.map(level=>value[level.key]||'').concat(value.regionCode||''));
}
Component({
  properties:{value:{type:Object,value:{},observer:'syncValue'},disabled:Boolean},
  data:{rows:[],error:'',manualVillage:false},
  lifetimes:{
    attached(){this._ready=true;this._cache={};this.syncValue(this.data.value);},
    detached(){this._ready=false;this._generation=(this._generation||0)+1;}
  },
  methods:{
    syncValue(value){
      if(!this._ready||signature(value||{})===this._signature)return;
      this._signature=signature(value||{});this.load(value||{});
    },
    async load(value){
      const generation=this._generation=(this._generation||0)+1;
      const session=http.sessionGeneration();
      const alive=()=>this._ready&&generation===this._generation&&session===http.sessionGeneration();
      const rows=LEVELS.map(level=>({...level,options:[],index:0,selected:value[level.key]||'',disabled:true,loading:false}));
      this.setData({rows,error:'',manualVillage:false});
      let parentCode='';
      try{
        for(let index=0;index<rows.length;index++){
          rows[index].loading=true;this.setData({rows});
          const options=this._cache[parentCode]||await crm.regions.children(parentCode);
          if(!alive())return;
          this._cache[parentCode]=options;
          const row=rows[index];row.loading=false;row.options=options;row.disabled=!options.length;
          row.placeholder='请选择'+row.label;
          const selected=options.findIndex(option=>String(option.code)===String(value.regionCode||'').slice(0,row.length)||option.name===value[row.key]);
          row.index=Math.max(0,selected);
          this.setData({rows,manualVillage:index===4&&!options.length});
          if(selected<0)break;
          parentCode=String(options[selected].code);
        }
      }catch(error){
        if(alive()){rows.forEach(row=>{row.loading=false;});this.setData({rows,error:error.message||'地址选项加载失败，请重试'});}
      }
    },
    choose(event){
      if(this.data.disabled)return;
      const index=Number(event.currentTarget.dataset.level),row=this.data.rows[index];
      const option=row&&row.options[Number(event.detail.value)];if(!option)return;
      const value={...this.data.value,regionCode:String(option.code)};
      LEVELS.forEach((level,i)=>{if(i===index)value[level.key]=option.name;else if(i>index)value[level.key]='';});
      this._signature=signature(value);this.triggerEvent('change',{value});this.load(value);
    },
    manual(event){
      const value={...this.data.value,village:event.detail.value};
      this._signature=signature(value);this.triggerEvent('change',{value});
    },
    focus(){this.triggerEvent('fieldfocus',{manual:true});},
    blur(){this.triggerEvent('fieldblur');},
    measureManualField(callback){this.createSelectorQuery().select('.address-manual').boundingClientRect(callback).exec();},
    retry(){this.load(this.data.value||{});}
  }
});
