const message='设计生成与快速预算已停用，可查看历史成果或在客户跟进中上传外部成果';
const reject=()=>Promise.reject({code:1112100004,msg:message,message});
function blocked(method,path){
 if (path === '/generation-price-quotes' || path === '/budget-point-quotes') return true;
 if (method === 'GET') return false;
 return path === '/ai-design/note-polish' || /^\/(?:design-projects|budget-drafts|budget-estimates|budget)(?:\/|$)/.test(path);
}
module.exports={message,reject,blocked};
