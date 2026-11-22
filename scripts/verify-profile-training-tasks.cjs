const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const Learning = require('../miniprogram/utils/learning-catalog');
const Profile = require('../miniprogram/utils/platform-profile');
const flush = async () => { for (let n=0;n<15;n++) await new Promise(resolve=>setImmediate(resolve)); };
const allText = nodes => nodes.map(n => (n.text || '') + allText(n.children)).join('');
const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children)]);
const hasClass = (node, name) => String(node.attrs?.class || '').split(/\s+/).includes(name);
const franchiseCourses = {
  boss: ['ai-video','sales','engineering','finance','design','management'],
  owner: ['ai-video','sales','engineering','finance','design','management'],
  sales: ['ai-video','sales'], 'sales-lead': ['ai-video','sales'],
  engineer: ['engineering'], 'project-manager': ['engineering'],
  finance: ['finance'], 'finance-lead': ['finance']
};

async function setup(role, handler=()=>undefined) {
  const runtime=await connect(),auth=require('../miniprogram/api/auth');
  const post={postId:'post-'+role,userId:7,orgId:'store',orgType:role==='hq-design-admin'?'headquarters':'store',roleCode:role,orgName:'合家美宅-江西南昌-高新区合家美宅-裴火荣'};
  auth.saveLogin({accessToken:'test-token',userId:7});auth.savePosts([post]);auth.switchPost(post.postId);
  const profile={id:7,nickname:'裴火荣',mobile:'13800000000',avatar:'https://example.com/old.png'};
  const calls=[];
  wx.request=options=>{
    const url=new URL(options.url).pathname.replace('/admin-api','');calls.push({url,method:options.method,data:options.data});
    let data=handler(url,options);
    if(data===undefined&&url.startsWith('/training/app/')){
      const role=auth.currentApiPost()?.roleCode,allowed=Learning.coursesFor(role);
      const categories={'ai-video':'AI短视频获客',sales:'业务销售',engineering:'工程施工',finance:'财务对账',design:'设计交付',management:'经营管理'};
      const brief=c=>({id:c.id,title:c.name,category:categories[c.id],summary:'',lessons:c.lessons,completed:0,percent:0,coverVersionId:''});
      const resources=Learning.resourcesFor(role).map(r=>({...r,versionId:r.id,fileName:r.name+'.pdf',extension:'pdf',preview:true,download:false,version:1}));
      if(url==='/training/app/courses')data=allowed.map(brief);
      else if(url==='/training/app/materials')data=resources;
      else if(url.startsWith('/training/app/courses/')){const id=url.split('/').at(-1),c=allowed.find(c=>c.id===id);data=c?{...brief(c),lessons:Learning.chapters[id].map((title,i)=>({id:id+'-'+i,title,chapter:'岗位实操',attachments:[],completed:false})),attachments:resources.filter(r=>r.courseId===id)}:{__error:'当前岗位无此学习内容权限'};}
    }
    if(data===undefined&&url==='/platform/profile/mine'){
      if(options.method==='PUT'){
        profile.nickname=options.data.nickname.trim();
        if(Object.prototype.hasOwnProperty.call(options.data,'avatar'))profile.avatar=options.data.avatar||null;
      }
      data={...profile}; // 后端 GET/PUT 均返回完整、已持久化的本人资料。
    }
    if(data===undefined)data=url==='/platform/messages/unread-count'?{count:0}:[];
    setImmediate(()=>options.success({statusCode:200,data:data&&data.__error?{code:403,msg:data.__error,data:null}:{code:0,data}}));
  };
  return {runtime,auth,post,calls,async open(url){await runtime.callWxMethod('navigateTo',{url});await flush();return getCurrentPages().at(-1);}};
}

test('姓名下只保留品牌、地区、岗位，删除组织名称中重复品牌和负责人姓名',()=>{
  assert.equal(Profile.compactIdentity('合家美宅-江西南昌-高新区合家美宅-裴火荣','老板'),'合家美宅 · 江西南昌高新 · 老板');
  assert.equal(Profile.compactIdentity('总部','财务专员'),'总部 · 财务');
  assert.equal(Profile.compactIdentity('',''),'');
});

