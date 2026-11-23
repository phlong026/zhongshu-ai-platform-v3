// Role grants can change while postId and the HTTP session generation stay the same.
module.exports=()=>{
 const h=require('../../api/http'),p=h.token()?require('../../api/auth').currentApiPost():null;
 return JSON.stringify([h.sessionGeneration(),p&&p.tenantId,p&&p.orgId,p&&p.postId,p&&p.orgType,p&&p.roleCode]);
};
