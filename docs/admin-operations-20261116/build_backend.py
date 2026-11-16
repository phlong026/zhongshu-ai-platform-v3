"""Compile against the exact running artifact, without rebuilding other chats' changes."""
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path
from patch_backend_jar import patch,STORE,MODULE

STAGE=Path('E:/zs-admin-ops-20261116')
ROOT=Path('E:/众墅之家AI赋能平台V3版-后端/平台程序')
KEY='C:/Users/Administrator/.ssh/zhongshu-cvm'
HOST='ubuntu@124.223.61.18'
JAVA=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/javac.exe')
PROBE=r'''
import hashlib,json,os,re,subprocess
from pathlib import Path
unit='zhongshu-platform-b-integration.service'
def run(*args):return subprocess.check_output(args,text=True).strip()
command=run('systemctl','show',unit,'-p','ExecStart','--value')
jar=re.search(r'-jar\s+([^ ;}]+\.jar)',command).group(1)
assert Path(jar).parent==Path('/opt/zhongshu-platform-b-integration/release')
print(json.dumps({'jar':jar,'jarSha256':hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
'pid':run('systemctl','show',unit,'-p','MainPID','--value'),
'unitSha256':hashlib.sha256(subprocess.check_output(['systemctl','cat',unit])).hexdigest(),
'web':os.readlink('/opt/zhongshu-platform-b-integration/release/web-current'),
'serviceAPid':run('systemctl','show','zhongshu.service','-p','MainPID','--value')}))
'''
def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
snapshot=json.loads(run(['ssh','-i',KEY,'-oBatchMode=yes',HOST,'sudo python3 -'],input=PROBE,capture_output=True,text=True).stdout)
STAGE.mkdir(exist_ok=True)
(STAGE/'manifest.json').write_text(json.dumps(snapshot,indent=2),encoding='utf-8')
baseline=STAGE/'baseline.jar'
previous=Path('E:/zs-pricing-release-20261116/candidate.jar')
verified_local=next((file for file in [STAGE/'candidate.jar',previous] if file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest()==snapshot['jarSha256']),None)
if verified_local:
    shutil.copyfile(verified_local,baseline)
elif not baseline.is_file() or hashlib.sha256(baseline.read_bytes()).hexdigest()!=snapshot['jarSha256']:
    run(['scp','-q','-i',KEY,HOST+':'+snapshot['jar'],str(baseline)])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()==snapshot['jarSha256']
for name in ['baseline-lib','baseline-classes','patch-classes','sources']:
    target=STAGE/name
    assert target.resolve().parent==STAGE.resolve() and target.name==name and not target.is_symlink()
    if target.exists():shutil.rmtree(target)
lib=STAGE/'baseline-lib';base_classes=STAGE/'baseline-classes'
lib.mkdir(exist_ok=True);base_classes.mkdir(exist_ok=True)
classpath=[str(base_classes)]
with zipfile.ZipFile(baseline) as archive:
    for name in archive.namelist():
        if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
            target=lib/Path(name).name;target.write_bytes(archive.read(name));classpath.append(str(target))
        elif name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
            target=base_classes/name.removeprefix('BOOT-INF/classes/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
classes=STAGE/'patch-classes';sources=STAGE/'sources'
classes.mkdir(exist_ok=True);sources.mkdir(exist_ok=True)
files=[]
source_root=ROOT/'yudao-platform-operations/src/main/java'
for source in source_root.rglob('*.java'):
    target=sources/source.relative_to(source_root);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes());files.append(target)
store=ROOT/'domains/franchise/franchise-content/src/main/java'/Path(STORE+'.java')
target=sources/Path(STORE+'.java');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(store.read_bytes());files.append(target)
args=['--release','17','-parameters','-proc:none','-encoding','UTF-8','-classpath',';'.join(classpath),'-d',str(classes)]+[str(file) for file in files]
(STAGE/'javac.args').write_text('\n'.join('"'+value.replace('\\','/')+'"' for value in args),encoding='utf-8')
run([str(JAVA),'@'+str(STAGE/'javac.args')])
def package(destination,entries):
    with zipfile.ZipFile(destination,'w') as archive:
        directories=set()
        for name in entries:
            directories.update(parent.as_posix()+'/' for parent in Path(name).parents if parent.as_posix()!='.')
        for name in sorted(directories):
            info=zipfile.ZipInfo(name,date_time=(2026,11,16,0,0,0));info.create_system=3;info.external_attr=(0o40755<<16)|0x10;archive.writestr(info,b'')
        for name,data in sorted(entries.items()):
            info=zipfile.ZipInfo(name,date_time=(2026,11,16,0,0,0));info.create_system=3;info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
module_entries={file.relative_to(classes).as_posix():file.read_bytes() for file in (classes/'cn/iocoder/yudao/module/platform/operations').rglob('*.class')}
resources=ROOT/'yudao-platform-operations/src/main/resources'
module_entries.update({file.relative_to(resources).as_posix():file.read_bytes() for file in resources.rglob('*') if file.is_file()})
module_entries['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\r\nImplementation-Title: yudao-platform-operations\r\n\r\n'
package(STAGE/'operations.jar',module_entries)
store_entries={file.relative_to(classes).as_posix():file.read_bytes() for file in (classes/Path(STORE).parent).glob('ContentTaskStore*.class')}
# Payload contains class files only; nested module already has the package directories.
with zipfile.ZipFile(STAGE/'store-classes.zip','w') as archive:
    for name,data in sorted(store_entries.items()):archive.writestr(name,data)
receipt=patch(baseline,STAGE/'operations.jar',STAGE/'store-classes.zip',STAGE/'candidate.jar')
snapshot.update(receipt)
snapshot['sourceSha256']={file.relative_to(sources).as_posix():hashlib.sha256(file.read_bytes()).hexdigest() for file in files}
(STAGE/'manifest.json').write_text(json.dumps(snapshot,indent=2),encoding='utf-8')
print(json.dumps({'compiledSources':len(files),'moduleClasses':len(module_entries),'candidateSha256':receipt['candidateSha256'],'baselineSha256':receipt['baselineSha256']}))
