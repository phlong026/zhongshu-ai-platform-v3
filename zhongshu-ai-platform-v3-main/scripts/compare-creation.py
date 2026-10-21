from pathlib import Path
from PIL import Image, ImageDraw
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
suffix = sys.argv[1] if len(sys.argv) > 1 else 'round1'
new = sorted((ROOT/'效果图').glob('微信图片_*.png'))
refs = dict(zip(['records','detail','publish','tasks','result','digital','remix'], new))
refs.update({'creation': next((ROOT/'效果图').glob('文案提取页*')), 'video-frames': ROOT/'效果图/AI视频页-图生视频.jpg', 'video-reference': ROOT/'效果图/AI视频页-参考生视频.jpg'})
out = ROOT/'qa/comparisons'
out.mkdir(exist_ok=True)
info_path = ROOT/'qa/creation-capture-info.json'
measurements = json.loads(info_path.read_text(encoding='utf-8')).get('measurements', {}) if info_path.exists() else {}
capture_info = json.loads(info_path.read_text(encoding='utf-8')) if info_path.exists() else {}
viewport_scale = 390 / capture_info.get('system', {}).get('windowWidth', 390)
regions = {
    'creation': (30,196,770,737), 'video-frames': (30,283,770,759),
    'video-reference': (30,816,770,1143), 'remix': (26,185,747,546),
    'digital': (30,668,823,1306), 'tasks': (30,300,823,718),
    'result': (53,218,800,1041), 'publish': (30,438,824,784),
    'records': (33,350,822,640), 'detail': (30,499,824,1162)
}
for key, source in refs.items():
    screenshot = ROOT/'qa/screenshots'/f'{key}-{suffix}.png'
    if not screenshot.exists(): continue
    src = Image.open(source).convert('RGB')
    src = src.resize((390, round(src.height * 390 / src.width)), Image.Resampling.LANCZOS)
    dst = Image.open(screenshot).convert('RGB')
    dst = dst.resize((390, round(dst.height * 390 / dst.width)), Image.Resampling.LANCZOS)
    combined = Image.new('RGB', (792, max(src.height,844)+30), '#ddd8cf')
    combined.paste(src, (0,30)); combined.paste(dst, (402,30))
    d = ImageDraw.Draw(combined); d.text((12,8),'REFERENCE',fill='black'); d.text((414,8),'WECHAT DEVTOOLS',fill='black')
    combined.save(out/f'{key}-{suffix}.png')
    if key in measurements:
        rect = {key:float(value)*viewport_scale for key,value in measurements[key].items()}
        scale = 390 / Image.open(source).width
        src_box = tuple(round(value*scale) for value in regions[key])
        src_focus = src.crop(src_box)
        dst_box = (round(rect['left']), round(rect['top']), round(rect['left']+rect['width']), round(rect['top']+rect['height']))
        dst_focus = dst.crop(dst_box)
        # Both regions retain their natural proportions; no stretching to hide drift.
        w,h = max(src_focus.width,dst_focus.width),max(src_focus.height,dst_focus.height)
        focus = Image.new('RGB',(w*4+12,h*2+30),'#ddd8cf')
        focus.paste(src_focus.resize((src_focus.width*2,src_focus.height*2)),(0,30))
        focus.paste(dst_focus.resize((dst_focus.width*2,dst_focus.height*2)),(w*2+12,30))
        d = ImageDraw.Draw(focus);d.text((8,8),'REFERENCE',fill='black');d.text((w*2+20,8),'WECHAT DEVTOOLS',fill='black')
        focus.save(out/f'{key}-focus-{suffix}.png')
    if key == 'remix':
        bottom_path = ROOT/'qa/screenshots'/f'remix-bottom-{suffix}.png'
        if bottom_path.exists():
            bottom = Image.open(bottom_path).convert('RGB')
            bottom = bottom.resize((390,round(bottom.height*390/bottom.width)),Image.Resampling.LANCZOS)
            # Compare the bottom of the long source with the bottom of its scrolled runtime.
            bottom_comparison = Image.new('RGB',(792,530),'#ddd8cf')
            bottom_comparison.paste(src.crop((0,src.height-500,390,src.height)),(0,30))
            bottom_comparison.paste(bottom.crop((0,bottom.height-500,390,bottom.height)),(402,30))
            d=ImageDraw.Draw(bottom_comparison);d.text((8,8),'REFERENCE BOTTOM',fill='black');d.text((414,8),'SCROLLED WECHAT VIEW',fill='black')
            bottom_comparison.save(out/f'remix-bottom-{suffix}.png')
print('Saved source + simulator comparisons at equal 390px width.')
