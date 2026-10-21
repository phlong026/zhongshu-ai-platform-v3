from pathlib import Path
from PIL import Image
import json
root=Path(__file__).resolve().parents[1];out=root/'miniprogram/pages/build/assets'; manifest=[]

# The manifest includes the reviewed icon crops added after the first extraction.
# Reuse it so rerunning extraction cannot restore obsolete crop coordinates.
existing = out/'manifest.json'
if existing.exists():
 for item in json.loads(existing.read_text(encoding='utf-8')):
  im=Image.open(root/'效果图'/item['source']).convert('RGB').crop(item['crop'])
  im.save(out/item['asset'],quality=93,optimize=True)
 print('Rebuilt', len(json.loads(existing.read_text(encoding='utf-8'))), 'assets')
 raise SystemExit(0)
def crop(name,src,box):
 im=Image.open(root/'效果图'/src).convert('RGB').crop(box); im.save(out/(name+'.jpg'),quality=93,optimize=True);manifest.append(dict(asset=name+'.jpg',source=src,crop=box))
crop('house','01_我的项目_E01.jpg',(50,486,321,849));crop('site','01_我的项目_E01.jpg',(50,918,321,1282));crop('house-lin','01_我的项目_E01.jpg',(50,1345,320,1617))
crop('foundation','06_E11_当前施工节点_v2.png',(58,1275,205,1362))
crop('cement','E19_差异补发.jpg',(54,202,320,450))
crop('rectify-before','E20_整改复验.jpg',(60,1000,405,1230));crop('rectify-after','E20_整改复验.jpg',(425,1000,789,1230))
for name,src,box in [('house-icon','02_待开工项目总览_E03.jpg',(347,299,387,339)),('manager','02_待开工项目总览_E03.jpg',(347,354,387,394)),('calendar','02_待开工项目总览_E03.jpg',(347,410,387,450)),('document','02_待开工项目总览_E03.jpg',(55,1110,151,1207)),('brief','02_待开工项目总览_E03.jpg',(55,1232,151,1329)),('drawing','02_待开工项目总览_E03.jpg',(55,1358,151,1455)),('clock','02_待开工项目总览_E03.jpg',(58,1559,105,1607)),('box','02_待开工项目总览_E03.jpg',(57,1656,145,1740)),('cart','02_待开工项目总览_E03.jpg',(458,1656,546,1740)),('pdf','02_E05_项目资料_v2.png',(70,613,146,694)),('bell','02_E05_项目资料_v2.png',(74,1306,144,1378)),('file','03_E06_施工计划_v2.png',(61,447,159,545)),('cement-icon','05_E09_供应安排_v2.png',(60,375,169,480)),('shovel','06_E11_当前施工节点_v2.png',(57,443,166,553)),('inspect','06_E11_当前施工节点_v2.png',(55,675,154,777))]:crop(name,src,box)
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
