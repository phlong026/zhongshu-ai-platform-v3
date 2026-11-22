// Persist only deliberate edits, using the revision read with their cloud baseline.
const copy=value=>JSON.parse(JSON.stringify(value));
// A signed URL or local preview file is not the identity of an uploaded asset.
const signature=value=>{
  const form=copy(value);
  if(form.firstFrameAssetId)delete form.firstFrame;
  if(form.lastFrameAssetId)delete form.lastFrame;
  if(Array.isArray(form.references))form.references.forEach(item=>{if(item.assetId)delete item.path;});
  return JSON.stringify(form);
};
const CONFLICT=1114000011;
function create({api,storage,storageKey,key,getForm,applyForm,valid,onStatus,initialDirty=false}) {
  let ready=false,stopped=false,conflict=false,revision=null,observed=null,flight=null,loading=null;
  let saved=signature(getForm()),cached,dirty=initialDirty;
  const owner=Date.now()+'-'+Math.random();let claimed=false;
  try{cached=storage.getStorageSync(storageKey);}catch(_){}
  if(cached&&cached.form){applyForm(copy(cached.form));saved=cached.saved||signature(cached.form);dirty=!!cached.dirty;revision=cached.revision??null;conflict=!!cached.conflict;observed=cached.observed??null;}
  const initial=signature(getForm());
  const check=()=>{if(!valid())throw Error('工作身份已变化，请重新打开视频编辑');};
  const status=text=>{if(!stopped&&valid())onStatus(text);};
  function cache(){if(valid())try{dirty=dirty||signature(getForm())!==saved;const current=storage.getStorageSync(storageKey);if(claimed&&current&&current.owner&&current.owner!==owner)return;storage.setStorageSync(storageKey,{owner,form:copy(getForm()),saved,dirty,revision,conflict,observed});claimed=true;}catch(_){status('本机保存失败，请保持页面打开后重试');}}
  async function start(){
    if(ready)return;if(loading)return loading;cache();
    loading=(async()=>{
      try{
        const remote=await api.read(key);check();if(stopped)return;
        // Older stores have a single unversioned draft. Never write a per-task draft into it.
        if(remote && typeof remote.exists==='boolean' && remote.revision==null){status('云端暂不可同步，草稿仅存本机');return;}
        if(remote.revision==null||!Number.isSafeInteger(Number(remote.revision))||Number(remote.revision)<0)throw Error('视频草稿读取失败');
        observed=Number(remote.revision);
        const edited=signature(getForm())!==initial;
        if(conflict||((dirty||edited)&&remote.exists&&revision!==observed))conflict=true;
        else if(!dirty&&!edited){if(remote.exists&&(!cached||revision!==observed))applyForm(remote);saved=signature(getForm());revision=observed;}
        else revision=observed;
        ready=true;dirty=dirty||edited;cache();status(conflict?'云端草稿有更新，点此处理':'');
      }catch(e){status('云端草稿读取失败，点此重试');throw e;}
      finally{loading=null;}
    })();return loading;
  }
  async function save(){
    check();const current=signature(getForm());
    dirty=dirty||current!==saved;
    if(!dirty)return;
    cache();if(!ready||stopped||conflict)return;
    if(flight)return flight;
    const sent=copy(getForm()),expected=revision;
    flight=(async()=>{
      try{
        const result=await api.save(key,{...sent,revision:expected});check();
        if(Number(result.revision)!==expected+1)throw Error('草稿保存结果待确认');
        revision=Number(result.revision);observed=revision;saved=signature(result.form||sent);dirty=signature(getForm())!==saved;cache();status(dirty?'保存中…':'草稿已保存');
      }catch(e){if(Number(e.code)===CONFLICT)conflict=true;cache();status(conflict?'云端草稿有更新，点此处理':'已存本机，点此重试');throw e;}
      finally{flight=null;}
    })();await flight;if(dirty&&!stopped)return save();
  }
  async function resolve(useLocal){
    check();if(flight)await flight.catch(()=>{});
    const before=signature(getForm()),remote=await api.read(key);check();if(stopped)return;
    const next=Number(remote.revision);if(remote.revision==null||!Number.isSafeInteger(next)||next<0)throw Error('云端暂不可同步，草稿仅存本机');
    if(useLocal&&observed!==next){observed=next;conflict=true;cache();status('云端再次更新，请重新确认');throw Error('云端再次更新，请重新确认');}
    if(!useLocal&&before!==signature(getForm()))throw Error('填写内容已变化，请再次选择');
    revision=next;observed=next;ready=true;conflict=false;
    if(useLocal){dirty=true;cache();await save();}
    else {applyForm(remote.exists?remote:{mode:'frames',description:'',parameters:{resolution:'768P',duration:8,ratio:'9:16'}});saved=signature(getForm());dirty=false;cache();status('已恢复云端草稿');}
  }
  return{start,save,resolve,cache,stop:()=>{stopped=true;},conflicted:()=>conflict};
}
module.exports={create};
