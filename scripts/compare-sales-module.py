from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json

root = Path(__file__).resolve().parents[1]
folder = root / 'qa/business-module-redesign-20261019'
out = folder / 'comparisons'
out.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 17)
sources = {
    'home': ('首页', '效果图/业务人员四页改版方案_20261019/02_首页_方案2.png'),
    'customers': ('客户', '效果图/业务人员四页改版方案_20261019/10_客户_方案1.png'),
    'creation': ('创作', '效果图/创作与设计页面第二轮方案_20261019/01_内容创作_方案1.png'),
    'design': ('设计', '效果图/创作与设计页面第二轮方案_20261019/04_AI设计_方案1.png'),
}
metadata = []
def normalized(file):
    image = Image.open(file).convert('RGB')
    return image.resize((390, round(image.height * 390 / image.width)), Image.Resampling.LANCZOS)

for key, (label, source) in sources.items():
    actual = folder / 'final-native' / (key + '-top.png')
    bottom = folder / 'final-native' / (key + '-bottom.png')
    files = [root / source, actual, bottom]
    images = [normalized(p) for p in files]
    board = Image.new('RGB', (1202, max(p.height for p in images) + 42), '#eae6df')
    draw = ImageDraw.Draw(board)
    for i, (pic, title) in enumerate(zip(images, [label+' · 选定效果图', label+' · 微信首屏', label+' · 微信滚动底部'])):
        board.paste(pic, (i * 406, 42))
        draw.text((i * 406 + 8, 10), title, fill='#141312', font=font)
    board.save(out / (key + '.png'))
    # Two complete first-screen panels preserve legibility when reviewing typography.
    focus = Image.new('RGB', (796, 492), '#eae6df')
    starts = {'home': (157, 135), 'customers': (127, 101), 'creation': (68, 101), 'design': (59, 101)}[key]
    for i, pic in enumerate(images[:2]):
        focus.paste(pic.crop((0, starts[i], 390, starts[i]+450)), (i * 406, 42))
    d = ImageDraw.Draw(focus)
    d.text((8, 10), label+' · 效果图局部', fill='#141312', font=font)
    d.text((414, 10), label+' · 微信局部', fill='#141312', font=font)
    focus.save(out / (key + '-focus.png'))
    metadata.append({'page':key, 'source':source, 'sourcePixels':Image.open(files[0]).size,
                     'implementation':str(actual.relative_to(root)), 'implementationPixels':Image.open(actual).size,
                     'normalizedWidth':390, 'fullComparison':str((out/(key+'.png')).relative_to(root)),
                     'focusedComparison':str((out/(key+'-focus.png')).relative_to(root)),
                     'focusedNormalizedTop':{'source':starts[0],'implementation':starts[1]}})
(out/'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
print('Created 4 full-view and 4 focused reference/native comparisons.')
