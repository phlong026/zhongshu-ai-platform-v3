// 仅复制到隔离开发者工具项目；生产 miniprogram 不加载此文件。
(function () {
  const post={postId:'991',userId:'992',orgId:'993',tenantId:'11',roleCode:'boss',orgType:'FRANCHISE',orgName:'隔离验收公司'};
  wx.setStorageSync('zs-api-token-v1','isolated-native-test');wx.setStorageSync('zs-api-post-v1',post.postId);wx.setStorageSync('zs-api-posts-v1',[post]);
  const person={id:'90071992547409930',name:'张工',who:'乡墅设计师',sell:'泸州自建房设计、施工服务',audience:'准备回乡建房的家庭',expressionStyle:'专业、通俗、不过度承诺',audienceNeeds:'预算、采光、施工质量',factualBackground:'已核实的从业资料',sampleScript:'先把预算讲清楚，再选适合家人的方案。',forbiddenClaims:'全网最低、保证零增项',enabled:true};
  const state=globalThis.__creationFixture={people:[person],clones:[],tasks:[],calls:[],post,works:[]};
  const readyPair=()=>[{id:'31',personaId:person.id,kind:'AVATAR',title:'张工口播分身',status:'READY',ready:true},{id:'32',personaId:person.id,kind:'VOICE',title:'张工克隆声音',status:'READY',ready:true,demoUrl:'https://fixture.example/demo.mp3'}];
  state.readyPair=readyPair;
  wx.request=function(o){
    const route=String(o.url).split('/admin-api')[1]?.split('?')[0]||'',method=String(o.method||'GET').toUpperCase(),body=o.data||{};state.calls.push({route,method,body});let data=[];
    if(route==='/platform/posts/mine')data=[post];
    else if(route==='/platform/profile/mine')data={nickname:'隔离验收'};
    else if(route==='/franchise/content/personas'){
      if(method==='POST'){data={...body,id:String(BigInt(person.id)+BigInt(state.people.length))};state.people.push(data);}else data=state.people;
    }else if(/^\/franchise\/content\/personas\/\d+$/.test(route)){const id=route.split('/').pop();data=state.people.find(p=>p.id===id);if(data&&method==='PUT')Object.assign(data,body);}
    else if(/\/personas\/\d+\/clones$/.test(route)){
      const id=route.split('/')[4];if(method==='POST'){data={id:String(100+state.clones.length),personaId:id,kind:body.kind,title:body.title,status:'RUNNING',ready:false};state.clones.push(data);}else data=state.clones.filter(c=>c.personaId===id);
    }else if(route==='/franchise/content/clones/quote')data={available:true,credits:5,consentVersion:'digital-clone-v1'};
    else if(/\/clones\/\d+\/refresh$/.test(route)){data=state.clones.find(c=>c.id===route.split('/')[4]);if(data)Object.assign(data,{status:'READY',ready:true,demoUrl:data.kind==='VOICE'?'https://fixture.example/demo.mp3':null});}
    else if(route==='/franchise/content/tasks'&&method==='POST'){data={id:'501',taskId:'501',kind:'digital',status:'RUNNING',title:body.title};state.tasks.push({...data,result:{text:body.text}});}
    else if(route==='/franchise/content/tasks')data=state.tasks;
    else if(route==='/franchise/content/scripts/optimize')data={text:'大家好，我是张工。在泸州准备自建房，先把预算、采光和施工质量这三件事想清楚。'};
    else if(route==='/franchise/company-points/summary')data={balance:1000,inFlight:0,status:'ACTIVE'};
    else if(route==='/platform/messages/unread-count')data={count:0};
    else if(/\/tasks\/\d+\/save-work$/.test(route)){data={workId:'601'};state.works.push(data);}
    else if(/\/tasks\/\d+$/.test(route))data={id:'501',kind:'digital',status:'SUCCEEDED',resultUrl:'https://fixture.example/digital.mp4',result:{text:'张工口播成片',durationSec:30}};
    else if(/result-url-status$/.test(route))data={expired:false};
    setTimeout(()=>{const response={statusCode:200,data:{code:0,data}};if(o.success)o.success(response);if(o.complete)o.complete(response);},10);
    return {abort(){}};
  };
})();
