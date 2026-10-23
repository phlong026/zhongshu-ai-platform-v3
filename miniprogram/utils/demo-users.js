// Local test identities; effective jobs are configured on organization staff records.
const people=[{id:'hq-design-admin',staffId:'hq-design-admin',name:'林设计',roles:['hq-design-admin'],companyId:'headquarters',personId:'hq-design-admin'},{"id":"zhang","staffId":"zhang","name":"张业务","roles":["sales"],"companyId":"quanzhou","personId":"zhang"},{"id":"liu","staffId":"liu","name":"刘业务","roles":["sales"],"companyId":"quanzhou","personId":"liu"},{"id":"sales-lead","staffId":"zhao-sales-lead","name":"业务负责人","roles":["sales-lead"],"companyId":"quanzhou","personId":"zhao-sales-lead"},{"id":"sales-dual","staffId":"sales-dual","name":"李业务","roles":["sales","sales-lead"],"companyId":"quanzhou","personId":"sales-dual"},{"id":"manager-li","staffId":"li","name":"李工程师","roles":["engineer"],"companyId":"quanzhou","personId":"li"},{"id":"manager-wang","staffId":"wang-manager","name":"王工程师","roles":["engineer"],"companyId":"quanzhou","personId":"wang-manager"},{"id":"engineering-lead","staffId":"engineering-lead","name":"项目经理","roles":["project-manager"],"companyId":"quanzhou","personId":"engineering-lead"},{"id":"engineering-dual","staffId":"engineering-dual","name":"张工程","roles":["engineer","project-manager"],"companyId":"quanzhou","personId":"engineering-dual"},{"id":"finance-wang","staffId":"wang-finance","name":"王财务","roles":["finance"],"companyId":"quanzhou","personId":"wang-finance"},{"id":"finance-li","staffId":"li-finance","name":"李财务","roles":["finance"],"companyId":"quanzhou","personId":"li-finance"},{"id":"finance-lead","staffId":"chen-finance-lead","name":"门店财务负责人","roles":["finance-lead"],"companyId":"quanzhou","personId":"chen-finance-lead"},{"id":"finance-dual","staffId":"finance-dual","name":"王财务","roles":["finance","finance-lead"],"companyId":"quanzhou","personId":"finance-dual"},{"id":"boss","staffId":"zhao-boss","name":"张老板","roles":["boss","owner"],"companyId":"quanzhou","personId":"zhao-boss"},{"id":"store-owner","staffId":"store-owner","name":"门店负责人","roles":["owner"],"companyId":"quanzhou","personId":"store-owner"},{"id":"hq-finance","staffId":"hq-finance","name":"总部财务","roles":["finance"],"companyId":"headquarters","personId":"hq-finance"},{"id":"hq-finance-2","staffId":"hq-finance-2","name":"总部第二财务","roles":["finance"],"companyId":"headquarters","personId":"hq-finance-2"},{"id":"hq-finance-lead","staffId":"hq-finance-lead","name":"总部财务负责人","roles":["finance-lead"],"companyId":"headquarters","personId":"hq-finance-lead"},{"id":"hq-finance-lead-2","staffId":"hq-finance-lead-2","name":"总部第二财务负责人","roles":["finance-lead"],"companyId":"headquarters","personId":"hq-finance-lead-2"},{"id":"hq-finance-dual","staffId":"hq-finance-dual","name":"罗财务","roles":["finance","finance-lead"],"companyId":"headquarters","personId":"hq-finance-dual"},{"id":"hq-boss","staffId":"hq-boss","name":"总部老板","roles":["boss"],"companyId":"headquarters","personId":"hq-boss"}];
// 保留内部测试人员与历史归属，登录入口仅展示这六个账号。
const loginPeople=[
 ['sales-dual','李业务'],
 ['engineering-dual','张工程'],
 ['finance-dual','王财务'],
 ['boss','张老板'],
 ['hq-design-admin','林设计（总部设计）'],
 ['hq-finance-dual','罗财务（总部财务）']
].map(([id,label])=>({...people.find(p=>p.id===id),label}));
// 仅升级已知的旧预设姓名；自定义称呼和历史经办快照不改写。
const previousNames={
 'sales-dual':'业务兼岗人员',
 'engineering-dual':'工程兼岗人员',
 'finance-dual':'门店财务兼岗人员',
 boss:'赵老板',
 'hq-design-admin':'总部设计行政',
 'hq-finance-dual':'总部财务兼岗人员'
};
function renameDefaultName(profile,person){
 if(profile&&person&&profile.name===previousNames[person.id]){profile.name=person.name;return true;}
 return false;
}
function renameAccount(state){
 let renamed=false;
 for(const person of loginPeople){
  if(state.session?.loggedIn&&state.session.staffId===person.staffId&&state.session.companyId===person.companyId&&renameDefaultName(state.profile,person))renamed=true;
  if(renameDefaultName(state.personal?.[person.companyId+':'+person.staffId]?.profile,person))renamed=true;
 }
 return renamed;
}
// Demo handovers use the same configured engineer; preserve deliberate organization choices.
function seedEngineeringDefault(book,O){
 const settings=book.managementSettings,assignments=settings?.assignments||{};
 if(Object.prototype.hasOwnProperty.call(assignments,'engineering'))return;
 const engineer=book.staff?.find(p=>p.id==='engineering-dual'&&p.active!==false&&O.staffHas(p,'engineer'));
 if(!engineer)return;
 book.managementSettings=settings||{version:0,company:{},assignments:{},history:[],events:[]};
 book.managementSettings.assignments={...assignments,engineering:engineer.id};
}
function ensure(options={}){let raw=wx.getStorageSync('zhongshu-management-v1')||require('./management-seed')();raw.books=raw.books||{};const O=require('./organization'),localDemo=options.demo===true||wx.getStorageSync('zhongshu-account-v1')?.session?.demo===true;for(const companyId of [...new Set(people.map(p=>p.companyId))]){let book=raw.companyId===companyId?raw:raw.books[companyId];if(!book){book={version:1,companyId,staff:[],receipts:[],payments:[],payables:[],refunds:[],work:[],sources:[],archives:[],appeals:[],events:[],drafts:{},transfers:[],rules:{period:'2026-10',targets:[]},change:{confirmed:true}};raw.books[companyId]=book;}if((book.schemaVersion||0)<8){book.staff=book.staff||[];for(const m of book.staff){const rename=r=>r==='manager'?'engineer':r==='engineering-lead'?'project-manager':r;m.roles=(m.roles||[m.roleId]).filter(Boolean).map(rename);m.roleId=rename(m.roleId);if(m.roleId)m.role=O.roles[m.roleId]||m.role;}for(const p of people.filter(p=>p.companyId===companyId)){let member=book.staff.find(x=>x.id===p.staffId);if(!member){member={id:p.staffId,name:p.name,roles:p.roles.slice(),roleId:p.roles[0],role:O.roles[p.roles[0]],active:true};book.staff.push(member);}if(!member.roles)member.roles=[member.roleId];if(p.id==='boss'&&member.roles.includes('boss')&&!member.roles.includes('owner'))member.roles.push('owner');if(p.id==='manager-li'&&member.name==='李经理'||p.id==='manager-wang'&&member.name==='王经理')member.name=p.name;member.personId=member.personId||p.personId;member.teamIds=member.teamIds||Object.fromEntries(member.roles.filter(r=>O.allowed(companyId==='headquarters'?'headquarters':'store',r)).map(r=>[O.teamForRole(r),O.teamForRole(r)]));}for(const old of book.staff)if((old.roles||[]).some(r=>!O.allowed(companyId==='headquarters'?'headquarters':'store',r))){old.legacyRoles=old.roles.slice();old.roles=[];old.active=false;}book.schemaVersion=8;}for(const person of loginPeople.filter(p=>p.companyId===companyId))renameDefaultName(book.staff?.find(member=>member.id===person.staffId),person);if(localDemo&&companyId==='quanzhou')seedEngineeringDefault(book,O);}const hq=raw.companyId==='headquarters'?raw:raw.books.headquarters;if(hq&&!hq.designServicePolicy)hq.designServicePolicy={active:true,demo:true,limitCents:100000000,scope:'design-service',source:'本地模拟总部老板初始授权政策，非逐笔审批结果'};wx.setStorageSync('zhongshu-management-v1',raw);return raw;}
function login(state,id){const p=people.find(p=>p.id===id);if(!p)throw Error('请选择有效账号');const raw=ensure({demo:true}),book=raw.companyId===p.companyId?raw:raw.books[p.companyId],staff=book.staff.find(x=>x.id===p.staffId),O=require('./organization');if(!staff||staff.active===false)throw Error('该人员已停用或成员资格已撤回');const grants=(staff.roles||[]).filter(r=>O.enabled(p.companyId==='headquarters'?'headquarters':'store',r));if(!grants.length)throw Error('该人员未分配有效岗位');state.session={loggedIn:true,userId:'demo-'+p.staffId,staffId:p.staffId,companyId:p.companyId,role:O.defaultRole(grants,p.roles[0]),demo:true,roleSchemaVersion:5};state.profile={name:staff.name,phone:'',avatar:'/assets/account-avatar.jpg'};state.companies=[{id:p.companyId,type:p.companyId==='headquarters'?'headquarters':'store',name:p.companyId==='headquarters'?'众墅之家总部':'湖北加盟商',store:p.companyId==='headquarters'?'总部':'湖北店',roles:grants}];return state;}
module.exports={people,loginPeople,renameAccount,ensure,login};
