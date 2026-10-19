from pathlib import Path
import sys,io,json,base64,hashlib
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.tools/font-subset'))
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
source=Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
groups=[('account',[ROOT/'miniprogram/pages/account',ROOT/'miniprogram/utils/account.js'],ROOT/'miniprogram/pages/account/font-sources.js'),('admin',[ROOT/'miniprogram/pages/admin',ROOT/'miniprogram/utils/management-seed.js',ROOT/'miniprogram/utils/finance-source.js'],ROOT/'miniprogram/pages/admin/font-sources.js'),('dashboard',[ROOT/'miniprogram/pages/profile',ROOT/'miniprogram/pages/management',ROOT/'miniprogram/pages/business',ROOT/'miniprogram/pages/engineering',ROOT/'miniprogram/utils/workbench.js',ROOT/'miniprogram/utils/management-guest.js'],ROOT/'miniprogram/utils/dashboard-font-sources.js')]
for group,paths,destination in groups:
 if sys.argv[1:] and group not in sys.argv[1:]:continue
 files=[]
 for path in paths:files.extend([path] if path.is_file() else [p for p in path.glob('*') if p.suffix in ['.js','.wxml'] and p.name!='font-sources.js'])
 text=''.join(p.read_text(encoding='utf-8') for p in files)+'¥·›×—'+''.join(chr(c) for c in range(32,127))
 if group=='dashboard':text+='工程师业务员财务负责人业务负责人总部设计负责人总部设计师老板门店负责人财务专员项目经理总部设计服务工程一期预收款独立设计费部分已付待核算结果待核实需补充已付清张业务测试王经理李经理刘业务陈业务王财务'
 font=TTFont(source);options=subset.Options();options.name_IDs=[0,1,2,3,4,5,6,13,14];subsetter=subset.Subsetter(options=options);subsetter.populate(text=text);subsetter.subset(font);buffer=io.BytesIO();font.save(buffer);faces=[]
 audit=ROOT/'qa/typography-20261018/fonts';audit.mkdir(parents=True,exist_ok=True)
 for weight in [400,500,700]:
  face=instantiateVariableFont(TTFont(io.BytesIO(buffer.getvalue())),{'wght':weight},inplace=True)
  if group=='dashboard' and weight!=400:
   content=('工期调整申请绩效申诉张先生在建项目陈先生林女士工程一期预收款总部设计服务付款结果经营数据项目收支团队人员分工财务公司目标规则考核历史学习短视频获客实战消息我的绩效查看展陈设计众墅甄选订单帮助与反馈账号隐私' if weight==500 else '待配置管理工作台我的财务老板经营摘要重点待办重点项目常用功能今日待办待我处理资金摘要学习培训加盟商服务张业务王经理李经理赵老板张敏刘芳陈业务刘业务王财务欢迎来到众墅之家万')
   content+='访客预览经营管理财务管理团队绩效人员与组织经营数据项目收支财务收支财务待办收付款计划项目费用资料我的目标考核方案周期复核历史考核绩效申诉人员与分工责任转交离岗交接公司管理有效已收有效已付收支结余陈先生工程一期预收款总部设计服务林女士设计服务泉州乡墅张先生在建项目登录工作台登录后使用继续浏览'
   content+=('客户自主设计了解流程客户与预约我的作品爆款二创数字人口播视频户型检索平面方案立面方案报价测算新建项目我的项目施工计划材料计划节点款项去确认开工准备交付验收示例两层乡墅陈先生林女士张先生王先生设计任务列表登录后查看本人工作台' if weight==500 else '业务工作台工程工作台设计工作台客户跟进视频创作方案设计常用功能重点待办本人设计任务团队设计办理业务与交付待办审批待办项目总览负责项目施工中待处理')
   content+='财务专员财务负责人门店负责人业务负责人项目经理工程师总部设计负责人总部设计师老板经营收支摘要授权业务收支返回业务工作台返回工程工作台返回管理工作台返回财务工作台返回我的选择有效工作身份登录后继续'
   content+='关闭确定知道了众墅甄选'
   narrow=subset.Subsetter(options=options);narrow.populate(text=content+'¥·›×—'+''.join(chr(c) for c in range(32,127)));narrow.subset(face)
  face.flavor='woff2';binary=io.BytesIO();face.save(binary);data=binary.getvalue()
  family='ZS'+group.title();(audit/(family+'-'+str(weight)+'.woff2')).write_bytes(data)
  faces.append({'family':family,'weight':weight,'source':'url("data:font/woff2;base64,'+base64.b64encode(data).decode('ascii')+'")'})
 destination.write_text('module.exports='+json.dumps(faces)+';\n',encoding='utf-8');print(group,len(set(text)),destination.stat().st_size)
 (audit/(group+'-manifest.json')).write_text(json.dumps({'family':'ZS'+group.title(),'weights':[400,500,700],'characters':''.join(sorted(set(text))),'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source':'Noto Sans SC','license':'SIL OFL 1.1','upstream':'https://github.com/notofonts/noto-cjk','moduleBytes':destination.stat().st_size},ensure_ascii=False,indent=2),encoding='utf-8')
