const groups={incoming:['incoming-register','engineering-collection'],pay:['pay']};
const titles={incoming:'收款登记',pay:'登记付款'};
function filter(tasks,type){return groups[type]?tasks.filter(t=>groups[type].includes(t.view)):tasks;}
function entries(role,tasks=[]){const hq=require('./access').context().orgType==='headquarters';return (hq?['pay']:['incoming','pay']).map(key=>({key,taskType:key,label:titles[key],description:'登记线下实际收付款',icon:'money',view:'todos',count:filter(tasks,key).length})).concat([{key:'todos',label:'全部待办',description:'查看登记事项',icon:'chart',view:'todos',count:tasks.length},{key:'finance',label:'收支明细',description:'查看原笔记录',icon:'money',view:'finance'},{key:'projects',label:'项目收支',description:'查看项目款项',icon:'folder',view:'projects'}]);}
module.exports={filter,entries,titles};
