// 全站 Tab 导航统一入口：真实登录（后端岗位）优先，未登录后端时回退 demo 身份。
// 未知岗位留痕（2026-11-10）：后端新增岗位而前端未发版时导航静默回退本地身份——同岗位只告警一次便于发现枚举落后。
const warnedRoles = {};
module.exports=()=>{
 const O=require('./organization');
 const post=(()=>{try{const auth=require('../api/auth');const token=require('../api/http').token();return token?auth.currentApiPost():null;}catch(e){return null;}})();
 if(post){const c=O.apiContext(post);if(c&&O.roles[c.role])return O.navigation(c);
  if(!warnedRoles[post.roleCode]){warnedRoles[post.roleCode]=1;console.warn('[workbench-nav] API 岗位无导航映射，回退本地身份 roleCode='+(post.roleCode||''));}}
 return O.navigation(require('./access').context());
};
