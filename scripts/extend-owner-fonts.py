from pathlib import Path
import sys,json,re,io,base64,argparse
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'.tools/font-subset'))
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont
parser=argparse.ArgumentParser(description='核对或补足老板管理工作台的现有字体子集')
parser.add_argument('--font',type=Path,help='NotoSansSC 可变字体文件，补字时必填')
parser.add_argument('--check-only',action='store_true',help='只检查当前字库，不修改源码')
args=parser.parse_args()
if not args.check_only and (not args.font or not args.font.is_file()):
 parser.error('请用 --font 指定现有 NotoSansSC 可变字体文件')
target=root/'miniprogram/pages/admin/font-sources.js'
faces=json.loads(target.read_text(encoding='utf8').split('=',1)[1].strip().rstrip(';'))
paths=[root/'miniprogram/pages/admin'/name for name in ['owner-model.js','owner-page.js','owner-home.js','owner-business.js','owner-performance.js','owner-settings.js','management-settings.wxml','collection-commission.wxml']]
for directory in ['home','finance','performance','profile','detail','owner-header','owner-nav','commission','simulation']:
 paths+=list((root/'miniprogram/pages/admin'/directory).glob('*.wxml'))
paths += [root/'miniprogram/utils'/name for name in ['operating-report.js','collection-commission.js','management-handover.js']]
text=''.join(p.read_text(encoding='utf8') for p in paths if p.exists())+'0123456789.,%/¥万项位年月日周一二三四五六截至未设置在岗成员权限审批交接记录湖北武汉黄陂蔡甸江夏张刘陈林王李赵老板经理业务财务负责人开工款封顶款'
needed={ord(c) for c in text if '\u4e00'<=c<='\u9fff' or c in '0123456789.,%/¥万'}
audit=[]
for face in faces:
 old=TTFont(io.BytesIO(base64.b64decode(re.search(r'base64,([^"\\)]+)',face['source']).group(1))))
 before=set(old.getBestCmap())
 font=old
 if not args.check_only:
  font=TTFont(args.font)
  if 'fvar' not in font: raise ValueError('需要支持各字重的 NotoSansSC 可变字体')
  opts=subset.Options();opts.name_IDs=[0,1,2,3,4,5,6,13,14]
  sub=subset.Subsetter(options=opts);sub.populate(unicodes=before|needed);sub.subset(font)
  font=instantiateVariableFont(font,{'wght':int(face['weight'])},inplace=True)
  font.flavor='woff2';buf=io.BytesIO();font.save(buf)
  face['source']='url("data:font/woff2;base64,'+base64.b64encode(buf.getvalue()).decode('ascii')+'")'
 after=set(font.getBestCmap())
 missing=sorted((needed-after)-{9,10,13})
 audit.append({'family':face['family'],'weight':face['weight'],'retainedEveryExistingGlyph':before<=after,'glyphs':len(after),'missing':''.join(map(chr,missing))})
out=root/'qa/owner-workbench-port-20261023';out.mkdir(parents=True,exist_ok=True)
(out/'font-coverage.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf8')
assert all(r['retainedEveryExistingGlyph'] and not r['missing'] for r in audit),audit
if not args.check_only: target.write_text('module.exports='+json.dumps(faces)+';\n',encoding='utf8')
print(json.dumps({'result':'passed','faces':len(audit),'weights':[r['weight'] for r in audit]}))

