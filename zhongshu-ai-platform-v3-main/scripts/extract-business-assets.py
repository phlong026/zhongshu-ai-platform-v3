"""Extract individual supplied business illustrations, never whole UI screens."""
from pathlib import Path
from PIL import Image
import json
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'miniprogram/assets'
BIZOUT=ROOT/'miniprogram/pages/workflow/assets'
BIZOUT.mkdir(parents=True,exist_ok=True)
manifest=json.loads((OUT/'manifest.json').read_text(encoding='utf-8'))
manifest=[x for x in manifest if not Path(x['asset']).name.startswith('biz-')]
def crop(name, source, box, alpha=False):
    im=Image.open(ROOT/'效果图'/source).convert('RGB').crop(box)
    if alpha:
        im=im.convert('RGBA')
        im.putdata([(r,g,b, max(0,min(255,int((250-min(r,g,b))*255/60)))) for r,g,b,a in im.getdata()])
    filename='biz-'+name+('.png' if alpha else '.jpg')
    if alpha: im.save(BIZOUT/filename,optimize=True)
    else: im.save(BIZOUT/filename,quality=91,optimize=True)
    manifest.append({'asset':'../pages/workflow/assets/'+filename,'source':source,'crop':box,'transparent':alpha})
for n,b in enumerate([(39,812,420,1183),(435,812,817,1183),(39,1314,420,1667),(435,1314,817,1667)],1):
    crop('case-'+str(n),'04A_灵感库_案例推荐.jpg',b)
crop('case-hero','04B_案例详情_立面效果.jpg',(37,305,817,820))
crop('case-plan-hero','04D_案例详情_平面参考.jpg',(38,290,815,644))
crop('checkbox-checked','21_补图后的项目经理接收.jpg',(82,1358,122,1399))
crop('checkbox-empty','15A_总部资料受理.jpg',(94,1470,130,1506))
crop('reference-plan','04D_案例详情_平面参考.jpg',(76,981,743,1600))
for n,b in enumerate([(86,806,405,1060),(450,806,768,1060),(86,1221,405,1479),(450,1221,768,1479)],1):
    crop('plan-'+str(n),'05B_AI 平面设计_选用保存.jpg',b)
crop('plan-result','06_已选用平面方案结果.jpg',(63,528,790,1170))
crop('formal-plan','18A_总部正式下发.jpg',(65,559,790,870))
crop('facade-reference','05C_立面设计配置.jpg',(65,482,343,695))
crop('facade-1','05D_立面候选选用.jpg',(74,795,779,1148))
crop('facade-2','05D_立面候选选用.jpg',(74,1252,779,1569))
crop('facade-result','06B_立面方案结果.jpg',(62,517,794,1013))
crop('timeline-dot','15C_业务员正式设计进度.jpg',(82,529,115,563))
for name,box in [('quote-house',(82,915,153,983)),('quote-window',(82,1007,153,1074)),('quote-other',(82,1099,153,1167))]:
    crop(name,'11_测量报价：正式报价.jpg',box)
for name,box in [('foundation',(80,1006,144,1070)),('structure',(80,1083,144,1147)),('roof',(80,1160,144,1224)),('window',(80,1467,144,1531)),('paint',(332,1467,396,1531)),('stone',(585,1467,649,1531)),('light',(80,1545,144,1609)),('waterproof',(332,1545,396,1609)),('lightning',(585,1545,649,1609)),('insurance',(80,1621,144,1685))]:
    crop(name,'07_预算工作台：核定参数.jpg',box)
crop('site','10_测量报价：实测资料.jpg',(64,1297,306,1475))
crop('measure','10_测量报价：实测资料.jpg',(325,1297,551,1475))
crop('empty-follow','02_保存后的客户工作区.jpg',(350,1009,518,1171))
for name,source,box in [
 ('pdf','18A_总部正式下发.jpg',(100,905,156,968)),
 ('location','02_保存后的客户工作区.jpg',(248,341,273,372)),
 ('phone','02_保存后的客户工作区.jpg',(246,294,276,325)),
 ('success','18A_总部正式下发.jpg',(84,1128,123,1168)),
 ('info','18A_总部正式下发.jpg',(63,1652,99,1687)),
 ('add','16_设计师提交成果.jpg',(386,1104,467,1184)),
 ('close','01_登记客户.jpg',(777,501,810,533)),
 ('method-phone','03_记录首次跟进.jpg',(120,818,154,852)),
 ('method-wechat','03_记录首次跟进.jpg',(381,817,420,854)),
 ('method-store','03_记录首次跟进.jpg',(643,815,682,854)),
 ('camera','10_测量报价：实测资料.jpg',(652,1345,705,1389)),
 ('heart','04B_案例详情_立面效果.jpg',(688,877,725,913)),
 ('record','03B_到店沟通完成.jpg',(79,1163,167,1252)),
 ('document','02_保存后的客户工作区.jpg',(78,1427,164,1514)),
 ('book','02_保存后的客户工作区.jpg',(456,1427,541,1514)),
 ('money','02_保存后的客户工作区.jpg',(78,1551,164,1638)),
 ('house','02_保存后的客户工作区.jpg',(456,1551,541,1638)),
 ('calculator','09_方案与预算回存客户.jpg',(94,1110,195,1212)),
]: crop(name,source,box,False)
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Extracted',sum(Path(x['asset']).name.startswith('biz-') for x in manifest),'business assets')
