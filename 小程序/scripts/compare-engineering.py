from pathlib import Path
from PIL import Image,ImageDraw
import json,sys
root=Path(__file__).resolve().parents[1];out=root/'qa/engineering-20261018';round=sys.argv[1] if len(sys.argv)>1 else 'round1';info=json.loads((out/('capture-'+round+'.json')).read_text(encoding='utf-8'));dest=out/(round+'-comparisons');dest.mkdir(exist_ok=True)
for row in info['results']:
 a=Image.open(root/'效果图'/row['source']).convert('RGB');b=Image.open(out/round/row['file']).convert('RGB');a=a.resize(b.size,Image.Resampling.LANCZOS);c=Image.new('RGB',(b.width*2,b.height+24),'#eee');c.paste(a,(0,24));c.paste(b,(b.width,24));d=ImageDraw.Draw(c);d.text((8,4),'REFERENCE',fill='black');d.text((b.width+8,4),'WECHAT SIMULATOR',fill='black');c.save(dest/row['file'])
print('Compared',len(info['results']),'screens')
