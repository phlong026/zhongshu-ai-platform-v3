from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json

root = Path(__file__).resolve().parents[1]
folder = root / 'qa/business-completion-20261018'
rows = json.loads((folder / 'capture.json').read_text(encoding='utf-8'))
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)
for offset in range(0, len(rows), 6):
    board = Image.new('RGB', (1080, 1710), '#eeeae3')
    for index, row in enumerate(rows[offset:offset + 6]):
        image = Image.open(folder / 'screenshots' / row['file']).convert('RGB')
        image.thumbnail((348, 798), Image.Resampling.LANCZOS)
        x, y = index % 3 * 360 + 6, index // 3 * 855 + 42
        board.paste(image, (x, y))
        draw = ImageDraw.Draw(board)
        draw.text((x + 4, y - 30), row['title'], fill='#25211e', font=font)
    board.save(folder / ('overview-' + str(offset // 6 + 1) + '.png'))
print('Created boards for', len(rows), 'native screenshots')
