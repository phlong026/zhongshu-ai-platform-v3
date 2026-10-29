const B=require('../../utils/business');
const kv=(label,value)=>({type:'kv',label,value});
const text=(value,style)=>({type:'text',value,style:style||''});
const file=(name,key)=>({type:'file',name,key:key||'sample',icon:/\.(jpg|png|jpeg)$/i.test(name)?'image':'pdf'});
const input=(label,key,placeholder,unit)=>({type:'input',label,key,placeholder:placeholder||'',unit:unit||''});
const upload=(label,key)=>({type:'upload',label,key});
const check=(label,key)=>({type:'check',label,key});
const notice=(value)=>({type:'notice',value});
const stats=(values)=>({type:'stats',values});
const section=(title,items,extra)=>Object.assign({title,items},extra||{});
const titles={customers:'我的客户',customer:'客户工作区',arrival:'客户工作区',progress:'客户工作区',inspiration:'方案参考',search:'户型检索',case:'案例详情','case-plan':'户型详情','ai-config':'平面方案','ai-results':'平面方案候选',result:'平面方案结果',budget:'报价测算','budget-result':'报价测算结果',history:'历史版本',preview:'附件预览','ai-facade':'立面方案'};
const defaults={name:'',phone:'',wechat:'',source:'视频号咨询',province:'湖北省',city:'武汉市',summary:'',method:'电话',followResult:'已联系',followContent:'',next:'到店沟通',appointment:'',width:'12.5',depth:'16',floors:'2',orientation:'南',courtyard:'前院',drawingType:'彩色平面',notes:'一层安排老人房，客餐厅通透；二层保留家庭起居区。',resolution:'2K',aspect:'横图',count:'2',footprint:'200',area:'400',roofArea:'220',foundation:'独立基础',structure:'框架结构',roof:'坡屋顶',quotePrice:'1050',otherPrice:'160000',extraPrice:'100000',signedDate:'',requestText:'现代简约立面；一层老人房、客餐厅与厨房；二层三间卧室，保留南向采光。',amount:'',paymentDate:'',transaction:'',verified:false,intakeChecked:false,designer:'林设计师',dueDate:'',deliverableNote:'按电子需求 v1 完成平面、立面及配套设计，详见本次成果文件。',drawingVersion:'v1',manager:'李经理',handoverNote:'按补传施工图 v1 交底；核对宅基地南侧高差及一楼老人房门洞位置。',receiveContract:false,receiveDrawing:false,receiveBrief:false,confirmationChecked:false,returnReason:'',receiveDate:'',budgetChecked:true};
function config(view,c,form) {
 let out={title:titles[view]||'项目办理',sections:[],primary:'',secondary:'',badge:'',role:'',large:true,tabs:[],activeTab:0};
 if(view==='history')Object.assign(out,{title:'方案与预算历史',sections:[section('方案与预算版本',c.plans.map(x=>kv('平面方案','V'+x.version)).concat(c.budgets.map(x=>kv('参考预算 V'+x.version,'¥ '+B.money(x.total)))))],primary:'返回客户工作区',primaryView:'customer'});
 return out;
}
Object.assign(titles,{transfer:'责任转交','facade-config':'立面方案','facade-candidates':'立面方案候选','facade-result':'立面方案结果'});
Object.assign(defaults,{requestScope:'',designBasisChecked:false,designReceiveChecked:false,designReceiveNote:'',designFeedbackNote:'',designFeedbackFile:null,style:'现代简约',palette:'米白 + 深灰',facadeNotes:'白墙灰瓦、入口简洁大气，适当增加木色细节。',newOwner:'刘业务',transferReason:'人员调岗，客户资料及待跟进事项一并转交。',tailApplyNote:'正式成果 v1 已完成，请按原设计服务单办理尾款。',receiptPurpose:'客户定金',receiptReceiver:'王财务',receiptTransaction:'',facadeArea:'400'});
defaults.exterior={window:true,paint:true,stone:true,light:true,waterproof:true,lightning:true,insurance:true};
defaults.sitePhotos=['/pages/workflow/assets/biz-site.jpg','/pages/workflow/assets/biz-measure.jpg'];
module.exports={titles,defaults,config};