test('所有岗位课程和资料使用同一规则，未知身份不显示课程',()=>{
  for(const role of Object.keys(Learning.roleCourses)) {
    const courses=Learning.coursesFor(role),resources=Learning.resourcesFor(role);
    assert.ok(courses.length>0);
    assert.ok(resources.every(resource=>Learning.canStudy(role,resource.courseId)));
    assert.ok(courses.every(course=>Learning.chapters[course.id].length===course.lessons));
  }
  assert.deepEqual(Learning.coursesFor('finance').map(c=>c.id),['finance']);
  assert.deepEqual(Learning.coursesFor('engineer').map(c=>c.id),['engineering']);
  assert.deepEqual(Learning.coursesFor('boss').map(c=>c.id),Learning.courses.map(c=>c.id));
  assert.deepEqual(Learning.coursesFor('owner').map(c=>c.id),Learning.courses.map(c=>c.id));
  assert.deepEqual(Object.keys(franchiseCourses).sort(),[...require('../miniprogram/utils/organization').templates.store].sort());
  assert.deepEqual(Learning.coursesFor('unknown'),[]);
});

for(const [role,expected] of Object.entries(franchiseCourses)) test(role+'从服务端展示课程、详情和资料，个人主页保留学习入口',async()=>{
 const env=await setup(role),profile=await env.open('/pages/profile/index');
 assert.equal(profile.data.showStoreServices,true);assert.equal(profile.data.learningAvailable,true);
 assert.equal(profile.data.courseId,['boss','owner'].includes(role)?'management':expected[0]);
 const nodes=render(path.resolve(__dirname,'../miniprogram/pages/profile/index.wxml'),profile.data);
 assert.ok(allText(nodes).includes('学习培训')&&allText(nodes).includes('加盟商服务'));
 let page=await env.open('/pages/account/index?view=learning');
 assert.equal(page.route,'pages/account/training/index');assert.deepEqual(page.data.visibleCourses.map(c=>c.id),expected);
 assert.deepEqual(page.data.visibleResources.map(r=>r.courseId),Learning.resourcesFor(role).map(r=>r.courseId));
 for(const c of Learning.courses){page=await env.open('/pages/account/index?view=course&id='+c.id);if(expected.includes(c.id)){assert.equal(page.data.course.id,c.id);assert.equal(page.data.course.lessons.length,c.lessons);}else{assert.equal(page.data.course,null);assert.match(page.data.error,/无此学习内容权限/);}}
 assert.ok(env.calls.some(c=>c.url==='/training/app/courses'));assert.ok(!env.calls.some(c=>c.url==='/franchise/learning/progress'));
});

test('切换岗位后重新请求服务端目录并移除旧岗位课程',async()=>{
 const env=await setup('owner'),list=await env.open('/pages/account/index?view=learning');
 assert.equal(list.data.visibleCourses.length,6);
 env.auth.savePosts([env.post,{...env.post,postId:'post-finance',roleCode:'finance'}]);env.auth.switchPost('post-finance');list.onShow();await flush();
 assert.deepEqual(list.data.visibleCourses.map(c=>c.id),['finance']);
 env.auth.switchPost('post-owner');list.onShow();await flush();assert.equal(list.data.visibleCourses.length,6);
});

test('服务端未发布课程时不回落本地演示目录',async()=>{
 const env=await setup('boss',url=>url.startsWith('/training/app/')?[]:undefined),page=await env.open('/pages/account/index?view=learning');
 assert.deepEqual(page.data.visibleCourses,[]);assert.equal(page.data.resumeCourse,null);
});

test('未登录的我的页展示培训课程，课程入口登录后再访问且不加载个人进度',async()=>{
  const runtime=await connect(),requests=[],navigations=[];
  wx.request=options=>requests.push(options);
  await runtime.callWxMethod('navigateTo',{url:'/pages/profile/index'});await flush();
  const page=getCurrentPages().at(-1),ui=await runtime.currentPage();
  assert.equal(page.data.loggedIn,false);assert.equal(page.data.learningAvailable,true);
  assert.equal(page.data.courseId,'ai-video');assert.ok(page.data.courseImage);
  const text=allText(render(path.resolve(__dirname,'../miniprogram/pages/profile/index.wxml'),page.data));
  assert.ok(text.includes('学习培训')&&text.includes('AI短视频获客实战')&&text.includes('登录学习'));
  assert.ok(!text.includes('已完成'));assert.equal(requests.length,0);
  wx.navigateTo=options=>{navigations.push(options.url);return {};};
  for(const [selector,target] of [
    ['.academy-section [data-action="learning"]','/pages/account/index?view=learning'],
    ['.academy-section .course-card','/pages/account/index?view=course&id=ai-video']
  ]){
    await (await ui.$(selector)).tap();
    const url=new URL(navigations.at(-1),'http://local');
    assert.equal(url.pathname,'/pages/account/index');assert.equal(url.searchParams.get('view'),'login');
    assert.equal(url.searchParams.get('return'),target);
  }
  assert.equal(requests.length,0);
});

