from pathlib import Path
from PIL import Image,ImageOps,ImageDraw
import zipfile
root=Path.cwd(); out=root/'qa/engineering-20261018'; out.mkdir(parents=True,exist_ok=True)
for p in (root/'效果图').glob('*.zip'):
 if '工程' in p.name or '简化版' in p.name:
  with zipfile.ZipFile(p) as z:
   print(p.name, z.namelist())
imgs=[]
for p in (root/'效果图').iterdir():
 if p.suffix.lower() in ['.jpg','.png','.jfif'] and ('_E0' in p.name or p.name.startswith('E') or '工程工作台' in p.name or '_E1' in p.name):
  im=Image.open(p); print(p.name, im.size);imgs.append(p)
for start in range(0,len(imgs),8):
 batch=imgs[start:start+8]; canvas=Image.new('RGB',(1200,900),'#ddd'); d=ImageDraw.Draw(canvas)
 for n,p in enumerate(batch):
  im=Image.open(p).convert('RGB');im.thumbnail((290,840));x=(n%4)*300;y=(n//4)*450;im.thumbnail((290,420));canvas.paste(im,(x,y+25));d.text((x+4,y+4),p.stem,fill='black')
 canvas.save(out/f'sources-{start//8+1}.jpg')
