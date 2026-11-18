from pathlib import Path
import json,html,datetime,zipfile
P=Path(__file__).parent
esc=lambda s:html.escape(str(s))
cases=json.loads((P/'cases.json').read_text(encoding='utf-8'))
cross=json.loads((P/'cross-role-results.json').read_text(encoding='utf-8'))
backend=json.loads((P/'backend-test-results.json').read_text(encoding='utf-8'))
native=json.loads((P/'native-final.json').read_text(encoding='utf-8'))
assert cross['passed'] and native['passed']
assert all(int(x['failures'])==0 and int(x['errors'])==0 for x in backend)
frontend=(P/'frontend-tests.txt').read_text(encoding='utf-8-sig')
assert ('pass 29' in frontend if (P/'mobile-section.html').exists() else 'pass 17' in frontend) and 'fail 0' in frontend
passed=[x for x in cases if x['passed']]
resolved=[]
execution_resolutions={
 '前期账本流程异常':('自动化选择器','调整测试为当前账本页面入口；前期收款、修订、关联和抵扣链路续跑通过。'),
 '付款报销流程异常':('自动化字段映射','报销接口使用payable_id，测试脚本改为读取实际字段；设计费垫付、分次报销与归还链路通过。'),
 '费用与更正流程异常':('产品缺陷 F04','退款更正仍保留旧详情弹层，已修改页面状态切换；两轮修复后更正提交与返回检查通过。'),
 '修复及导航复测异常':('自动化组件生命周期','月份确认后组件重建，测试重新获取组件再操作；月份确认、取消与历史月检查通过。'),
 '最终四端验收异常':('自动化显示格式、会话与渲染等待','先修正工程金额的千分位格式断言；最终版本复测时会话到期，重新登录取得会话；账本返回后的按钮在异步刷新完成后出现，测试增加元素等待。继续复测首页入口、收付款返回及四岗位页面通过。')
}
for c in cases:
 if not c['passed']:
  category,explanation=execution_resolutions[c['name']]
  resolved.append({**c,'resolved':True,'category':category,'explanation':explanation})
