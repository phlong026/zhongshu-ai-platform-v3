const X=require('../../utils/access');
module.exports=function(state,keyword=''){
 if(!state.canWork)return {deskKeyword:'',deskProjects:[],deskTodos:[],deskStats:[],deskShortcuts:[]};
 const raw=wx.getStorageSync('zhongshu-engineering-v1'),source=Array.isArray(raw?.projects)?raw:require('../../utils/engineering-seed')();
 const projects=source.projects.filter(p=>X.project(p)),active=projects.filter(p=>p.actualStart&&!p.delivery&&!p.archived),query=keyword.trim().toLowerCase();
 const deskProjects=active.map((p,index)=>{
  const nodes=p.plan?.nodes||[],complete=nodes.filter(n=>n.status==='已完成').length,current=nodes.findIndex(n=>n.status!=='已完成'),next=nodes[current];
  return {id:p.id,name:p.name,city:p.city||'地址待补充',area:p.area+'㎡',stage:next?.name||'节点已完成',progress:nodes.length?Math.round(complete/nodes.length*100):0,
   featured:index===0,image:p.image||'/pages/engineering/assets/project-chen.jpg',next:next?'下一节点：'+next.name+' · '+(next.end||'日期待安排'):'施工节点已完成，待办理交付',
   stages:nodes.slice(0,4).map((n,i)=>({label:n.name.replace('工程',''),current:i===current}))};
 }).filter(p=>(p.name+p.city+p.stage).toLowerCase().includes(query));
 const deskTodos=state.todoId?[{id:state.todoId,title:state.todoText,note:'按原单状态办理',icon:'tasks',action:'siteConfirm',button:'查看待办'}]:[];
 return {deskKeyword:keyword,deskProjects,deskTodos,
  deskStats:[{label:'在建',value:active.length,unit:'个',icon:'helmet',action:'projects'},{label:'待交付',value:projects.filter(p=>!p.archived&&p.delivery?.status!=='已交付'&&(p.delivery?.status==='待核验'||p.actualStart&&p.plan?.nodes?.length&&p.plan.nodes.every(n=>n.status==='已完成'))).length,unit:'个',icon:'house',action:'projects'},{label:'待处理',value:state.todoCount,unit:'项',icon:'tasks',action:'siteConfirm'}],
  deskShortcuts:[{title:X.isBoss()?'项目总览':'我的项目',note:X.isBoss()?'本组织项目':'本人授权范围',icon:'folder',action:'projects'},{title:'施工计划',note:'节点与进度',icon:'calendar',action:'schedule'},{title:'材料计划',note:'配材与到货',icon:'material',action:'materials'},{title:'节点款项',note:'收付款记录',icon:'tasks',action:'payments'}]};
};
