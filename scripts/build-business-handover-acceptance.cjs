const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const dir=path.resolve('qa/business-flow-optimization-20261020'),read=file=>JSON.parse(fs.readFileSync(path.join(dir,file),'utf8'));
const suites=read('suite-results.json'),regression=read('regression-results.json'),native=read('native/results.json'),exceptions=read('native-exceptions/results.json'),navigation=read('navigation/results.json'),navigationNative=read('navigation-native/results.json');
for(const r of [suites,regression,exceptions,navigation,navigationNative])assert.equal(r.result,'passed');assert(native.complete&&native.checks.length===36);assert.equal(native.exceptions.length,0);
const polish=read('ui-polish-results.json'),visual=read('final-visual/results.json');assert.equal(polish.result,'passed');assert.equal(visual.result,'passed');
const restored=[read('final-visual/restored.json'),read('native/business-continuous-restored.json'),read('native-exceptions/restored.json'),read('navigation-native/restored.json')];assert(restored.every(r=>r.restored));
const escape=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const batches=[
 ['第一批 · 业务流程与数据','已完成',[
  ['F01、F02','报价退回可编辑后以新版本重提，保留老板意见；独立预算无需先生成AI方案，可继续设计委托。'],
  ['F03、F04','生成前显示真实预算草稿；分项金额可核对，金额调整必须说明依据。面积和层数沿预算、委托、报价、合同、工程统一传递。'],
  ['F05、F06','设计付款按应付、累计有效已付、实际到账和余额显示；导航不受付款字段校验禁用。'],
  ['F07、F08','移交只列出在岗工程负责人，冻结报价、合同、正式成果、客户签认、施工图、专业审核及抄送七项资料。'],
  ['F09、F10','统一待办和办理状态；业务仅查看专业审核。工程退回后沿原单补充重提，接收后仍待开工。']
 ]],
 ['第二批 · 页面布局与操作','已完成',[
  ['F11','项目办理按方案预算、设计服务、正式设计、建造签约、工程交接分组，突出当前事项和责任岗位。'],
  ['F12','两种预算入口使用同一结果模板；关联客户后，底部主操作切换为继续办理。'],
  ['F13、F14','缩小项目头部，财务入口改为轻量链接；长字段分行，增加说明和占位提示，展示真实文件名与摘要。'],
  ['F15','沿用现有暖白底、棕色主按钮、白卡片、竖线标题与原图标。共享颜色、字级、间距、圆角、表单和固定操作区；弹窗也使用同一品牌色。'],
  ['新增返回问题','业务、工程、管理、账号、创作、预算和导出子页统一固定返回和工作台入口；原生栈保留来源，深层重定向保存直接来源、表单、筛选与滚动位置。检索和结果视图按原路径返回，切换身份后不恢复旧身份临时表单。']
 ]],
 ['第三批 · 完整流程与回归','已完成',[
  ['原生主链','同一项目只安装一次起始状态，连续执行36个操作，跨业务、老板、两侧财务、设计与工程岗位；全过程未逐步覆盖业务数据。'],
  ['异常回路','报价金额校验与退回重提、工程退回补充及重新接收，共5组原生测试通过。异常回路分别安装明确的模拟前置状态。'],
  ['导航验证','10组导航逻辑回归及'+navigationNative.checks.length+'组原生导航验证通过；原生验证包括八层页面回退、预算参数、原检索视图及各岗位工作台。'],
  ['兼容与规则','两条完整Page/模型主链、16组业务专项、既定审批10组、设计协同12组、最终显示4组，以及分包、资源和静态检查通过。']
 ]]
];
const figures=[
 ['native/01-calculate-after.png','独立预算结果','两种预算入口共用结果组件和固定操作区。'],
 ['native/05-request-before.png','设计委托','项目规模来自本次预算，当前事项与责任岗位清晰。'],
 ['final-visual/01-assign.png','设计派单弹窗','动态显示层数与面积，按钮沿用品牌棕色。'],
 ['final-visual/02-deliverable.png','设计成果提交','文件上传区显示本次已选文件，不再叠加静态待上传提示。'],
 ['final-visual/03-documents.png','施工图资料','签约文件按权限显示归档状态，设计人员可继续补图。'],
 ['final-visual/04-engineering-workbench.png','工程工作台','保留现有首页品牌页头与底部导航。'],
 ['native/28-quote-before.png','建造报价草稿','先核对真实依据与分项金额，再提交老板审批。'],
 ['native/34-handover-before.png','工程移交','指定工程负责人，核对当前版本资料后提交。'],
 ['native/35-receive-after.png','工程接收回执','绑定本次移交版本，保持待开工边界。'],
 ['final-visual/05-returned-quote.png','报价退回可重提','保留退回意见，以新版本重新报审。'],
 ['native-exceptions/04-sales-returned-task.png','工程退回后的业务待办','从待补充事项直接进入原单重提。'],
 ['navigation-native/01-budget-fixed-navigation.png','预算固定返回','返回原预算参数，工作台入口固定在顶部。'],
 ['navigation-native/02-deep-return.png','深层返回','实际八层页面验证直接来源逐级回退。'],
 ['navigation-native/03-account-navigation.png','账号子页面','统一返回与工作台入口。'],
 ['navigation-native/04-creation-navigation.png','创作子页面','沿用原有暖色页面和导航样式。'],
 ['navigation-native/05-owner-navigation.png','管理详情','固定导航返回所属工作台。'],
 ['navigation-native/07-engineering-navigation.png','工程子页面','从子页直接回到工程工作台。']
];
for(const [file]of figures)assert(fs.existsSync(path.join(dir,file)),file);
const table=rows=>'<table><tbody>'+rows.map(([label,text])=>'<tr><th>'+escape(label)+'</th><td>'+escape(text)+'</td></tr>').join('')+'</tbody></table>';
const checks=items=>'<ol>'+items.map(c=>'<li>'+escape(c.label||c.name)+' <span class="pass">通过</span></li>').join('')+'</ol>';
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>三批优化与完整流程验收</title><style>
*{box-sizing:border-box}body{margin:0;background:#faf8f4;color:#26221f;font:16px/1.8 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}main{max-width:1180px;margin:auto;padding:40px 24px 80px}h1{font-size:30px;line-height:1.4;margin:0 0 14px}h2{font-size:23px;border-left:4px solid #89512a;padding-left:12px;margin:34px 0 16px}h3{font-size:18px}p{margin:10px 0}a{color:#89512a}header,.panel{background:white;border:1px solid #eee8df;border-radius:16px;padding:24px;margin-bottom:20px}.meta{color:#6e737c;font-size:14px}.pass{color:#277242;font-size:14px;white-space:nowrap}.counts{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:22px 0}.count{background:#f7f3ed;border-radius:12px;padding:14px}.count strong{display:block;font-size:29px;color:#89512a}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:14px 10px;border-bottom:1px solid #eee8df;vertical-align:top}th{width:155px;font-weight:500;color:#6e737c}.flow{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0}.flow span{padding:8px 14px;background:#f7f3ed;border-radius:8px}.flow span+span:before{content:'→ ';color:#89512a}.gallery{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}.gallery figure{margin:0;padding:16px;background:#fff;border:1px solid #eee8df;border-radius:14px}.gallery img{width:100%;height:565px;object-fit:contain;background:#f7f3ed;border-radius:12px}.gallery figcaption{font-size:14px;line-height:1.7;margin-top:12px}.gallery strong{display:block;font-size:16px}details{border:1px solid #eee8df;border-radius:12px;background:#fff;padding:16px 20px;margin:14px 0}summary{cursor:pointer;color:#89512a}li{margin:8px 0}@media(max-width:760px){main{padding:24px 14px}.counts{grid-template-columns:repeat(2,1fr)}.gallery{grid-template-columns:1fr}h1{font-size:25px}th{width:95px}.gallery img{height:620px}}
</style><main><header><p class="meta">众墅之家 · 本地开发验收 · 2026-10-21</p><h1>预算至工程移交：三批优化完成</h1><p>业务流程、数据一致性、页面操作和返回导航已完成本地优化，沿用现有小程序设计风格。原生完整链路、退回重提及导航专项均已通过，模拟器测试前的13项本地存储已恢复。本轮尚未上传微信。</p><div class="counts"><div class="count"><strong>36 / 36</strong>原生连续操作</div><div class="count"><strong>5 / 5</strong>原生异常回路</div><div class="count"><strong>16 / 16</strong>业务专项回归</div><div class="count"><strong>${navigationNative.checks.length} / ${navigationNative.checks.length}</strong>原生导航验证</div></div><p class="meta">依据：<a href="../business-handover-audit-20261020/预算至工程移交_业务与页面优化审查.html">原业务与页面审查方案</a>。新增范围：整个小程序子页面返回问题。</p></header>
<section class="panel"><h2>本轮验证的完整流程</h2><div class="flow">${['独立预算','关联客户','设计委托','费用核定与老板审批','分笔支付和到账核对','受理与设计协作','成果专业审核','尾款与正式下发','门店收图和客户签认','建造报价审批','签约与施工图审核','工程移交','负责人接收','开工条件核查'].map(v=>'<span>'+v+'</span>').join('')}</div><p>原生主链使用三层540㎡项目：未生成AI平面方案，合同及交接资料持续保持同一计价面积，工程接收不自动登记开工。</p><p class="meta">测试使用模拟账号、示例文件和付款凭据。验证范围为本地业务规则、真实Page处理器、WXML控件及微信开发者工具渲染；未执行真实银行转账、生产文件签署或真机操作。</p></section>
${batches.map(([name,status,rows])=>'<section class="panel"><h2>'+name+' <span class="pass">'+status+'</span></h2>'+table(rows)+'</section>').join('')}
<h2>最终页面截图</h2><p class="meta">以下为本地微信开发者工具截图。主链使用“陈先生”，异常回路与导航测试使用独立模拟前置状态，客户名称和版本可能不同。</p><div class="gallery">${figures.map(([file,title,desc])=>'<figure><a href="'+file+'"><img loading="lazy" src="'+file+'" alt="'+escape(title)+'"></a><figcaption><strong>'+escape(title)+'</strong>'+escape(desc)+'</figcaption></figure>').join('')}</div>
<h2>可核对的测试记录</h2><details><summary>原生连续操作 · 36项</summary>${checks(native.checks)}<a href="native/results.json">原生结果JSON</a></details><details><summary>原生异常回路 · 5组</summary>${checks(exceptions.checks)}<a href="native-exceptions/results.json">异常回路JSON</a></details><details><summary>原生返回导航 · ${navigationNative.checks.length}组</summary>${checks(navigationNative.checks)}<a href="navigation-native/results.json">原生导航JSON</a></details><details><summary>业务专项与导航逻辑</summary>${checks(regression.checks.concat(navigation.checks))}<a href="suite-results.json">完整测试套件</a></details>
<section class="panel"><h2>复测与交付状态</h2><p>常规回归：<code>npm run check:business:handover</code>。</p><p>官方模拟器端口9430就绪后，依次运行：<code>check:business:handover:native</code>、<code>check:business:handover:exceptions:native</code>、<code>check:navigation:native</code>，均通过 <code>npm run</code> 执行。原生测试不可并行。</p><p>源码与验收资料已保存在当前工作区。本轮没有上传开发版本或切换体验版。</p></section></main></html>`;
fs.writeFileSync(path.join(dir,'三批优化与完整流程验收.html'),html);
fs.writeFileSync(path.join(dir,'acceptance-results.json'),JSON.stringify({result:'passed',date:'2026-10-21',nativeContinuous:native.checks.length,nativeExceptions:exceptions.checks.length,regression:regression.checks.length,navigationLogic:navigation.checks.length,navigationNative:navigationNative.checks.length,uiPolish:polish.checks.length,storageRestored:restored.every(r=>r.restored),uploaded:false},null,2));
console.log(path.join(dir,'三批优化与完整流程验收.html'));
