// 页面回归测试使用的内存HTTP契约；持久性、事务和权限另由真实PG测试覆盖。
module.exports=function withDrafts(personas={}){
  const rows=new Map();
  return Object.assign({},personas,{
    draft:async key=>structuredClone(rows.get(key)||{exists:false,revision:0,form:{}}),
    saveDraft:async(key,body)=>{
      const row=rows.get(key)||{revision:0};
      if(row.revision!==body.revision)throw{code:1114000009};
      const saved={exists:true,revision:row.revision+1,form:structuredClone(body.form)};rows.set(key,saved);return structuredClone(saved);
    },
    publishDraft:async(key,revision)=>{
      const row=rows.get(key);if(!row||row.revision!==revision)throw{code:1114000009};
      const result=key==='new'?await personas.create(row.form):await personas.update(key,row.form);
      rows.set(key,{exists:false,revision:revision+1,form:{}});return result;
    }
  });
};
