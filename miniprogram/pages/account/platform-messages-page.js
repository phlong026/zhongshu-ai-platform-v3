const http = require('../../api/http');
const C = require('./message-center');
const Nav = require('../../utils/page-navigation');
const BASE='/platform/messages/center';
const TYPES={业务:'BUSINESS',学习:'LEARNING',系统:'SYSTEM'};
function target(m) {
  if(m.actionPath){
    if(!/^\/pages\/(workflow(?:\/commission)?|build\/(record|materials)|admin\/(finance-desk|tasks)|account(?:\/training)?|studio)\/index\?/.test(m.actionPath))throw Error('此消息的业务入口暂不可用');
    return m.actionPath;
  }
  throw Error('此事项当前无可办理入口，请查看消息详情');
}
async function sync(append=false){
  if(!http.token()){this.__messagesGen=(this.__messagesGen||0)+1;this.apiMessages=[];this.apiUnread=0;this.setData({messageStatus:'error',messageError:'请先登录后查看消息',messageHasMore:false,messagePending:0,messageDetail:null});this.filter();return;}
  if(append&&(!this.data.messageHasMore||this.data.messageStatus==='loading'))return;
  const alive=http.requestGuard('messages'),gen=this.__messagesGen=(this.__messagesGen||0)+1;
  const current=()=>alive()&&gen===this.__messagesGen;
  const query={limit:30,type:TYPES[this.data.messageTab]||'',module:this.data.messageModule||'',mode:this.data.messageMode||'ALL',keyword:this.data.messageKeyword||'',...(append?{beforeId:this.__messagesCursor}:{})};
  this.setData({messageStatus:'loading',messageError:'',messageModules:C.modules});
  try{
    const response=await http.get(BASE,query);if(!current())return;
    if(!response||!Array.isArray(response.items))throw Error('消息数据格式不正确');
    const incoming=response.items.map(C.row).filter(Boolean),seen=new Set();
    this.apiMessages=[...(append?this.apiMessages||[]:[]),...incoming].filter(r=>!seen.has(r.id)&&seen.add(r.id));
    this.apiUnread=Number(response.unread)||0;this.__messagesCursor=response.nextCursor||'';
    if(!append)this.__messagesSnapshot=response.snapshotAt;
    this.setData({messageStatus:'ready',messageHasMore:!!response.hasMore,messagePending:Number(response.pending)||0,messagePartial:response.failedDomains&&response.failedDomains.length?'部分业务待办暂未加载，请稍后重试':''});this.filter();
  }catch(e){if(current()&&!e.silent)this.setData({messageStatus:'error',messageError:e.message||e.msg||'消息加载失败，请重试'});}
}
async function read(m,navigate=true){
  if(this.data.messageBusy)return;
  const alive=http.requestGuard('messages-read');this.setData({messageBusy:true,messageError:''});
  try{
    const raw=await http.post(BASE+'/open',{id:String(m.id)});if(!alive())return;
    const latest=C.row(raw);if(!latest)throw Error('消息数据格式不正确');
    this.apiMessages=(this.apiMessages||[]).map(r=>r.id===latest.id?latest:r);
    if(!m.read&&!String(m.id).startsWith('todo:'))this.apiUnread=Math.max(0,(this.apiUnread||0)-1);
    this.filter();
    if(navigate&&latest.actionPath){this.setData({messageDetail:null});Nav.open(target(latest));}
    else this.setData({messageDetail:latest});
  }catch(e){if(alive()&&!e.silent)this.setData({messageError:e.message||e.msg||'事项状态已变化，请刷新消息后重试'});}
  finally{if(alive())this.setData({messageBusy:false});}
}
async function readAll(){
  if(this.data.messageBusy||!http.token()||!this.__messagesSnapshot)return;
  const alive=http.requestGuard('messages-read-all');this.setData({messageBusy:true,messageError:''});
  try{await http.post(BASE+'/read-all',{through:this.__messagesSnapshot});if(alive())await sync.call(this);}
  catch(e){if(alive()&&!e.silent)this.setData({messageError:e.message||e.msg||'已读状态保存失败，请重试'});}
  finally{if(alive())this.setData({messageBusy:false});}
}
function change(patch){this.__messagesGen=(this.__messagesGen||0)+1;this.apiMessages=[];this.setData({...patch,messageHasMore:false,messageDetail:null});this.filter();return sync.call(this);}
function details(e){const id=String(e.currentTarget.dataset.id),m=(this.apiMessages||[]).find(x=>x.id===id);if(m)return read.call(this,m,false);return this.read(e);}
function action(e){const id=String(e.currentTarget.dataset.id),m=(this.apiMessages||[]).find(x=>x.id===id);if(m)return read.call(this,m,true);return this.read(e);}
module.exports={sync,read,readAll,target,change,details,action};
