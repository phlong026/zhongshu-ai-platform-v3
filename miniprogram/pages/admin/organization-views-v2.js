const X=require('../../utils/access'),O=require('../../utils/organization');
const titles={'staff-roles':'人员岗位权限配置','business-team':'业务团队','engineering-team':'工程团队','finance-team':'财务团队'};
const kv=(label,value)=>({type:'row',label,value:value??'—'}),notice=value=>({type:'notice',value}),section=(title,rows)=>({title,rows}),area=(key,placeholder)=>({type:'textarea',key,placeholder}),upload=label=>({type:'upload',label,key:'file'}),check=label=>({type:'check',key:'checked',label}),input=(label,key)=>({type:'input',label,key,inputType:key==='amount'?'digit':'text',placeholder:'请填写'+label}),link=(label,view,id,subtitle)=>({type:'link',label,view,id,subtitle});
function config(view,s,f,id){if(!titles[view])return null;const a=X.context(),out={title:titles[view],sections:[],primary:'',secondary:'',hero:null};
if(view==='staff-roles'){const p=s.staff.find(p=>p.id===id);if(!p)return {...out,missing:true};if(!Array.isArray(f.roles))f.roles=(p.roles||[]).slice();out.roleChoices=O.templates[a.orgType].map(role=>({id:role,name:O.roles[role],checked:f.roles.includes(role)}));out.sections=[section('当前人员',[kv('姓名',p.name),kv('人员编号',p.id),kv('实际人员标识',p.personId||p.id),notice('负责人继承本专业员工操作；员工和负责人可同人或分人配置。金额事项交老板审批。设计行政记录线下完成与下发；新工程由项目经办人记录施工和验收。')]),section('管理范围',[input('授权管理团队编号（逗号分隔）','managedTeamIds'),notice('岗位资格和任务协作分别配置。团队编号为空时保留原范围，默认 sales、engineering、design 或 finance。')])];out.primary='保存本人员岗位配置';out.action='set-roles';return out;}
if(view.endsWith('-team')){const team={'business-team':'sales','engineering-team':'engineering','finance-team':'finance'}[view],people=s.staff.filter(p=>(p.roles||[]).some(r=>O.teamForRole(r)===team));
if(team==='sales'||team==='engineering'){const perf=require('../../utils/performance'),period=require('../../utils/finance-source').period(s),sources=perf.sourceRows({...s,period:period});
 const customers=team==='sales'?(wx.getStorageSync('zhongshu-business-v3')?.customers||[]).filter(c=>(c.companyId||'quanzhou')===a.companyId):[];
 const projects=team==='engineering'?(wx.getStorageSync('zhongshu-engineering-v1')?.projects||[]).filter(p=>(p.companyId||'quanzhou')===a.companyId):[];
 const specialists=people.filter(m=>(m.roles||[]).includes(team)).map(m=>{
  const cycle=s.archives.filter(c=>c.dynamic&&c.person===m.id&&c.period===period).slice(-1)[0];
  if(team==='sales'){const mine=customers.filter(c=>X.ownerId(c)===m.id);return {id:m.id,name:m.name,summary:'客户 '+mine.length+' · 跟进中 '+mine.filter(c=>c.status==='跟进中').length+' · 已到店 '+mine.filter(c=>c.status==='已到店').length,perf:'本月来源 '+sources.filter(r=>r.person===m.id).length+' 项 · 考核 '+(cycle?cycle.status:'暂无样本')};}
  const mine=projects.filter(pj=>X.managerId(pj)===m.id);return {id:m.id,name:m.name,summary:'项目 '+mine.length+' · 在建 '+mine.filter(pj=>pj.actualStart&&!pj.delivery&&!pj.archived).length+' · 待开工 '+mine.filter(pj=>!pj.actualStart&&!pj.archived).length,perf:'本月来源 '+sources.filter(r=>r.person===m.id).length+' 项 · 考核 '+(cycle?cycle.status:'暂无样本')};});
 if(specialists.length)out.sections.push(section('专员进展与绩效',specialists.map(m=>link(m.name,'targets',m.id,m.summary+' · '+m.perf))));
}
out.sections.push(section('授权范围内成员',people.length?people.map(p=>a.role==='boss'?link(p.name,'staff-roles',p.id,(p.roles||[]).map(r=>O.roles[r]).join('、')):kv(p.name,(p.roles||[]).filter(r=>O.teamForRole(r)===team).map(r=>O.roles[r]).join('、'))):[notice('当前授权范围暂无成员')]));
out.sections.push(section('工作分工',[notice(team==='finance'?'加盟商财务登记收款和付款，总部财务只登记付款。':team==='sales'?'客资由经办人登记后直接跟进，不进行分配；负责人查看团队客户与绩效来源，责任转交仅用于人员变动。':'成员可兼任员工与负责人，具体任务和动作另行授权。')]));
if(team==='engineering'||team==='sales')out.sections.push(section('原任务责任交接',(s.work||[]).filter(w=>team==='engineering'?w.kind==='project-responsibility':w.kind==='customer-responsibility').map(w=>link(w.customer+' · '+w.name,'transfer',w.id,'原作者保留，变更后续任务责任'))));
if(team==='finance')out.sections.push(section('原笔待办',[link('查看收付款登记待办','todos','')]));
return out;}
return null;}
module.exports={titles,config};
