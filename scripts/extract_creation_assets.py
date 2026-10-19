"""Reuse the supplied artwork as independent assets, following the five-page build."""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'miniprogram/assets'
sources = {'creation': next((ROOT / '效果图').glob('文案提取页*')).name, 'video-frames': 'AI视频页-图生视频.jpg', 'video-reference': 'AI视频页-参考生视频.jpg'}
refs = {key: Image.open(ROOT / '效果图' / name).convert('RGB') for key, name in sources.items()}
manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8'))
manifest = [entry for entry in manifest if not entry['asset'].startswith('creation-')]

def crop(name, source, box, transparent=False):
    im = refs[source].crop(box)
    ext = '.png' if transparent else '.jpg'
    if transparent:
        rgba = Image.new('RGBA', im.size)
        pixels = []
        for r, g, b in im.getdata():
            low = min(r, g, b)
            a = 255 if low < 200 else max(0, min(255, round((244 - low) / 44 * 255)))
            pixels.append((r, g, b, a) if a else (0, 0, 0, 0))
        rgba.putdata(pixels)
        im = rgba
        im.save(OUT / (name + ext), optimize=True)
    else:
        im.save(OUT / (name + ext), quality=93, subsampling=0, optimize=True)
    manifest.append({'asset': name + ext, 'source': sources[source], 'crop': box, 'transparent': transparent})

crop('creation-ai-cover', 'creation', (56, 790, 265, 918))
crop('creation-house', 'creation', (56, 1262, 275, 1381))
crop('creation-courtyard', 'creation', (56, 1403, 275, 1522))
for name, source, box in [
    ('back', 'creation', (39, 98, 65, 135)),
    ('link', 'creation', (83, 417, 123, 459)),
    ('folder', 'creation', (90, 1005, 155, 1065)),
    ('tasks', 'creation', (278, 1000, 340, 1066)),
    ('works', 'creation', (458, 1006, 529, 1063)),
    ('publish', 'creation', (645, 998, 720, 1069)),
    ('chevron', 'creation', (725, 1308, 742, 1336)),
    ('chevron-muted', 'creation', (727, 1199, 746, 1226)),
    ('plus', 'video-frames', (186, 449, 251, 513)),
    ('sparkle', 'video-frames', (604, 814, 641, 853)),
    ('upload', 'video-reference', (289, 661, 331, 701)),
    ('help', 'video-reference', (450, 739, 482, 771)),
    ('down', 'video-frames', (341, 1323, 367, 1343)),
    ('section-mark', 'video-frames', (47, 307, 61, 348)),
]:
    crop('creation-' + name, source, box, True)
# Preserve the supplied button's subtle lighting using an empty slice of its surface.
crop('creation-button-surface', 'creation', (120, 515, 260, 592))
(OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'sources': {k: im.size for k, im in refs.items()}, 'creationAssets': sum(e['asset'].startswith('creation-') for e in manifest)}, ensure_ascii=False))
