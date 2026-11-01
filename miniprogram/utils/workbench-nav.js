// 全站 Tab 导航统一入口：真实登录（后端岗位）优先，未登录后端时回退 demo 身份。
module.exports=()=>{
 const O=require('./organization');
 const post=(()=>{try{const auth=require('../api/auth');const token=require('../api/http').token();return token?auth.currentApiPost():null;}catch(e){return null;}})();
 if(post){const c=O.apiContext(post);if(c&&O.roles[c.role])return O.navigation(c);}
 return O.navigation(require('./access').context());
};
