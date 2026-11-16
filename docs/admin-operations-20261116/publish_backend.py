"""Guard B's version, migrate once as owner, then switch only B's jar path."""
import json
import subprocess
from pathlib import Path

STAGE=Path('E:/zs-admin-ops-20261116')
DOC=Path(__file__).resolve().parent
KEY='C:/Users/Administrator/.ssh/zhongshu-cvm'
HOST='ubuntu@124.223.61.18'
REMOTE='/tmp/zs-admin-operations-20261116'
manifest=json.loads((STAGE/'manifest.json').read_text(encoding='utf-8'))
subprocess.run(['ssh','-i',KEY,'-oBatchMode=yes',HOST,'install -d -m 700 '+REMOTE],check=True)
subprocess.run(['scp','-q','-i',KEY,str(DOC/'patch_backend_jar.py'),str(STAGE/'operations.jar'),str(STAGE/'store-classes.zip'),HOST+':'+REMOTE+'/'],check=True)
script=r'''
import hashlib,json,os,subprocess,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,'/tmp/zs-admin-operations-20261116')
from patch_backend_jar import patch
unit='zhongshu-platform-b-integration.service'
release=Path('/opt/zhongshu-platform-b-integration/release')
def run(*args):return subprocess.check_output(args,text=True).strip()
def sha(data):return hashlib.sha256(data).hexdigest()
def guard():
 assert Path(m['jar']).parent==release and sha(Path(m['jar']).read_bytes())==m['jarSha256'],'Baseline changed'
 assert run('systemctl','show',unit,'-p','MainPID','--value')==m['pid'],'B was restarted by another session'
 assert sha(subprocess.check_output(['systemctl','cat',unit]))==m['unitSha256'],'B configuration changed'
guard()
candidate=release/('platform-operations-20261116-'+m['candidateSha256'][:12]+'.jar')
receipt=patch(m['jar'],'/tmp/zs-admin-operations-20261116/operations.jar','/tmp/zs-admin-operations-20261116/store-classes.zip',candidate)
assert receipt['candidateSha256']==m['candidateSha256'],'Candidate differs from verified local artifact'
log=release/('operations-migration-'+m['candidateSha256'][:12]+'.log')
fd=os.open(log,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'wb') as output:
 migration=subprocess.run(['/usr/bin/java','-jar',str(candidate),'migrate','--spring.profiles.active=pg',
 '--spring.config.location=file:/etc/zhongshu-platform-b-integration/migration.properties',
 '--spring.config.import=configtree:/etc/zhongshu-platform-b-integration/',
 '--platform.legacy-design-bridge.enabled=false','--spring.flyway.clean-disabled=true',
 '--spring.flyway.validate-on-migrate=true','--spring.flyway.baseline-on-migrate=false'],stdout=output,stderr=subprocess.STDOUT,timeout=120)
assert migration.returncode==0,'Explicit migration failed; active B service was not changed; log='+str(log)
guard()
paths=[run('systemctl','show',unit,'-p','FragmentPath','--value')]+run('systemctl','show',unit,'-p','DropInPaths','--value').split()
matches=[]
for name in paths:
 path=Path(name);assert path.is_file() and not path.is_symlink()
 original=path.read_bytes()
 if m['jar'].encode() in original:matches.append((path,original))
assert len(matches)==1,'Ambiguous effective unit configuration'
config,original=matches[0]
assert config.parent==Path('/etc/systemd/system/'+unit+'.d') or config==Path('/etc/systemd/system/'+unit)
assert original.count(m['jar'].encode())==1
backup=release/('operations-unit-before-'+m['candidateSha256'][:12]+'.conf')
if backup.exists():assert backup.read_bytes()==original
else:backup.write_bytes(original);backup.chmod(0o600)
def atomic(data):
 next_file=config.with_name(config.name+'.operations-next')
 next_file.write_bytes(data);next_file.chmod(config.stat().st_mode&0o777);os.replace(next_file,config)
def healthy():
 for attempt in range(55):
  try:
   with urllib.request.urlopen('http://127.0.0.1:48081/actuator/health',timeout=1) as response:
    if response.status==200 and json.load(response).get('status')=='UP':return True
  except Exception:pass
  time.sleep(1)
 return False
atomic(original.replace(m['jar'].encode(),str(candidate).encode()))
try:
 subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',unit],check=True)
 assert healthy(),'New B did not become healthy'
except BaseException:
 atomic(original);subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','restart',unit],check=True)
 assert healthy(),'Rollback health failed'
 raise
assert run('systemctl','show','zhongshu.service','-p','MainPID','--value')==m['serviceAPid'],'A service unexpectedly changed'
receipt.update({'previousJar':m['jar'],'currentJar':str(candidate),'unitFile':str(config),'unitBackup':str(backup),
'migrationLog':str(log),'pid':run('systemctl','show',unit,'-p','MainPID','--value'),'health':'UP','serviceAUnchanged':True})
print(json.dumps(receipt))
'''
result=subprocess.run(['ssh','-i',KEY,'-oBatchMode=yes',HOST,'sudo python3 -'],input='m='+repr(manifest)+'\n'+script,text=True,capture_output=True)
if result.returncode:
 print(result.stderr)
 raise SystemExit(result.returncode)
receipt=json.loads(result.stdout.strip().splitlines()[-1])
(DOC/'backend-release.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
