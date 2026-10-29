// Opt-in local demo logins only. Existing records and real sessions are never reset.
const KEY=require('./storage-keys').salesDemo;
function ensure(){
 const A=require('./account'),B=require('./business'),S=require('./studio'),X=require('./access'),a=X.context(),session=wx.getStorageSync(A.KEY)?.session;
 if(!a.active||!session?.demo||!require('./organization').has(a.role,'sales'))return false;
 const marker=wx.getStorageSync(KEY)||{version:1,owners:{}},scope=a.companyId+':'+a.staffId;if(marker.owners[scope]?.seedVersion===3)return false;
 const keys=[B.KEY,require('./storage-keys').management,S.scope(),S.scopedKey(require('./storage-keys').creationDraft),S.scopedKey(require('./storage-keys').videoDraft),KEY],backup=keys.map(k=>[k,wx.getStorageSync(k)]),day=A.localDate(),salt=Array.from(a.staffId).reduce((sum,c)=>sum+c.charCodeAt(0),0)%800;
 try{
  const ids=[],current=B.load().customers.find(c=>c.id==='customer-chen');if(current)ids.push(current.id);
  const examples=[{name:'张先生',city:'武汉市江夏区',summary:'两层新中式，一层安排老人房，先了解户型与预算。'},{name:'林女士',city:'武汉市蔡甸区',summary:'已到店讨论家庭需求，希望继续平面与立面方案。'}];
  examples.forEach((example,i)=>{const phone='139'+String(10000000+salt*10+i),existing=B.load().customers.find(c=>c.phone===phone);let c=existing||B.add({...example,phone,source:i?'朋友介绍':'视频号咨询',province:'湖北省'});ids.push(c.id);if(i&&!c.follows.length)c=B.write(c.id,n=>{n.follows.push({method:'到店',result:'已到店',author:a.name,content:'已记录居住人数、老人房与平面布局需求。',next:'电话联系',appointment:day+' 16:30',date:day});n.status='已到店';},'预置客户跟进记录','follow');});
  const draftKey=S.scopedKey(require('./storage-keys').creationDraft);if(!wx.getStorageSync(draftKey)){const sample=require('./creation-demo').samples[0];wx.setStorageSync(draftKey,{title:sample.title,text:sample.original,workingText:sample.original,rewrittenText:'',updatedAt:Date.now(),demo:true});}
  const videoKey=S.scopedKey(require('./storage-keys').videoDraft);if(!wx.getStorageSync(videoKey))wx.setStorageSync(videoKey,{mode:'frames',description:'日间自然光，镜头缓慢推进，展示二层新中式乡墅的外观、入口与庭院。',firstFrame:'/assets/creation-house.jpg',lastFrame:'',references:[],parameters:[],updatedAt:Date.now(),demo:true});
  if(!S.read().works.length){const task=S.createDemoTask('ai','庭院生活短视频',{parameters:[{key:'count',value:'1 个'}],demoFixture:true});S.completeTask(task.id);S.saveWork(task.id);}
  marker.owners[scope]={customerIds:ids,seededAt:Date.now(),seedVersion:3};wx.setStorageSync(KEY,marker);return true;
 }catch(error){for(const[k,v]of backup){if(v===undefined||v==='')wx.removeStorageSync(k);else wx.setStorageSync(k,v);}throw error;}
}
module.exports={KEY,ensure};
