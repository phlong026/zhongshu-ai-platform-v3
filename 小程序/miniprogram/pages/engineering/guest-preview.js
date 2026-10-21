// Public, read-only examples. Guest search never reads customers or real projects.
const projects = [
  {id:'preview-chen',name:'陈先生 · 湖北乡墅',city:'武汉',area:'400m²',stage:'主体施工',progress:65,featured:true,image:'/pages/engineering/assets/project-chen.jpg',next:'下一节点：二层楼板验收 · 10月22日',stages:[{label:'基础完成'},{label:'主体施工',current:true},{label:'装修'},{label:'交付'}]}
];
const stats=[{label:'在建',value:projects.length,unit:'个',icon:'helmet',action:'projects'},{label:'待交付',value:1,unit:'个',icon:'house',action:'projects'},{label:'待处理',value:3,unit:'项',icon:'tasks',action:'siteConfirm'}];
const todos=[{id:'preview-steel',title:'陈先生 · 钢筋进场验收',note:'今日14:00 · 李经理',icon:'helmet',action:'siteConfirm',button:'去验收'},{id:'preview-delivery',title:'林女士 · 交付资料待核对',note:'10月20日前',icon:'tasks',action:'siteConfirm',button:'查看',outline:true}];
const shortcuts=[{title:'现场记录',note:'拍照 · 日志',icon:'camera',action:'siteConfirm'},{title:'材料收货',note:'到货登记',icon:'material',action:'materials'},{title:'验收整改',note:'问题跟进',icon:'inspection',action:'siteConfirm'},{title:'项目资料',note:'图纸 · 文档',icon:'folder',action:'projects'}];
function search(value){const keyword=String(value||'').trim().toLowerCase();return projects.filter(p=>(p.name+p.city+p.stage).toLowerCase().includes(keyword));}
module.exports={projects,stats,todos,shortcuts,search};
