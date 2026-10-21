module.exports=function(state,keyword=''){
 const query=keyword.trim().toLowerCase(),match=item=>['name','label','description','status','amount'].some(key=>String(item[key]||'').toLowerCase().includes(query));
 const visibleFinanceTodos=(state.financeTodos||[]).filter(match),visibleMenu=(state.menu||[]).filter(match).map(item=>({...item,key:item.key||item.view}));
 return {staffKeyword:keyword,staffHasSearch:!!query,visibleFinanceTodos,visibleMenu,staffResultCount:visibleFinanceTodos.length+visibleMenu.length,
 displaySummary:{income:Number(state.summary.income||0).toFixed(2),paid:Number(state.summary.paid||0).toFixed(2),balance:(Number(state.summary.income||0)-Number(state.summary.paid||0)).toFixed(2)}};
};
