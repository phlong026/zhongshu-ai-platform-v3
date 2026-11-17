// 草稿接口随创作分包加载，避免挤占主包。
module.exports=http=>{
  const path=key=>'/franchise/content/personas/drafts/'+encodeURIComponent(key);
  return{
    draft:key=>http.get(path(key)),
    saveDraft:(key,data)=>http.put(path(key),data),
    publishDraft:(key,revision)=>http.post(path(key)+'/publish',{revision})
  };
};
