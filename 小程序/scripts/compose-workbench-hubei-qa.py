from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
root=Path(__file__).resolve().parents[1]
qa=root/'qa/workbench-hubei-20261019'
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
def compose(name,panels):
    width,gap,top=353,18,42
    canvas=Image.new('RGB',(width*len(panels)+gap*(len(panels)+1),822),'#f1eee8')
    draw=ImageDraw.Draw(canvas)
    for i,(label,path) in enumerate(panels):
        x=gap+i*(width+gap)
        draw.text((x,10),label,font=font,fill='#37271d')
        image=Image.open(qa/path).convert('RGB');image.thumbnail((width,762),Image.Resampling.LANCZOS)
        canvas.paste(image,(x,top))
    canvas.save(qa/name,quality=93)
compose('boss-before-overview.jpg',[
 ('1 业务页 · 本轮调整前','boss-audit/01-business-top.png'),
 ('2 工程页 · 本轮调整前','boss-audit/02-engineering-top.png'),
 ('3 管理页 · 本轮调整前','boss-audit/03-management-top.png')])
compose('boss-current-overview.jpg',[
 ('1 业务页 · 当前老板身份','native/boss-business-final-top.png'),
 ('2 工程页 · 已同步新版布局','native/boss-engineering-final-top.png'),
 ('3 管理页 · 已调整首页顺序','native/boss-management-final-top.png')])
compose('implemented-pages.jpg',[
 ('工程师登录后 · 新版首页','native/engineering-signed-in-top.png'),
 ('财务专员 · 8个常用功能','native/finance-staff-functions.png'),
 ('我的 · 已移除演示入口','native/profile-guest-final.png')])
print('Three native screenshot comparisons saved.')
