from pathlib import Path
import json,html,shutil,datetime
P=Path(__file__).parent
R=json.loads((P/'result.json').read_text(encoding='utf-8'))
assert R['passed'] and R['pdf']['passed'] and all(c['passed'] for c in R['checks'])
e=lambda s:html.escape(str(s))
issues=[
 ('M01 凭证来源','财务原入口仅能选择聊天文件，纸质凭证或手机截图要绕路。','收款、付款、报销、退款及补充凭证统一提供拍照/相册和聊天文件；取消不清除已有凭证；选择失败显示可处理的提示。','代码检查、选择器回调测试、真实PNG上传与财务登记'),
 ('M02 异步回填','选文件后返回、换单或切换身份，旧选择结果可能回填到后开的表单。','校验会话、单据来源、表单代次和选择顺序；过期回调直接忽略，沿用已有登记接口。','取消、换原单、换岗位与连续选文件回归通过'),
 ('M03 PDF格式识别','上传仅按临时路径后缀推断格式，无扩展名PDF会被当作JPEG。','上传同时传递原文件名，以原文件名确定MIME；票据、字节上传、完成、原单关联及下载链路不变。','真实PDF临时文件不带扩展名；下载返回application/pdf且字节以%PDF-开头'),
 ('M04 键盘与底部导航','财务固定底部导航未监听键盘，输入框未统一设置键盘间距。','键盘弹起时隐藏底部导航，收起后恢复；39个输入框设置24px光标间距；返回账本主动收起键盘，页面隐藏/卸载释放监听。','真实页面回调注入280/300px键盘事件及逻辑回归；未模拟系统键盘外观')
]
screens=[('01-receipt-photo.png','收款表单：保留原账本和已填内容'),('02-keyboard-event.png','键盘事件后隐藏底部导航；图中不含手机系统键盘'),('03-reimbursement.png','报销：沿原垫付办理'),('04-refund.png','退款：关联原收款'),('05-book-final.png','新增验收账本：结余7元，费用11元'),('06-project-return.png','返回原项目，保留费用标签'),('07-home-final.png','当前财务首页'),('08-pdf-supplement.png','PDF凭证补充到原收款'),('09-pdf-linked.png','补充成功后回看原记录')]
def table(head,rows):return '<div class="table-wrap"><table><thead><tr>'+''.join('<th>'+e(h)+'</th>' for h in head)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+e(c)+'</td>' for c in row)+'</tr>' for row in rows)+'</tbody></table></div>'
def section(prefix):
 s='<section id="mobile"><h2>开发者工具页面与接口补测 · 2026-11-17</h2><p>按最新确认，在微信开发者工具中继续验收，不连接手机投屏。以下为本轮新增证据，前轮完整业务链路与结果仍保留在后文。</p>'
 s+='<div class="note"><strong>已完成：</strong>8项补测链路、29项前端回归、29项跨岗位金额/权限复核。真实上传5笔收付凭证，并完成无扩展名PDF的真实上传下载。后端代码本轮未改动，88项后端测试沿用前轮记录。</div>'
 s+='<h3>操作链路与实际结果</h3>'+table(['链路','实际操作','结果'],[
 ('收款','验收账本 → 登记收款20元 → 选择图片 → 保存 → 返回原账本','通过；收款及凭证真实入库'),
 ('付款','原账本 → 登记付款 → 补记服务费5元 → 聊天文件 → 保存 → 返回','通过；公司实付5元，费用5元'),
 ('垫付与报销','原账本 → 员工已垫付6元 → 自动生成报销事项 → 报销6元 → 返回列表','通过；费用只计6元，报销计公司实付，员工往来结清'),
 ('退款','收款明细 → 登记退款2元 → 关联凭证 → 保存 → 返回原账本','通过；沿原收款登记，不触发真实转账'),
 ('PDF补充','原收款 → 补充PDF → 保存 → 原记录查看 → 真实下载接口','通过；正确返回PDF格式及文件字节，不新增资金记录'),
 ('返回项目','原项目费用标签 → 收款/付款表单 → 注入键盘事件 → 返回项目账本','通过；项目、费用标签和原项目金额均保留')])
 s+='<h3>本轮发现及修复</h3>'+table(['问题','修改前','修改后','验证方式'],issues)
 s+='<h3>金额复核</h3><p>新增独立验收账本：收款20元－公司付款5元－报销6元－退款2元＝<strong>现金结余7元</strong>；项目费用口径为5＋6＝<strong>11元</strong>。员工垫付没有直接计入公司现金支出，报销没有重复增加费用。</p><p>原“李先生乡墅·全流程验收”项目仍为结余21,560元、费用4,450元，业务和工程净收26,010元，老板与财务一致。全公司测试数据结余由110,878元增加至110,885元，增量全部来自本轮独立验收账本。</p>'
 s+='<h3>屏幕、键盘与文件选择覆盖范围</h3>'+table(['项目','本轮证据','结论'],[
 ('390×844模拟器','系统返回platform=devtools，model=iPhone 12/13 (Pro)，基础库3.17.4；以下为原生运行截图','已完成页面检查'),
 ('键盘事件','注入280/300px高度，验证导航隐藏恢复和账本返回；输入值未丢失','事件处理通过；不作为手机输入法遮挡的实机结论'),
 ('320×568 / 430×932模拟器','后续适配补测已切换iPhone 5与iPhone 15 Pro Max，真实systemInfo、页面截图及元素位置均已记录','22项通过；修复账本滚动顶部遮挡，详见小屏与大屏适配补测报告'),
 ('系统相册/相机/聊天选择','由自动化提供真实测试文件和系统选择回调；未调用真实手机相机','选择后的页面、上传、关联、取消及错误处理已测'),
 ('金融与环境','独立本地测试库和测试账号，所有收付均为测试登记','未连接银行、未发生实际转账、未发布生产')])
 s+='<h3>本轮实际页面</h3><div class="gallery">'+''.join(f'<figure><a href="{prefix}{f}"><img src="{prefix}{f}" loading="lazy" alt="{e(c)}"></a><figcaption>{e(c)}</figcaption></figure>' for f,c in screens)+'</div>'
 s+='<h3>逐项执行记录</h3>'+table(['序号','检查项','结果'],[(i,c['name'],'通过') for i,c in enumerate(R['checks'],1)])
 s+=f'<p><a href="{prefix}result.json">接口与流程结果</a> · <a href="{prefix}frontend-tests.txt">29项前端回归记录</a> · <a href="{prefix}environment.json">运行环境记录</a></p></section>'
 return s
