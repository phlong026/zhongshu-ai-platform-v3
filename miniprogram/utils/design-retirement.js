const message='平面与立面设计请使用外部工具，完成后在客户跟进中上传成果';
const reject=()=>Promise.reject({code:1112100004,msg:message,message});
function blocked(method,path){
 return method==='POST'&&(/\/(flat-jobs|elevation-jobs|revision-requests|flat-selections|elevation-selections)$/.test(path)||path==='/ai-design/note-polish')
   ||path==='/generation-price-quotes';
}
module.exports={message,reject,blocked};
