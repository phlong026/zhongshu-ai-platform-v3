from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'miniprogram/assets'
files = sorted((ROOT / '效果图').glob('微信图片_*.png'))
keys = ['records', 'detail', 'publish', 'tasks', 'result', 'digital', 'remix']
sources = dict(zip(keys, files))
refs = {key: Image.open(file).convert('RGB') for key, file in sources.items()}
manifest = [entry for entry in json.loads((OUT / 'manifest.json').read_text(encoding='utf-8')) if not entry['asset'].startswith('studio-')]

def crop(name, key, box, transparent=False):
    im = refs[key].crop(box)
    ext = '.png' if transparent else '.jpg'
    if transparent:
        rgba = Image.new('RGBA', im.size)
        pixels = []
        for r, g, b in im.getdata():
            low = min(r, g, b)
            if name in ['control-play', 'volume', 'fullscreen']:
                alpha = max(0, min(255, round((max(r, g, b) - 100) / 155 * 255)))
                pixels.append((255, 255, 255, alpha))
            else:
                alpha = 255 if low < 200 else max(0, min(255, round((245 - low) / 45 * 255)))
                pixels.append((r, g, b, alpha) if alpha else (0, 0, 0, 0))
        rgba.putdata(pixels)
        rgba.save(OUT / ('studio-' + name + ext), optimize=True)
    else:
        im.save(OUT / ('studio-' + name + ext), quality=93, subsampling=0, optimize=True)
    manifest.append({'asset': 'studio-' + name + ext, 'source': sources[key].name, 'crop': box, 'transparent': transparent})

crop('portrait', 'tasks', (55, 325, 320, 694))
crop('portrait-wide', 'detail', (60, 604, 343, 792))
crop('portrait-large', 'result', (53, 218, 800, 862))
crop('digital-presenter', 'digital', (61, 779, 794, 1157))
crop('video-poster', 'result', (53, 218, 800, 957))
crop('publication-cover', 'publish', (60, 213, 367, 399))
crop('record-cover', 'records', (57, 375, 304, 595))
crop('douyin', 'publish', (84, 565, 151, 630))
crop('channels', 'publish', (84, 672, 151, 737))
for name, key, box in [
    ('success-large', 'detail', (379, 195, 476, 291)),
    ('success', 'result', (64, 1092, 117, 1145)),
    ('check-brown', 'publish', (719, 572, 771, 624)),
    ('check-empty', 'publish', (719, 680, 771, 730)),
    ('clock', 'records', (347, 560, 382, 595)),
    ('check-green', 'records', (347, 877, 382, 912)),
    ('warning', 'records', (347, 1187, 382, 1222)),
    ('error', 'records', (347, 1531, 382, 1566)),
    ('spinner', 'tasks', (366, 470, 406, 510)),
    ('save', 'tasks', (596, 963, 636, 1006)),
    ('task-success', 'tasks', (367, 965, 408, 1006)),
    ('task-error', 'tasks', (595, 1374, 636, 1415)),
    ('refresh', 'records', (331, 1692, 378, 1738)),
    ('edit', 'digital', (674, 241, 717, 285)),
    ('voice', 'digital', (88, 1470, 172, 1553)),
    ('play', 'digital', (518, 1483, 570, 1535)),
    ('ip', 'remix', (84, 764, 157, 834)),
    ('clear', 'publish', (738, 917, 776, 955)),
    ('image', 'publish', (654, 339, 689, 371)),
    ('section-mark', 'digital', (57, 238, 72, 288)),
    ('video-play', 'result', (365, 568, 490, 694)),
    ('control-play', 'result', (88, 979, 125, 1018)),
    ('volume', 'result', (650, 977, 694, 1018)),
    ('fullscreen', 'result', (731, 977, 772, 1018)),
]: crop(name, key, box, True)
crop('button-surface', 'publish', (40, 1642, 243, 1721))
(OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'sources': {key: im.size for key, im in refs.items()}, 'assets': len(manifest)}, ensure_ascii=False))
