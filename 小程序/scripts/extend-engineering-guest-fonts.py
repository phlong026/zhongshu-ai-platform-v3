"""Keep every existing dashboard glyph and add the selected engineering UI text."""
from pathlib import Path
import sys, io, json, base64, re, hashlib
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.tools/font-subset'))
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

target = root / 'miniprogram/utils/dashboard-font-sources.js'
faces = json.loads(target.read_text(encoding='utf-8').split('=', 1)[1].strip().rstrip(';'))
needed = {
  400: '搜索项目客户或地址在建待交付待处理个项泉州晋江主体施工基础施工计划进度基础完成装修下一节点二层楼板验收月日前今日李经理拍照日志到货登记问题跟进图纸文档示例项目与进度登录后查看本人项目没有找到匹配项目试试客户姓名项目名称或所在城市m²·|%管理事项没有找到匹配内容试试客户姓名项目或事项关键词',
  500: '新建全部项目查看项目全部待办陈先生钢筋进场验收林女士交付资料待核对去验收查看现场记录材料收货验收整改项目资料重置搜索',
  700: '工程工作台在建项目今日待办快捷操作陈先生泉州乡墅王先生晋江乡墅主体施工'
}
source = Path('C:/Windows/Fonts/NotoSansSC-VF.ttf')
audit = []
for face in faces:
    raw = base64.b64decode(re.search(r'base64,([^"\)]+)', face['source']).group(1))
    old = TTFont(io.BytesIO(raw))
    previous = set(old.getBestCmap())
    required = set(map(ord, needed[face['weight']]))
    missing = required - previous
    if missing:
        font = TTFont(source)
        options = subset.Options()
        options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
        sub = subset.Subsetter(options=options)
        sub.populate(unicodes=previous | required)
        sub.subset(font)
        font = instantiateVariableFont(font, {'wght':face['weight']}, inplace=True)
        font.flavor = 'woff2'
        output = io.BytesIO()
        font.save(output)
        data = output.getvalue()
        face['source'] = 'url("data:font/woff2;base64,' + base64.b64encode(data).decode('ascii') + '")'
        current = set(font.getBestCmap())
        assert previous <= current and required <= current
    else:
        current = previous
    audit.append({'weight':face['weight'],'addedGlyphs':''.join(map(chr, sorted(missing))), 'retainedEveryExistingGlyph':previous <= current,'missingRequiredGlyphs':''.join(map(chr,sorted(required-current)))})
target.write_text('module.exports=' + json.dumps(faces) + ';\n', encoding='utf-8')
report = {'family':'ZSDashboard','font':'Noto Sans SC','sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'faces':audit,'moduleBytes':target.stat().st_size}
(root / 'qa/engineering-guest-home-20261019/font-coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=True))
