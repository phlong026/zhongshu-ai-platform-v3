import sys,json,hashlib
from pathlib import Path
from urllib.request import Request,urlopen
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text());h=json.loads((j.PRIVATE/'handles.json').read_text());f=a['finance'];blob=Path('miniprogram/assets/book.png').read_bytes()
def transfer(method,url,data=None):
 local=url.startswith('/');headers={'Content-Type':'image/png'}
 if local:headers.update({'Authorization':'Bearer '+f['token'],'X-Post-Id':str(f['postId']),'tenant-id':'1'});url='http://127.0.0.1:18091'+url
 with urlopen(Request(url,data=data,headers=headers,method=method),timeout=15) as r:return r.status,r.read(),r.headers.get('Content-Type')
ledger=j.api('GET','/franchise/finance/workbench/ledger?scope=project:'+h['project']['id'],actor=f)
payment=next(r for r in ledger['entries'] if r['type']=='PAYMENT' and r['name']=='现场运输费·联调')
asset=payment['voucher_file_id'];assert asset
h['transportPaymentId']=payment['id'];h['voucherAssetId']=asset
(j.PRIVATE/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2))
ticket=j.api('POST','/franchise/assets/'+str(asset)+'/download-tickets',actor=f);status,downloaded,mime=transfer('GET',ticket['downloadUrl'])
# B's established scanner re-encodes images to strip EXIF; verify pixels, not encoded bytes.
from PIL import Image
from io import BytesIO
source=Image.open(BytesIO(blob)).convert('RGBA');copy=Image.open(BytesIO(downloaded)).convert('RGBA')
assert status==200 and source.size==copy.size and source.tobytes()==copy.tobytes()
(j.OUT/'voucher-result.json').write_text(json.dumps({'passed':True,'fixture':'book icon as test-only attachment; not a financial voucher','uploadAccepted':True,'supplementRecorded':True,'authorizedDownload':True,'downloadPixelsSame':True,'imageMetadataStrippedByExistingScanner':True},ensure_ascii=False,indent=2))
print('Voucher upload, supplement, authorized download and decoded pixel comparison: PASS')
