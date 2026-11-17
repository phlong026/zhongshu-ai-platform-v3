// 串行同步完整草稿；版本冲突保留本机填写，绝不自动覆盖另一处更新。
const copy=value=>JSON.parse(JSON.stringify(value)), conflictCode=1114000009, personaConflictCode=1114000010;
const same=(a,b)=>JSON.stringify(Object.keys(a).sort().map(k=>[k,a[k]]))===JSON.stringify(Object.keys(b).sort().map(k=>[k,b[k]]));
function create(options) {
  const {api,storage,storageKey,key,valid,onForm,onStatus,onLock}=options;
  const owner=Date.now()+'-'+Math.random();
  let form=copy(options.form),revision=0,dirty=false,changes=0,loaded=false,exists=false,loading,flight,timer,stopped=false,conflict=false,pending=null,localOk=true,claimed=false;
  let observedRevision=null,observedPersonaVersion=null,basePersonaVersion=options.basePersonaVersion??null,personaConflict=false,rebase=false;
  let cached;
  try {cached=storage.getStorageSync(storageKey);}catch(_){}
  if(cached&&cached.form&&typeof cached.form==='object'){
    form=Object.assign(form,cached.form);revision=Number(cached.revision)||0;
    conflict=!!cached.conflict||(!!cached.dirty&&cached.schema!==2);personaConflict=!!cached.personaConflict;
    observedRevision=cached.observedRevision??null;observedPersonaVersion=cached.observedPersonaVersion??null;
    basePersonaVersion=cached.basePersonaVersion??null;rebase=!!cached.rebase;
    dirty=!!cached.dirty;pending=cached.pending==null?null:Number(cached.pending);
  }
  const status=text=>{if(!stopped&&valid())onStatus(text);};
  function cache(){
    if(!valid())return;
    try{
      const current=storage.getStorageSync(storageKey);
      if(claimed&&current&&current.owner&&current.owner!==owner)return;
      storage.setStorageSync(storageKey,{schema:2,owner,form:copy(form),revision,dirty,pending,conflict,observedRevision,observedPersonaVersion,basePersonaVersion,personaConflict,rebase});localOk=true;claimed=true;
    }catch(_){localOk=false;}
  }
  function fail(error){
    if([conflictCode,personaConflictCode].includes(Number(error&&error.code))){conflict=true;personaConflict=Number(error.code)===personaConflictCode;cache();}
    status(conflict?'草稿有更新，点此处理':localOk?'已存本机，点此重试':'保存失败，点此重试');
  }
  function schedule(){
    clearTimeout(timer);
    if(!stopped&&valid()&&dirty&&!conflict&&pending===null)
      timer=setTimeout(()=>flush().catch(()=>{}),options.delay===undefined?700:options.delay);
  }
  function check(){if(!valid())throw Error('工作身份已变化，请重新打开人物库');}
  async function start(){
    if(loaded)return;
    if(loading)return loading;
    onForm(copy(form));if(pending!==null)onLock(true);
    cache();const before=changes;
    loading=(async()=>{
      try{
        const remote=await api.draft(key);check();
        if(!remote||!Number.isSafeInteger(Number(remote.revision)))throw Error('草稿读取失败');
        const next=Number(remote.revision);exists=!!remote.exists;
        observedRevision=next;observedPersonaVersion=remote.personaVersion??null;
        if(pending!==null)status('保存结果待确认，请点保存定位');
        else if(!conflict&&(dirty||changes!==before)&&revision!==next){
          if(remote.exists&&same(remote.form,form))dirty=false;
          else conflict=true;
        }else if(!conflict&&!dirty&&changes===before){
          form=Object.assign({},options.form,remote.exists?remote.form:(remote.personaForm||{}));
          basePersonaVersion=remote.exists?(remote.basePersonaVersion??null):(remote.personaVersion??basePersonaVersion);
          onForm(copy(form));
        }
        if(!conflict)revision=next;
        if(pending===null&&key!=='new'&&remote.personaVersion!=null&&basePersonaVersion!==remote.personaVersion){conflict=true;personaConflict=true;}
        loaded=true;cache();
        if(conflict)status('草稿有更新，点此处理');
        else if(pending===null)status(dirty?'保存中…':remote.exists?'草稿已自动保存':'');
        schedule();
      }catch(error){fail(error);throw error;}
      finally{loading=null;}
    })();return loading;
  }
  function change(next){
    if(!valid()||stopped||pending!==null)return false;
    if(same(next,form))return true;
    form=copy(next);dirty=true;changes++;cache();status(conflict?'草稿有更新，点此处理':'保存中…');schedule();return true;
  }
  async function flush(){
    clearTimeout(timer);check();await start();check();
    if(conflict)throw{code:conflictCode,message:'草稿有更新，请点击保存状态选择保留的内容'};
    if(flight){await flight;return dirty?flush():undefined;}
    if(!dirty||pending!==null)return;
    const sent=changes,snapshot=copy(form),expected=revision;
    flight=(async()=>{
      try{
        const result=await api.saveDraft(key,{revision:expected,form:snapshot,...(key!=='new'?{basePersonaVersion,rebase}:{})});check();
        if(!result||Number(result.revision)!==expected+1)throw Error('自动保存结果待确认');
        revision=Number(result.revision);observedRevision=revision;rebase=false;exists=true;dirty=changes!==sent;cache();status(dirty?'保存中…':'草稿已自动保存');
      }catch(error){fail(error);throw error;}
      finally{flight=null;}
    })();await flight;if(dirty)return flush();
  }
  async function resolve(useLocal){
    check();if(flight)await flight.catch(()=>{});
    const before=changes,remote=await api.draft(key);check();
    const next=Number(remote&&remote.revision);
    if(!Number.isSafeInteger(next))throw Error('草稿读取失败');
    if(!useLocal&&before!==changes)throw Error('填写内容已变化，请再次选择');
    if(useLocal&&(observedRevision!==next||observedPersonaVersion!==(remote.personaVersion??null))){
      observedRevision=next;observedPersonaVersion=remote.personaVersion??null;conflict=true;cache();
      status('云端再次更新，请重新确认保留的内容');throw{code:conflictCode,message:'云端再次更新，请重新确认保留的内容'};
    }
    const official=personaConflict;
    revision=next;observedRevision=next;observedPersonaVersion=remote.personaVersion??null;conflict=false;loaded=true;exists=!!remote.exists;
    basePersonaVersion=official?observedPersonaVersion:(remote.basePersonaVersion??remote.personaVersion??basePersonaVersion);
    rebase=official;personaConflict=false;
    if(!useLocal){
      form=Object.assign({},options.form,official?(remote.personaForm||{}):(remote.exists?remote.form:(remote.personaForm||{})));
      dirty=official;changes++;onForm(copy(form));
    } else dirty=true;
    cache();if(dirty)await flush();else status(remote.exists?'草稿已自动保存':'');
  }
  async function publish(){
    check();await start();if(!exists&&pending===null){dirty=true;changes++;}await flush();check();
    if(pending===null){pending=revision;cache();onLock(true);}
    try{
      const result=await api.publishDraft(key,pending);check();
      if(!result||result.id==null)throw Error('保存结果待确认，请重试');
      dirty=false;stopped=true;clearTimeout(timer);
      try{if(storage.getStorageSync(storageKey)?.owner===owner)storage.removeStorageSync(storageKey);}catch(_){}
      onLock(false);return result;
    }catch(error){
      if([conflictCode,personaConflictCode,1114000001].includes(Number(error&&error.code))){pending=null;cache();onLock(false);fail(error);}
      else status('保存结果待确认，请点保存定位');
      throw error;
    }
  }
  function stop(){stopped=true;clearTimeout(timer);}
  return{start,change,flush,publish,resolve,stop,conflicted:()=>conflict};
}
module.exports={create};
