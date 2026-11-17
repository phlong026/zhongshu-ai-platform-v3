const schema = [
 ['项目与朝向', [['projectName','项目名称（与沟通群一致）','input'],['orientation','房屋朝向','choices',['东','南','西','北','东南','西南','东北','西北']],['entranceAligned','主要入口与房屋朝向','choices',['一致','不一致']],['entranceDirection','入口朝向（不一致时填写）','input']]],
 ['家庭结构', [['householdMembers','常住人口（人）','number'],['householdTags','家庭成员','checks',['有小孩','有老人']]]],
 ['必备房间', [['rooms','房间配置','checks',['客厅','餐厅','厨房','麻将房','尊亲房','儿童房','私卫','公卫','卧室','客房']],['bedrooms','卧室（间）','number'],['guestBedrooms','客房（间）','number'],['bathroomFirst','一层卫生间（个）','number'],['bathroomSecond','二层卫生间（个）','number']]],
 ['核心生活习惯', [['livingUses','客厅主要用途','checks',['家庭影院','孩子玩耍','会客接待','学习阅读']],['diningRound','圆桌就餐人数（人）','number'],['diningLong','长桌就餐人数（人）','number'],['kitchenTypes','厨房类型与使用习惯','checks',['封闭式','开放式','半开放式','中式爆炒','西式简餐']]]],
 ['特殊空间与要求', [['specialRooms','特殊空间（选填）','checks',['独立书房','茶室','健身房','影音室','琴房','衣帽间','储藏室','独立洗衣间','酒窖','佛龛位']],['specialNeeds','特殊要求（无则填“无”）','textarea']]],
 ['现居痛点与新家期待', [['painPoints','目前居住的不便','checks',['采光差','储物不足','动线混乱','卫生间不够','厨房太小','没有独立玄关']],['painOther','其他现居问题（选填）','textarea'],['expectations','最希望在新家实现','checks',['明亮通透','足够储物','亲子互动空间','安静书房','舒适主卫','社交型客厅']]]],
 ['风格与参考', [['styleRefs','喜欢的风格','checks',['新中式','欧式','现代','田园','中式','宋式']],['styleNotes','参考案例或其他偏好（选填）','textarea']]]
];
function empty(projectName='') { return {schemaVersion:1,projectName,orientation:'',entranceAligned:'',householdMembers:'',householdTags:[],rooms:[],livingUses:[],kitchenTypes:[],specialRooms:[],painPoints:[],expectations:[],styleRefs:[],specialNeeds:''}; }
function object(value) { try { const result=typeof value==='string'?JSON.parse(value):value;return result&&typeof result==='object'&&!Array.isArray(result)?result:{}; }catch(e){return {};} }
/** Only copy recorded facts. Image orientation (portrait/landscape) is not house orientation. */
function inherit(context={},saved) {
 const brief={...empty((context.customerName||'')+'自建房'),...saved},sources={};
 const budget=object(context.latestBudget&&context.latestBudget.payload);
 if(!(saved&&saved.projectName)&&budget.itemizedBudget&&budget.itemizedBudget.projectName&&!['独立快速预算','未命名项目'].includes(budget.itemizedBudget.projectName))brief.projectName=String(budget.itemizedBudget.projectName).slice(0,80);
 const basis=object(context.schemeBasis);
 const values=[object(budget.itemizedBudget&&budget.itemizedBudget.inputSummary),object(budget.configSnapshot),object(object(basis.PLAN).configSnapshot),object(object(basis.FACADE).configSnapshot)];
 const directions={EAST:'东',SOUTH:'南',WEST:'西',NORTH:'北',SOUTHEAST:'东南',SOUTHWEST:'西南',NORTHEAST:'东北',NORTHWEST:'西北'};
 function put(key,value,label){if(value===undefined||value===null||value===''||(Array.isArray(value)&&!value.length))return;if(brief[key]!==undefined&&brief[key]!==''&&!(Array.isArray(brief[key])&&!brief[key].length))return;brief[key]=value;sources[key]=label;}
 const decoration=(budget.itemizedBudget&&budget.itemizedBudget.items||[]).find(item=>item.itemCode==='DECORATION');
 if(decoration)put('styleRefs',(decoration.lines||[]).map(line=>line.optionLabel).filter(label=>schema[6][1][0][3].includes(label)),'预算');
 for(const raw of values){const value={...raw,...object(raw.inputs)};
  const house=value.houseOrientation||value.buildingOrientation;
  if(directions[house]||schema[0][1][1][3].includes(house))put('orientation',directions[house]||house,'方案');
  if(directions[value.entranceOrientation])put('entranceDirection',directions[value.entranceOrientation],'方案');
  if(value.householdMembers&&/^\d+$/.test(String(value.householdMembers)))put('householdMembers',String(value.householdMembers),'方案');
  const rooms=object(value.rooms),names=[];
  if(Number.isInteger(rooms.bedroom)&&rooms.bedroom>0){put('bedrooms',String(rooms.bedroom),'方案');names.push('卧室');}
  if(Number.isInteger(rooms.living)&&rooms.living>0)names.push('客厅');
  put('rooms',names,'方案');
  const styles=Array.isArray(value.styleRefs)?value.styleRefs:[value.style];
  put('styleRefs',styles.filter(x=>schema[6][1][0][3].includes(x)),'方案');
 }
 return {brief,sources};
}
function sections(brief) { return schema.map(([title,fields],index)=>({title,index,summary:fields.map(([key])=>Array.isArray(brief[key])?brief[key].join('、'):brief[key]).filter(Boolean).slice(0,2).join(' · ')||'点击补充',fields:fields.map(([key,label,type,options])=>({key,label,type,value:brief[key]||'',options:(options||[]).map(label=>({label,selected:Array.isArray(brief[key])?brief[key].includes(label):brief[key]===label}))}))})); }
function edit(brief,key,value,toggle=false) {
 const field=schema.flatMap(x=>x[1]).find(f=>f[0]===key);if(!field)return brief;
 if(field[3]&&!field[3].includes(value))return brief;
 const next={...brief};
 if(field[2]==='checks'&&toggle){const list=Array.isArray(brief[key])?brief[key]:[];next[key]=list.includes(value)?list.filter(x=>x!==value):list.concat(value);}
 else next[key]=String(value||'').slice(0,field[2]==='textarea'?400:80);
 if(next.entranceAligned==='一致'&&next.orientation)next.entranceDirection=next.orientation;
 return next;
}
function error(b) {
 if(!String(b.projectName||'').trim())return '请填写项目名称';
 if(!schema[0][1][1][3].includes(b.orientation))return '请选择房屋朝向';
 if(!['一致','不一致'].includes(b.entranceAligned))return '请确认主要入口是否与房屋朝向一致';
 if(b.entranceAligned==='不一致'&&!String(b.entranceDirection||'').trim())return '请补充主要入口朝向';
 if(!/^\d+$/.test(String(b.householdMembers||''))||Number(b.householdMembers)<1||Number(b.householdMembers)>99)return '请填写1至99人的常住人口';
 if(!Array.isArray(b.rooms)||!b.rooms.length)return '请至少选择一项必备房间';
 for(const [,fields]of schema)for(const [key,,type]of fields)if(type==='number'&&b[key]!==''&&b[key]!=null&&(!/^\d+$/.test(String(b[key]))||Number(b[key])>99))return '房间及人数请填写0至99的整数';
 return '';
}
function summary(b) {
 return schema.map(([title,fields])=>{const lines=fields.map(([key,label])=>{if(key==='entranceDirection'&&b.entranceAligned!=='不一致')return '';const v=Array.isArray(b[key])?b[key].join('、'):b[key];return v!==undefined&&v!==null&&v!==''?label.replace(/（.*?）/g,'')+'：'+v:'';}).filter(Boolean);return lines.length?title+'\n'+lines.join('\n'):'';}).filter(Boolean).join('\n\n');
}
function snapshot(request) {
 try {const raw=request&&request.schemeSnapshot;const data=typeof raw==='string'?JSON.parse(raw):raw;return data&&data.designBrief||null;}catch(e){return null;}
}
module.exports={empty,inherit,sections,edit,error,summary,snapshot};
