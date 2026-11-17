// Input is transient; paid-operation identities survive restarts within the same tenant/org/user/post.
const {Sha256}=require('./sha256-stream');
const PREFIX=require('./storage-keys').creationExtractPrefix;
let draft;
function current(scope,generation){
 if(!draft||draft.scope!==scope||draft.generation!==generation)draft={scope,generation,link:'',attempt:null};
 return draft;
}
function read(scope,generation){return current(scope,generation).link;}
function write(scope,generation,value){const text=String(value||'').slice(0,2000);current(scope,generation).link=text;return text;}
function storageKey(owner,url){
 const bytes=new Uint8Array(Array.from(unescape(encodeURIComponent(url)),c=>c.charCodeAt(0)));
 return PREFIX+owner+':'+new Sha256().update(bytes).hex();
}
function restore(owner,url){
 let saved;
 try{saved=wx.getStorageSync(storageKey(owner,url));}catch(e){throw Error('无法读取原任务记录，请到任务中心查看，勿重复提交');}
 if(!saved)return null;
 if(saved.version!==1||saved.owner!==owner||saved.url!==url||!saved.parseKey||!saved.asrKey||!saved.taskIds)
  throw Error('原任务记录无法确认，请到任务中心查看，勿重复提交');
 return Object.freeze(saved);
}
function persist(value){
 if(!value.owner)return; // Unpaid/transient callers have no durable owner. Online extraction always supplies one.
 try{
  const key=storageKey(value.owner,value.url);
  wx.setStorageSync(key,value);
  const saved=wx.getStorageSync(key);
  if(!saved||saved.parseKey!==value.parseKey||saved.asrKey!==value.asrKey||JSON.stringify(saved.taskIds)!==JSON.stringify(value.taskIds))throw Error('write not confirmed');
 }catch(e){throw Error('未能保存任务记录，请到任务中心查看或释放本机存储后重试，勿重复提交');}
}
function attempt(scope,generation,url,createKey,owner){
 const value=current(scope,generation);
 if(!value.attempt||value.attempt.url!==url||value.attempt.owner!==owner){
  let saved=owner?restore(owner,url):null;
  if(!saved){const key=createKey();saved=Object.freeze({version:1,owner,url,parseKey:key+'-parse',asrKey:key+'-asr',taskIds:{}});}
  // Persist before any paid HTTP request. A failed read/write cannot silently create a new operation.
  persist(saved);value.attempt=saved;
 }
 return value.attempt;
}
function owns(scope,generation,previous){return draft&&draft.scope===scope&&draft.generation===generation&&draft.attempt===previous;}
// Only a definite provider failure may rotate the request; delivery failure/unknown stays on the original task.
function failed(scope,generation,previous,phase,createKey){
 if(!owns(scope,generation,previous))return;
 const key=createKey(),next=Object.freeze({...previous,parseKey:phase==='parse'?key+'-parse':previous.parseKey,asrKey:key+'-asr',taskIds:phase==='parse'?{}:{...(previous.taskIds.parse?{parse:previous.taskIds.parse}:{})}});
 persist(next);draft.attempt=next;
}
function remember(scope,generation,previous,phase,taskId){
 if(!owns(scope,generation,previous))return;
 previous.taskIds[phase]=String(taskId);persist(previous);
}
module.exports={read,write,attempt,failed,remember};
