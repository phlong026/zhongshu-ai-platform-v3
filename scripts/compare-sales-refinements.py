from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json
root=Path(__file__).resolve().parents[1]
base=root/'qa/business-refinements-20261019'; out=base/'comparisons'; out.mkdir(exist_ok=True)
native=base/'native'; old=root/'qa/business-followups-20261019/final-native'
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
metadata=[]
def normalized(path):
    pic=Image.open(path).convert('RGB')
    return pic.resize((390,round(pic.height*390/pic.width)))
def board(name,items):
    pictures=[normalized(path) for _,path in items]
    result=Image.new('RGB',(len(items)*406-16,max(p.height for p in pictures)+44),'#eae6df');draw=ImageDraw.Draw(result)
    for i,((label,path),pic) in enumerate(zip(items,pictures)):
        result.paste(pic,(406*i,44));draw.text((406*i+8,12),label,font=font,fill='#352b23')
    result.save(out/(name+'.png'))
    metadata.append({'name':name,'panels':[{'label':label,'source':path.relative_to(root).as_posix(),'pixels':Image.open(path).size} for label,path in items],'normalizedWidth':390})
home=root/'效果图/业务人员四页改版方案_20261019/02_首页_方案2.png'
design=root/'效果图/创作与设计页面第二轮方案_20261019/04_AI设计_方案1.png'
board('home',[('选定效果图',home),('上一版',old/'home-top.png'),('本轮调整',native/'home-top.png')])
board('design',[('选定效果图',design),('上一版硬分区',old/'design-top.png'),('本轮自然过渡',native/'design-top.png')])
board('profile',[('业务专员 · 我的',native/'profile-sales-top.png'),('业务负责人 · 我的',native/'profile-lead-top.png'),('负责人服务区',native/'profile-lead-bottom.png')])
board('entries',[(label,native/('polish-entry-'+view+'.png')) for label,view in [('灵感库','inspiration'),('AI平面','ai-config'),('AI立面','ai-facade'),('快速预算','budget')]])
board('rewrite',[(label,native/('rewrite-final-'+state+'.png')) for label,state in [('尚未生成','empty-result'),('生成后自动定位','generated'),('选择AI优化方向','optimization'),('优化后','optimized')]])
board('picker-fix',[('修复前 · 选择框无背景',native/'entry-ai-config.png'),('修复后 · 表单层次统一',native/'polish-entry-ai-config.png')])
board('plan-flow',[('流程证据 · 尺寸检索',native/'plan-search.png'),('流程证据 · 分层参考',native/'case-plan.png'),('流程证据 · 三层四候选',native/'plan-results.png')])
board('budget-flow',[('独立立面',native/'facade-results.png'),('参考预算',native/'budget-result-top.png'),('选择客户保存',native/'save-customer.png'),('独立预算360㎡→实测（补测）',native/'polish-budget-measurement.png')])
for name,source,regions in [('home',home,[(0,157,390,250),(0,94,390,225)]),('design',design,[(0,58,390,265),(0,91,390,294)])]:
    pics=[normalized(source).crop(regions[0]),normalized(native/(name+'-top.png')).crop(regions[1])]
    result=Image.new('RGB',(796,max(p.height for p in pics)+44),'#eae6df');draw=ImageDraw.Draw(result)
    for i,(pic,label) in enumerate(zip(pics,['选定效果图局部','当前原生局部'])):
        result.paste(pic,(i*406,44));draw.text((i*406+8,10),label,font=font,fill='#352b23')
    result.save(out/(name+'-focus.png'))
(out/'manifest.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
print('Created 8 full boards and 2 focused comparisons.')
