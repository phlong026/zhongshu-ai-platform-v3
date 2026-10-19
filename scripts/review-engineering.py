from pathlib import Path
from PIL import Image, ImageDraw
import json
import sys

root = Path(__file__).resolve().parents[1]
qa = root / 'qa/engineering-20261018'
round_name = sys.argv[1] if len(sys.argv) > 1 else 'fonts'
rows = json.loads((qa / f'capture-{round_name}.json').read_text(encoding='utf-8'))['results']
for start in range(0, len(rows), 6):
    items = rows[start:start + 6]
    sheet = Image.new('RGB', (1059, 1215), '#ddd')
    draw = ImageDraw.Draw(sheet)
    for offset, row in enumerate(items):
        pair = Image.open(qa / f'{round_name}-comparisons' / row['file']).convert('RGB')
        pair.thumbnail((353, 575), Image.Resampling.LANCZOS)
        x, y = (offset % 3) * 353, (offset // 3) * 607
        draw.text((x + 5, y + 5), f'{start + offset + 1}: {row["view"]}', fill='black')
        sheet.paste(pair, (x, y + 25))
    sheet.save(qa / f'review-{round_name}-{start // 6 + 1}.jpg', quality=92)
print(f'Review sheets: {(len(rows) + 5) // 6}')
