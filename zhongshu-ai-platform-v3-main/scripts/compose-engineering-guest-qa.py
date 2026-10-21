"""Compose source/native screenshots for visual inspection; never ships in the app."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
out = root / 'qa/engineering-guest-home-20261019'
followups = root / 'qa/workbench-followups-20261019'
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
small = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
w, gap, top = 353, 18, 62
canvas = Image.new('RGB', (w * 3 + gap * 4, 762 + top + 20), '#f1eee8')
draw = ImageDraw.Draw(canvas)
panels = [
    ('选定效果图', '后续以用户确认的顺序、数量与统一样式为准', out / 'reference.png'),
    ('微信开发者工具 · 最终首屏', '搜索统计 → 今日待办 → 在建项目', out / 'final-top.png'),
    ('微信开发者工具 · 最终底部', '单项目 → 快捷操作 → 登录按钮', out / 'final-bottom.png'),
]
for i, (label, note, source) in enumerate(panels):
    x = gap + i * (w + gap)
    draw.text((x, 9), label, font=font, fill='#33251b')
    draw.text((x, 33), note, font=small, fill='#756b61')
    image = Image.open(source).convert('RGB')
    image.thumbnail((w, 762), Image.Resampling.LANCZOS)
    canvas.paste(image, (x, top))
canvas.save(out / 'source-native-comparison.jpg', quality=92)

headers = Image.new('RGB', (w * 3 + gap * 4, 130), '#f1eee8')
draw = ImageDraw.Draw(headers)
for i, (name, label) in enumerate([('business', '业务工作台'), ('engineering', '工程工作台'), ('management', '管理工作台')]):
    x = gap + i * (w + gap)
    draw.text((x, 12), label, font=font, fill='#33251b')
    image = Image.open(followups / (name + '-header.png')).convert('RGB')
    headers.paste(image.crop((0, 38, w, 91)), (x, 48))
headers.save(followups / 'header-comparison.jpg', quality=95)
print('Source/native comparison and three native header strips saved.')
