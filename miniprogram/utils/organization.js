const roles={'hq-admin':'总部管理员','hq-design-admin':'设计行政',sales:'业务员','sales-lead':'业务负责人',engineer:'工程人员','project-manager':'工程负责人',finance:'财务专员','finance-lead':'财务负责人',boss:'老板',owner:'门店负责人','hq-operations':'运营专员','hq-operations-lead':'运营负责人'};
const templates={store:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner'],headquarters:['hq-admin','hq-operations','hq-operations-lead','finance','finance-lead','boss']};
const teamForRole=r=>r==='hq-admin'?'management':['sales','sales-lead'].includes(r)?'sales':['engineer','project-manager'].includes(r)?'engineering':r.startsWith('hq-operations')?'operations':r.startsWith('hq-')?'design':['finance','finance-lead'].includes(r)?'finance':'management';
const allowed=(type,role)=>(templates[type]||[]).includes(role);
const enabled=(type,role)=>allowed(type,role)&&(type!=='headquarters'||['hq-admin','finance','finance-lead','boss'].includes(role));
const headquartersReader=c=>!!c&&c.orgType==='headquarters'&&['hq-admin','finance','finance-lead'].includes(c.roleCode||c.role);
const home=c=>c.active&&c.api&&headquartersReader(c)?'headquarters':!c.active||c.role==='hq-admin'?'profile':['finance','finance-lead','boss','owner'].includes(c.role)?'management':['engineer','project-manager'].includes(c.role)?'engineering':'business';
function navigation(c){
 const guest=!c.loggedIn,boss=['boss','owner'].includes(c.role),canBusiness=c.active&&(boss||['sales','sales-lead'].includes(c.role)),canEngineering=c.active&&(boss||['engineer','project-manager'].includes(c.role)),canManagement=c.active&&(boss||['finance','finance-lead'].includes(c.role)),homeTab=home(c),homeTitle={business:'业务工作台',engineering:'工程工作台',management:['finance','finance-lead'].includes(c.role)?'财务工作台':'管理工作台',headquarters:'总部工作台',profile:'我的'}[homeTab];
 const base=[{key:'home',text:'首页',index:0},{key:'business',text:'业务',index:1},{key:'engineering',text:'工程',index:2},{key:'management',text:!guest&&['finance','finance-lead'].includes(c.role)?'财务':'管理',index:3},{key:'profile',text:'我的',index:4}];
 let tabs;
 if(guest)tabs=[base[0],base[4]];
 else if(c.active&&c.api&&headquartersReader(c))tabs=[{key:'headquarters-home',text:'总部',index:21,icon:'home',native:false,url:'/pages/headquarters/index?section='+(c.role==='hq-admin'?'overview':'finance')},{key:'headquarters-books',text:'账本',index:22,icon:'management',native:false,url:'/pages/headquarters/index?section=books'},c.role==='hq-admin'?base[4]:{...base[4],native:false,url:'/pages/admin/finance-desk/index?view=profile'}];
 else if(!c.active||c.role==='hq-admin')tabs=[base[4]];
 else if(boss)tabs=[['home','工作台','home','/pages/admin/home/index',false],['business','业务','business','/pages/business/index',true],['engineering','工程','engineering','/pages/engineering/index',true],['profile','我的','profile','/pages/profile/index',true]].map(t=>c.orgType==='store'&&['business','engineering'].includes(t[0])?[t[0],t[1],t[2],'/pages/admin/department/index?area='+t[0],false]:t).map((t,i)=>({key:'owner-'+t[0],text:t[1],icon:t[2],index:t[0]==='profile'?4:10+i,url:t[3],native:t[4]}));
 else if(['sales','sales-lead'].includes(c.role))tabs=[{key:'business',text:'首页',index:1,icon:'home'},{key:'creation',text:'创作',index:5,icon:'business',url:'/pages/home/index',native:true},{key:'customers',text:'客户',index:7,customIcon:'/assets/team.png',url:'/pages/management/index',native:true},base[4]];
 else if(['engineer','project-manager'].includes(c.role))tabs=[{key:'engineering',text:'首页',index:2,icon:'home',url:'/pages/build/home/index',native:false},{key:'projects',text:'项目',index:8,customIcon:'/pages/engineering/assets/folder.png',url:'/pages/build/projects/index',native:false},{key:'materials',text:'材料',index:9,customIcon:'/assets/box.png',url:'/pages/build/projects/index?destination=materials',native:false},base[4]];

 else if(['finance','finance-lead'].includes(c.role))tabs=[{key:'finance-home',text:'首页',index:16,icon:'home',native:false,url:'/pages/admin/finance-desk/index'},{key:'finance-books',text:c.orgType==='headquarters'?'账本':'项目账本',index:17,icon:'management',native:false,url:c.orgType==='headquarters'?'/pages/admin/finance-desk/index?view=flows&tab=ledger':'/pages/admin/finance-desk/index?view=projects'},{...base[4],native:false,url:'/pages/admin/finance-desk/index?view=profile'}];
 else{const primary=base.find(t=>t.key===homeTab)||base[4];tabs=[{...primary,text:'首页',icon:'home'},...base.filter(t=>t.key!==homeTab&&t.index!==0&&(t.index===4||[false,canBusiness,canEngineering,canManagement][t.index]))];}
 return {guest,canBusiness,canEngineering,canManagement,homeTab,homeTitle,tabs:tabs.map(t=>({...t,url:t.url||'/pages/'+t.key+'/index',native:t.native!==false}))};
}
const inherited={'sales-lead':'sales','project-manager':'engineer','finance-lead':'finance','hq-operations-lead':'hq-operations'};
const has=(role,wanted)=>role===wanted||inherited[role]===wanted;
const staffHas=(p,wanted)=>(p?.roles||[p?.roleId]).some(r=>has(r,wanted));
const defaultRole=(grants,preferred)=>{const first=grants.includes(preferred)?preferred:grants[0];return Object.keys(inherited).find(r=>inherited[r]===first&&grants.includes(r))||first;};
const postDisplayRank=post=>{
 const role=post&&post.roleCode,type=post&&post.orgType;
 if(type==='store'&&['sales','sales-lead'].includes(role))return 0;
 if(type==='store'&&['engineer','project-manager'].includes(role))return 1;
 if(type==='store'&&['finance','finance-lead'].includes(role))return 2;
 if(type==='store'&&['boss','owner'].includes(role))return 3;
 if(type==='headquarters'&&['hq-admin','hq-design-admin'].includes(role))return 4;
 if(type==='headquarters'&&['hq-operations','hq-operations-lead'].includes(role))return 5;
 if(type==='headquarters'&&['finance','finance-lead'].includes(role))return 6;
 return 99;
};
const sortPostsForDisplay=posts=>(Array.isArray(posts)?posts:[]).map((post,index)=>({post,index,rank:postDisplayRank(post)})).sort((a,b)=>a.rank-b.rank||a.index-b.index).map(item=>item.post);
// Defaults only prefill new forms; the original record remains authoritative.
function defaultAssignee(book, department) {
 const role={sales:'sales',engineering:'engineer',finance:'finance',design:'hq-design-admin',operations:'hq-operations'}[department];
 const id=book?.managementSettings?.assignments?.[department];
 return role&&id&&(book.staff||[]).some(person=>person.id===id&&person.active!==false&&staffHas(person,role))?id:'';
}
// 后端任职（/platform/posts/mine 的 StaffPost：roleCode 与本文件角色 key 同名）→ 导航上下文。
// 仅接管导航与落地页；业务数据可见性仍走 demo 上下文（业务数据后端化逐页接入后再收口）。
const apiContext=p=>p?{loggedIn:true,active:true,role:p.roleCode,orgType:p.orgType,api:true,postId:p.postId,teamId:p.teamId||null,lead:!!p.lead}:null;
module.exports={defaultAssignee,defaultRole,has,staffHas,sortPostsForDisplay,roles,templates,teamForRole,allowed,enabled,home,navigation,apiContext,headquartersReader};
