const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve('.'),dir=path.join(root,'qa/finance-implementation-20261021');
const read=relative=>JSON.parse(fs.readFileSync(path.join(dir,relative),'utf8'));
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const model=read('page-model-results.json'),native=read('native/native-results.json'),menus=read('menus-native/results.json');
const restored=[read('native/finance-native-restored.json'),read('menus-native/finance-menus-restored.json')];
const packageCheck=read('package-check.json');
const menuFixture=read('menu-fixtures.json');
const facts=read('native-final-facts.json');
if(facts.result!=='passed')throw Error('Native stored financial facts not verified');
const navigation=read('navigation-restore-results.json');
if(navigation.result!=='passed')throw Error('Financial return state not verified');
const historic=JSON.parse(fs.readFileSync(path.join(root,'qa/closed-loop-remediation-20261020/regression-results.json'),'utf8'));
for(const r of [model,native,menus,historic])if(r.result!=='passed'||r.checks.some(c=>c.result!=='passed'))throw Error('Acceptance has unpassed checks');
if(!restored.every(r=>r.restored)||menus.exceptions.length||native.exceptions?.length)throw Error('Native acceptance or storage restoration incomplete');
const files=['miniprogram/utils/finance-desk.js','miniprogram/pages/admin/finance-desk/index.js','miniprogram/pages/admin/finance-desk/index.wxml','miniprogram/pages/admin/finance-desk/finance-home.wxml','miniprogram/styles/finance-desk.wxss','miniprogram/pages/management/index.js','miniprogram/utils/organization.js','miniprogram/pages/admin/finance-views.js','miniprogram/components/brand-header/index.js','miniprogram/pages/admin/operations/index.js','miniprogram/utils/operations.js','miniprogram/utils/project-settlement.js'];
const source=files.map(file=>({file,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')}));
const shots=[['加盟商财务专员首页','01-store-staff-home.png'],['加盟商财务负责人首页','02-store-lead-home.png'],['加盟商收支页 · 搜索优先','03-store-flows-search-first.png'],['加盟商项目账详情','04-store-project-account.png'],['总部财务专员首页','05-hq-staff-home.png'],['总部财务负责人首页','06-hq-lead-home.png'],['总部加盟商往来页','07-hq-partners.png'],['设计尾款双方对账详情','08-design-tail-reconciliation.png']];
for(const[,file]of shots)if(!fs.existsSync(path.join(dir,'native',file)))throw Error('Missing native screen '+file);
const table=rows=>'<div class="table-wrap"><table>'+rows.map((r,i)=>'<tr>'+r.map(c=>'<'+(i?'td':'th')+'>'+c+'</'+(i?'td':'th')+'>').join('')+'</tr>').join('')+'</table></div>';
const linked=(target,label)=>'<a href="'+encodeURI(target)+'">'+esc(label)+'</a>';
const businessReport='../business-flow-priority-20261021/预算至工程移交_业务流程验收.html';
const businessEvidence=fs.existsSync(path.resolve(dir,businessReport))?linked(businessReport,'预算、设计协同至工程移交：完整验收报告'):linked('../business-flow-priority-20261021/native-real-tap/results.json','预算、设计协同至工程移交：已完成的连续原生实测证据');
const jointSummaryFile=path.resolve(dir,'../engineering-finance-audit-20261021/summary.json'),jointSuiteFile=path.resolve(dir,'../engineering-finance-audit-20261021/final-native-suite.json');
const jointSummary=JSON.parse(fs.readFileSync(jointSummaryFile,'utf8')),jointSuite=JSON.parse(fs.readFileSync(jointSuiteFile,'utf8'));
if(jointSummary.result!=='passed'||jointSuite.result!=='passed'||jointSuite.checks.some(c=>c.result!=='passed')||!jointSuite.settlement.closed||!jointSummary.continuation.sourceUnchanged||jointSummary.continuation.projectId!==jointSummary.continuation.finalProjectId)throw Error('Joint end-to-end native evidence is incomplete');
const rows=checks=>checks.map((c,i)=>[String(i+1),esc(c.name||c.label),'<span class="ok">通过</span>']);
const interactions=menus.interactions.reduce((all,i)=>(all[i.kind]=(all[i.kind]||0)+1,all),{});
const inputFallbacks=menus.interactions.filter(i=>i.sdkInputFallback).length;
const triggeredInputs=interactions['Element.trigger(input)']||0;
const summary={result:'passed',date:new Date().toISOString(),scope:'财务八页改造、四岗位菜单及跨业务衔接；原生与模型证据分开记录',modelChecks:model.checks.length,historicChecks:historic.checks.length,nativeGroups:native.checks.length,nativeMenuAndBusinessChecks:menus.checks.length,interactions,storageRestored:restored.every(r=>r.restored),screens:shots.map(([label,file])=>({label,file:'native/'+file})),source};
summary.package={mainBytes:packageCheck.mainBytes,totalBytes:packageCheck.packageBytes,subPackages:packageCheck.subPackages};
summary.jointAcceptance={result:jointSummary.result,nativeSteps:jointSummary.endToEndNativeSteps,businessSteps:jointSummary.businessNativeSteps,engineeringContinuationSteps:jointSummary.businessContinuationSteps,engineeringPageStates:jointSummary.nativeEngineeringViews,engineeringMenus:jointSummary.nativeMenus,businessMenus:jointSummary.businessMenus,sourceUnchanged:jointSummary.continuation.sourceUnchanged,finalFacts:jointSummary.finalFacts,finalSource:jointSuite.finalSource};
summary.jointAcceptance.finalNativeGroups=jointSuite.checks.length;
fs.writeFileSync(path.join(dir,'acceptance-summary.json'),JSON.stringify(summary,null,2));
const html=`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>财务八页改造与全流程验收报告</title><style>
:root{--paper:#faf8f4;--brown:#89512a;--ink:#28221e;--muted:#74716d}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.75 "Microsoft YaHei","PingFang SC",sans-serif}main{max-width:1180px;margin:auto;padding:42px 28px 80px}h1{font-size:30px;line-height:1.4;margin:8px 0 18px}h2{font-size:23px;margin:0 0 18px;border-left:5px solid var(--brown);padding-left:12px}h3{font-size:18px}p{margin:12px 0}section{background:white;border-radius:18px;padding:28px;margin:24px 0}a{color:var(--brown);text-underline-offset:4px}.kicker{color:var(--brown);font-weight:700}.muted,small{color:var(--muted)}.ok{color:#346649;font-weight:700}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.metric{background:white;border-radius:14px;padding:20px}.metric b{display:block;color:var(--brown);font-size:30px}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;font-size:14px}td,th{text-align:left;vertical-align:top;padding:13px 14px;border-bottom:1px solid #eee7df}th{background:#f6f1e9;color:#6d4227;white-space:nowrap}.gallery{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px}.gallery img{width:100%;height:auto;border:1px solid #e3d9cb;border-radius:12px}.gallery figure{margin:0}.gallery figcaption{font-size:14px;margin-top:8px}details{border-top:1px solid #eee7df;margin-top:18px;padding-top:16px}summary{cursor:pointer;font-weight:600;color:var(--brown)}code{background:#f4f0e9;padding:2px 5px;border-radius:4px;font-size:13px}.note{border-left:3px solid #d5b998;background:#fcf8f2;padding:16px 20px}.flow{color:#714325;font-weight:600;line-height:2.2}.badge{display:inline-block;background:#edf4ee;color:#346649;border-radius:20px;padding:4px 14px;font-size:14px}@media(max-width:760px){main{padding:24px 16px}section{padding:22px 16px}.metrics,.gallery{grid-template-columns:repeat(2,1fr)}h1{font-size:25px}}@media print{body{background:white}section{break-inside:avoid;border:1px solid #eee}.gallery{grid-template-columns:repeat(4,1fr)}details{display:block}}
</style></head><body><main>
<div class="kicker">众墅之家 · 2026年10月21日</div><h1>财务八页改造与全流程验收报告</h1><span class="badge">本轮财务验收通过</span>
<p>已将批准的8张效果图落到原生小程序。保留暖白底、棕色按钮和统一品牌页头，按门店／总部、专员／负责人区分业务重点；收支页优先显示搜索框。新页面与原项目、审批、收付、复核及财务收尾流程相连。</p>
<div class="metrics"><div class="metric"><b>8页</b>原生财务界面</div><div class="metric"><b>4岗位</b>分别检查菜单与权限</div><div class="metric"><b>${model.checks.length}+${historic.checks.length}</b>本轮与历史业务回归</div><div class="metric"><b>${menus.checks.length}项</b>原生菜单及连续提交校验</div></div>
<section><h2>一、页面与岗位落地</h2>${table([
 ['对象','底部Tab','本轮重点'],
 ['加盟商财务专员','首页、收支、项目、我的','实际收付、已批原事项付款、原单补证和总部对账。'],
 ['加盟商财务负责人','首页、收支、项目、我的','保留收付办理；突出独立复核、未收／逾期、近期应付、团队、成本资料。'],
 ['总部财务专员','首页、收支、加盟商、我的','总部本身收付、设计费应收、代垫未收回、门店已付后总部的实际到账登记。'],
 ['总部财务负责人','首页、收支、加盟商、我的','加盟商往来与风险、总部独立核算、经办交接、团队和资金核对。'],
 ['收支页面','搜索 → 应收／应付／流水 → 月份与汇总 → 筛选 → 原单','搜索支持客户、项目、组织及原单；状态、类别、排序和空结果可以继续操作。']
 ])}</section>
<section><h2>二、业务衔接与金额口径</h2><p class="flow">预算与委托 → 老板批准设计费 → 门店付款及复核 → 总部实际核收 → 设计指派／审核／下发 → 客户签认与建造合同 → 工程收款、材料及费用 → 成本核对 → 财务结清</p>${table([
 ['衔接事项','本轮处理与验证'],
 ['设计阶段项目账','未签建造合同时已可查看设计服务账；不强制以建造合同作为设计前置。'],
 ['总部应收来源','按老板已批准的当前版服务约定形成设计应收，门店尚未付款时总部仍能看到未收。'],
 ['双方分笔款项','门店有效已付、待复核占用、总部实际已核收、在途待核分别计算；按原单ID及服务版本匹配，不凭门店回单自动记总部到账。'],
 ['设计办理条件','首款双方足额核对后可指派，尾款双方足额核对后可正式下发；财务结果回写原设计任务。'],
 ['合同与工程','项目账接通原合同分期、工程实际收款、材料应付与费用；老板金额审批和合同收款放行规则继续执行。'],
 ['材料代垫','总部实际代付、门店归还、总部核收沿同一材料原单追踪。归还不重复作为收入或项目成本。'],
 ['业主直付','保留独立核对链，不混入公司现金或客户合同实收。'],
 ['公司费用与绩效','公司费用先提交、老板批准再登记原应付实付；员工已归档绩效按老板明确的模拟计费规则生成结算，经批准后发放。'],
 ['财务结清','工程资料归档与财务结清分别记录。合同、设计、材料、往来或成本未核对齐时显示阻断事项；账目变化后需重新核对。'],
 ['本店设计与现金','本店设计不虚构内部往来或总部在途。月份是个人视图偏好，实际收付按银行事实日期统计；收支结余不等于利润，成本不全时不显示确定毛利。']
 ])}</section>
<section><h2>三、原生页面截图</h2><p class="muted">以下为微信开发者工具实际渲染。数字来自明确的模拟业务记录，与效果图中的示意金额不同；顶部系统状态栏、微信胶囊及底部安全区由微信运行环境提供。</p><div class="gallery">${shots.map(([label,file],i)=>'<figure><a href="native/'+file+'"><img loading="lazy" src="native/'+file+'" alt="'+esc(label)+'"></a><figcaption>'+String(i+1).padStart(2,'0')+' '+esc(label)+' · '+linked('visual-comparison/'+String(i+1).padStart(2,'0')+'-comparison-final.png','图稿对照')+'</figcaption></figure>').join('')}</div><p>${linked('native/08-design-tail-reconciliation-bottom.png','双方对账页下半屏')} · ${linked('native/01-store-staff-home-bottom.png','专员首页下半屏')} · ${linked('native/extra-reconciled-statement.png','双方足额核对后')}</p></section>
<section><h2>补充的模拟业务数据</h2>${table([
 ['数据','用途与来源'],
 ['设计尾款两笔各2,500元','由当前业务命令生成5,000元尾款应付；连续完成门店登记、门店独立复核、总部两笔登记及独立核算。'],
 ['工程收款 '+(menuFixture.collectionAmount/100).toLocaleString('zh-CN')+' 元','使用原工程收款线索，回到待财务查实状态；验证专员实际登记、另一负责人核算。金额取当前原单，未写死旧合同价。'],
 ['公司费用120元及新增150元','原命令提交并经老板审批；新增150元在原生表单真实输入、批准后按原应付付款。'],
 ['归档绩效3个节点 × 100元＝300元','先用实际绩效提交、复核归档，老板明确本次模拟计费规则；财务生成、老板核准，再按原应付发放。'],
 ['总部材料代垫200元、门店已归还100元','按原材料供应单经老板审批后分别登记实际支付；保留部分归还待总部查实的真实待办。'],
 ['期初资金1,000元、剩余预计成本逐类填0','用原财务负责人命令建立明确期初与成本核对前置状态；不是自动推定所有真实项目成本已齐。']
 ])}<p class="muted">所有数据均在隔离验收副本使用，运行前后存储已核对恢复。原生菜单检查与连续提交子链分轮记录，未将跨轮重置描述为同一轮连续办理。</p></section>
<section><h2>四、发现的断点与修复</h2>${table([
 ['问题','修复','复验'],
 ['公司费用入口中的 company%3Aquanzhou 被误判无权访问','operations 页面安全解码原项目ID；新财务页也对原ID与筛选值安全解码。','公司费用菜单实点、原费用提交／批准／实际支付。'],
 ['财务看不到员工已归档绩效，结算无法继续','仅在老板／财务的绩效结算视图使用同组织完整归档来派生结算行；不向页面暴露未过滤原账簿。','已归档3个节点，明确100元／节点的模拟规则，生成300元结算并批准、实际发放。'],
 ['本店设计已付可能被显示为总部在途','内部设计在途为0，剩余与逾期按本店有效已付核对。','本轮财务模型专项通过，避免生成内部往来。'],
 ['通用子页导航隐藏了财务主页面的品牌标识','brand-header 新增可选 branded；仅新财务页启用，保留内容区返回。','最终8页截图与批准图稿并列检查。'],
 ['页面切换后截图过早，项目账截图仍显示列表','验收等待项目账原生节点渲染完成并等待过渡结束，再采集截图。','重新捕获详情；不把数据已变化当成视觉已验证。'],
 ['开发工具自动化实际输入响应超时','保存43项已通过的菜单证据并恢复存储；统一任务串行占用微信服务，输入前滚动并重新定位原生控件。','实际到账、公司费用、绩效结算的提交子链独立复测，结果与首次超时记录分别保留。'],
 ['历史测试写死旧合同金额、旧导航和旧设计前置数据','按当前原合同／收款线索动态断言金额；使用本版业务命令生成设计前置数据；从新项目账点击到原办理页。','历史22项与当前财务模型全部通过。']
 ])}</section>
<section><h2>五、验证结果与证据</h2>${table([
 ['验证层','结果','证据'],
 ['本轮财务 Page/WXML 与原命令','通过 '+model.checks.length+' 项',linked('page-model-results.json','结果JSON')+'；'+linked('source-main/results.json','当前主链13阶段')],
 ['历史闭环回归','通过 '+historic.checks.length+' 项',linked('../closed-loop-remediation-20261020/regression-results.json','历史专项结果')],
 ['原生8页／关键交互／四Tab','通过 '+native.checks.length+' 组',linked('native/native-results.json','原生结果')],
 ['逐菜单与连续提交','通过 '+menus.checks.length+' 项',linked('menus-native/results.json','完整点击与输入记录')+'；'+linked('menus-native/menu-navigation-results.json','首轮43项导航证据')],
 ['真实控件事件','Element.tap '+(interactions['Element.tap']||0)+' 次；Element.input '+(interactions['Element.input']||0)+' 次；Element.trigger(input) '+triggeredInputs+' 次','按实际日志统计；日期选择使用原生picker change事件。数值输入框使用官方Element.trigger在同一渲染控件派发input事件，并逐一核对绑定值；未计为Element.input或人工键盘测试。'+(inputFallbacks?'另有'+inputFallbacks+'个SDK输入方法超时后改用官方input事件，日志单列。':'')],
 ['原生异常与存储恢复','业务脚本完成时无未处理异常；两轮恢复均为 true',linked('native/finance-native-restored.json','8页轮次恢复')+'；'+linked('menus-native/finance-menus-restored.json','菜单／连续提交轮次恢复')],
 ['原生提交后落账核对','只读核对5项通过：实付状态、金额、工程款独立核算、原费用与绩效、经营费用汇总',linked('native-final-facts.json','已保存业务事实')],
 ['深层返回筛选与列表刷新','Page集成通过；最后补齐flowTab、状态、排序、到期筛选及财务列表刷新钩子',linked('navigation-restore-results.json','深层返回结果')+'。该小项在截图轮结束后通过Page集成复验，未另起微信轮次。'],
 ['静态结构与分包','通过：31个原生页面；主包 '+packageCheck.mainBytes.toLocaleString('en-US')+' 字节，总包 '+packageCheck.packageBytes.toLocaleString('en-US')+' 字节',linked('package-check.json','完整分包检查')],
 ['首次输入超时现场','已单独归档，后续成功复验保留',linked('menus-native/navigation-then-input-timeout.json','超时现场与43项已过记录')],
 ['源码与证据清单','本轮摘要包含关键文件SHA256',linked('acceptance-summary.json','验收摘要')]
 ])}<details><summary>逐项查看原生菜单和业务提交结果</summary>${table([['序号','检查内容','结果'],...rows(menus.checks)])}</details><details><summary>逐项查看财务业务与权限回归</summary>${table([['序号','检查内容','结果'],...rows(model.checks)])}</details><details><summary>逐项查看原生8页与交互结果</summary>${table([['序号','检查内容','结果'],...rows(native.checks)])}</details></section>
<section><h2>六、联合验收最终结果与任务协调</h2><p><strong>业务 → 工程 → 财务已沿同一客户、同一项目连续走到财务结清。</strong>业务35步原生实际办理完成后的存储，直接接续工程47步，合计${jointSummary.endToEndNativeSteps}步；接续前后客户与项目ID一致，源状态未被改写，结清状态为“财务已结清”、无阻断事项。最终结果及各轮原数据恢复均已核验。</p>${table([
 ['联合案例核对项','最终保存事实（金额单位：元）'],
 ['项目规模',jointSummary.finalFacts.area+'㎡，'+jointSummary.finalFacts.floors+'层；'+esc(jointSummary.finalFacts.projectId)],
 ['建造合同 / 建造有效实收',jointSummary.finalFacts.contractYuan.toLocaleString('zh-CN')+' / '+jointSummary.finalFacts.constructionIncomeYuan.toLocaleString('zh-CN')],
 ['设计服务费',jointSummary.finalFacts.designServiceYuan.toLocaleString('zh-CN')+'，与建造合同收款分账核对'],
 ['项目应付 / 有效已付',jointSummary.finalFacts.payableYuan.toLocaleString('zh-CN')+' / '+jointSummary.finalFacts.paidYuan.toLocaleString('zh-CN')],
 ['施工与交付',jointSummary.finalFacts.completedNodes+'个完成节点，'+jointSummary.finalFacts.reships+'次短缺补发，'+jointSummary.finalFacts.rectifications+'次整改；已交付、已归档、已财务结清'],
 ['补充原生覆盖',jointSummary.businessMenus+'项业务菜单；'+jointSummary.nativeEngineeringViews+'种工程页面状态、'+jointSummary.nativeMenus+'项工程菜单；27类提交落点、5项原单关联、6项主入口及双项目CSV导出通过'],
 ['最终权限补测与版本',jointSuite.checks.length+'组联合原生验收通过，包含旧来源文档直链写权限拦截补测；'+jointSuite.finalSource.files+'文件，SHA256：'+esc(jointSuite.finalSource.sha256)]
 ])}<p class="muted">此联合案例与上方150元公司费用、300元绩效发放专项采用不同的隔离模拟数据，金额不相互相加。最终源码版本、交付标签变动及旧来源文档直链权限修复的精确覆盖证明保存在联合验收JSON中，未将模型结果替代原生执行证据。</p><p>财务使用独立副本 <code>.tools/finance-desk-20261021</code> 和端口9470；业务／设计使用9450，工程使用独立端口。各轮按约串行使用微信调试服务，完成后恢复存储、断开并明确释放；最终均已结束，不再占用自动化端口。</p><ul><li>${businessEvidence}。</li><li>${linked('../engineering-finance-audit-20261021/工程业务到财务闭环完整验收报告.html','工程业务至财务结清：完整验收报告')}；${linked('../engineering-finance-audit-20261021/final-native-suite.json','联合原生验收最终汇总')}；${linked('../engineering-finance-audit-20261021/native-business-continuation/continuous-results.json','同项目接续47步原生结果')}。</li></ul></section>
<section><h2>七、交付与使用</h2><p>交付源码位于根目录 <code>miniprogram/</code>。本轮未上传、发布或执行真实银行转账。模拟账号、示例凭据和100元／节点的绩效规则仅为本地验收数据，未设成产品默认规则。</p><p>可复跑 <code>npm run check:finance</code>。独立微信自动化实例就绪并协调占用后，依次运行 <code>npm run check:finance:native</code>、<code>npm run check:finance:menus:native</code>；可用 <code>FRANCHISE_WS</code> 指定已就绪端口。</p><div class="note">本报告证明本地原生小程序在明确模拟数据下的页面与业务衔接结果。真实服务端跨设备同步、云凭据、真实银行核验及真机表现需在对应环境继续验证。</div></section>
<p class="muted">本报告由实际结果文件汇总生成。${linked('acceptance-summary.json','机器可读摘要')} · ${linked('../../design-qa.md','设计验收记录')}</p>
</main></body></html>`;
const file=path.join(dir,'财务八页改造与全流程验收报告.html');fs.writeFileSync(file,html);console.log(JSON.stringify({report:file,summary:{...summary,source:source.length}}));
