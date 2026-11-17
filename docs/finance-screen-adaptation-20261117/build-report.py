from pathlib import Path
import json,html,shutil,datetime,hashlib
P=Path(__file__).parent
reports={k:json.loads((P/(k+'-result.json')).read_text(encoding='utf-8')) for k in ['small','large']}
assert all(r['passed'] and len(r['checks'])==11 and all(c['passed'] for c in r['checks']) for r in reports.values())
assert reports['small']['system']['screenWidth']==320 and reports['large']['system']['screenWidth']==430
e=lambda v:html.escape(str(v))
def table(head,rows):
 return '<div class="table-wrap"><table><thead><tr>'+''.join('<th>'+e(x)+'</th>' for x in head)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+e(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'
def section(prefix=''):
 s='<section id="screen-adaptation"><h2>小屏与大屏适配补测 · 2026-11-17</h2><p>本轮按要求直接使用微信开发者工具模拟器，分别切换 iPhone 5（320×568）和 iPhone 15 Pro Max（430×932），每个尺寸完成11项检查，共22项通过。加上前轮390×844，财务页面已覆盖小、中、大三个屏幕尺寸。</p>'
 s+='<div class="note"><strong>修复1项实际显示问题：</strong>项目账本滚动后，原品牌导航离开视口，流水文字会进入微信胶囊区域。现复用品牌导航组件增加可选固定模式，只在财务页启用，并保留等高占位；财务项目账本、列表及老板账本滚动时，顶部导航均保持可见。首页大框架、登记业务及接口没有改变。</div>'
 s+='<h3>机型与运行证据</h3>'+table(['机型','实际屏幕尺寸','基础库','验证方式'],[(r['system']['model'],str(r['system']['screenWidth'])+'×'+str(r['system']['screenHeight']),r['system']['SDKVersion'],'platform=devtools；原生页面截图与渲染元素位置') for r in reports.values()])
 s+='<p>桌面机型下拉控件捕获超时，改为修改该隔离项目在开发者工具中保存的机型配置并重新编译。随后读取模拟器真实 systemInfo 核验尺寸；未模拟 systemInfo，也未用缩放图片代替机型切换。</p>'
 s+='<h3>测试链路与修复后的业务流程</h3>'+table(['链路','本轮操作','小屏 / 大屏结果'],[
 ('财务首页','问候与月份 → 四项指标 → 今日待办 → 快捷办理 → 正在办理','金额128,840.00、17,793.00、110,885.00完整；可滚动到达所有模块'),
 ('项目收款','原项目费用明细 → 登记收款 → 输入1234567.89 → 滚动至确认按钮 → 返回项目账本','输入值完整；按钮可见；保留原项目与费用标签；取消未新增入账'),
 ('采购付款','首页材料采购待办 → 自动带入原采购及剩余350.00 → 滚动至登记按钮 → 返回首页','合计500、已付150、待付350一致；不需重复选原单'),
 ('补记付款','项目账本 → 登记付款 → 没有原单，补记其他支出 → 返回项目账本','提交按钮无遮挡；保持原项目和费用标签'),
 ('设计费报销','报销列表 → 原垫付事项 → 待报销1000.00 → 底部确认按钮 → 返回列表','垫付2400、已报1400、剩余1000正确；返回原列表'),
 ('退款','原收款流水 → 登记退款 → 查看剩余可退18.00 → 底部按钮 → 返回原账本','沿原收款办理；不需重新选择账本；取消后结余不变'),
 ('凭据入口与键盘事件','表单滚动到底部 → 凭据入口与提交按钮可见；注入280px键盘事件 → 返回原项目','导航隐藏恢复，项目与标签保留；文件真实上传沿用前轮已验证结果'),
 ('账本滚动与老板衔接','项目账本 → 滚动至最后一条流水 → 固定顶部导航 → 切老板账本核对','顶部流水不再穿过胶囊；最后一条流水在底栏上方；老板与财务结余均21560')])
 s+='<h3>发现的问题与修改前后</h3>'+table(['问题','修改前','修改后','复测'],[('S01 账本滚动顶部遮挡','品牌导航随内容滚走，金额进入胶囊按钮下方','增加可选fixed属性及等高占位，只在财务常规标题启用；报销/收付款操作标题沿用原固定方式','两尺寸滚动后标题top=0；最后一条流水可完整显示；老板账本共享修复')])
 s+='<div class="gallery">'
 for f,c in [('small-book-bottom-before.png','修复前：流水金额进入胶囊区域'),('small-book-bottom.png','修复后：小屏账本顶部保留导航'),('large-book-bottom.png','修复后：大屏账本顶部保留导航')]:s+=f'<figure><a href="{prefix}{f}"><img src="{prefix}{f}" loading="lazy" alt="{e(c)}"></a><figcaption>{e(c)}</figcaption></figure>'
 s+='</div><h3>实际页面对比</h3><div class="gallery">'
 for page,title in [('home','财务首页'),('book','项目账本'),('receipt-bottom','收款底部操作'),('purchase-bottom','采购付款底部操作'),('reimbursement-bottom','报销底部操作'),('refund-bottom','退款底部操作'),('project-return','返回原项目费用标签'),('boss-book','老板只读账本')]:
  for k,name in [('small','小屏320×568'),('large','大屏430×932')]:
   f=k+'-'+page+'.png';caption=e(name+' · '+title);s+=f'<figure><a href="{prefix}{f}"><img src="{prefix}{f}" loading="lazy" alt="{e(title+name)}"></a><figcaption>{caption}</figcaption></figure>'
 s+='</div><h3>逐项验收记录</h3>'+table(['检查项','小屏','大屏'],[(c['name'],'通过','通过') for c in reports['small']['checks']])
 s+='<h3>数据与回归结果</h3><p>本轮仅打开、输入并取消表单，没有新增收付款。全公司结余110,885元，原项目结余21,560元、费用4,450元，独立验收账本结余7元，均未改变。29项跨岗位金额/权限复核、29项财务前端回归、33项导航回归、共享头部/确认弹层检查及935个WXML表达式检查通过。后端本轮未改动，88项后端测试沿用前轮记录。</p>'
 s+='<p>另外修复了测试运行器的Windows路径兼容问题：原先用斜杠匹配路径，无法展开action-panel；现统一路径分隔符，共享组件检查已复跑通过。报销列表及原生文本节点选择器也已更正。这些属于测试执行问题，未计为产品缺陷。</p>'
 s+='<h3>边界</h3><p>此处是开发者工具的真实机型渲染，不是实体手机。键盘检查验证页面高度回调、导航隐藏和返回恢复；不宣称验证了手机输入法的实际弹出外观。相册/相机选择回调及真实上传链路的范围见前轮报告。本轮不重复写入财务登记，也不重复声称进行了银行转账。</p>'
 s+=f'<p><a href="{prefix}small-result.json">小屏原始结果</a> · <a href="{prefix}large-result.json">大屏原始结果</a> · <a href="{prefix}frontend-tests.txt">财务回归</a> · <a href="{prefix}navigation-tests.txt">导航回归</a> · <a href="{prefix}shared-shell-tests.txt">共享组件</a> · <a href="{prefix}cross-role-results.json">跨岗位复核</a> · <a href="{prefix}tested-version.json">本轮文件指纹</a></p></section>'
 return s
target=P.parent/'finance-fullflow-20261117';dest=target/'screens';dest.mkdir(exist_ok=True)
shutil.copy2(target/'cross-role-results.json',P/'cross-role-results.json')
root=P.parent.parent;preview=Path('C:/Users/Administrator/.codex/artifacts/finance-joint-20261117/run1/project')
paths=['miniprogram/components/brand-header/index.'+x for x in ['js','wxml','wxss']]+['miniprogram/pages/admin/finance-desk/index.wxml']
files=[]
for f in paths:
 a=hashlib.sha256((root/f).read_bytes()).hexdigest();b=hashlib.sha256((preview/f).read_bytes()).hexdigest();assert a==b
 files.append({'file':f,'sha256':a,'previewMatches':True})
(P/'tested-version.json').write_text(json.dumps({'capturedAt':datetime.datetime.now().astimezone().isoformat(timespec='seconds'),'files':files,'scope':'本轮4个应用文件的实际预览一致性；历史其他文件见主报告指纹'},ensure_ascii=False,indent=2),encoding='utf-8')
for f in list(P.glob('*.png'))+[P/f for f in ['small-result.json','large-result.json','frontend-tests.txt','navigation-tests.txt','shared-shell-tests.txt','wxml-check.txt','cross-role-results.json','tested-version.json']]:
 if 'error' not in f.name:shutil.copy2(f,dest/f.name)
(target/'screen-section.html').write_text(section('screens/'),encoding='utf-8')
style='body{margin:0;background:#f6f4f0;color:#302d29;font:15px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1120px;margin:auto;padding:30px}section{background:white;padding:30px;border-radius:14px}h2{font-size:28px}h3{margin:30px 0 16px}.note{padding:18px;background:#f7f2ea;border-left:4px solid #af825a}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:14px;border-bottom:1px solid #e8e3db;text-align:left;vertical-align:top}th{background:#f6f1e9}.gallery{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}figure{margin:0;padding:12px;background:#f6f4f0;border-radius:10px}img{display:block;width:100%;height:auto;border-radius:10px}figcaption{font-size:13px;padding-top:12px}a{color:#89532c}@media(max-width:700px){main,section{padding:12px}}'
(P/'report.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>财务小屏与大屏适配验收</title><style>'+style+'</style><main>'+section()+'</main></html>',encoding='utf-8')
print(json.dumps({'screenChecks':sum(len(r['checks']) for r in reports.values()),'fixes':1,'passed':True}))
