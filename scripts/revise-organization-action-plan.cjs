const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const root=path.resolve('qa/org-permissions-20261018'),file=path.join(root,'organization-permissions.json'),s=JSON.parse(fs.readFileSync(file,'utf8'));
if(!fs.existsSync(path.join(root,'organization-permissions-v1.json')))fs.copyFileSync(file,path.join(root,'organization-permissions-v1.json'));
s.status='方案修订版 V2：按具体动作和任务协作授权；本轮为源码评估，尚未按新版修改页面。';
s.authorizationModel='岗位资格＋组织边界＋任务关系＋具体动作授权＋字段范围＋当前流程/版本条件＋独立性限制。缺一不可。';
s.releaseClarification='设计师可以执行正式图纸下发。默认由主设计师办理，也可由负责人指定同组织且具设计岗位资格的下发执行人。负责人负责派单、改派、审核和下发授权；系统在正式下发前强制核对本版审核、约定款项和接收范围。';
const designer=s.roles.find(r=>r.org==='总部'&&r.post==='设计师');designer.write='开始本人任务、提交本人负责的设计内容、按授权参与协作、修订版本；主设计师或明确获授权的设计师可在条件满足后执行正式下发。';designer.review='不能审核本人参与设计的成果；下发执行权不包含审核权、财务复核权或派单权。';designer.deny='未授权任务、派单改派、覆盖他人原稿、修改门店原需求、代财务确认、本人作品自审、条件不满足或未获下发授权时正式下发。';
const lead=s.roles.find(r=>r.org==='总部'&&r.post==='设计负责人');lead.write='受理/退回、派单改派、分配主设计师与协作者、排期、审核本版成果和图纸、指定或撤销下发执行人；可按同样条件执行下发。';
for(const row of s.businessFlow)if(row[1].includes('当前成果审核、约定款项')){row[0]='主设计师／指定下发执行人';row[1]='负责人完成本版审核，系统核对约定款项及交付条件后，由获授权人员正式下发；记录实际下发人、版本、文件包和接收人。';}
s.actionPermissions=[
['客户跟进','业务主责人；获授权的同组织业务协作者','授权协作者可追加本人跟进、测量或附件；客户归属、合同金额及历史原件单独保护。'],
['客户分配／主责交接','业务负责人／门店负责人','主责变更需正式交接；增加协作者不转移客户所有权或主责。'],
['设计需求提交与补资料','门店业务主责人；明确指定的资料协作者','协作者提交指定材料，正式需求和商业约定由相应主责确认；保留材料实际提交人。'],
['设计资料预检／受理','设计负责人默认办理；可授权指定设计师做资料预检或受理','受理动作与派单拆开；受理不能代财务确认到账。'],
['设计派单／改派','获授权设计负责人','指定主设计师、协作者、分工和时间；设计师可申请改派，不能自行改派或扩权。'],
['分项设计／协作','主设计师及被授权的现有设计师','按任务、专业或文件范围提交各自版本；主设计师整合正式成果，不能无痕覆盖协作者原稿。'],
['提交正式成果','主设计师或明确指定的提交人','绑定参与人员、需求版本和文件包；协作者的草稿提交不自动成为正式交付版本。'],
['设计成果／图纸审核','获授权设计负责人','审核人不得属于该版本实际设计参与人；审核结论绑定版本和文件摘要。'],
['正式图纸下发','默认主设计师；负责人指定的具资格设计师或负责人','必须有本版有效审核、约定款项核对和授权接收范围；记录实际下发执行人。'],
['同版补发／新版本再下发','获授权下发执行人','同版补发保留同版标识并记录原因、对象和次数；文件变化须升版，重新判断审核及下发条件。'],
['客户签认材料登记','业务主责人；被明确授予签认材料登记的业务协作者','上传真实签认材料，注明内部登记人和绑定版本；不建立客户登录身份。'],
['客户签认核验','另一名获授权业务负责人／门店负责人','与材料登记人员分离；退回原登记或正式接手人员补充。'],
['工程移交／接收','业务主责提交、指定项目经理接收','协作者可整理或提交获授权附件；正式接收责任不因协作名单自动改变。'],
['工程现场／材料协作','主项目经理；被授予具体任务的现有项目经理','可授权现场记录、到货或资料补充；正式开工、验收结论、交付等另按主责及动作授权判断。'],
['设计／工程变更','原主责或被授权的现场经办人提出；授权负责人审批','申请、商业影响审批、客户签认、设计修改和工程接收分别流转；协作者不能直接改原合同或原图。'],
['采购需求／报价确认','需求经办人确认规格、数量；采购承诺由授权负责人批准','需求确认、报价接受、采购批准和付款分开；可查看项目不等于可批准采购金额。'],
['付款／收款登记与补证','本组织财务经办；原材料提交人补指定材料','财务助办必须有财务岗位资格或正式指定的材料补充任务，不能靠普通协作名单获得记账或复核权。'],
['独立财务复核','本组织另一名获授权财务负责人','原经办、接手补证经办及本次更正申请人不能自审；角色切换和改派不能绕过。'],
['退款','业务依据核验／授权审批→财务经办→另一财务负责人复核','总部退款支出和门店退款到账分别记录与核对，保留原单及可退余额占用。'],
['已生效账务更正','财务岗位申请；另一名获授权财务负责人复核','保留原值与原因，不把一次更正操作直接当成申请和复核两个步骤。'],
['展陈／服务设计订单','设计负责人派单与审核，实际设计师制作及授权交付，门店指定人员验收','复用任务关系和动作授权；取消中的业务判断与资金核实分别由设计/业务及财务办理。'],
['人员岗位／协作授权','组织负责人管理岗位；各业务负责人仅管理获授权任务协作','负责人只能授予自己被允许分配的任务动作，不能借协作授予财务复核、跨组织访问或更高岗位。'],
['个人学习／收藏／创作草稿','本人','加入项目协作不自动共享个人学习、账号资料、收藏、私有视频和草稿；团队作品需明确交付到团队任务。']
];
s.collaborationRules=[
'协作者是现有人员在具体客户、项目、订单或任务中的参与关系；不是新增岗位或登录身份。',
'每个对象记录组织、主责人、协作者、分项任务、允许动作、字段/文件范围、授权人、有效期和撤销状态。',
'主责人对整体结果负责；协作者只能办理明确分配的动作。创建人、主责人、实际作者、审核人和下发人分别留存。',
'默认禁止协作者转交主责、增加协作者、扩展授权、正式审核、财务复核或覆盖他人历史。确有业务需要的执行动作逐项授予。',
'主设计师可以邀请或申请协作者，最终增加/撤销范围按负责人授权规则办理，不能让邀请动作成为自主扩权。',
'业务协作按跟进、测量、附件、签认材料等细分；设计协作按专业、分项或文件包细分；工程协作按现场、到货、资料等任务细分。',
'财务经办和独立复核不采用笼统协作名单。补证责任转交必须正式留痕，接手补证人员也进入自审排除名单。',
'实际参与设计的全部人员均进入该版本审核回避名单；只检查当前主设计师会遗漏改派前作者和其他协作者。',
'改派不自动删除历史作者或把旧审核变成本人的审核。正式成果内容改变时，旧审核与下发资格重新判断。',
'跨组织协作先满足订单/项目的组织协作关系，再给具体人员必要的动作和字段；不能通过添加个人绕过门店与总部隔离。',
'撤销协作、到期、离职、停用或撤权后停止新的读写并刷新缓存；历史贡献和办理记录保留。主责交接单独办理。',
'并行协作需要记录版本、冲突检查与重复提交保护，避免一个人的保存覆盖其他人的更新。'
];
const finding=(id,area,current,change,evidence,probe='')=>({id,area,current,change,evidence,probe});
s.codeFindings=[
finding('F01','派单、审核、下发的岗位硬绑定','release 被固定为 hq-release，assign 固定为 hq-dispatch；设计负责人岗位尚不存在。','移除独立流程岗位；派单归负责人，审核与正式下发分别授权，下发可由设计师执行。',[['miniprogram/utils/access.js',14],['miniprogram/utils/access.js',15],['miniprogram/utils/demo-users.js',12]],'A01'),
finding('F02','授权层之间不一致','设计师字段白名单含 release 等字段，但动作校验不允许其办理 release；仅改其中一层会产生错误拦截或扩大权限。','建立同一动作策略，页面、按钮、写入字段及提交入口使用一致规则。',[['miniprogram/utils/access.js',16],['miniprogram/pages/workflow/index.js',88]],'A01'),
finding('F03','业务协作者范围过宽','participantIds 让业务参与人获得客户访问，后续写入沿用业务员通用能力；内存探针可修改他人主责客户的电话及进入合同准备。','协作细分为具体动作/字段；跟进协作不自动开放合同、费用、客户主资料和责任交接。',[['miniprogram/utils/access.js',12],['miniprogram/utils/access.js',15],['miniprogram/utils/business.js',18]],'A02'),
finding('F04','缺少协作授权生命周期','参与人/总部办理人只有名单，未在访问判断中区分动作、字段、有效期与撤销；名单变更又被原单写入保护拦截。','专用授权、撤销和交接动作；权限不能靠普通客户表单直接改。',[['miniprogram/utils/access.js',12],['miniprogram/utils/access.js',16],['miniprogram/utils/business.js',25]],'A09'),
finding('F05','设计多人协作支持不足','设计开始和成果提交必须等于 assignment.designerId；派单会把设计人员名单设置为单人。','保留主设计师，并增加分项协作者和正式提交/下发执行人的独立授权。',[['miniprogram/utils/access.js',16],['miniprogram/utils/business.js',25]],'A04'),
finding('F06','设计版本与独立审核','正式下发主要检查首尾款、成果存在和版本；目前没有把设计成果的全部实际参与人及有效审核作为统一下发门槛。','审核绑定冻结文件包；所有实际作者回避；新版本重新审核；下发执行人可与设计人相同。',[['miniprogram/utils/access.js',16],['miniprogram/pages/workflow/views.js',34],['miniprogram/pages/workflow/index.js',87]]),
finding('F07','工程协作与专业审核','工程访问、写入以 managerId 为主，专业审核固定给 reviewer 账号，无法直接表达指定设计负责人的审核任务。','主项目经理保留，单项工程协作细分；审核分给现有设计负责人，新增任务入口而非新增岗位。',[['miniprogram/utils/access.js',13],['miniprogram/pages/build/model.js',44],['miniprogram/pages/build/model.js',48]]),
finding('F08','客户签认语义与账号依赖','界面称客户线下确认登记，权限却要求 customer 账号操作；员工页面被改为只读提示。','真实客户作为业务对象；内部人员登记签认材料，另一名负责人核验，绑定具体版本。',[['miniprogram/utils/access.js',15],['miniprogram/pages/workflow/views.js',39],['miniprogram/pages/workflow/index.js',18]]),
finding('F09','总部设计服务订单','服务订单 provider 限 hq-intake，方案制作、交付、补发及取消核实集中在同一旧岗位。','方案派单/审核、设计制作、授权交付和财务核实分别处理，并复用通用任务动作策略。',[['miniprogram/utils/account.js',34],['miniprogram/utils/account.js',49],['miniprogram/utils/account.js',52]],'A06'),
finding('F10','跨组织财务未形成双边核对','门店付款复核后回写业务 firstPayment/tailPayment；总部账簿按本组织过滤，不能据此生成总部实际到账核对。','门店付款与总部到账分立，通过同一订单关联；缺总部核对时业务条件不能直接完成。',[['miniprogram/utils/finance-ledger.js',11],['miniprogram/pages/admin/finance-save.js',9],['miniprogram/utils/access.js',16]],'A05'),
finding('F11','财务更正的独立性','correct 允许财务负责人直接追加生效更正记录，已有留痕但没有独立更正申请再复核。','更正也区分申请、依据及另一人复核，保留原值和退款余额约束。',[['miniprogram/pages/admin/finance-actions.js',17]],'A07'),
finding('F12','退款业务审批与资金复核','退款有可退余额和财务独立复核，但退款登记命令未检查业务退款审批依据。','先核验退款业务条件及授权审批，再登记实际退款，由另一财务人员核对；双方分别记退回到账。',[['miniprogram/pages/admin/finance-actions.js',14],['miniprogram/pages/admin/finance-actions.js',15]],'A08'),
finding('F13','财务补证交接规则不统一','付款/退款复核同时排除原登记人和接手补证人；收款交接尚未纳入同一转交动作，收款核对主要检查原登记人。','统一各类原单的当前补证责任、交接及自审回避；已有付款/退款的独立性保护保留。',[['miniprogram/pages/admin/finance-actions.js',6],['miniprogram/pages/admin/finance-actions.js',7],['miniprogram/pages/admin/finance-actions.js',12]]),
finding('F14','业务负责人管理边界','客户可见判断对 sales-lead 直接开放同公司，未使用授权团队；采购项目列表也存在负责人整公司范围判断。','加团队范围；可见客户、项目、统计和审批均按同一范围过滤。',[['miniprogram/utils/access.js',12],['miniprogram/pages/account/procurement.js',5]],'A03'),
finding('F15','采购动作含义需拆分','报价确认主要依赖项目可见、订单版本和登录权限，未区分需求确认与采购金额承诺审批。','需求经办确认规格数量；商业采购承诺和实际付款使用各自审批及财务流程。',[['miniprogram/pages/account/procurement.js',10]]),
finding('F16','页面应从任务动作生成','当前工作台按旧角色固定跳到单个环节，协作者无法只领取/办理获授权任务。','导航仍按岗位展示；工作台待办和按钮按任务关系、动作及状态生成，所有拒绝均给明确原因。',[['miniprogram/utils/dashboard.js',11],['miniprogram/utils/dashboard.js',22],['miniprogram/utils/workbench-nav.js',2]])
];
s.currentGaps=s.codeFindings.map(f=>[f.area,f.current,f.change]);
s.implementationOrder=[
'先建立组织、岗位资格、任务关系、动作、字段范围及流程条件的统一授权模型。',
'逐项梳理客户/设计/工程/采购/服务/财务动作，确定主责、协作者、审核人、下发执行人及禁止组合。',
'实现协作授权、撤销、正式交接、实际参与人留痕及版本冲突保护；再迁移旧角色与旧待办。',
'建立版本审核与下发条件；允许获授权设计师下发，负责人保留派单/审核/授权管理。',
'完善双方财务、退款和更正独立复核，统一原单补证与接手人员回避。',
'统一后端/本机命令校验与页面动作策略；最后调整导航、首页待办和我的入口。',
'测试允许场景与禁止场景：协作者不越权、设计师可授权下发、负责人可派单、作者不能自审、两边财务分别核对、撤权即时生效。'
];
s.acceptance.push('设计师获正式下发动作授权且条件满足时可成功下发；无授权、旧版本、未审核、款项不齐、收件范围不符时分别拒绝。','协作关系按任务/动作/字段限制，添加协作者不转移主责、不扩大组织边界，撤销与到期后停止新的读写。','同版的全部设计参与人员均不能完成本版独立审核；改派和换账号不清除回避关系。','服务订单、采购和工程协作采用相同的动作判定原则，个人草稿与学习数据继续隔离。');
const evidence=[];for(const f of s.codeFindings)for(const [p,line]of f.evidence){const source=fs.readFileSync(p,'utf8'),rows=source.split(/\r?\n/);assert(rows[line-1],p+':'+line);evidence.push({finding:f.id,path:p,line,sha256:crypto.createHash('sha256').update(source).digest('hex')});}
fs.writeFileSync(path.join(root,'action-audit-source-evidence.json'),JSON.stringify({date:s.date,evidence},null,2));fs.writeFileSync(file,JSON.stringify(s,null,2));
console.log(JSON.stringify({result:'revised',findings:s.codeFindings.length,actions:s.actionPermissions.length,collaborationRules:s.collaborationRules.length,evidence:evidence.length}));
