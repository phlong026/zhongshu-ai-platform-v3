from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
folder = root / 'qa/engineering-module-20261019'
output = folder / 'comparisons'
output.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)
targets = {
    'home': '13_首页_融合修订稿_V2.png',
    'projects': '06_项目_方案3.png',
    'materials': '08_材料_方案2.png',
    'profile': '10_我的_方案1.png',
}
for key, source in targets.items():
    paths = [root / '效果图/工程人员四页改版方案_20261019' / source,
             folder / 'native' / (key + '-top.png'),
             folder / 'native' / (key + '-bottom.png')]
    images = []
    for path in paths:
        with Image.open(path) as image:
            images.append(image.convert('RGB').resize((390, round(image.height * 390 / image.width))))
    canvas = Image.new('RGB', (1210, max(image.height for image in images) + 50), '#ebe8e2')
    draw = ImageDraw.Draw(canvas)
    for index, (image, label) in enumerate(zip(images, ['效果图', '原生页面上部', '原生页面下部'])):
        draw.text((10 + index * 400, 10), label, font=font, fill='#141312')
        canvas.paste(image, (10 + index * 400, 42))
    canvas.save(output / (key + '.jpg'), quality=90)
