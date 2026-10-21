from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
references = root / 'qa/management-profile-20261018/references'
assets = root / 'miniprogram/assets'
icons = [
    ('M02', 'management-receipt', (35, 269, 74, 307)),
    ('M02', 'management-payment', (35, 339, 74, 377)),
    ('M02', 'management-wallet', (34, 410, 74, 448)),
    ('M01', 'management-schedule', (34, 315, 70, 351)),
    ('M01', 'management-appeal', (34, 376, 70, 412)),
]
for prefix, name, box in icons:
    source = Image.open(next(references.glob(prefix + '_*.png'))).convert('RGB')
    scale = source.width / 390
    crop = source.crop(tuple(round(v * scale) for v in box))
    crop.thumbnail((56, 56), Image.Resampling.LANCZOS)
    output = assets / (name + '.jpg')
    crop.save(output, quality=83, optimize=True)
    print(output.name, output.stat().st_size)
