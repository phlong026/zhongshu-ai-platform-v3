from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
refs = {'home':'首页.jfif','business':'业务工作台.png','engineering':'工程工作台.jfif','management':'管理工作台.jfif','profile':'我的页面.jfif'}
out = ROOT/'qa/comparisons'
out.mkdir(parents=True, exist_ok=True)
import sys
suffix = sys.argv[1] if len(sys.argv)>1 else 'round1'
for key, name in refs.items():
    src = Image.open(ROOT/'效果图'/name).convert('RGB').resize((390,844), Image.Resampling.LANCZOS)
    impl = Image.open(ROOT/'qa/screenshots'/f'{key}-{suffix}.png').convert('RGB').resize((390,844), Image.Resampling.LANCZOS)
    combined = Image.new('RGB', (792,874), '#ddd8cf')
    combined.paste(src,(0,30)); combined.paste(impl,(402,30))
    d=ImageDraw.Draw(combined); d.text((12,8),'REFERENCE',fill='black'); d.text((414,8),'WECHAT DEVTOOLS',fill='black')
    combined.save(out/f'{key}-{suffix}.png')
print('Comparison images saved.')
