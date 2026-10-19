from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,sys
from builtins import round as round_fn
root=Path(__file__).resolve().parents[1];folder=root/'qa/management-profile-20261018';round=sys.argv[1] if len(sys.argv)>1 else 'round1'
info=json.loads((folder/f'capture-{round}.json').read_text(encoding='utf-8'));out=folder/f'comparisons-{round}';out.mkdir(exist_ok=True);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);tiles=[]
for row in info['results']:
 if not row.get('source'):continue
 source=Image.open(folder/'references'/row['source']).convert('RGB');actual=Image.open(folder/round/row['file']).convert('RGB');source=source.resize((390,round_fn(source.height*390/source.width)));actual=actual.resize((390,round_fn(actual.height*390/actual.width)))
 board=Image.new('RGB',(796,max(source.height,actual.height)+40),'#eee9df');board.paste(source,(0,40));board.paste(actual,(406,40));d=ImageDraw.Draw(board);d.text((8,7),'效果图',font=font,fill='black');d.text((414,7),'微信开发者工具',font=font,fill='black');board.save(out/row['file']);tile=board.copy();tile.thumbnail((398,460));canvas=Image.new('RGB',(398,495),'#eee9df');canvas.paste(tile,(0,30));ImageDraw.Draw(canvas).text((5,5),row['prefix']+' '+row['view'],font=font,fill='black');tiles.append(canvas)
for start in range(0,len(tiles),8):
 board=Image.new('RGB',(1592,990),'#eee9df')
 for i,im in enumerate(tiles[start:start+8]):board.paste(im,(i%4*398,i//4*495))
 board.save(folder/f'overview-{round}-{start//8+1}.jpg')
print('Compared',len(tiles),'reference / actual screen pairs')
