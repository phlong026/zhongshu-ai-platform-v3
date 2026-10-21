"""Extract the supplied design's photographic/brand/icon assets (no page rasterization)."""
from pathlib import Path
from PIL import Image
import json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'miniprogram/assets'
OUT.mkdir(parents=True, exist_ok=True)
refs = {k: Image.open(ROOT / '效果图' / v).convert('RGB') for k, v in {
    'home': '首页.jfif', 'business': '业务工作台.png',
    'engineering': '工程工作台.jfif', 'management': '管理工作台.jfif', 'profile': '我的页面.jfif'
}.items()}
manifest = []

def crop(name, ref, box, transparent=False):
    im = refs[ref].crop(box)
    if transparent:
        # Recover antialiased source ink on a transparent background. This keeps
        # the original icon strokes, rather than drawing a substitute icon.
        rgba = Image.new('RGBA', im.size)
        pixels = []
        for r, g, b in im.getdata():
            if min(r,g,b) < 205:
                pixels.append((r,g,b,255))
            elif min(r,g,b) < 245:
                a = max(0,min(255,round((245-min(r,g,b))/40*255)))
                pixels.append((r,g,b,a))
            else:
                pixels.append((0,0,0,0))
        rgba.putdata(pixels)
        im = rgba
    ext = '.png' if transparent or name == 'floor-plan' else '.jpg'
    if ext == '.jpg':
        im.save(OUT / (name + ext), quality=97, subsampling=0, optimize=True)
        old = OUT / (name + '.png')
        if old.is_file(): old.unlink()
    else:
        im.save(OUT / (name + ext), optimize=True)
    manifest.append({'asset': name+ext, 'source': ref, 'crop': box, 'transparent': transparent})

crop('brand', 'business', (34,96,284,160), True)
crop('home-hero', 'home', (0,163,853,731))
crop('home-video', 'home', (33,1063,420,1276))
crop('home-design', 'home', (434,1063,821,1276))
crop('home-partner', 'home', (33,1586,821,1695))
crop('business-video', 'business', (48,800,452,1037))
crop('floor-plan', 'business', (49,1163,418,1365))
crop('facade', 'business', (435,1163,807,1365))
crop('avatar-wang', 'business', (71,394,156,479))
crop('avatar-li', 'business', (71,514,156,599))
crop('site', 'engineering', (64,1009,258,1113))
crop('materials-banner', 'engineering', (30,1167,771,1518))
crop('avatar-profile', 'profile', (57,230,220,393))
crop('course', 'profile', (73,752,330,917))

# Icons are individually extracted at their measured source bounds.
icons = {
 'search':('business',(63,208,100,247)),
 'fire':('business',(492,811,535,856)),
 'person-filled':('business',(494,894,530,938)),
 'video-filled':('business',(488,983,538,1023)),
 'plan-file':('business',(112,1447,146,1485)),
 'cube':('business',(358,1446,398,1485)),
 'check':('business',(604,1446,645,1485)),
 'ruler':('business',(65,1595,122,1650)),
 'contract':('business',(340,1595,389,1650)),
 'house':('business',(593,1594,651,1650)),
 'folder':('engineering',(92,550,154,607)),
 'calendar':('engineering',(446,549,507,611)),
 'box':('engineering',(92,728,157,793)),
 'money':('engineering',(445,727,509,793)),
 'approval':('management',(333,704,393,763)),
 'chart':('management',(565,702,620,762)),
 'target':('management',(95,1156,181,1241)),
 'settings':('management',(92,1328,152,1385)),
 'team':('management',(329,1328,394,1383)),
 'review':('management',(569,1326,630,1385)),
 'graduate':('profile',(92,564,174,632)),
 'book':('profile',(449,564,518,631)),
 'headset':('profile',(78,1092,136,1150)),
 'message':('profile',(80,1201,134,1254)),
 'shield':('profile',(82,1307,133,1363)),
 'home-video-icon':('home',(87,851,153,902)),
 'home-design-icon':('home',(296,846,358,906)),
 'home-engineer-icon':('home',(493,846,560,905)),
 'home-chart-icon':('home',(702,843,761,904)),
 'brand-support':('home',(36,1466,80,1514)),
 'training-support':('home',(247,1466,297,1514)),
 'design-support':('home',(458,1466,507,1514)),
 'delivery-support':('home',(665,1466,715,1514)),
}
for name, (ref, box) in icons.items(): crop(name, ref, box, True)

tab_boxes=[(59,1708,106,1756),(231,1708,280,1756),(398,1708,455,1757),(574,1708,619,1756),(747,1708,796,1756)]
active=[('home',(68,1721,116,1767)),('business',tab_boxes[1]),('engineering',(370,1582,430,1632)),('management',(535,1582,584,1633)),('profile',(697,1582,747,1633))]
for i, name in enumerate(['home','business','engineering','management','profile']):
    crop('tab-'+name, 'business', tab_boxes[i], True) if i != 1 else crop('tab-'+name, 'engineering',(217,1586,263,1632), True)
    crop('tab-'+name+'-active', *active[i], True)
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2), encoding='utf-8')
print(f'Extracted {len(manifest)} assets; {sum(p.stat().st_size for p in OUT.iterdir()) / 1024:.0f} KiB')
