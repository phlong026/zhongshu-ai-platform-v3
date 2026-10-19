from pathlib import Path
import json
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1]
source = root / 'qa/engineering-guest-home-20261019/reference.png'
target = root / 'miniprogram/pages/engineering/assets'
target.mkdir(exist_ok=True)
im = Image.open(source).convert('RGB')
regions = {
    'project-chen': (48, 496, 344, 830),
    'helmet': (65, 271, 134, 322),
    'house': (337, 268, 398, 325),
    'tasks': (619, 267, 679, 325),
    'search': (54, 149, 96, 188),
    'add': (706, 152, 745, 190),
    'calendar': (382, 784, 414, 815),
    'clock': (155, 1299, 189, 1333),
    'arrow': (791, 431, 811, 456),
    'arrow-muted': (787, 510, 811, 536),
    'arrow-white': (770, 858, 789, 888),
    'camera': (85, 1575, 180, 1645),
    'material': (282, 1575, 377, 1645),
    'inspection': (477, 1575, 572, 1645),
    'folder': (673, 1575, 768, 1645),
}
manifest = []
for name, box in regions.items():
    crop = im.crop(box)
    if name in ['camera', 'material', 'inspection', 'folder']:
        mask = Image.new('L', crop.size)
        mask.putdata([255 if r > g + 25 and g > b + 12 and 50 < r < 200 else 0 for r, g, b in crop.getdata()])
        tight = mask.getbbox()
        if tight:
            tight = (max(0, tight[0]-3), max(0, tight[1]-3), min(crop.width, tight[2]+3), min(crop.height, tight[3]+3))
            box = (box[0]+tight[0], box[1]+tight[1], box[0]+tight[2], box[1]+tight[3])
            crop = im.crop(box)
    suffix = '.jpg' if name.startswith('project-') else '.png'
    filename = name + suffix
    if suffix == '.jpg':
        crop.thumbnail((240, 280), Image.Resampling.LANCZOS)
        crop.save(target / filename, quality=68, optimize=True)
    else:
        crop.quantize(colors=24).save(target / filename, optimize=True)
    manifest.append({'asset': filename, 'source': 'qa/engineering-guest-home-20261019/reference.png', 'crop': box})
(root / 'qa/engineering-guest-home-20261019/asset-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
sheet = Image.new('RGB', (600, 600), '#faf8f4')
draw = ImageDraw.Draw(sheet)
for i, item in enumerate(manifest):
    tile = Image.open(target / item['asset']).convert('RGB')
    tile.thumbnail((110, 120))
    x, y = (i % 4)*150, (i // 4)*150
    sheet.paste(tile, (x+15, y+20))
    draw.text((x+5, y+135), item['asset'], fill='#111')
sheet.save(root / 'qa/engineering-guest-home-20261019/asset-review.jpg')
print('Extracted', len(manifest), 'assets')
