"""发布已构建前端；要求提供当前版本，原子切换并保留旧静态资源。"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--expected', required=True)
args = parser.parse_args()
assert args.expected.startswith('web-') and '/' not in args.expected and '..' not in args.expected
build = Path('E:/众墅之家AI赋能平台V3版-后端/平台程序/admin-ui/dist-pricing-20261116')
output = Path(__file__).resolve().parent
assert (build / 'index.html').is_file()
archive = Path(tempfile.gettempdir()) / 'zs-admin-pricing-20261116.tgz'
with tarfile.open(archive, 'w:gz') as tar:
    for file in sorted(build.rglob('*')):
        if file.is_file():
            tar.add(file, arcname='admin/' + file.relative_to(build).as_posix(), recursive=False)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
key = 'C:/Users/Administrator/.ssh/zhongshu-cvm'
destination = 'ubuntu@124.223.61.18'
remote = '/tmp/zs-admin-pricing-' + digest[:12] + '.tgz'
subprocess.run(['scp', '-i', key, '-o', 'BatchMode=yes', str(archive), destination + ':' + remote], check=True)
script = '''from pathlib import Path
import os,hashlib,tarfile,shutil,json
root=Path('/opt/zhongshu-platform-b-integration/release')
current=root/'web-current'
expected=EXPECTED
old=os.readlink(current)
assert old==expected, 'Frontend changed; refusing to overwrite: '+old
archive=Path(ARCHIVE)
assert hashlib.sha256(archive.read_bytes()).hexdigest()==DIGEST
name='web-pricing-20261116-'+DIGEST[:12]
new=root/name
assert not new.exists(), 'Candidate already exists'
new.mkdir()
with tarfile.open(archive) as tar:
    for member in tar.getmembers():
        assert member.isfile() and member.name.startswith('admin/') and '..' not in Path(member.name).parts
    tar.extractall(new,filter='data')
assert (new/'admin/index.html').stat().st_size>1000
previous=current.resolve()
assert previous.is_relative_to(root.resolve())
for source in (previous/'admin/assets').rglob('*'):
    if source.is_file():
        target=new/'admin/assets'/source.relative_to(previous/'admin/assets')
        if not target.exists():
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(source,target)
next_link=root/(name+'.next')
assert not next_link.exists()
os.symlink(name,next_link)
assert os.readlink(current)==expected, 'Frontend changed during staging'
os.replace(next_link,current)
print(json.dumps({'previous':old,'current':name,'archiveSha256':DIGEST,'rollback':'sudo ln -sfn '+old+' '+str(current)},ensure_ascii=False))
'''.replace('EXPECTED', repr(args.expected)).replace('ARCHIVE', repr(remote)).replace('DIGEST', repr(digest))
result = subprocess.run(['ssh', '-i', key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', destination, 'sudo python3 -'], input=script, text=True, capture_output=True, check=True)
receipt = json.loads(result.stdout)
receipt['indexSha256'] = hashlib.sha256((build / 'index.html').read_bytes()).hexdigest()
record = output / 'frontend-release.json'
if record.exists():
    (output / ('frontend-release-before-' + digest[:12] + '.json')).write_bytes(record.read_bytes())
record.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(receipt, ensure_ascii=False))