target=P.parent/'finance-fullflow-20261117';dest=target/'mobile';dest.mkdir(exist_ok=True)
for f in ['result.json','environment.json','frontend-tests.txt','wxml-check.txt']+[f for f,_ in screens]:
 assert (P/f).exists(),f
 shutil.copy2(P/f,dest/f)
(target/'mobile-section.html').write_text(section('mobile/'),encoding='utf-8')
style='body{font:15px/1.8 "Microsoft YaHei",sans-serif;background:#f6f4f0;color:#302d29;margin:0}main{max-width:1120px;margin:auto;padding:30px}section{background:white;padding:30px;border-radius:14px}h2{font-size:26px}h3{margin:32px 0 16px}.note{padding:18px;background:#f7f2ea;border-left:4px solid #af825a}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:14px;border-bottom:1px solid #e8e3db;text-align:left;vertical-align:top}th{background:#f6f1e9}.gallery{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}figure{margin:0}img{width:100%;border-radius:12px}figcaption{font-size:13px}a{color:#89532c}@media(max-width:700px){main,section{padding:16px}.gallery{grid-template-columns:1fr 1fr}}'
(P/'report.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>财务开发者工具补测报告</title><style>'+style+'</style><main>'+section('')+'</main></html>',encoding='utf-8')
print(json.dumps({'supplementChecks':len(R['checks']),'fixes':len(issues),'passed':True}))