(P/'resolved-execution-errors.json').write_text(json.dumps(resolved,ensure_ascii=False,indent=2),encoding='utf-8')
flows=[
 ('01 前期定金与项目关联','财务：项目账本 → 新建前期客户账本 → 登记其他收款 → 上传凭据 → 修订收款 → 关联工程 → 抵扣合同款','定金先登记5,000元，再按银行实到修订为5,200元；关联项目及抵扣期款只改变归属，不重复生成收款。','03-deposit-success.png'),
 ('02 业主回款','业务：合同办理回款 → 财务：核实到账 / 退回补充 → 业务：查看退回原因、补充原金额申报 → 财务：确认到账 → 业务与工程刷新净实收','1,000元申报完成退回与确认；另用业务原生页面重复两轮10元链路。未核实前不计入实收，财务确认后才记一笔。','24-sales-return-fixed.png'),
 ('03 财务直接收款与合同变更','项目账本 → 登记收款 → 选择合同期次 → 登记实际到账 → 合同金额变更 → 业务 / 工程 / 老板查看','直接收款20,000元；合同新增5,000元尾款，总额由300,000元变为305,000元；未来到账日期、缺少变更依据均被拦截。','37-sales-final.png'),
 ('04 材料采购与分次付款','工程材料计划 → 财务补供应方、单价、运费及凭据 → 生成采购应付 → 公司付款400元 → 正在办理续付600元 → 供应商退回100元 → 原应付结算调整为900元','材料采购使用公司付款，页面不提供工程垫付选项；工程端最终显示应付900元、已付900元、待付0元。到货数量未做虚假补齐。','36-material-final.png'),
 ('05 工程劳务费用','工程登记现场劳务600元 → 填收款方及费用依据 → 挂接原应付 → 财务沿原单付款 → 工程查看费用和付款','工程成本与财务付款分别记录；支付后不再重复增加600元费用。','31-project-cost-final.png'),
 ('06 设计费垫付与报销','设计费待办 → 登记业务已垫付2,400元 → 自动形成员工往来 → 报销1,000元 → 续报1,400元','垫付不进入公司实付；报销进入公司现金支出，项目设计费用仍只计一次。设计审批完成状态属于本轮前置测试资料。','17-design-advance.png'),
 ('07 已报销后的退款与员工归还','原垫付明细 → 平台退600元至原垫付员工 → 员工形成应归还600元 → 财务登记归还 → 公司补付原设计单600元','设计合同金额仍为2,400元，因此原应付恢复600元；员工归还后往来为零，公司补付后原设计费结清。员工收到的退款不直接算公司收入。','32-employee-settled.png'),
 ('08 系统外费用','项目 / 公司账本 → 登记付款 → 没有原单，补记其他支出；或待付事项 → 登记其他待付费用 → 调整结算 → 调整账本归属 → 付款','项目运输300元、公司办公86元分别归账；搬运待付200元调整为250元后转入项目并付款。','33-company-final.png'),
 ('09 客户退款与误录更正','原收款明细 → 登记退给客户200元 → 财务负责人作废重登210元 → 返回项目账本；误录付款10元 → 负责人撤销 → 原误录费用调整为0','退款登记记录已发生的线下退款，不在小程序发起转账；退款不超过原单可退余额。多次更正复测后最终仅一笔210元有效。','25-correction-fixed.png'),
 ('10 流水与凭据','收支流水 / 账本流水 → 查看原记录 → 补充说明或凭据 → 保存 → 回看原记录','说明与文件绑定原记录，不新增收付款；空补充内容被拦截，上传完成走实际资产接口。系统文件选择与临时文件保存边界由自动化模拟。','20-supplement-detail.png'),
 ('11 月份、搜索与对账','首页右上月份 → 取消 / 确认 → 历史月；账本与流水搜索 → 清空；对账页 → 完成对账留痕','月份显示2026-11，取消不改变账期；切换历史月不清除待办。已核对2026-11的服务端对账事件1条。','26-month-picker.png'),
 ('12 四岗位与返回动线','财务首页各指标 / 快捷办理 → 对应事项 → 原单办理 → 成功 / 取消返回原入口；老板打开项目账本只读查看','项目收付款返回保留原账本与费用标签。业务、工程、老板直接请求付款接口均返回403；老板页面没有财务输入框。','38-boss-final.png')
]
issues=[
 ('F01','待办不够明确，办理绕路','设计待办出现FULL及编号，部分付款仍容易让人看到总额；“去办理”先到通用流水。','按原单显示项目、费用名称和剩余金额；首页、待办、正在办理直接打开原收款、付款或报销，保留原返回位置。已办结的旧待办不再打开空表单。','业主确认、材料续付、设计费垫付原生复测；任务匹配与已办结保护回归通过。'),
 ('F02','业务看不到收款退回原因','财务退回后，业务合同页仍只显示待回款，无法明确补什么。','合同接口返回本人待核实 / 已退回申报；合同页展示退回原因，重新提交预填原金额、日期和流水号。分期金额改为分行显示，避免按钮把文字挤成竖排。','两轮业务原生申报、退回、补充、财务确认完成；归属隔离与退回状态测试通过。'),
 ('F03','工程与合同约定不同步','工程页读取开工阶段保存的旧约定；合同增项和尾款可能未体现，催收仍可能使用工程留痕额。','工程收款和结算汇总读取统一合同期次，包含尾款；开工资金门槛读取最新约定，催收以统一净实收计算；已有施工事实不撤销。','四端金额核对通过；收齐、退款回缺、补收及合同变更后门槛/催收测试通过。'),
 ('F04','退款更正被详情遮挡，日期与来源难读','从账本明细进入“作废重登”，旧详情弹层仍在，且返回来源不完整；日期数组显示成逗号分隔，来源显示长编号。','确认更正后关闭旧详情，打开带原退款关联的表单，保存原项目与原标签作为返回目标；日期统一YYYY-MM-DD，来源展示项目与用途。','实际复现并截图；修复后再次作废重登，检查日期、来源和详情关闭，提交成功并返回原项目。'),
 ('F05','撤销后账本刷新不完整','付款撤销后只刷新通用资金数据，原账本详情与余额可能仍显示旧状态。','撤销成功后关闭详情，同时刷新资金、应付及当前账本。','10元误录付款撤销留痕；应付恢复后把误录费用调整为0，无残留待付。'),
 ('F06','采购按钮岗位文案不准确','财务自己录采购金额，按钮却写“保存付款信息，交财务办理”。','改为“保存采购金额”，继续复用原采购与财务付款链路。','采购凭据上传、应付生成、分次付款及结算复测通过。'),
 ('F07','兼容汇总接口漏合同收款','旧项目汇总仅按流水上的project_id统计，漏掉通过统一合同关联到项目的收款。','兼容接口的实收、退款和净已付复用项目账本同一聚合；保留其原待登记应收字段语义。','兼容接口与财务、老板项目账本交叉核对通过；新增HTTP契约测试通过。')
]
money=[('合同应收总额','305,000.00','已含5,000元增项尾款'),('业主合同净收','26,010.00','销售、工程一致；包含两轮10元原生复测'),('合同待收','278,990.00','合同总额减业主净收'),('项目累计流入','26,820.00','含员工归还600元；不等同于合同净收'),('项目累计付款','5,150.00','公司现金实付，包含报销'),('客户退款','210.00','旧退款及中间更正均作废'),('退回公司款','100.00','材料退款；退到员工的600元不计此项'),('项目收支结余','21,560.00','26,820＋100－5,150－210'),('项目费用','4,450.00','设计2,400＋材料900＋劳务600＋运输300＋搬运250'),('项目待付 / 员工往来','0.00 / 0.00','本轮新项目全部结清'),('全公司收支结余','110,885.00','包含历史验收数据；原场景增量21,474元，加界面补测7元')]
screens=[('29-finance-home-final.png','财务首页：大框架保持，月份、指标、待办、快捷办理、正在办理'),('30-finance-project-final.png','财务项目账本：结余与四项指标'),('31-project-cost-final.png','费用明细：已付与成本分别归集'),('32-employee-settled.png','员工往来：报销及归还后结清'),('25-correction-fixed.png','退款更正：关闭旧详情并保留返回项目账本'),('26-month-picker.png','月份选择：YYYY-MM展示，底部月份弹层'),('34-engineer-collections-final.png','工程收款：与统一合同的约定和实收一致'),('38-boss-final.png','老板项目账本：只读、金额与财务一致')]
for f,_ in screens:assert (P/f).exists(),f
def table(head,rows):return '<div class="table-wrap"><table><thead><tr>'+''.join('<th>'+esc(x)+'</th>' for x in head)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+esc(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'
body=f'''<header><div class="eyebrow">众墅之家 · 加盟商财务 · 2026-11-17</div><h1>财务业务全流程测试报告</h1><p>面向8—10人的乡墅公司，验证日常记账能否顺利完成，并核对业务、工程、财务与老板是否看同一套数据。</p><div class="summary"><b>{len(passed)} 项页面与流程检查</b><b>29 项跨岗位核对</b><b>88 项后端测试</b><b>17 项前端回归</b></div></header>
<nav><a href="#conclusion">结论</a><a href="#flows">业务链路</a><a href="#issues">问题与修复</a><a href="#money">金额核对</a><a href="#screens">实际页面</a><a href="#cases">逐项记录</a><a href="#limits">验收边界</a><button onclick="window.print()">打印 / 保存PDF</button></nav>
<main><section id="conclusion"><h2>测试结论</h2><p>本报告列明的财务核心链路已完成操作验证、问题修复及对应复测。首页大框架保持原确认样式，继续复用现有收款、应付、付款、报销、退款和账本接口。</p><p><strong>本轮发现并修复7类业务 / 界面问题。</strong>新测试项目最终收支结余为21,560元，项目费用4,450元，待付和员工往来均为0；业务与工程合同净收26,010元，老板账本与财务账本一致。首页仍有2项此前验收场景的未结事项，未为美化截图而删除。</p><div class="note">执行环境：Windows微信开发者工具的原生小程序模拟器＋真实本地后端＋隔离PostgreSQL测试库。财务账号实际完成账号查找、密码登录及岗位进入；其他岗位使用真实登录取得的测试会话切换。截图是运行页面，非效果图；本轮不属于物理手机验收，也没有向生产环境发布。<a href="tested-version.json">验收预览与后端构建指纹</a>已记录，便于后续版本复核。</div></section>
<section id="flows"><h2>测试业务链路与修复后的操作流程</h2><p>每条链路均区分业务来源、财务登记和查询结果。退款、报销、付款都是对已发生线下资金往来的登记，不触发实际转账。</p>'''
for name,chain,result,shot in flows:
 body+=f'<article class="flow"><h3>{esc(name)}</h3><p class="chain">{esc(chain)}</p><p>{esc(result)}</p><a href="{shot}">查看过程页面</a><span class="pass">已验证</span></article>'
body+='</section><section id="issues"><h2>存在问题、修改内容与复测结果</h2>'+table(['编号 / 问题','修改前','修改后业务流程','验证'],[(i+' '+title,before,after,test) for i,title,before,after,test in issues])+'''<h3>测试环境问题单独处理</h3><p>重新构建后发现隔离数据库尚未应用仓库已有的消息中心946迁移，导致工程费用挂接应付在消息留痕时回滚。已在隔离库应用该迁移并补齐测试应用用户对新增表的权限，然后重走劳务费用链路成功。此项为本地测试环境与当前代码版本不一致，不混记为采购/记账业务算法缺陷。</p><p>执行中发生的选择器失效、月份组件重建、会话自动续期及金额显示格式断言问题，修正自动化后均续跑成功。原始执行错误保留在 <a href="resolved-execution-errors.json">执行异常记录</a>，不会把这些错误冒充产品缺陷，也不会计为额外通过用例。</p></section>'''
body+='<section id="money"><h2>最终金额与跨岗位核对</h2><p>金额单位：元。新测试项目为“李先生乡墅·全流程验收”。全公司数包含此前验收数据，不能与单项目数直接相减解释为其他业务收益。</p>'+table(['核对项','金额','说明'],money)+'''<p>员工垫付2,400元、公司报销2,400元、平台退员工600元、员工归还公司600元、公司补付600元：公司实际承担设计费仍为2,400元，项目费用没有因报销再算一遍。项目“收支结余”是现金口径，不是利润。</p><p>只读核对还检查了：前期定金抵扣不新增实收、原退款及中间更正作废、最后一笔退款唯一有效、误录付款作废、兼容汇总接口一致，以及业务/工程/老板请求财务付款均返回403。</p><a href="cross-role-results.json">查看29项核对及接口结果</a></section>'''
body+='<section><h2>对小型乡墅公司的使用安排</h2>'+table(['岗位','日常操作','最少衔接'],[('业务','业主付款后申报实际到账；设计费确已个人垫付时提供依据','退回原因直接在合同页查看，补充原笔资料后重提'),('工程','提材料计划、确认到货、登记劳务等现场费用','材料由公司付款；工程采购费用及付款结果沿原项目查询'),('财务','核实到账、记录采购金额、付款、报销、零星费用、退款、月末核对','优先从待办或原账本办理；已有原单直接支付，无原单再补记'),('老板','看项目收支、费用、待收待付及员工往来','与财务同源查询；小程序项目账本不提供财务录入按钮')])+'''<p>本轮没有增加新的多级审批、重复建单或强制填写无关字段。财务登记前仍需核对实际银行/微信/现金凭据；后续若扩展发票、报税、工资核算，应独立规划，避免把日常收支页变成会计总账系统。</p></section>'''
body+='<section id="screens"><h2>最终实际页面</h2><p>点击图片可查看原尺寸。截图来自本轮运行的小程序原生模拟器。</p><div class="gallery">'+''.join(f'<figure><a href="{f}"><img src="{f}" loading="lazy" alt="{esc(c)}"></a><figcaption>{esc(c)}</figcaption></figure>' for f,c in screens)+'</div></section>'
body+='<section id="cases"><h2>逐项测试记录</h2>'+table(['序号','检查项','结果'],[(str(i),c['name'],'通过') for i,c in enumerate(passed,1)])+'<h3>代码与接口回归</h3>'+table(['测试集','通过数','失败 / 错误'],[(b['name'].split('.')[-1],b['tests'],b['failures']+' / '+b['errors']) for b in backend])+'''<p>前端17项测试单元通过（含回归脚本），935个财务WXML绑定表达式解析通过。旧回归脚本中依赖重复入口数量、旧回调文本的断言已更新到当前组件结构，同时保留收退款方向、金额上限、幂等、更正关联及返回行为验证。</p><p><a href="frontend-tests.txt">前端执行结果</a> · <a href="backend-test-results.json">后端测试结果</a> · <a href="native-final.json">最终原生页面结果</a> · <a href="cases.json">过程测试明细</a></p></section>'''
body+='<section id="limits"><h2>验收边界与未验证项</h2>'+table(['范围','本轮情况','后续验收'],[('物理手机、键盘与安全区','未连接真实iOS/Android设备；不能以模拟器替代实机结论','真机检查软键盘遮挡、不同屏宽、微信返回与文件选择'),('附件选取与预览','模拟系统文件选择和临时保存结果；文件字节、上传票据、上传完成及记录关联真实执行','真机相册/相机/聊天文件与系统预览仍需验收'),('设计业务前置','使用已审设计委托资料作为前置；垫付、报销、退款、归还均实际操作','设计生产、审批、派单及总部实际收款不是本轮端到端范围'),('工程全生命周期','覆盖材料采购资金、劳务、合同期次、结算查询及相关门槛回归','现场质检、补发、所有施工节点、交付归档未在本轮逐页重跑'),('银行与外部资金','登记、对账和留痕已测；未连接银行，也未发生真实转账','真实银行导入/自动匹配、支付渠道另行集成验收'),('其他通用页面','财务“我的”和工作台导航可达；非财务内容未逐一办理','学习、商城等通用模块不计入本报告通过范围'),('全量软件故障场景','测试了必填、日期、超付、超退、权限、重复入账与更正；未在所有页面注入断网/崩溃','不将本报告宣称为全部终端、全部异常路径100%通过')])+'''<p>以上边界不计为已通过事项。当前结论是：本报告列明的财务业务链路和跨岗位数据核对通过，可据此继续开展物理手机及外部渠道验收。</p></section></main><footer>本报告与截图均为隔离测试环境产物。无真实客户付款，无生产数据改写，无生产部署。</footer>'''
css='''*{box-sizing:border-box}body{margin:0;background:#f6f4f0;color:#302d29;font:15px/1.75 -apple-system,BlinkMacSystemFont,"Microsoft YaHei",sans-serif}header{padding:44px max(24px,calc((100vw - 1120px)/2));background:#fff;border-bottom:1px solid #e5dfd6}h1{font-size:32px;margin:10px 0}h2{font-size:24px;margin:0 0 20px}h3{font-size:18px;margin:0 0 12px}.eyebrow{font-size:13px;color:#89532c}.summary{display:flex;gap:14px;flex-wrap:wrap;margin-top:25px}.summary b{background:#f6eee4;border-radius:9px;padding:12px 18px;color:#7f4d29}nav{position:sticky;top:0;z-index:2;background:#fffefcee;border-bottom:1px solid #e3ddd4;display:flex;justify-content:center;gap:22px;flex-wrap:wrap;padding:13px}a{color:#8c542b;text-decoration:none}a:hover{text-decoration:underline}button{border:1px solid #ccbba8;background:white;border-radius:6px;color:#80502d;padding:4px 12px;cursor:pointer}main{max-width:1168px;margin:auto;padding:24px}section{background:white;border:1px solid #e9e3db;border-radius:14px;padding:30px;margin-bottom:24px;scroll-margin-top:90px}p{margin:12px 0}.note{background:#f7f2ea;border-left:4px solid #af825a;padding:18px;margin-top:20px}.flow{padding:22px 0;border-bottom:1px solid #eee7dd}.chain{background:#f7f4ef;border-radius:8px;padding:16px;color:#6e4d34}.pass{display:inline-block;margin-left:18px;color:#39704a;font-size:13px}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:14px}th,td{border-bottom:1px solid #e8e3db;padding:13px 15px;vertical-align:top;text-align:left}th{background:#f6f1e9;white-space:nowrap}tr:last-child td{border-bottom:0}.gallery{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px}figure{margin:0;background:#f4f2ef;padding:12px;border-radius:12px}figure img{width:100%;height:auto;display:block;border-radius:10px}figcaption{font-size:13px;padding-top:12px;color:#6e6257}footer{text-align:center;color:#817568;padding:25px}@media(max-width:800px){header{padding:24px}main{padding:12px}section{padding:18px}.gallery{grid-template-columns:repeat(2,minmax(0,1fr))}nav{position:static;gap:12px}h1{font-size:26px}th,td{min-width:100px}}@media print{body{background:white;font-size:11px}nav{display:none}header{padding:15px}main{padding:0}section{break-inside:auto;padding:15px;border:0;border-bottom:1px solid #ddd}h2,h3{break-after:avoid}.flow,tr,figure{break-inside:avoid}.gallery{grid-template-columns:repeat(3,1fr)}a{color:inherit}footer{font-size:10px}}'''
if (P/'mobile-section.html').exists():
 body=body.replace('<section id="flows">',(P/'mobile-section.html').read_text(encoding='utf-8')+'<section id="flows">')
 body=body.replace('<a href="#flows">业务链路</a>','<a href="#mobile">本轮补测</a><a href="#flows">业务链路</a>')
 body=body.replace('17 项前端回归','29 项前端回归').replace('前端17项测试单元','前端29项测试单元')
 body=body.replace('本轮发现并修复7类业务 / 界面问题。','前轮修复7类业务 / 界面问题，本轮页面与接口补测另修复4类问题。')
 body=body.replace('当前结论是：本报告列明的财务业务链路和跨岗位数据核对通过，可据此继续开展物理手机及外部渠道验收。','当前结论：已列明的财务链路、页面补测和跨岗位核对通过；按最新确认采用开发者工具验收。小屏320×568、中屏390×844、大屏430×932均已有原生页面证据，具体范围见补测章节。')
if (P/'screen-section.html').exists():
 body=body.replace('<section id="flows">',(P/'screen-section.html').read_text(encoding='utf-8')+'<section id="flows">')
 body=body.replace('<a href="#mobile">本轮补测</a>','<a href="#screen-adaptation">小屏 / 大屏</a><a href="#mobile">凭证与键盘</a>')
 body=body.replace('前轮修复7类业务 / 界面问题，本轮页面与接口补测另修复4类问题。','前轮修复7类业务 / 界面问题，页面与接口补测另修复4类问题，最新屏幕适配补测再修复1项顶部遮挡问题。')
 body=body.replace('真机检查软键盘遮挡、不同屏宽、微信返回与文件选择','按用户要求已在开发者工具完成320/390/430屏宽检查；实体输入法与系统文件选择未作真机结论')
(P/'report.html').write_text('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>加盟商财务全流程测试报告</title><style>'+css+'</style></head><body>'+body+'</body></html>',encoding='utf-8')
with zipfile.ZipFile(P/'财务测试报告与截图.zip','w',zipfile.ZIP_DEFLATED) as z:
 for f in ['report.html','cases.json','cross-role-results.json','backend-test-results.json','frontend-tests.txt','native-final.json','resolved-execution-errors.json','tested-version.json','wxml-check.txt']+[f for f,_ in screens]+[f[3] for f in flows]:
  if f not in z.namelist() and (P/f).exists():z.write(P/f,f)
with zipfile.ZipFile(P/'财务测试报告与截图.zip','a',zipfile.ZIP_DEFLATED) as z:
 for f in (P/'mobile').glob('*'):
  if f.is_file():z.write(f,'mobile/'+f.name)
 for f in (P/'screens').glob('*'):
  if f.is_file():z.write(f,'screens/'+f.name)
print(json.dumps({'report':str((P/'report.html').resolve()),'pageFlowChecks':len(passed),'backendTests':sum(int(b['tests']) for b in backend),'issues':len(issues)},ensure_ascii=False))
