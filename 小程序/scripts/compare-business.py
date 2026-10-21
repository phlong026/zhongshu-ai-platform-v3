from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,sys,re
ROOT=Path(__file__).resolve().parents[1]
suffix=sys.argv[1] if len(sys.argv)>1 else 'round1'
folder=ROOT/'qa/business-20261018'
info=folder/f'capture-{suffix}.json'
capture=json.loads(info.read_text(encoding='utf-8')) if info.exists() else {'results':[{'source':p.stem+'.jpg','file':p.name} for p in sorted((folder/suffix).glob('*.png')) if not p.stem.endswith('-bottom')]}
out=folder/f'comparisons-{suffix}';out.mkdir(exist_ok=True)
tiles=[]
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
focus_boxes={'inspiration':(40,418,814,707),'quote':(58,750,794,1262),'measurement':(64,598,793,854),'payment-first':(38,890,814,1670),'facade-config':(65,482,790,696)}
def reading_order(row):
    m=re.match(r'(\d+)([A-Z]*)',row['source'])
    return (int(m.group(1)),m.group(2) or '-')
for row in sorted(capture['results'],key=reading_order):
    if not (folder/suffix/row['file']).exists():
        continue
    source=Image.open(ROOT/'效果图'/row['source']).convert('RGB')
    actual=Image.open(folder/suffix/row['file']).convert('RGB')
    source=source.resize((390,round(source.height*390/source.width)),Image.Resampling.LANCZOS)
    actual=actual.resize((390,round(actual.height*390/actual.width)),Image.Resampling.LANCZOS)
    combined=Image.new('RGB',(796,max(source.height,actual.height)+36),'#ddd8cf')
    combined.paste(source,(0,36));combined.paste(actual,(406,36))
    d=ImageDraw.Draw(combined);d.text((8,6),'效果图',fill='#151515',font=font);d.text((414,6),'微信开发者工具',fill='#151515',font=font)
    combined.save(out/row['file'])
    if row.get('view') in focus_boxes and row.get('measurements',{}).get('focus'):
        rect=row['measurements']['focus'];scale=390/capture['system']['windowWidth']
        src_scale=390/853
        src_box=tuple(round(v*src_scale) for v in focus_boxes[row['view']])
        dst_box=(round(rect['left']*scale),round(rect['top']*scale),round((rect['left']+rect['width'])*scale),round((rect['top']+rect['height'])*scale))
        left=source.crop(src_box);right=actual.crop(dst_box)
        left=left.resize((left.width*2,left.height*2));right=right.resize((right.width*2,right.height*2))
        focused=Image.new('RGB',(left.width+right.width+16,max(left.height,right.height)+36),'#ddd8cf');focused.paste(left,(0,36));focused.paste(right,(left.width+16,36))
        d=ImageDraw.Draw(focused);d.text((8,6),'效果图 · 局部',font=font,fill='black');d.text((left.width+24,6),'微信 · 局部',font=font,fill='black')
        focused.save(out/(Path(row['file']).stem+'-focus.png'))
    tile=combined.copy();tile.thumbnail((398,460));canvas=Image.new('RGB',(398,480),'#eee9e1');canvas.paste(tile,(0,25));ImageDraw.Draw(canvas).text((6,3),row['source'][:20],fill='black',font=font);tiles.append(canvas)
for start in range(0,len(tiles),8):
    group=tiles[start:start+8];board=Image.new('RGB',(398*4,480*2),'white')
    for n,tile in enumerate(group):board.paste(tile,((n%4)*398,(n//4)*480))
    board.save(folder/f'overview-{suffix}-{start//8+1}.png')
print('Created',len(tiles),'equal-width reference / WeChat comparisons')