test('头像上传和资料保存全部成功才更新显示，保存失败保留原头像',async()=>{
  const env=await setup('sales');const page=await env.open('/pages/account/index?view=profile');
  let uploads=0;wx.uploadFile=options=>{uploads++;assert.equal(options.name,'file');setImmediate(()=>options.success({statusCode:200,data:JSON.stringify({code:0,data:'https://example.com/new.png'})}));};
  const oldRequest=wx.request;
  wx.request=options=>options.method==='PUT'?setImmediate(()=>options.success({statusCode:200,data:{code:500,msg:'系统异常'}})):oldRequest(options);
  await page.onChooseAvatar({detail:{avatarUrl:'wxfile://avatar.png'}});
  assert.equal(page.data.profile.avatar,'https://example.com/old.png');assert.match(page.data.error,/系统异常/);assert.equal(page.data.busy,false);
  wx.request=oldRequest;await page.onChooseAvatar({detail:{avatarUrl:'wxfile://avatar.png'}});
  assert.equal(page.data.profile.avatar,'https://example.com/new.png');assert.equal(uploads,2);
  const writes=env.calls.filter(call=>call.method==='PUT').length;
  wx.uploadFile=options=>options.success({statusCode:200,data:JSON.stringify({code:0,data:'wxfile://not-a-public-url'})});
  await page.onChooseAvatar({detail:{avatarUrl:'wxfile://avatar.png'}});
  assert.equal(env.calls.filter(call=>call.method==='PUT').length,writes);assert.equal(page.data.profile.avatar,'https://example.com/new.png');
  const markup=fs.readFileSync(path.resolve(__dirname,'../miniprogram/pages/account/index.wxml'),'utf8');
  const section=markup.slice(markup.indexOf("view==='profile'"),markup.indexOf("view==='learning'"));
  assert.ok(!/我的工作身份|资料使用说明|公司与岗位由管理员维护|点击头像可使用/.test(section));
});

test('上月自定义任务来自服务端，复用不复制完成状态，确认派发后下月仍可再用',async()=>{
  const records=new Map([['2026-11',[{id:'9007199254741001',period:'2026-11',title:'每周回访重点客户',type:'manual',targetCents:0,assigneeUserId:'7',assigneeName:'张业务',assigneeDept:'business',due:'2026-11-30',status:'已完成',manualDone:'{"note":"已完成"}'}]]]);
  const created=[];
  const env=await setup('boss',(url,options)=>{
    if(url==='/platform/org/staff')return[{userId:'7',postId:'p7',name:'张业务',roleCode:'sales',active:true}];
    if(url==='/franchise/performance/tasks'){
      if(options.method==='GET')return records.get(options.data.period)||[];
      created.push(options.data);const row={...options.data,id:'9007199254741002',status:'进行中'};records.set(row.period,[row]);return row;
    }
  });
  let page=await env.open('/pages/admin/tasks/index?view=team&period=2026-12');
  let ui=await env.runtime.currentPage();
  await (await ui.$('.task-dropdown-toggle')).tap();assert.equal(page.data.reusableTasks.length,1);
  const markupText=allText(render(path.resolve(__dirname,'../miniprogram/pages/admin/tasks/index.wxml'),page.data));
  assert.ok(!/上月实绩建议|生成本月建议任务|复用上月自定义任务/.test(markupText));
  await (await ui.$('[data-id="reuse:9007199254741001"]')).tap();
  assert.equal(page.data.form.title,'每周回访重点客户');assert.equal(page.data.form.due,'2026-12-30');assert.equal(created.length,0);
  assert.equal(page.data.form.manualDone,undefined);assert.equal(page.data.form.assigneeId,'7');
  await page.submitTask({detail:{value:{}}});await flush();
  assert.equal(created.length,1);assert.equal(created[0].targetCents,0);assert.equal(created[0].period,'2026-12');
  assert.equal(page.data.rows[0].status,'进行中');
  page=await env.open('/pages/admin/tasks/index?view=team&period=2027-01');await page.loadReusableTasks();
  assert.equal(page.data.reusableTasks[0].title,'每周回访重点客户');assert.equal(page.data.reusableTasks[0].manualDone,null);
  records.set('2027-01',[{...records.get('2026-12')[0],period:'2027-01',due:'2027-01-31'}]);
  page=await env.open('/pages/admin/tasks/index?view=team&period=2027-02');await page.loadReusableTasks();
  page.useReusableTask({currentTarget:{dataset:{id:'9007199254741002'}}});assert.equal(page.data.form.due,'2027-02-28');
});

