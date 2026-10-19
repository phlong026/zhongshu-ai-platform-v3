// Opt-in local demo logins only. Existing records and real sessions are never reset.
const KEY='zhongshu-sales-demo-v1';
function ensure(){
 const A=require('./account'),B=require('./business'),S=require('./studio'),X=require('./access'),a=X.context(),session=wx.getStorageSync(A.KEY)?.session;
 if(!a.active||!session?.demo||!require('./organization').has(a.role,'sales')||wx.getStorageSync('zhongshu-live-demo-v1')||wx.getStorageSync('zhongshu-demo-session-v1'))return false;
 const marker=wx.getStorageSync(KEY)||{version:1,owners:{}},scope=a.companyId+':'+a.staffId;if(marker.owners[scope]?.seedVersion===2)return false;
 const keys=[B.KEY,'zhongshu-management-v1',S.scope(),S.scopedKey('zhongshu-creation-draft'),S.scopedKey('zhongshu-video-draft'),KEY],backup=keys.map(k=>[k,wx.getStorageSync(k)]),day=A.localDate(),tomorrow=A.localDate(Date.now()+86400000),salt=Array.from(a.staffId).reduce((sum,c)=>sum+c.charCodeAt(0),0)%800;
 try{
  const examples=[{name:'张先生',city:'武汉市江夏区',summary:'两层新中式，一层安排老人房，先了解户型与预算。'},{name:'陈先生',city:'武汉市黄陂区',summary:'已有宅基地，预约到店沟通两层乡墅与建造费用。'},{name:'林女士',city:'武汉市蔡甸区',summary:'已到店讨论家庭需求，希望继续平面与立面方案。'}],ids=[];
  examples.forEach((example,i)=>{const phone='139'+String(10000000+salt*10+i),existing=B.load().customers.find(c=>c.phone===phone);let c=existing||B.add({...example,phone,source:i===1?'朋友介绍':'视频号咨询',province:'湖北省'});ids.push(c.id);if(i&&!c.follows.length)c=B.write(c.id,n=>{n.follows.push({method:i===2?'到店':'电话',result:i===2?'已到店':'已联系',author:a.name,content:i===2?'已记录居住人数、老人房与平面布局需求。':'已核实建房需求，约定下次到店沟通。',next:i===1?'到店沟通':'电话联系',appointment:(i===1?tomorrow:day)+(i===1?' 10:00':' 16:30'),date:day});n.status=i===2?'已到店':'跟进中';},'预置本地模拟跟进','follow');});
  const draftKey=S.scopedKey('zhongshu-creation-draft');if(!wx.getStorageSync(draftKey)){const sample=require('./creation-demo').samples[0];wx.setStorageSync(draftKey,{title:sample.title,text:sample.original,workingText:sample.original,rewrittenText:sample.original,updatedAt:Date.now(),demo:true});}
  const videoKey=S.scopedKey('zhongshu-video-draft');if(!wx.getStorageSync(videoKey))wx.setStorageSync(videoKey,{mode:'frames',description:'日间自然光，镜头缓慢推进，展示二层新中式乡墅的外观、入口与庭院。',firstFrame:'/assets/creation-house.jpg',lastFrame:'',references:[],parameters:[],updatedAt:Date.now(),demo:true});
  if(!S.read().works.length){const task=S.createDemoTask('ai','庭院生活短视频',{parameters:[{key:'count',value:'1 个'}],demoFixture:true});S.completeTask(task.id);S.saveWork(task.id);}
  marker.owners[scope]={customerIds:ids,seededAt:Date.now(),seedVersion:2};wx.setStorageSync(KEY,marker);return true;
 }catch(error){for(const[k,v]of backup){if(v===undefined||v==='')wx.removeStorageSync(k);else wx.setStorageSync(k,v);}throw error;}
}
module.exports={KEY,ensure};
