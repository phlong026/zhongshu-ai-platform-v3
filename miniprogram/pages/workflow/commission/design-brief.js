const schema = [
 ['项目与朝向', [['projectName','项目名称（与沟通群一致）','input'],['orientation','房屋朝向','choices',['东','南','西','北','东南','西南','东北','西北']],['entranceAligned','主要入口与房屋朝向','choices',['一致','不一致']],['entranceDirection','入口朝向（不一致时填写）','input']]],
 ['家庭结构', [['householdMembers','常住人口（人）','number'],['householdTags','家庭成员','checks',['有小孩','有老人']]]],
 ['必备房间', [['rooms','房间配置','checks',['客厅','餐厅','厨房','麻将房','尊亲房','儿童房','私卫','公卫','卧室','客房']],['bedrooms','卧室（间）','number'],['guestBedrooms','客房（间）','number'],['bathroomFirst','一层卫生间（个）','number'],['bathroomSecond','二层卫生间（个）','number']]],
 ['核心生活习惯', [['livingUses','客厅主要用途','checks',['家庭影院','孩子玩耍','会客接待','学习阅读']],['diningRound','圆桌就餐人数（人）','number'],['diningLong','长桌就餐人数（人）','number'],['kitchenTypes','厨房类型与使用习惯','checks',['封闭式','开放式','半开放式','中式爆炒','西式简餐']]]],
 ['特殊空间与要求', [['specialRooms','特殊空间（选填）','checks',['独立书房','茶室','健身房','影音室','琴房','衣帽间','储藏室','独立洗衣间','酒窖','佛龛位']],['specialNeeds','特殊要求（无则填“无”）','textarea']]],
 ['现居痛点与新家期待', [['painPoints','目前居住的不便','checks',['采光差','储物不足','动线混乱','卫生间不够','厨房太小','没有独立玄关']],['painOther','其他现居问题（选填）','textarea'],['expectations','最希望在新家实现','checks',['明亮通透','足够储物','亲子互动空间','安静书房','舒适主卫','社交型客厅']]]],
 ['风格与参考', [['styleRefs','喜欢的风格','checks',['新中式','欧式','现代','田园','中式','宋式']],['styleNotes','参考案例或其他偏好（选填）','textarea']]]
];
function sections(brief) { return schema.map(([title,fields],index)=>({title,index,summary:fields.map(([key])=>Array.isArray(brief[key])?brief[key].join('、'):brief[key]).filter(Boolean).slice(0,2).join(' · ')||'点击补充',fields:fields.map(([key,label,type,options])=>({key,label,type,value:brief[key]||'',options:(options||[]).map(label=>({label,selected:Array.isArray(brief[key])?brief[key].includes(label):brief[key]===label}))}))})); }
function summary(b) {
 return schema.map(([title,fields])=>{const lines=fields.map(([key,label])=>{if(key==='entranceDirection'&&b.entranceAligned!=='不一致')return '';const v=Array.isArray(b[key])?b[key].join('、'):b[key];return v!==undefined&&v!==null&&v!==''?label.replace(/（.*?）/g,'')+'：'+v:'';}).filter(Boolean);return lines.length?title+'\n'+lines.join('\n'):'';}).filter(Boolean).join('\n\n');
}
function snapshot(request) {
 try {const raw=request&&request.schemeSnapshot;const data=typeof raw==='string'?JSON.parse(raw):raw;return data&&data.designBrief||null;}catch(e){return null;}
}
module.exports={sections,summary,snapshot};