test('一个任务输入框可选择业务指标或直接自定义，按类型校验目标后派发',async()=>{
  const created=[];
  const env=await setup('boss',(url,options)=>{
    if(url==='/platform/org/staff')return[{userId:'7',postId:'p7',name:'张业务',roleCode:'sales',active:true}];
    if(url==='/franchise/performance/tasks'&&options.method==='POST'){created.push(options.data);return {id:'8',...options.data};}
  });
  const page=await env.open('/pages/admin/tasks/index?view=team&period=2026-11');
  const ui=await env.runtime.currentPage();
  await (await ui.$('.task-dropdown-toggle')).tap();
  await (await ui.$('[data-id="type:auto:collectionsAmount"]')).tap();
  assert.equal(page.data.form.type,'auto:collectionsAmount');assert.equal(page.data.form.title,'回款金额');
  await (await ui.$('.task-select')).trigger('change',{value:0});
  await page.submitTask({detail:{value:{}}});assert.equal(created.length,0);assert.match(page.data.error,/目标值/);
  page.field({currentTarget:{dataset:{key:'target'}},detail:{value:'123.45'}});
  await page.submitTask({detail:{value:{}}});await flush();
  assert.equal(created[0].targetCents,12345);assert.equal(created[0].type,'auto:collectionsAmount');
  await (await ui.$('.task-title-input')).input('安排客户回访');
  assert.equal(page.data.form.type,'manual');assert.equal(page.data.form.target,'');
  await (await ui.$('[data-id="manual"]')).tap();
  assert.equal(page.data.taskOptionsOpen,false);assert.equal(page.data.form.type,'manual');
  await (await ui.$('.task-select')).trigger('change',{value:0});
  await page.submitTask({detail:{value:{}}});await flush();
  assert.equal(created[1].title,'安排客户回访');assert.equal(created[1].targetCents,0);
});

test('派发响应丢失后原内容重试复用键，成功后再次派发使用新键',async()=>{
  const env=await setup('boss',url=>url==='/platform/org/staff'?[{userId:'7',postId:'p7',name:'张业务',roleCode:'sales',active:true}]:undefined);
  const page=await env.open('/pages/admin/tasks/index?view=team&period=2026-11');
  const original=wx.request,stored=new Map(),attempts=[];let loseResponse=true;
  wx.request=options=>{
    if(options.method!=='POST'||!options.url.endsWith('/franchise/performance/tasks'))return original(options);
    const key=options.header['Idempotency-Key'],body=JSON.stringify(options.data);attempts.push({key,body});
    if(stored.has(key))assert.equal(stored.get(key),body,'相同幂等键必须对应同一请求内容');
    else stored.set(key,body);
    setImmediate(()=>loseResponse?options.fail({errMsg:'响应丢失'}):options.success({statusCode:200,data:{code:0,data:{id:'8'}}}));
  };
  const form={title:'每周回访重点客户',assigneeId:'7',due:'2026-11-30',type:'manual',target:''};
  page.setData({form});await page.submitTask({detail:{value:{}}});
  assert.equal(page.data.form.title,form.title);assert.equal(stored.size,1);assert.equal(page.data.submitting,false);
  loseResponse=false;await page.submitTask({detail:{value:{}}});await flush();
  assert.equal(stored.size,1);assert.equal(attempts[0].key,attempts[1].key);assert.equal(page.data.form.title,'');
  page.setData({form});await page.submitTask({detail:{value:{}}});await flush();
  assert.equal(stored.size,2);assert.notEqual(attempts[1].key,attempts[2].key);
});

