"""Fetch an OFL-licensed, minimal Noto Serif SC subset from Google Fonts."""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import re, json, base64
import time
def fetch(url):
    for attempt in range(3):
        try: return urlopen(Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=25).read()
        except Exception:
            if attempt==2: raise
            time.sleep(1)
root=Path(__file__).resolve().parents[1]
text='今日待办视频创作方案设计业务流程常用功能重点待办本月经营日常管理绩效管理众墅学堂服务与设置'
url='https://fonts.googleapis.com/css2?'+urlencode({'family':'Noto Serif SC:wght@700','text':text})
css=fetch(url).decode()
fonturl=re.search(r'url\(([^)]+)\)',css).group(1)
data=fetch(fonturl)
fmt='woff2' if data[:4]==b'wOF2' else 'woff' if data[:4]==b'wOFF' else 'ttf'
assets=root/'miniprogram/assets'
(assets/('heading.'+fmt)).write_bytes(data)
(root/'miniprogram/utils/heading-font.js').write_text('module.exports = '+json.dumps('url("data:font/'+fmt+';base64,'+base64.b64encode(data).decode()+'")')+';\n',encoding='utf-8')
license=fetch('https://raw.githubusercontent.com/google/fonts/main/ofl/notoserifsc/OFL.txt')
(assets/'FONT-LICENSE.txt').write_bytes(license)
(assets/'font-source.json').write_text(json.dumps({'family':'Noto Serif SC','weight':700,'css':url,'font':fonturl,'license':'SIL OFL 1.1'},ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Noto Serif SC subset: {len(data)} bytes ({fmt})')
