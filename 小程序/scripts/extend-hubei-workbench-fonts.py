"""Retain every existing glyph while covering region names and new workbench copy."""
from pathlib import Path
import sys, json, io, re, base64
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'.tools/font-subset'))
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont
regions='湖北省武汉市麻城市红安县武穴市宜昌市襄阳市黄冈市蕲春县罗田县英山县黄梅县团风县浠水县'
dashboard={
 400:regions+'本组织项目按原单状态办理地址待补充日期待安排施工节点已完成待办理交付当前暂无需要本人处理的事项暂无符合条件的在建项目可搜索客户或城市也可进入全部项目查看本人授权范围节点与进度配材与到货收付款记录核对实际到账按已批原单办理办理已批准退款补齐退回资料查看本人办理事项查有效收款与付款按项目核对收支跟进近期款项安排独立核对到账核对原笔实付核对原单依据原笔责任转交搜索待办项目或办理事项搜索本人待办与办理事项试试客户款项或功能名称待核算待复核与退回补证中的款项不计入有效累计收支结余不代表项目利润',
 500:regions+'项目总览收款登记付款登记退款办理原单补证全部待办收支明细项目收支收付款计划收款核算付款复核退款及更正复核经办交接查看待办查看全部项目我的项目施工计划材料计划节点款项',
 700:regions+'完成进度在建项目今日待办经营摘要常用功能快捷操作基础主体屋面外墙'
}
source=root/'miniprogram'
targets=[source/'utils/dashboard-font-sources.js',source/'pages/account/font-sources.js',source/'pages/admin/font-sources.js',source/'pages/build/font-sources.js']
font_path=Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
audit=[]
for target in targets:
 faces=json.loads(target.read_text(encoding='utf-8').split('=',1)[1].strip().rstrip(';'))
 for face in faces:
  old=TTFont(io.BytesIO(base64.b64decode(re.search(r'base64,([^"\)]+)',face['source']).group(1))))
  before=set(old.getBestCmap())
  text=dashboard[int(face['weight'])] if 'dashboard' in target.name else regions+'收款登记付款登记退款办理原单补证收款核算付款复核退款及更正复核经办交接当前没有需要本人处理的事项原单收付款安排尚未付清约定待核对收款待付款事项当前没有待付款事项当前没有待核对收款的收款计划与待核对金额不计入实际已收已付'
  needed=set(map(ord,text));missing=needed-before
  if missing:
   font=TTFont(font_path);options=subset.Options();options.name_IDs=[0,1,2,3,4,5,6,13,14]
   sub=subset.Subsetter(options=options);sub.populate(unicodes=before|needed);sub.subset(font)
   font=instantiateVariableFont(font,{'wght':int(face['weight'])},inplace=True);font.flavor='woff2';buffer=io.BytesIO();font.save(buffer)
   face['source']='url("data:font/woff2;base64,'+base64.b64encode(buffer.getvalue()).decode('ascii')+'")';after=set(font.getBestCmap())
  else:after=before
  assert before<=after and needed<=after
  audit.append({'path':str(target.relative_to(root)),'family':face['family'],'weight':face['weight'],'added':''.join(map(chr,sorted(missing))),'retainedEveryExistingGlyph':before<=after,'missing':''.join(map(chr,sorted(needed-after)))})
 target.write_text('module.exports='+json.dumps(faces)+';\n',encoding='utf-8')
(root/'qa/workbench-hubei-20261019/font-coverage.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'faces':len(audit),'allPreserved':all(row['retainedEveryExistingGlyph'] and not row['missing'] for row in audit)}))