test('派发失败后改内容、责任人、日期或身份会换键；目标数字格式变化不换键',async()=>{
  const env=await setup('boss',url=>url==='/platform/org/staff'?[
    {userId:'7',postId:'p7',name:'张业务',roleCode:'sales',active:true},
    {userId:'9',postId:'p9',name:'李业务',roleCode:'sales',active:true}]:undefined);
  const page=await env.open('/pages/admin/tasks/index?view=team&period=2026-11');
  const original=wx.request,attempts=[];
  wx.request=options=>{
    if(options.method!=='POST'||!options.url.endsWith('/franchise/performance/tasks'))return original(options);
    attempts.push({key:options.header['Idempotency-Key'],body:options.data});
    setImmediate(()=>options.fail({errMsg:'网络异常'}));
  };
  page.setData({form:{title:'回款金额',assigneeId:'7',due:'2026-11-30',type:'auto:collectionsAmount',target:'1'}});
  const submit=()=>page.submitTask({detail:{value:{}}});
  await submit();await submit();assert.equal(attempts[0].key,attempts[1].key);
  page.setData({'form.target':'1.00'});await submit();assert.equal(attempts[1].key,attempts[2].key);
  for(const patch of [{'form.title':'本月回款'},{'form.assigneeId':'9'},{'form.due':'2026-11-29'},{'form.target':'2'}]){
    const before=attempts.at(-1).key;page.setData(patch);await submit();assert.notEqual(attempts.at(-1).key,before);
  }
  const before=attempts.at(-1).key;
  require('../miniprogram/api/http').saveSession({token:'refreshed-token'});await submit();
  assert.notEqual(attempts.at(-1).key,before);
  const previous=attempts.at(-1).key;
  page.month({detail:{value:'2026-12'}});await flush();
  page.setData({form:{title:'本月回款',assigneeId:'9',due:'2026-12-29',type:'auto:collectionsAmount',target:'2'}});await submit();
  assert.notEqual(attempts.at(-1).key,previous);assert.equal(attempts.at(-1).body.period,'2026-12');
});

test('完成凭证失败后原内容重试复用键，改说明或换任务后换键',async()=>{
  const env=await setup('sales',(url,options)=>url==='/franchise/performance/tasks'&&options.method==='GET'?[
    {id:'8',period:'2026-11',title:'客户回访',type:'manual',assigneeUserId:'7',due:'2026-11-30'},
    {id:'9',period:'2026-11',title:'整理资料',type:'manual',assigneeUserId:'7',due:'2026-11-30'}]:undefined);
  const page=await env.open('/pages/admin/tasks/index?view=mine&period=2026-11');
  const original=wx.request,attempts=[];
  wx.request=options=>{
    if(options.method!=='POST')return original(options);
    attempts.push({key:options.header['Idempotency-Key'],body:options.data});
    setImmediate(()=>options.fail({errMsg:'网络异常'}));
  };
  page.beginManual({currentTarget:{dataset:{id:'8'}}});
  const submit=note=>page.completeManual({detail:{value:{note}}});
  await submit('已经回访');await submit('已经回访');
  assert.equal(attempts[0].key,attempts[1].key);
  page.setData({'evidence.photos':['wxfile://local-preview.png']});await submit('已经回访');
  assert.equal(attempts[1].key,attempts[2].key);assert.deepEqual(attempts[2].body.photos,[]);
  await submit('已回访并登记需求');assert.notEqual(attempts[2].key,attempts[3].key);
  page.beginManual({currentTarget:{dataset:{id:'9'}}});await submit('已回访并登记需求');
  assert.notEqual(attempts[3].key,attempts[4].key);
});

test('切换月份后，上月任务的迟到响应不会填回新的月份',async()=>{
  const env=await setup('boss');
  const page=await env.open('/pages/admin/tasks/index?view=team&period=2026-11');
  const original=wx.request;let pending;
  wx.request=options=>{
    if(options.method==='GET'&&options.url.endsWith('/franchise/performance/tasks')&&options.data.period==='2026-10')pending=options;
    else original(options);
  };
  const load=page.openTaskOptions();assert(pending);assert.equal(page.data.reuseBusy,true);
  page.month({detail:{value:'2026-12'}});await flush();
  pending.success({statusCode:200,data:{code:0,data:[{id:'old',title:'旧月份任务',type:'manual',assigneeUserId:'7'}]}});
  await load;await flush();
  assert.equal(page.data.period,'2026-12');assert.equal(page.data.reuseBusy,false);assert.equal(page.data.taskOptionsOpen,false);
  assert.deepEqual(page.data.reusableTasks,[]);assert.equal(page.data.form.title,'');
  await page.openTaskOptions();assert.equal(page.data.reusePeriod,'2026-11');
  assert.ok(!page.data.taskOptions.some(option=>option.label==='旧月份任务'));
});
