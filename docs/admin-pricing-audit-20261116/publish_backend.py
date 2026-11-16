"""Guarded B-only release; retain preceding login fixes and roll back on failed health."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

STAGE = Path('E:/zs-pricing-release-20261116')
KEY = 'C:/Users/Administrator/.ssh/zhongshu-cvm'
HOST = 'ubuntu@124.223.61.18'
REMOTE = '/tmp/zs-pricing-20261116'


def command(args, **kwargs):
    try:
        return subprocess.run(args, check=True, **kwargs)
    except subprocess.CalledProcessError as error:
        if error.stderr:
            sys.stderr.write(error.stderr)
        raise


if __name__ == '__main__':
    manifest = json.loads((STAGE / 'manifest.json').read_text(encoding='utf-8'))
    manifest['candidateSha256'] = hashlib.sha256((STAGE / 'candidate.jar').read_bytes()).hexdigest()
    command(['ssh', '-i', KEY, '-oBatchMode=yes', HOST, 'install -d -m 700 ' + REMOTE])
    command(['scp', '-q', '-i', KEY, str(Path(__file__).with_name('patch_backend_jar.py')),
             str(STAGE / 'classes.zip'), HOST + ':' + REMOTE + '/'])
    script = r'''
import hashlib, json, os, subprocess, sys, time, urllib.request
from pathlib import Path
sys.path.insert(0, '/tmp/zs-pricing-20261116')
from patch_backend_jar import patch
m=MANIFEST
unit='zhongshu-platform-b-integration.service'
release=Path('/opt/zhongshu-platform-b-integration/release')
def run(*args): return subprocess.check_output(args,text=True).strip()
def sha(data): return hashlib.sha256(data).hexdigest()
baseline=Path(m['jar'])
assert baseline.parent==release and sha(baseline.read_bytes())==m['jarSha256'], 'Live jar changed'
assert run('systemctl','show',unit,'-p','MainPID','--value')==m['pid'], 'Service changed'
assert sha(subprocess.check_output(['systemctl','cat',unit]))==m['unitSha256'], 'Unit changed'
candidate=release/('platform-pricing-20261116-'+m['candidateSha256'][:8]+'.jar')
receipt=patch(baseline,'/tmp/zs-pricing-20261116/classes.zip',candidate)
assert receipt['candidateSha256']==m['candidateSha256'], 'Artifact mismatch'
paths=[run('systemctl','show',unit,'-p','FragmentPath','--value')]
paths+=run('systemctl','show',unit,'-p','DropInPaths','--value').split()
matches=[]
for name in paths:
    p=Path(name)
    assert p.is_file() and not p.is_symlink()
    data=p.read_bytes()
    if m['jar'].encode() in data: matches.append((p,data))
assert len(matches)==1, 'Ambiguous active unit configuration'
config,original=matches[0]
assert config.parent==Path('/etc/systemd/system/'+unit+'.d') or config==Path('/etc/systemd/system/'+unit)
assert original.count(m['jar'].encode())==1
updated=original.replace(m['jar'].encode(),str(candidate).encode())
backup=release/('pricing-unit-before-'+m['candidateSha256'][:8]+'.conf')
backup.write_bytes(original); backup.chmod(0o600)
def atomic(data):
    temp=config.with_name(config.name+'.pricing-next')
    temp.write_bytes(data); temp.chmod(config.stat().st_mode & 0o777)
    os.replace(temp,config)
atomic(updated)
try:
    subprocess.run(['systemctl','daemon-reload'],check=True)
    subprocess.run(['systemctl','restart',unit],check=True)
    healthy=False
    for attempt in range(45):
        try:
            with urllib.request.urlopen('http://127.0.0.1:48081/actuator/health',timeout=1) as response:
                if response.status==200 and json.load(response).get('status')=='UP': healthy=True;break
        except Exception: pass
        time.sleep(1)
    assert healthy, 'B health did not recover'
except BaseException:
    atomic(original)
    subprocess.run(['systemctl','daemon-reload'],check=True)
    subprocess.run(['systemctl','restart',unit],check=True)
    raise
receipt.update({'previousJar':str(baseline),'currentJar':str(candidate),'unitFile':str(config),
 'unitBackup':str(backup),'pid':run('systemctl','show',unit,'-p','MainPID','--value'),'health':'UP',
 'unitChange':'Only the jar path was replaced','newDatabaseMigrations':0})
print(json.dumps(receipt))
'''.replace('MANIFEST', repr(manifest), 1)
    result=command(['ssh','-i',KEY,'-oBatchMode=yes',HOST,'sudo python3 -'],input=script,
                   capture_output=True,text=True)
    receipt=json.loads(result.stdout.strip().splitlines()[-1])
    destination=Path(__file__).with_name('backend-release.json')
    destination.write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False))
