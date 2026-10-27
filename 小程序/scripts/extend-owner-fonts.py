from pathlib import Path
import sys,json,re,io,base64
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'.tools/font-subset'))
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont
target=root/'miniprogram/pages/admin/font-sources.js'
faces=json.loads(target.read_text(encoding='utf8').split('=',1)[1].strip().rstrip(';'))
paths=[root/'miniprogram/pages/admin/owner-model.js',root/'miniprogram/pages/admin/owner-page.js',root/'miniprogram/pages/admin/owner-home.js',root/'miniprogram/pages/admin/owner-business.js']
for directory in ['home','finance','performance','profile','detail','owner-header','owner-nav']:
 paths+=list((root/'miniprogram/pages/admin'/directory).glob('*.wxml'))
text=''.join(p.read_text(encoding='utf8') for p in paths)+'0123456789.,%/¥万项位年月日周一二三四五六截至未设置在岗成员权限审批交接记录湖北武汉黄陂蔡甸江夏张刘陈林王李赵老板经理业务财务负责人开工款封顶款'
needed=set(map(ord,text))
audit=[]
for face in faces:
 old=TTFont(io.BytesIO(base64.b64decode(re.search(r'base64,([^"\\)]+)',face['source']).group(1))))
 before=set(old.getBestCmap())
 font=TTFont('C:/Windows/Fonts/NotoSansSC-VF.ttf')
 opts=subset.Options();opts.name_IDs=[0,1,2,3,4,5,6,13,14]
 sub=subset.Subsetter(options=opts);sub.populate(unicodes=before|needed);sub.subset(font)
 font=instantiateVariableFont(font,{'wght':int(face['weight'])},inplace=True)
 font.flavor='woff2';buf=io.BytesIO();font.save(buf)
 face['source']='url("data:font/woff2;base64,'+base64.b64encode(buf.getvalue()).decode('ascii')+'")'
 after=set(font.getBestCmap())
 missing=sorted((needed-after)-{9,10,13})
 audit.append({'family':face['family'],'weight':face['weight'],'retainedEveryExistingGlyph':before<=after,'glyphs':len(after),'missing':''.join(map(chr,missing))})
target.write_text('module.exports='+json.dumps(faces)+';\n',encoding='utf8')
out=root/'qa/boss-workbench-20261020';out.mkdir(parents=True,exist_ok=True)
(out/'font-coverage.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf8')
assert all(r['retainedEveryExistingGlyph'] and not r['missing'] for r in audit),audit
print(json.dumps({'result':'passed','faces':len(audit),'weights':[r['weight'] for r in audit]}))

