// Public, fixed examples only. Never read a company book or a previous session here.
module.exports=function(){return {
 deskTitle:'管理工作台',role:'访客预览',mode:'preview',companyId:'',monthText:'2026年10月',
 summary:{income:'8.50',paid:'2.68',balance:'5.82'},
 financeCounts:{reconcile:1,pay:1,verify:1},
 guestMenu:[{key:'data',label:'经营数据',icon:'chart'},{key:'finance',label:'财务收支',icon:'money'},{key:'team',label:'团队绩效',icon:'target'},{key:'staff',label:'人员与分工',icon:'team'}],
 guestTodos:[
  {key:'receipt',icon:'receipt',name:'陈先生 · 工程一期预收款',amount:'100,000.00',status:'待核算',note:'核算完成后计入有效已收'},
  {key:'pay',icon:'payment',name:'陈先生 · 总部设计服务',amount:'4,000.00',status:'待办理',note:'约定6,000元，已付2,000元'},
  {key:'verify',icon:'wallet',name:'林女士 · 设计服务',amount:'6,000.00',status:'待核实',note:'结果核实前不计入有效已付'}
 ],
 guestProjects:[
  {id:'chen',name:'陈先生 · 湖北乡墅',status:'主体施工',note:'两层 · 400㎡ · 收支与费用关联查看'},
  {id:'zhang',name:'张先生 · 在建项目',status:'重点跟进',note:'施工进度、验收记录与计划一处查看'}
 ],
 guestGroups:[
  {name:'经营管理',items:[
   {key:'data',label:'经营数据',icon:'chart',target:'data',text:'按业务、工程和收支查看经营情况，了解项目进度、资金变化与重点事项。'},
   {key:'projects',label:'项目收支',icon:'folder',target:'projects',text:'从项目列表进入同一项目，查看收款、付款和费用资料；收支结余与项目利润分别核对。'}
  ]},
  {name:'财务管理',items:[
   {key:'finance',label:'财务收支',icon:'money',target:'finance',text:'按账期查看有效收款、付款、退款及原笔凭据；待核算或待核实的款项不计入有效汇总。'},
   {key:'todos',label:'财务待办',icon:'chart',target:'todos',text:'集中查看核算、付款、补证与复核事项。实际办理按岗位、原单状态和本人参与情况校验。'},
   {key:'plans',label:'收付款计划',icon:'calendar',target:'plans',text:'查看预计收款、计划付款及待跟进事项；计划金额不代表已经实际收付。'},
   {key:'costs',label:'项目费用资料',icon:'folder',target:'projects',text:'先选择项目，再核对人工、材料、分包、运输和设计费用；成本资料齐全后测算项目利润。'}
  ]},
  {name:'团队绩效',items:[
   {key:'team',label:'团队绩效',icon:'target',target:'team',text:'按周期查看授权团队的目标、完成情况和指标来源，进入成员绩效明细。'},
   {key:'targets',label:'我的目标',icon:'target',target:'team',text:'查看个人目标、权重及完成情况；登录后按实际岗位查看本人或授权团队的目标。'},
   {key:'rules',label:'考核方案',icon:'chart',target:'rules',text:'设置周期、指标、目标与权重，核对方案版本；锁定后的历史考核保留原口径。'},
   {key:'review',label:'周期复核',icon:'calendar',target:'review',text:'在周期结束时核对指标来源、异常说明和考核结果，完成本周期复核。'},
   {key:'history',label:'历史考核',icon:'folder',target:'history',text:'按周期回看已归档考核、指标来源及复核记录，便于追溯。'},
   {key:'appeal',label:'绩效申诉',icon:'team',target:'team',text:'先选择具体考核记录，再说明异议并补充证据；保留申诉与复核结果。'}
  ]},
  {name:'人员与组织',items:[
   {key:'staff',label:'人员与分工',icon:'team',target:'staff',text:'查看成员、岗位和工作责任，按组织授权配置人员分工。'},
   {key:'transfer',label:'责任转交',icon:'team',target:'staff',text:'先选择人员及原任务，再明确后续责任人；历史作者和既有证据保留。'},
   {key:'departure',label:'离岗交接',icon:'folder',target:'staff',text:'核对未完成工作并安排交接，再处理人员访问资格，避免任务无人跟进。'},
   {key:'company',label:'公司管理',icon:'house',target:'company',text:'查看当前组织资料及管理配置；实际可见范围由本组织的岗位授权决定。'}
  ]}
 ]
};};
