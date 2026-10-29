const roles={'hq-design-admin':'设计行政',sales:'业务员','sales-lead':'业务负责人',engineer:'工程人员','project-manager':'工程负责人',finance:'财务专员','finance-lead':'财务负责人',boss:'老板',owner:'门店负责人','hq-operations':'运营专员','hq-operations-lead':'运营负责人'};
const templates={store:['sales','sales-lead','engineer','project-manager','finance','finance-lead','boss','owner'],headquarters:['hq-design-admin','hq-operations','hq-operations-lead','finance','finance-lead','boss']};
const teamForRole=r=>['sales','sales-lead'].includes(r)?'sales':['engineer','project-manager'].includes(r)?'engineering':r.startsWith('hq-operations')?'operations':r.startsWith('hq-')?'design':['finance','finance-lead'].includes(r)?'finance':'management';
const allowed=(type,role)=>(templates[type]||[]).includes(role);
const enabled=(type,role)=>allowed(type,role)&&(type!=='headquarters'||['hq-design-admin','finance','finance-lead','boss'].includes(role));
const home=c=>!c.active?'profile':['finance','finance-lead','boss','owner'].includes(c.role)?'management':['engineer','project-manager'].includes(c.role)?'engineering':'business';
function navigation(c){
 const guest=!c.loggedIn,boss=['boss','owner'].includes(c.role),canBusiness=c.active&&(boss||['sales','sales-lead','hq-design-admin'].includes(c.role)),canEngineering=c.active&&(boss||['engineer','project-manager'].includes(c.role)),canManagement=c.active&&(boss||['finance','finance-lead'].includes(c.role)),homeTab=home(c),homeTitle={business:c.orgType==='headquarters'?'设计工作台':'业务工作台',engineering:'工程工作台',management:['finance','finance-lead'].includes(c.role)?'财务工作台':'管理工作台',profile:'我的'}[homeTab];
 const base=[{key:'home',text:'首页',index:0},{key:'business',text:!guest&&c.orgType==='headquarters'&&!boss?'设计':'业务',index:1},{key:'engineering',text:'工程',index:2},{key:'management',text:!guest&&['finance','finance-lead'].includes(c.role)?'财务':'管理',index:3},{key:'profile',text:'我的',index:4}];
 let tabs;
 if(guest)tabs=base;
 else if(!c.active)tabs=[base[4]];
 else if(boss)tabs=[['home','工作台','home','/pages/admin/home/index',false],['business','业务','business','/pages/business/index',true],['engineering','工程','engineering','/pages/engineering/index',true],['profile','我的','profile','/pages/admin/profile/index',false]].map((t,i)=>({key:'owner-'+t[0],text:t[1],icon:t[2],index:10+i,url:t[3],native:t[4]}));
 else if(['sales','sales-lead'].includes(c.role))tabs=[{key:'business',text:'首页',index:1,icon:'home'},{key:'creation',text:'创作',index:5,icon:'business',native:false},{key:'design',text:'方案',index:6,customIcon:'/assets/home-design-icon.png',native:false},{key:'customers',text:'客户',index:7,customIcon:'/assets/team.png',native:false},base[4]];
 else if(['engineer','project-manager'].includes(c.role))tabs=[{key:'engineering',text:'首页',index:2,icon:'home',url:'/pages/build/home/index',native:false},{key:'projects',text:'项目',index:8,customIcon:'/pages/engineering/assets/folder.png',url:'/pages/build/projects/index',native:false},{key:'materials',text:'材料',index:9,customIcon:'/assets/box.png',url:'/pages/build/materials/index',native:false},base[4]];
 else if(c.role==='hq-design-admin')tabs=[{key:'business',text:'首页',index:19,icon:'home',native:false,url:'/pages/workflow/commission/index?view=admin'},{key:'design-admin-projects',text:'项目',index:20,customIcon:'/assets/folder.png',native:false,url:'/pages/workflow/commission/index?view=projects'},base[4]];
 else if(['finance','finance-lead'].includes(c.role))tabs=[{key:'finance-home',text:'首页',index:16,icon:'home',native:false,url:'/pages/admin/finance-desk/index'},{key:'finance-flows',text:'收支',index:17,icon:'management',native:false,url:'/pages/admin/finance-desk/index?view=flows'},{key:'finance-books',text:c.orgType==='headquarters'?'加盟商':'项目',index:18,customIcon:c.orgType==='headquarters'?'/assets/account-shop.jpg':'/assets/folder.png',native:false,url:'/pages/admin/finance-desk/index?view='+(c.orgType==='headquarters'?'partners':'projects')},base[4]];
 else{const primary=base.find(t=>t.key===homeTab)||base[4];tabs=[{...primary,text:'首页',icon:'home'},...base.filter(t=>t.key!==homeTab&&t.index!==0&&(t.index===4||[false,canBusiness,canEngineering,canManagement][t.index]))];}
 return {guest,canBusiness,canEngineering,canManagement,homeTab,homeTitle,tabs:tabs.map(t=>({...t,url:t.url||'/pages/'+t.key+'/index',native:t.native!==false}))};
}
const inherited={'sales-lead':'sales','project-manager':'engineer','finance-lead':'finance','hq-operations-lead':'hq-operations'};
const has=(role,wanted)=>role===wanted||inherited[role]===wanted;
const staffHas=(p,wanted)=>(p?.roles||[p?.roleId]).some(r=>has(r,wanted));
const defaultRole=(grants,preferred)=>{const first=grants.includes(preferred)?preferred:grants[0];return Object.keys(inherited).find(r=>inherited[r]===first&&grants.includes(r))||first;};
// Defaults only prefill new forms; the original record remains authoritative.
function defaultAssignee(book, department) {
 const role={sales:'sales',engineering:'engineer',finance:'finance',design:'hq-design-admin',operations:'hq-operations'}[department];
 const id=book?.managementSettings?.assignments?.[department];
 return role&&id&&(book.staff||[]).some(person=>person.id===id&&person.active!==false&&staffHas(person,role))?id:'';
}
module.exports={defaultAssignee,defaultRole,has,staffHas,roles,templates,teamForRole,allowed,enabled,home,navigation};
